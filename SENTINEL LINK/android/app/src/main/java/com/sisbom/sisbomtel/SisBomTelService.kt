package com.sisbom.sisbomtel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

class SisBomTelService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val repository = FirebaseRepository()

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null
    private var currentCallNumber: String = ""
    private var currentCallState: String = "IDLE"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Servicio de Telefonía y SMS Activo"))

        initTelephonyListener()
        startSmsQueueMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val actionType = intent?.getStringExtra("action_type")
        if (actionType == "PHONE_STATE_CHANGED") {
            val stateStr = intent.getStringExtra("phone_state") ?: ""
            val incomingNumber = intent.getStringExtra("incoming_number") ?: ""
            handlePhoneState(stateStr, incomingNumber)
        }
        return START_STICKY
    }

    private var lastServicePhone: String = ""
    private var lastServiceTimestamp: Long = 0L

    private fun getLastCallLogNumber(): String {
        return try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                return ""
            }
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )
            var found = ""
            cursor?.use {
                if (it.moveToFirst()) {
                    val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                    val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                    if (numIdx != -1) {
                        val callDate = if (dateIdx != -1) it.getLong(dateIdx) else 0L
                        val now = System.currentTimeMillis()
                        if (now - callDate < 45000L || callDate == 0L) {
                            found = it.getString(numIdx) ?: ""
                        }
                    }
                }
            }
            found
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun handlePhoneState(stateStr: String, incomingNumber: String) {
        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                currentCallState = "RINGING"
                var resolvedNumber = if (incomingNumber.isNotBlank()) incomingNumber.trim() else currentCallNumber.trim()
                if (resolvedNumber.isBlank() || resolvedNumber == "LLAMADA ENTRANTE") {
                    val fromLog = getLastCallLogNumber()
                    if (fromLog.isNotBlank()) resolvedNumber = fromLog
                }
                val isUnknownInitially = resolvedNumber.isBlank()
                val displayPhone = if (isUnknownInitially) "LLAMADA ENTRANTE" else resolvedNumber

                currentCallNumber = displayPhone
                activeIncomingCallNumber = displayPhone
                activeIncomingCallStatus = "TIMBRANDO"
                updateNotification("📞 LLAMADA ENTRANTE: $displayPhone")

                val now = System.currentTimeMillis()
                val isDuplicate = (displayPhone == lastServicePhone && (now - lastServiceTimestamp < 15000L))
                if (!isDuplicate) {
                    lastServicePhone = displayPhone
                    lastServiceTimestamp = now
                    repository.registerIncomingCall(displayPhone, "TIMBRANDO")
                } else {
                    repository.updateActiveCallState(displayPhone, "TIMBRANDO")
                }

                // If number was unknown at first millisecond of ringing, poll CallLog with quick retries
                if (isUnknownInitially) {
                    serviceScope.launch {
                        for (attempt in 1..8) {
                            kotlinx.coroutines.delay(350L * attempt)
                            if (currentCallState != "RINGING" && currentCallState != "OFFHOOK") break
                            val logNum = getLastCallLogNumber()
                            if (logNum.isNotBlank() && logNum != "LLAMADA ENTRANTE") {
                                currentCallNumber = logNum
                                activeIncomingCallNumber = logNum
                                lastServicePhone = logNum
                                updateNotification("📞 LLAMADA ENTRANTE: $logNum")
                                repository.registerIncomingCall(logNum, if (currentCallState == "OFFHOOK") "ATENDIDA" else "TIMBRANDO")
                                break
                            }
                        }
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                currentCallState = "OFFHOOK"
                if (currentCallNumber.isBlank() || currentCallNumber == "LLAMADA ENTRANTE") {
                    val logNum = getLastCallLogNumber()
                    if (logNum.isNotBlank()) currentCallNumber = logNum
                }
                val phone = if (currentCallNumber.isNotBlank()) currentCallNumber else "EN LLAMADA"
                activeIncomingCallStatus = "EN_LLAMADA"
                activeIncomingCallNumber = phone
                updateNotification("📞 En llamada con: $phone")
                repository.registerIncomingCall(phone, "ATENDIDA")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (currentCallState != "IDLE") {
                    val finalPhone = if (currentCallNumber.isNotBlank()) currentCallNumber else getLastCallLogNumber()
                    repository.finishIncomingCall(finalPhone.ifBlank { "DESCONOCIDO" })
                    activeIncomingCallNumber = ""
                    activeIncomingCallStatus = "DISPONIBLE"
                    updateNotification("SENTINEL LINK Listo - Monitoreando")
                }
                currentCallNumber = ""
                currentCallState = "IDLE"
            }
        }
    }

    private fun initTelephonyListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        val stateStr = when (state) {
                            TelephonyManager.CALL_STATE_RINGING -> TelephonyManager.EXTRA_STATE_RINGING
                            TelephonyManager.CALL_STATE_OFFHOOK -> TelephonyManager.EXTRA_STATE_OFFHOOK
                            else -> TelephonyManager.EXTRA_STATE_IDLE
                        }
                        handlePhoneState(stateStr, currentCallNumber)
                    }
                }
                telephonyCallback = callback
                telephonyManager?.registerTelephonyCallback(mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                phoneStateListener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        val stateStr = when (state) {
                            TelephonyManager.CALL_STATE_RINGING -> TelephonyManager.EXTRA_STATE_RINGING
                            TelephonyManager.CALL_STATE_OFFHOOK -> TelephonyManager.EXTRA_STATE_OFFHOOK
                            else -> TelephonyManager.EXTRA_STATE_IDLE
                        }
                        handlePhoneState(stateStr, phoneNumber ?: "")
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startSmsQueueMonitoring() {
        serviceScope.launch {
            repository.getSmsQueueFlow().collectLatest { queueList ->
                for (item in queueList) {
                    if (item.estado == "PENDIENTE" && item.telefono.isNotBlank()) {
                        sendNativeSms(item)
                    }
                }
            }
        }
    }

    private fun sendNativeSms(item: SmsQueueItem) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applicationContext.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val text = if (item.mensaje.isNotBlank()) {
                item.mensaje
            } else if (item.enlace.isNotBlank()) {
                "CUERPO DE BOMBEROS: Por favor presione el siguiente enlace para compartir su ubicacion exacta con la Central: ${item.enlace}"
            } else {
                "CUERPO DE BOMBEROS: Mensaje informativo de la Central de Alarmas."
            }

            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(item.telefono, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(item.telefono, null, text, null, null)
            }

            repository.updateSmsStatus(
                smsId = item.id,
                idDespacho = item.idDespacho,
                estado = "ENVIADO"
            )
            totalSmsSentCount++
        } catch (e: Exception) {
            e.printStackTrace()
            repository.updateSmsStatus(
                smsId = item.id,
                idDespacho = item.idDespacho,
                estado = "FALLIDO",
                errorMsg = e.message ?: "Error al despachar SMS"
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SENTINEL LINK Central Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitoreo 24/7 de llamadas y pasarela de SMS para Central de Alarmas"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SENTINEL LINK - Central de Alarmas")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.logo)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    (it as? TelephonyCallback)?.let { cb ->
                        telephonyManager?.unregisterTelephonyCallback(cb)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                phoneStateListener?.let {
                    telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 9110
        const val CHANNEL_ID = "sisbom_tel_service_channel"

        var activeIncomingCallNumber: String = ""
        var activeIncomingCallStatus: String = "DISPONIBLE"
        var totalSmsSentCount: Int = 0

        fun startService(context: Context) {
            val intent = Intent(context, SisBomTelService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }
}
