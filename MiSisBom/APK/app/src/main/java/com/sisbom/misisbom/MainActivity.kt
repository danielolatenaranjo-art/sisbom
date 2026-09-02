package com.sisbom.misisbom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.core.app.ActivityCompat
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import coil.compose.AsyncImage

object PlayedSoundsTracker {
    private val playedDispatchIds = LinkedHashSet<String>()

    fun hasPlayed(dispatchId: String): Boolean {
        synchronized(this) {
            return playedDispatchIds.contains(dispatchId)
        }
    }

    fun markPlayed(dispatchId: String) {
        synchronized(this) {
            if (playedDispatchIds.size > 200) {
                val iterator = playedDispatchIds.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            playedDispatchIds.add(dispatchId)
        }
    }
}

object NotificationHelper {

    fun clearAllNotifications(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancelAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val dispatchSoundResId = context.resources.getIdentifier("despacho", "raw", context.packageName)
            val dispatchSound = if (dispatchSoundResId != 0) {
                Uri.parse("android.resource://" + context.packageName + "/" + dispatchSoundResId)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            val alertSoundResId = context.resources.getIdentifier("alerta", "raw", context.packageName)
            val alertSound = if (alertSoundResId != 0) {
                Uri.parse("android.resource://" + context.packageName + "/" + alertSoundResId)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            val alarmAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val notificationAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            // 1. Dispatch Urgent Channel (0-9)
            val dispatchUrgentChannel = NotificationChannel(
                "sisbom_dispatch_urgent_v1",
                "Despachos Urgentes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarmas de despachos de emergencias (Disponible)"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1200, 400, 1200, 400, 1200, 400, 1200)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(dispatchUrgentChannel)

            // 2. Dispatch Silent Channel (0-8)
            val dispatchSilentChannel = NotificationChannel(
                "sisbom_dispatch_silent_v1",
                "Despachos Silenciosos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarmas de despachos de emergencias sin sonido (Fuera de Servicio)"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1200, 400, 1200, 400, 1200, 400, 1200)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(dispatchSilentChannel)

            // 3. Critical Alerts & Orders Channel (Alerts Grade 3 / Orders)
            val alertsCriticalChannel = NotificationChannel(
                "sisbom_alertas_critical_v10",
                "Alertas Críticas y Órdenes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Órdenes del día y alertas de máxima urgencia"
                setSound(alertSound, alarmAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(alertsCriticalChannel)

            // 4. Normal Alerts & Chats Channel (Chats / Alerts Grade 1-2)
            val alertsNormalChannel = NotificationChannel(
                "sisbom_alertas_normal_v10",
                "Chats y Avisos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de chats y avisos comunes"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), notificationAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 100, 300)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(alertsNormalChannel)

            // 5. Silent Confirmation Channel
            val silentChannel = NotificationChannel(
                "sisbom_actions_v1",
                "Confirmaciones del Sistema",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Respuestas a botones"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(silentChannel)

            // 6. Alertas Grado 1 (Notificación visual únicamente)
            val alertsG1Channel = NotificationChannel(
                "sisbom_alertas_g1_v1",
                "Alertas - Grado 1 (Silencioso)",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones de alerta visuales sin sonido ni vibración"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(alertsG1Channel)

            // 7. Alertas Grado 2 (Vibración únicamente)
            val alertsG2Channel = NotificationChannel(
                "sisbom_alertas_g2_v1",
                "Alertas - Grado 2 (Vibración)",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones de alerta con vibración y sin sonido"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            notificationManager.createNotificationChannel(alertsG2Channel)

            // 8. Alertas Grado 3 (Sonido Fuerte + Vibración)
            val alertsG3Channel = NotificationChannel(
                "sisbom_alertas_g3_v1",
                "Alertas - Grado 3 (Sonido Fuerte)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de alerta con vibración y sonido fuerte"
                setSound(alertSound, alarmAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(alertsG3Channel)
        }
    }

    fun scheduleRepeatAlert(context: Context, idServicio: String, clave: String, is09: Boolean) {
        if (!is09) return
        val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        serviceScope.launch {
            kotlinx.coroutines.delay(60000) // 1 minute
            
            // Check if user is still 0-9 and has not assisted
            val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val isAirplaneMode = prefs.getBoolean("MODO_AVION", false)
            if (isAirplaneMode) return@launch
            val cachedUser = prefs.getString("fire_user", null)
            var enServicio = "0"
            val userStatus = if (cachedUser != null) {
                try {
                    val userObj = org.json.JSONObject(cachedUser)
                    enServicio = userObj.optString("enServicio", "0").trim()
                    userObj.optString("estado", "").trim().uppercase()
                } catch (_: Exception) { "" }
            } else { "" }
            
            if (userStatus != "0-9") return@launch
            if (enServicio != "0" && enServicio.isNotEmpty() && !enServicio.startsWith("-")) return@launch
            
            val isCentral = prefs.getBoolean("IS_CENTRAL_MODE", false)
            if (isCentral) return@launch

            // Also check Firestore to be absolutely certain they have not assisted or changed status in the last 60 seconds
            val userId = prefs.getString("USER_ID", "") ?: ""
            if (userId.isNotEmpty()) {
                try {
                    val db = FirebaseFirestore.getInstance()
                    val userTask = db.collection("personal").document(userId).get()
                    val userDoc = Tasks.await(userTask, 3, TimeUnit.SECONDS)
                    if (userDoc.exists()) {
                        val freshEstado = userDoc.getString("estado")?.trim()?.uppercase() ?: ""
                        val freshEnServicio = userDoc.getString("enServicio")?.trim() ?: "0"
                        if (freshEstado != "0-9" || (freshEnServicio != "0" && freshEnServicio.isNotEmpty() && !freshEnServicio.startsWith("-"))) {
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // Check if the dispatch is still active in Firestore
            try {
                val db = FirebaseFirestore.getInstance()
                val task = db.collection("despachos").document(idServicio).get()
                val doc = Tasks.await(task, 5, TimeUnit.SECONDS)
                if (doc.exists()) {
                    val operadorFinal = doc.getString("operadorFinal") ?: ""
                    val fechaDespacho = doc.getString("fechaDespacho") ?: ""
                    val horaDespacho = doc.getString("horaDespacho") ?: ""
                    if (operadorFinal.isEmpty()) {
                        if (TimeValidation.isTooOld(fechaDespacho, horaDespacho)) {
                            return@launch
                        }
                        // Still active! Sound the alarm again!
                        var cleanClave = clave.trim().replace("-", "_").replace(" ", "_").lowercase()
                        if (cleanClave.isEmpty()) {
                            val fullText = (doc.getString("clave") ?: "").lowercase()
                            val keys = listOf("10_0", "10_1", "10_2", "10_3", "10_4", "10_5", "10_6", "10_7", "10_8", "10_9", "10_10", "10_12", "10_15", "10_30")
                            for (k in keys) {
                                if (fullText.contains(k.replace("_", "-")) || fullText.contains(k)) {
                                    cleanClave = k
                                    break
                                }
                            }
                        }
                        val possibleSound = if (cleanClave.startsWith("c10")) cleanClave else "c$cleanClave"
                        val resId = context.resources.getIdentifier(possibleSound, "raw", context.packageName)
                        val soundToPlay = if (resId != 0) possibleSound else "despacho"
                        
                        // Set volumes loud
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val originalRingerMode = audioManager.ringerMode
                        val originalNotifVol = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                        val originalAlarmVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                        val originalMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        
                        try {
                            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                            val maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
                            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotif, 0)
                            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                        } catch (_: Exception) {}
                        
                        SoundPlayer.playSound(context, soundToPlay)
                        
                        // Restore volume after 10s
                        kotlinx.coroutines.delay(10000)
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotifVol, 0)
                            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVol, 0)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVol, 0)
                            audioManager.ringerMode = originalRingerMode
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendNotification(
        context: Context,
        title: String,
        message: String,
        payloadId: String,
        userId: String,
        type: String,
        isFromFCM: Boolean = false,
        claveOpt: String = "",
        gradoAlerta: String = "1",
        forceSilent: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val isAirplaneMode = prefs.getBoolean("MODO_AVION", false)

        val cachedUser = prefs.getString("fire_user", null)
        val (userStatus, hasCDS) = if (cachedUser != null) {
            try {
                val json = org.json.JSONObject(cachedUser)
                val st = json.optString("estado", "").trim().uppercase()
                val cdsVal = json.opt("cds")
                val isCds = when (cdsVal) {
                    is Number -> cdsVal.toInt() == 1
                    is String -> cdsVal.equals("SI", ignoreCase = true) || cdsVal == "1" || cdsVal.uppercase().contains("CDS")
                    else -> false
                } || st == "CDS" || st.contains("CDS") || st.contains("COMISION") || st.contains("COMISIÓN")
                Pair(st, isCds)
            } catch (_: Exception) { Pair("", false) }
        } else { Pair("", false) }

        val isExcluded = userStatus.contains("SUSPENDIDO") || 
                         userStatus.contains("LICENCIA") || 
                         userStatus == "PERMISO"

        if (isAirplaneMode || isExcluded) return

        val ignoredPayloads = prefs.getStringSet("IGNORED_PAYLOADS", emptySet()) ?: emptySet()
        val isUnavailable = prefs.getString("IS_UNAVAILABLE", "false") == "true"

        val is08 = isUnavailable || userStatus == "0-8" || userStatus == "10-8"

        val userCargo = if (cachedUser != null) {
            try {
                org.json.JSONObject(cachedUser).optString("cargo", "").trim().uppercase()
            } catch (_: Exception) { "" }
        } else { "" }

        // If user is a Bombero Honorario, do not show or play anything for dispatches or requests
        if (userCargo == "BOMBERO HONORARIO" && (type == "DISPATCH" || type == "DISPATCH_UPDATE")) {
            return
        }

        if (type != "STATUS_CHANGE" && isFromFCM && MainActivity.isAppInForeground) return
        // Do NOT block dispatches when user is 0-8 (isUnavailable)
        if (type == "DISPATCH" && is08) {
            // Let it proceed
        } else if (payloadId.isNotEmpty() && ignoredPayloads.contains(payloadId)) {
            return
        }

        // Active central operator DND mode
        val isCentral = prefs.getBoolean("IS_CENTRAL_MODE", false)

        val channelId = when (type) {
            "STATUS_CHANGE" -> "sisbom_alertas_normal_v10"
            "DISPATCH" -> {
                if (isCentral) "sisbom_actions_v1"
                else if (is08) "sisbom_dispatch_silent_v1"
                else "sisbom_dispatch_urgent_v1"
            }
            "DISPATCH_UPDATE" -> {
                if (isCentral) "sisbom_actions_v1"
                else if (is08) "sisbom_dispatch_silent_v1"
                else "sisbom_dispatch_urgent_v1"
            }
            "CHAT" -> {
                if (isCentral) "sisbom_actions_v1"
                else "sisbom_alertas_normal_v10"
            }
            "ALERT" -> {
                if (isCentral) {
                    "sisbom_actions_v1"
                } else if (is08) {
                    "sisbom_dispatch_silent_v1"
                } else {
                    val isOrden = title.contains("ORDEN", ignoreCase = true)
                    if (isOrden) {
                        "sisbom_alertas_critical_v10"
                    } else {
                        when (gradoAlerta) {
                            "1" -> "sisbom_alertas_g1_v1"
                            "2" -> "sisbom_alertas_g2_v1"
                            "3" -> "sisbom_alertas_g3_v1"
                            else -> "sisbom_alertas_g1_v1"
                        }
                    }
                }
            }
            "ORDEN" -> {
                if (isCentral) "sisbom_actions_v1"
                else if (is08) "sisbom_dispatch_silent_v1"
                else "sisbom_alertas_critical_v10"
            }
            else -> "sisbom_alertas_normal_v10"
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalRingerMode = audioManager.ringerMode
        val originalNotifVol = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val originalAlarmVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val originalMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        val is1210 = title.contains("12-10") || message.contains("12-10")
        val is66 = title.contains("6-6") || message.contains("6-6")
        val isOrden = type == "ORDEN" || title.contains("ORDEN", ignoreCase = true)
        val isGrade3 = type == "ALERT" && gradoAlerta == "3"
        val is1030 = (type == "DISPATCH" || type == "DISPATCH_UPDATE") && (claveOpt.trim() == "10-30" || title.contains("10-30") || message.contains("10-30"))

        val isConductor = if (cachedUser != null) {
            try {
                val uJson = org.json.JSONObject(cachedUser)
                val condInt = uJson.optInt("conductor", 0)
                val condStr = uJson.optString("conductor", "")
                val cargoStr = uJson.optString("cargo", "").uppercase()
                val radStr = uJson.optString("idRadial", "").uppercase()
                condInt == 1 || condStr == "1" || cargoStr.contains("CONDUCTOR") || cargoStr.contains("MAQUINISTA") || radStr.startsWith("C")
            } catch (_: Exception) { false }
        } else { false }

        if (is1210 && !isConductor) {
            return
        }

        val userEnServicio = if (cachedUser != null) {
            try {
                org.json.JSONObject(cachedUser).optString("enServicio", "").trim()
            } catch (_: Exception) { "" }
        } else { "" }

        // If user already pressed "Asistir" for this specific dispatch, do NOT notify 12-10 or 6-6
        if ((is1210 || is66) && userEnServicio.isNotEmpty() && userEnServicio == payloadId) {
            return
        }

        val hasDeclined = userEnServicio.startsWith("-") || (payloadId.isNotEmpty() && ignoredPayloads.contains(payloadId))
        val is09 = userStatus == "0-9"
        val isCDS = hasCDS

        var playLoud = false
        var forceVibrateOnly = false

        if (is1210 || is66) {
            if (is09 && !hasDeclined) {
                // 0-9 and has NOT declined: Ring loud with alerta.mp3 + vibration
                playLoud = !isCentral
            } else {
                // 0-8 or has declined: Only strong vibration without loud sound
                playLoud = false
                forceVibrateOnly = !isCentral
            }
        } else if (is1030) {
            // Alarma 10-30 declarada: Suena c10_30.mp3 para:
            // 1. Bomberos en 0-9 que no hayan puesto Asistir (o que hayan puesto No Asistir).
            // 2. Bomberos en 0-8 (sin silencio absoluto).
            // Excluidos: Silencio absoluto, 0-8 absoluto, CDS, licencia médica, suspendido, o si ya asiste a este despacho.
            val isAbsoluteSilence = prefs.getBoolean("SILENCIO_ABSOLUTO", false) || userStatus.contains("ABSOLUTO")
            val isAttending = userEnServicio.isNotEmpty() && userEnServicio == payloadId
            if (isCDS || isExcluded || isAbsoluteSilence || isAttending) {
                playLoud = false
            } else {
                playLoud = !isCentral
            }
        } else {
            playLoud = (type == "DISPATCH" || type == "DISPATCH_UPDATE" || isOrden || isGrade3) && !isCentral
            if ((is08 || isCDS)) {
                playLoud = false
            }
            if (forceSilent) {
                playLoud = false
            }
        }

        if (playLoud) {
            try {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                val maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
                val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotif, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            } catch (_: Exception) {}

            val soundToPlay = if (is1210 || is66) {
                "alerta"
            } else if (is1030) {
                "c10_30"
            } else if (type == "DISPATCH" || type == "DISPATCH_UPDATE") {
                var detectedKey = claveOpt.trim().replace("-", "_").replace(".", "_").replace(" ", "_").lowercase()
                val fullText = "$title $message".lowercase()

                if (detectedKey.contains("llamado") || detectedKey.contains("comandancia") || fullText.contains("llamado comandancia")) {
                    "llamado_comandancia"
                } else {
                    if (detectedKey == "9_0" || detectedKey == "9.0" || fullText.contains("9-0") || fullText.contains("9_0")) {
                        detectedKey = "9_0"
                    }
                    if (detectedKey.isEmpty()) {
                        val keys = listOf("10_0", "10_1", "10_2", "10_3", "10_4", "10_5", "10_6", "10_7", "10_8", "10_9", "10_10", "10_12", "10_15", "10_30", "9_0")
                        for (k in keys) {
                            if (fullText.contains(k.replace("_", "-")) || fullText.contains(k)) {
                                detectedKey = k
                                break
                            }
                        }
                    }
                    if (detectedKey.isNotEmpty()) {
                        val possibleSound = if (detectedKey == "9_0") "c9_0" else if (detectedKey.startsWith("c9") || detectedKey.startsWith("c10")) detectedKey else "c$detectedKey"
                        val resId = context.resources.getIdentifier(possibleSound, "raw", context.packageName)
                        if (resId != 0) possibleSound else if (detectedKey == "9_0") "c10_9" else if (detectedKey.contains("10_30")) "c10_30" else "despacho"
                    } else {
                        "despacho"
                    }
                }
            } else if (isOrden || isGrade3) {
                "alerta"
            } else {
                "alerta"
            }

            val isDispatchOr1030 = type == "DISPATCH" || (type == "DISPATCH_UPDATE" && is1030)
            if (isDispatchOr1030 && payloadId.isNotEmpty()) {
                val trackerKey = if (is1030) payloadId + "_10_30" else payloadId
                if (!PlayedSoundsTracker.hasPlayed(trackerKey)) {
                    PlayedSoundsTracker.markPlayed(trackerKey)
                    if (is1030) {
                        PlayedSoundsTracker.markPlayed(payloadId)
                    }
                    if (playLoud) {
                        SoundPlayer.playSound(context, soundToPlay)
                        // Schedule repeat after 1 minute
                        scheduleRepeatAlert(context, payloadId, claveOpt.ifEmpty { soundToPlay }, true)
                    }
                }
            } else if (playLoud) {
                SoundPlayer.playSound(context, soundToPlay)
            }

            thread {
                try {
                    Thread.sleep(10000)
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotifVol, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVol, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVol, 0)
                    audioManager.ringerMode = originalRingerMode
                } catch (_: Exception) {}
            }
        } else {
            // Trigger custom strong/long vibration if dispatch/update or forceVibrateOnly
            val isDispatchOrUpdate = (type == "DISPATCH" || type == "DISPATCH_UPDATE")
            if ((isDispatchOrUpdate || forceVibrateOnly) && !isCentral && !forceSilent) {
                SoundPlayer.triggerVibration(context, true)
            }
        }

        val notificationId =
            if (payloadId.isNotEmpty()) payloadId.hashCode() else System.currentTimeMillis().toInt()

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (type == "DISPATCH" || type == "DISPATCH_UPDATE") {
                putExtra("DISPATCH_ID", payloadId)
                putExtra("SHOW_FULLSCREEN_ALERT", true)
            } else if (type == "CHAT") {
                putExtra("CHAT_ID", payloadId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIcon = try {
            val clientFile = java.io.File(context.filesDir, "client_logo.png")
            if (clientFile.exists() && clientFile.length() > 0) {
                BitmapFactory.decodeFile(clientFile.absolutePath)
            } else {
                val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                val key = prefs.getString("saas_license_key", "") ?: ""
                var resId = 0
                if (key.isNotEmpty()) {
                    val formattedKey = key.lowercase().replace("-", "_")
                    resId = context.resources.getIdentifier("logo_$formattedKey", "drawable", context.packageName)
                }
                val finalRes = if (resId != 0) resId else R.drawable.logo
                BitmapFactory.decodeResource(context.resources, finalRes)
            }
        } catch (_: Throwable) {
            null
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_firefighter)
            .apply {
                if (largeIcon != null) {
                    setLargeIcon(largeIcon)
                }
            }
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .apply {
                if (type == "DISPATCH" && !is08 && !isCentral) {
                    setFullScreenIntent(pendingIntent, true)
                    setCategory(NotificationCompat.CATEGORY_CALL)
                }
            }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            var soundResId = 0

            if (type == "DISPATCH") {
                if (!is08 || is1030) {
                    soundResId = context.resources.getIdentifier("despacho", "raw", context.packageName)
                }
            } else {
                val isOrden = title.contains("ORDEN", ignoreCase = true)
                if (isOrden || gradoAlerta == "3") {
                    soundResId = context.resources.getIdentifier("alerta", "raw", context.packageName)
                }
            }

            if (soundResId != 0 && !forceSilent) {
                builder.setSound(Uri.parse("android.resource://" + context.packageName + "/" + soundResId))
            } else {
                if (forceSilent) {
                    builder.setSound(null)
                } else {
                    builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
                }
            }

            if (type == "DISPATCH_UPDATE" && is08 && !forceSilent) {
                builder.setVibrate(longArrayOf(0, 1000, 300, 1000, 300, 1000))
            }
        }

        if (payloadId.isNotEmpty() && userId.isNotEmpty()) {
            val baseIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                putExtra("PAYLOAD_ID", payloadId)
                putExtra("USER_ID", userId)
                putExtra("NOTIFICATION_ID", notificationId)
            }

            if (type == "DISPATCH" || type == "DISPATCH_UPDATE") {
                baseIntent.putExtra("ACTION_TYPE", "DISPATCH")
                val actionPending = PendingIntent.getBroadcast(
                    context,
                    payloadId.hashCode(),
                    baseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_directions, "ASISTIR", actionPending)
            } else if (type == "CHAT") {
                baseIntent.putExtra("ACTION_TYPE", "CHAT_REPLY")
                val replyPending = PendingIntent.getBroadcast(
                    context,
                    payloadId.hashCode() + 1,
                    baseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                val remoteInput = RemoteInput.Builder("KEY_TEXT_REPLY")
                    .setLabel("Escribe un mensaje...")
                    .build()

                val replyAction = NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    "Responder",
                    replyPending
                ).addRemoteInput(remoteInput).build()

                builder.addAction(replyAction)
            } else if (type == "ALERT") {
                val ackIntent = Intent(baseIntent).apply {
                    putExtra("ACTION_TYPE", "ALERT_ACK")
                }
                val ackPending = PendingIntent.getBroadcast(
                    context,
                    payloadId.hashCode() + 2,
                    ackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_agenda, "Dar Conforme", ackPending)

                val pinIntent = Intent(baseIntent).apply {
                    putExtra("ACTION_TYPE", "ALERT_PIN")
                }
                val pinPending = PendingIntent.getBroadcast(
                    context,
                    payloadId.hashCode() + 3,
                    pinIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_save, "Anclar", pinPending)
            }
        }

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notify(notificationId, builder.build())
            }
        }
    }
}

class DispatchForegroundService : Service() {

    private var dbListener1: com.google.firebase.firestore.ListenerRegistration? = null
    private var dbListener2: com.google.firebase.firestore.ListenerRegistration? = null

    companion object {
        const val CHANNEL_ID = "sisbom_foreground_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "ACTION_START_FOREGROUND"
        const val ACTION_STOP = "ACTION_STOP_FOREGROUND"

        fun startService(context: Context) {
            val intent = Intent(context, DispatchForegroundService::class.java).apply {
                action = ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DispatchForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    START_NOT_STICKY
                }

                ACTION_START, null -> {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    startFirebaseListeners()
                    START_STICKY
                }

                else -> START_STICKY
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startFirebaseListeners() {
        try {
            val prefs = getSharedPreferences("SisBomPrefs", MODE_PRIVATE)
            val userId = prefs.getString("USER_ID", "") ?: ""
            if (userId.isNotEmpty()) {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                
                if (dbListener1 == null) {
                    dbListener1 = db.collection("personal").document(userId)
                        .addSnapshotListener { snapshot, error ->
                            if (error == null && snapshot != null && snapshot.exists()) {
                                val data = snapshot.data
                                if (data != null) {
                                    val json = org.json.JSONObject()
                                    data.forEach { (k, v) -> json.put(k, v) }
                                    prefs.edit().putString("fire_user", json.toString()).apply()
                                    
                                    val estado = snapshot.getString("estado") ?: ""
                                    val isUnavailable = (estado == "0-8" || estado == "10-8")
                                    prefs.edit().putString("IS_UNAVAILABLE", isUnavailable.toString()).apply()

                                    val enServicio = snapshot.getString("enServicio") ?: "0"
                                    val solicitarGpsTimestamp = snapshot.getLong("solicitarGpsTimestamp") ?: 0L
                                    val solicitarGpsServiceId = snapshot.getString("solicitarGpsServiceId") ?: enServicio
                                    val isRecentGpsRequest = (System.currentTimeMillis() - solicitarGpsTimestamp) < 120000L // Menos de 2 minutos

                                    if (isRecentGpsRequest && solicitarGpsServiceId.isNotEmpty() && solicitarGpsServiceId != "0" && !solicitarGpsServiceId.startsWith("-")) {
                                        startGpsTracking(solicitarGpsServiceId, snapshot)
                                    } else {
                                        checkAndManageGpsTracking(enServicio, snapshot)
                                    }
                                }
                            }
                        }
                }

                if (dbListener2 == null) {
                    dbListener2 = db.collection("despachos").whereEqualTo("operadorFinal", "")
                        .addSnapshotListener { snapshot, error ->
                            if (error == null && snapshot != null) {
                                snapshot.documentChanges.forEach { change ->
                                    val doc = change.document
                                    val idServicio = doc.id
                                    val clave = doc.getString("clave") ?: ""
                                    val claveApoyo = doc.getString("claveApoyo") ?: ""
                                    val lugar = doc.getString("lugar") ?: ""
                                    val preinforme = doc.getString("preinforme") ?: ""
                                    val fechaDespacho = doc.getString("fechaDespacho") ?: ""
                                    val horaDespacho = doc.getString("horaDespacho") ?: ""
                                    val is1030 = clave.contains("10-30") || preinforme.contains("10-30")
                                    val trackerKey = if (is1030) idServicio + "_10_30" else idServicio
                                    val isTooOld = TimeValidation.isTooOld(fechaDespacho, horaDespacho)
                                    val cachedUser = prefs.getString("fire_user", null)
                                    val enServicio = if (cachedUser != null) {
                                        try {
                                            org.json.JSONObject(cachedUser).optString("enServicio", "").trim()
                                        } catch (_: Exception) { "" }
                                    } else { "" }
                                    val inService = enServicio.isNotEmpty() && enServicio != "0" && !enServicio.startsWith("-")
                                    val shouldBeSilent = isTooOld || inService
                                    
                                    if (is1030) {
                                        if (!PlayedSoundsTracker.hasPlayed(trackerKey)) {
                                            PlayedSoundsTracker.markPlayed(trackerKey)
                                            PlayedSoundsTracker.markPlayed(idServicio)
                                            val title = "ALARMA 10-30 DECLARADA"
                                            val body = "$clave • $lugar"
                                            val isAttendingThisDispatch = enServicio.isNotEmpty() && enServicio == idServicio
                                            NotificationHelper.sendNotification(
                                                context = this@DispatchForegroundService,
                                                title = title,
                                                message = body,
                                                payloadId = idServicio,
                                                userId = userId,
                                                type = "DISPATCH_UPDATE",
                                                isFromFCM = true,
                                                claveOpt = "10-30",
                                                gradoAlerta = "3",
                                                forceSilent = isTooOld || isAttendingThisDispatch
                                            )
                                        }
                                    } else if (!PlayedSoundsTracker.hasPlayed(trackerKey)) {
                                        val isModified = change.type == com.google.firebase.firestore.DocumentChange.Type.MODIFIED
                                        if (!isModified) {
                                            val title = "NUEVO DESPACHO"
                                            val body = if (clave == "10-12" && claveApoyo.isNotEmpty()) {
                                                 "$clave ($claveApoyo) • $lugar"
                                            } else {
                                                 "$clave • $lugar"
                                            }
                                            
                                            NotificationHelper.sendNotification(
                                                context = this@DispatchForegroundService,
                                                title = title,
                                                message = body,
                                                payloadId = idServicio,
                                                userId = userId,
                                                type = "DISPATCH",
                                                isFromFCM = true,
                                                claveOpt = clave,
                                                gradoAlerta = "3",
                                                forceSilent = shouldBeSilent
                                            )
                                        }
                                    }

                                    // Unit-level 12-10 and 6-6 Checks
                                    val unidadesMap = doc.get("unidades") as? Map<*, *>
                                    if (unidadesMap != null) {
                                        for ((unitKey, unitVal) in unidadesMap) {
                                            val uMap = unitVal as? Map<*, *> ?: continue
                                            val uName = unitKey.toString()
                                            
                                            // Check 12-10
                                            val solCondTs = (uMap["solicitudConductorTimestamp"] as? Number)?.toLong() ?: 0L
                                            val solCondAt = uMap["solicitudConductorAt"]?.toString() ?: ""
                                            if (solCondAt.isNotEmpty() || solCondTs > 0L) {
                                                val key1210 = "${idServicio}_1210_${uName}_${if (solCondTs > 0L) solCondTs else solCondAt}"
                                                if (!PlayedSoundsTracker.hasPlayed(key1210)) {
                                                    PlayedSoundsTracker.markPlayed(key1210)
                                                    NotificationHelper.sendNotification(
                                                        context = this@DispatchForegroundService,
                                                        title = "SOLICITUD 12-10: $uName",
                                                        message = "Se solicita Conductor para la unidad $uName ($clave en $lugar)",
                                                        payloadId = idServicio,
                                                        userId = userId,
                                                        type = "DISPATCH_UPDATE",
                                                        isFromFCM = false,
                                                        claveOpt = clave,
                                                        gradoAlerta = "3",
                                                        forceSilent = isTooOld
                                                    )
                                                }
                                            }

                                            // Check 6-6
                                            val solPersTs = (uMap["solicitudPersonalTimestamp"] as? Number)?.toLong() ?: 0L
                                            val solPersAt = uMap["solicitudPersonalAt"]?.toString() ?: ""
                                            if (solPersAt.isNotEmpty() || solPersTs > 0L) {
                                                val key66 = "${idServicio}_66_${uName}_${if (solPersTs > 0L) solPersTs else solPersAt}"
                                                if (!PlayedSoundsTracker.hasPlayed(key66)) {
                                                    PlayedSoundsTracker.markPlayed(key66)
                                                    NotificationHelper.sendNotification(
                                                        context = this@DispatchForegroundService,
                                                        title = "SOLICITUD 6-6: $uName",
                                                        message = "Se solicita Personal para la unidad $uName ($clave en $lugar)",
                                                        payloadId = idServicio,
                                                        userId = userId,
                                                        type = "DISPATCH_UPDATE",
                                                        isFromFCM = false,
                                                        claveOpt = clave,
                                                        gradoAlerta = "3",
                                                        forceSilent = isTooOld
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var locationListener: android.location.LocationListener? = null
    private var activeGpsServiceId: String? = null
    private val gpsTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var gpsTimeoutRunnable: Runnable? = null

    private fun checkAndManageGpsTracking(enServicio: String, snapshot: com.google.firebase.firestore.DocumentSnapshot) {
        val cleanEnServicio = enServicio.trim()
        val isAttending = cleanEnServicio.isNotEmpty() && cleanEnServicio != "0" && !cleanEnServicio.startsWith("-")
        
        if (!isAttending) {
            stopGpsTracking()
            return
        }

        if (activeGpsServiceId == cleanEnServicio && locationListener != null) {
            return
        }

        startGpsTracking(cleanEnServicio, snapshot)
    }

    private fun startGpsTracking(serviceId: String, snapshot: com.google.firebase.firestore.DocumentSnapshot) {
        stopGpsTracking()
        activeGpsServiceId = serviceId

        // Temporizador estricto de 5 minutos (300.000 ms) para auto-detención del GPS
        gpsTimeoutRunnable?.let { gpsTimeoutHandler.removeCallbacks(it) }
        val timeoutRunnable = Runnable {
            android.util.Log.d("SisBom", "⏱️ Límite de 5 minutos alcanzado para GPS de bombero. Auto-deteniendo.")
            stopGpsTracking()
        }
        gpsTimeoutRunnable = timeoutRunnable
        gpsTimeoutHandler.postDelayed(timeoutRunnable, 5 * 60 * 1000L)

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("SisBom", "Permisos de ubicación no otorgados para rastreo en servicio foreground")
            return
        }

        try {
            val locationManager = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager ?: return
            val idRegistro = snapshot.getString("idRegistro") ?: snapshot.id
            val idRadial = snapshot.getString("idRadial") ?: ""
            val nombre = snapshot.getString("nombreBombero") ?: "Bombero"
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()

            val cuartelLat = -34.637373
            val cuartelLng = -71.125741

            fun sendLocationUpdate(loc: android.location.Location) {
                if (activeGpsServiceId != serviceId) return

                val now = System.currentTimeMillis()
                val horaStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now))

                val locData = hashMapOf<String, Any>(
                    "idRegistro" to idRegistro,
                    "idRadial" to idRadial,
                    "nombre" to nombre,
                    "asistira" to true,
                    "enServicio" to serviceId,
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "accuracy" to loc.accuracy,
                    "hora" to horaStr,
                    "timestamp" to now
                )

                // 1. Escribir en subcolección despachos/{serviceId}/asistencias/{userId}
                db.collection("despachos").document(serviceId).collection("asistencias").document(idRegistro)
                    .set(locData, com.google.firebase.firestore.SetOptions.merge())

                // 2. Escribir también en personal/{userId}
                db.collection("personal").document(idRegistro)
                    .update(
                        mapOf(
                            "lat" to loc.latitude,
                            "lng" to loc.longitude,
                            "gpsAccuracy" to loc.accuracy,
                            "gpsHora" to horaStr,
                            "gpsTimestamp" to now
                        )
                    )

                // 3. Chequear proximidad al Cuartel (<= 100m)
                val distResults = FloatArray(1)
                android.location.Location.distanceBetween(loc.latitude, loc.longitude, cuartelLat, cuartelLng, distResults)
                val distMetros = distResults[0]

                if (distMetros <= 100f) {
                    android.util.Log.d("SisBom", "Bombero llegó al Cuartel ($distMetros m). Auto-apagando GPS.")
                    stopGpsTracking()
                    NotificationHelper.sendNotification(
                        context = this@DispatchForegroundService,
                        title = "Llegada al Cuartel",
                        message = "Has llegado al Cuartel ($distMetros m). GPS desconectado.",
                        payloadId = serviceId,
                        userId = idRegistro,
                        type = "INFO",
                        isFromFCM = false,
                        forceSilent = true
                    )
                }
            }

            val lastGps = try { locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) } catch(_: SecurityException) { null }
            val lastNet = try { locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) } catch(_: SecurityException) { null }
            val bestLast = if (lastGps != null && lastNet != null) {
                if (lastGps.time > lastNet.time) lastGps else lastNet
            } else lastGps ?: lastNet
            if (bestLast != null) {
                sendLocationUpdate(bestLast)
            }

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    sendLocationUpdate(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationListener = listener

            if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    android.location.LocationManager.GPS_PROVIDER,
                    15000L,
                    0f,
                    listener,
                    android.os.Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    android.location.LocationManager.NETWORK_PROVIDER,
                    15000L,
                    0f,
                    listener,
                    android.os.Looper.getMainLooper()
                )
            }

            android.util.Log.d("SisBom", "Rastreo GPS de fondo activado para servicio $serviceId (5 min max)")
        } catch (e: Exception) {
            android.util.Log.e("SisBom", "Error iniciando GPS en foreground service: ${e.message}")
        }
    }

    private fun stopGpsTracking() {
        gpsTimeoutRunnable?.let {
            gpsTimeoutHandler.removeCallbacks(it)
            gpsTimeoutRunnable = null
        }
        try {
            if (locationListener != null) {
                val locationManager = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager
                locationManager?.removeUpdates(locationListener!!)
                locationListener = null
            }
        } catch (e: Exception) {
            android.util.Log.e("SisBom", "Error deteniendo GPS: ${e.message}")
        }
        val svcId = activeGpsServiceId
        val prefs = getSharedPreferences("SisBomPrefs", MODE_PRIVATE)
        val userId = prefs.getString("USER_ID", "") ?: ""
        if (svcId != null && userId.isNotEmpty()) {
            try {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                db.collection("despachos").document(svcId).collection("asistencias").document(userId).delete()
            } catch(_: Exception){}
        }
        activeGpsServiceId = null
    }

    override fun onDestroy() {
        stopGpsTracking()
        try {
            dbListener1?.remove()
            dbListener1 = null
            dbListener2?.remove()
            dbListener2 = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SisBom activo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene SisBom activo para escuchar despachos"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_firefighter)
            .setContentTitle("SisBom activo")
            .setContentText("Escuchando despachos y alertas")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        var isAppInForeground = false

        fun initializeDynamicFirebase(context: Context, configJsonStr: String) {
            try {
                val config = org.json.JSONObject(configJsonStr)
                val apiKey = config.getString("apiKey")
                val authDomain = config.getString("authDomain")
                val projectId = config.getString("projectId")
                val storageBucket = config.getString("storageBucket")
                val messagingSenderId = config.getString("messagingSenderId")
                val appId = config.getString("appId")

                try {
                    com.google.firebase.FirebaseApp.getInstance().delete()
                } catch (_: Exception) {}

                val options = com.google.firebase.FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setApplicationId(appId)
                    .setProjectId(projectId)
                    .setGcmSenderId(messagingSenderId)
                    .setStorageBucket(storageBucket)
                    .build()

                com.google.firebase.FirebaseApp.initializeApp(context, options)
                android.util.Log.d("SisBom", "Dynamic FirebaseApp initialized: $projectId")
            } catch (e: Exception) {
                android.util.Log.e("SisBom", "Error initializing dynamic Firebase: ${e.message}")
            }
        }
    }

    private lateinit var viewModel: SisBomViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        NotificationHelper.clearAllNotifications(this)
        if (::viewModel.isInitialized) {
            viewModel.refreshFromCacheAndFirebase()
        }
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefsTemp = getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        
        // Invalidate attendance cache once to fix abono calculations
        val cacheVersion = prefsTemp.getInt("cache_version_abonos_v2", 0)
        if (cacheVersion < 1) {
            val editor = prefsTemp.edit()
            editor.remove("cache_attendance")
            prefsTemp.all.keys.toList().forEach { key ->
                if (key.startsWith("cached_asistencias_")) {
                    editor.remove(key)
                }
            }
            editor.putInt("cache_version_abonos_v2", 1)
            editor.apply()
        }

        val fbConfigStr = prefsTemp.getString("saas_firebase_config", null)
        if (fbConfigStr != null) {
            initializeDynamicFirebase(this, fbConfigStr)
        }

        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        or android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        }

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        NotificationHelper.createChannels(this)

        val userId = prefsTemp.getString("USER_ID", "") ?: ""
        if (userId.isNotEmpty()) {
            DispatchForegroundService.startService(this)
        }

        viewModel = ViewModelProvider(this)[SisBomViewModel::class.java]

        handleIntent(intent)

        setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDarkMode provides viewModel.isDarkMode) {
                SisBomApp(viewModel)
            }
        }

        askNotificationPermission()
        scheduleDailyReminders(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    if (SoundPlayer.isPlaying()) {
                        SoundPlayer.stop(this)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("DISPATCH_ID")?.let { id ->
            val showFullscreen = intent.getBooleanExtra("SHOW_FULLSCREEN_ALERT", false)
            if (showFullscreen) {
                viewModel.fullscreenDispatchId = id
                intent.removeExtra("SHOW_FULLSCREEN_ALERT")
            }
            viewModel.currentScreen = AppScreen.Main
            viewModel.currentTab = MainTab.Actividad
        }
        intent?.getStringExtra("CHAT_ID")?.let { chatId ->
            viewModel.openChatRoom(chatId)
        }
    }

    fun askNotificationPermission() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.first())
        }
    }
}

@Composable
fun SisBomApp(viewModel: SisBomViewModel) {
    val view = androidx.compose.ui.platform.LocalView.current
    val isDark = LocalDarkMode.current
    val activeChatAlert = viewModel.activeChatAlert
    val currentScreen = viewModel.currentScreen
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
                val isChatOpen = activeChatAlert != null || currentScreen == AppScreen.Chat
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
                
                // Explicitly force transparency
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
                }
            }
        }
    }
    when (viewModel.currentScreen) {
        AppScreen.Setup -> SetupScreen(viewModel)
        AppScreen.Login -> LoginScreen(viewModel)
        AppScreen.Main, AppScreen.Chat -> MainScreen(viewModel)
    }

    var showOverlayPermissionDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(viewModel.currentUser) {
        if (viewModel.currentUser != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(view.context)) {
                    showOverlayPermissionDialog = true
                }
            }
        }
    }

    if (showOverlayPermissionDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            title = { Text("Permiso de Superposición Requerido", fontWeight = FontWeight.Bold) },
            text = { Text("Para recibir alertas de despacho a pantalla completa de forma inmediata cuando el teléfono está bloqueado o usando otra aplicación, debes activar el permiso 'Mostrar sobre otras aplicaciones'.") },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayPermissionDialog = false
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            try {
                                val intent = Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${view.context.packageName}")
                                )
                                view.context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))
                ) {
                    Text("Configurar", color = Color.White)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showOverlayPermissionDialog = false }) {
                    Text("Ahora No")
                }
            }
        )
    }



    val user = viewModel.currentUser
    val dispatches = viewModel.dispatchesList
    val isCentral = viewModel.isCentralActive

    val showFullscreenAlert = androidx.compose.runtime.remember(user, dispatches, isCentral, viewModel.fullscreenDispatchId) {
        if (user != null && user.estado.trim().uppercase() == "0-9" && !isCentral) {
            val inService = user.enServicio.trim().isNotEmpty() && user.enServicio.trim() != "0" && !user.enServicio.trim().startsWith("-")
            if (!inService && user.estado.trim().uppercase() != "NO ASISTIR") {
                val fId = viewModel.fullscreenDispatchId
                if (fId != null) {
                    dispatches.firstOrNull { d ->
                        d.idServicio == fId && d.operadorFinal.isEmpty() && !TimeValidation.isTooOld(d.fechaDespacho, d.horaDespacho)
                    }
                } else null
            } else null
        } else null
    }

    if (showFullscreenAlert != null) {
        val dispatch = showFullscreenAlert
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { /* Bloqueante */ },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                window?.let { w ->
                    w.setLayout(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    w.setStatusBarColor(android.graphics.Color.TRANSPARENT)
                    w.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFB91C1C)) // Red background
                    .systemBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header / Pulsing icon / Logo
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(top = 36.dp)
                    ) {
                        coil.compose.AsyncImage(
                            model = viewModel.getClientLogoModel(),
                            contentDescription = "Logo",
                            placeholder = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                            error = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                            modifier = Modifier
                                .size(75.dp)
                                .clip(CircleShape)
                                .border(2.dp, Color.White, CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "🚨 DESPACHO DE EMERGENCIA 🚨",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "¡CONFIRMA TU ASISTENCIA AHORA!",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    // Dispatch details card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
                        ) {
                            // Clave
                            Column {
                                Text(
                                    text = "CLAVE",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = dispatch.clave,
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            // Preinforme / Referencias
                            if (dispatch.preinforme.isNotEmpty()) {
                                Column {
                                    Text(
                                        text = "REFERENCIAS / PREINFORME",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = dispatch.preinforme,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            // Carros / Unidades
                            if (dispatch.carros.isNotEmpty()) {
                                Column {
                                    Text(
                                        text = "UNIDADES DESPACHADAS",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = dispatch.carros,
                                        color = Color(0xFFFBBF24), // Amber/Yellow
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Hora
                            Column {
                                Text(
                                    text = "HORA",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = dispatch.horaDespacho,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Action buttons
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { 
                                viewModel.attendService(dispatch.idServicio, true)
                                viewModel.fullscreenDispatchId = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)), // Green
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Text(
                                text = "ASISTIR",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Button(
                            onClick = { 
                                viewModel.declineService(dispatch.idServicio)
                                viewModel.fullscreenDispatchId = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text(
                                text = "NO ASISTIR",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        NotificationHelper.createChannels(this)

        val title = message.data["title"] ?: message.notification?.title ?: "SisBom"
        val body = message.data["body"] ?: message.notification?.body ?: "Nueva información disponible"
        val type = message.data["type"] ?: "ALERT"
        val payloadId = message.data["payloadId"] ?: ""
        val clave = message.data["clave"] ?: ""
        val gradoAlerta = message.data["gradoAlerta"] ?: "1"

        val sentTime = message.sentTime
        val now = System.currentTimeMillis()
        val forceSilent = sentTime > 0 && (now - sentTime) > 300000

        val prefs = getSharedPreferences("SisBomPrefs", MODE_PRIVATE)
        val userId = prefs.getString("USER_ID", "") ?: ""

        val senderId = message.data["senderId"] ?: ""
        if (senderId.isNotEmpty() && userId.isNotEmpty() && senderId.trim().lowercase() == userId.trim().lowercase()) {
            return
        }

        if (type == "STATUS_CHANGE") {
            val newStatus = message.data["newStatus"] ?: ""
            if (newStatus.isNotEmpty()) {
                val cachedUser = prefs.getString("fire_user", null)
                if (cachedUser != null) {
                    try {
                        val json = org.json.JSONObject(cachedUser)
                        json.put("estado", newStatus)
                        prefs.edit().putString("fire_user", json.toString()).apply()
                        val isUnavailable = (newStatus == "0-8" || newStatus == "10-8")
                        prefs.edit().putString("IS_UNAVAILABLE", isUnavailable.toString()).apply()
                    } catch (_: Exception) {}
                }
            }

            val lastSelfStatus = prefs.getString("LAST_SELF_STATUS_CHANGE", "") ?: ""
            val lastSelfTime = prefs.getLong("LAST_SELF_STATUS_TIME", 0L)
            val isSelfChange = newStatus.isNotEmpty() &&
                    (newStatus.trim().uppercase() == lastSelfStatus.trim().uppercase()) &&
                    (System.currentTimeMillis() - lastSelfTime < 20000)

            if (!isSelfChange) {
                SoundPlayer.triggerVibration(this, false)
                NotificationHelper.sendNotification(this, title, body, payloadId, userId, "STATUS_CHANGE", true, clave, "1", false)
            }
            return
        }

        if (type != "DISPATCH") {
            NotificationHelper.sendNotification(this, title, body, payloadId, userId, type, true, clave, gradoAlerta, forceSilent)
            return
        }

        if (userId.isNotEmpty()) {
            try {
                val db = FirebaseFirestore.getInstance()
                val task = db.collection("accesos").document("central").get()
                val doc = Tasks.await(task, 2, TimeUnit.SECONDS)

                var isCentral = false
                if (doc.exists()) {
                    val estado = doc.getString("estado")?.trim()?.lowercase() ?: ""
                    val idReg = doc.getString("idRegistro")?.trim()?.lowercase() ?: ""

                    if (estado == "activo" && idReg == userId.trim().lowercase()) {
                        isCentral = true
                    }
                }

                if (!isCentral) {
                    NotificationHelper.sendNotification(this, title, body, payloadId, userId, type, true, clave, gradoAlerta, forceSilent)
                } else {
                    prefs.edit().putBoolean("IS_CENTRAL_MODE", true).apply()
                }
            } catch (e: Exception) {
                NotificationHelper.sendNotification(this, title, body, payloadId, userId, type, true, clave, gradoAlerta, forceSilent)
            }
        } else {
            NotificationHelper.sendNotification(this, title, body, payloadId, userId, type, true, clave, gradoAlerta, forceSilent)
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actionType = intent.getStringExtra("ACTION_TYPE") ?: return
        val payloadId = intent.getStringExtra("PAYLOAD_ID") ?: return
        val userId = intent.getStringExtra("USER_ID") ?: return
        val notificationId = intent.getIntExtra("NOTIFICATION_ID", 0)

        NotificationHelper.createChannels(context)

        val builder = NotificationCompat.Builder(context, "sisbom_actions_v1")
            .setSmallIcon(R.drawable.ic_notification_firefighter)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDefaults(0)

        when (actionType) {
            "DISPATCH" -> builder
                .setContentTitle("Asistencia Registrada ✅")
                .setContentText("Confirmado en el despacho #" + payloadId)
                .setOngoing(true)

            "CHAT_REPLY" -> builder
                .setContentTitle("Mensaje Enviado ✅")
                .setContentText("Tu respuesta ha sido enviada.")

            "ALERT_ACK" -> builder
                .setContentTitle("Enterado ✅")
                .setContentText("Has dado conforme a la alerta.")

            "ALERT_PIN" -> builder
                .setContentTitle("Alerta Anclada 📌")
                .setContentText("Se ha fijado en tu muro.")
        }

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notify(notificationId, builder.build())
            }
        }

        thread {
            try {
                val auth = FirebaseAuth.getInstance()
                if (auth.currentUser == null) {
                    val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                    val cachedUser = prefs.getString("fire_user", null)
                    if (cachedUser != null) {
                        try {
                            val j = org.json.JSONObject(cachedUser)
                            val idReg = j.getString("idRegistro")
                            val pass = j.getString("contrasena")
                            val email = "$idReg@sisbom.com"
                            val securePass = pass + "_secure_sisbom"
                            Tasks.await(auth.signInWithEmailAndPassword(email, securePass))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                executeFirebaseAction(actionType, payloadId, userId, intent, context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun executeFirebaseAction(actionType: String, payloadId: String, userId: String, intent: Intent, context: Context) {
        val db = FirebaseFirestore.getInstance()
        if (actionType == "DISPATCH") {
            try {
                SoundPlayer.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            playAssistConfirmation(context)
            db.collection("personal").document(userId).update("enServicio", payloadId)
        } else {
            val docRef = db.collection("alertas").document(payloadId)
            docRef.get().addOnSuccessListener { snap ->
                if (snap.exists()) {
                    if (actionType == "CHAT_REPLY") {
                        val replyText = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence("KEY_TEXT_REPLY")
                            ?.toString()
                            ?.trim()

                        if (!replyText.isNullOrEmpty()) {
                            val currentMsg = snap.getString("mensajeAlerta") ?: ""
                            val now = java.text.SimpleDateFormat("dd-MM-yyyy/HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                            val newMsgStr = now + "/" + userId + ": " + replyText + " | "

                            val finalMsg = if (currentMsg.endsWith("|")) {
                                currentMsg.substringBeforeLast("|").trim() + " | " + newMsgStr
                            } else if (currentMsg.isNotEmpty()) {
                                currentMsg + " | " + newMsgStr
                            } else {
                                newMsgStr
                            }

                            docRef.update("mensajeAlerta", finalMsg)
                        }
                    } else if (actionType == "ALERT_ACK") {
                        val currentConforme = snap.getString("conforme") ?: ""
                        val list = currentConforme.split(",")
                            .map { it.trim().uppercase() }
                            .filter { it.isNotEmpty() }
                            .toMutableList()

                        if (!list.contains(userId.uppercase())) {
                            list.add(userId.uppercase())
                            docRef.update("conforme", list.joinToString(", "))
                        }
                    } else if (actionType == "ALERT_PIN") {
                        val currentFijar = snap.getString("fijar") ?: ""
                        val list = currentFijar.split(",")
                            .map { it.trim().uppercase() }
                            .filter { it.isNotEmpty() }
                            .toMutableList()

                        if (!list.contains(userId.uppercase())) {
                            list.add(userId.uppercase())
                            docRef.update("fijar", list.joinToString(", "))
                        }
                    }
                }
            }
        }
    }

    private fun playAssistConfirmation(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            val targetNotif = (maxNotif * 0.65f).toInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, targetNotif, 0)

            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(120)
                }
            } catch (_: Exception) {}

            val soundResId = context.resources.getIdentifier("asistir", "raw", context.packageName)
            if (soundResId != 0) {
                val uri = Uri.parse("android.resource://" + context.packageName + "/" + soundResId)
                val ringtone = RingtoneManager.getRingtone(context, uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.isLooping = false
                }
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone.play()
            } else {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(context, uri)
                ringtone.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun scheduleDailyReminders(context: Context) {
    try {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        
        val reminderTimes = listOf(
            Pair(8, 0) to 101,
            Pair(18, 30) to 102
        )
        
        for (entry in reminderTimes) {
            val hour = entry.first.first
            val minute = entry.first.second
            val requestCode = entry.second
            val intent = Intent(context, DailyReminderReceiver::class.java).apply {
                putExtra("REQUEST_CODE", requestCode)
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    android.app.AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            scheduleDailyReminders(context)
            return
        }
        
        scheduleDailyReminders(context)
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationHelper.createChannels(context)
        
        val requestCode = intent.getIntExtra("REQUEST_CODE", 101)
        
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val soundResId = context.resources.getIdentifier("alerta", "raw", context.packageName)
        val alertSound = if (soundResId != 0) {
            Uri.parse("android.resource://" + context.packageName + "/" + soundResId)
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
        
        val builder = NotificationCompat.Builder(context, "sisbom_alertas_normal_v10")
            .setSmallIcon(R.drawable.ic_notification_firefighter)
            .setContentTitle("Actualizar Estado 🚨")
            .setContentText("Recuerda actualizar tu estado (0-8 o 0-9) en la app.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setSound(alertSound)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setContentIntent(pendingIntent)
            
        notificationManager.notify(requestCode, builder.build())
    }
}