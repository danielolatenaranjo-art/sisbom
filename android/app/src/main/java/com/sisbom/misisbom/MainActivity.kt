package com.sisbom.misisbom

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ============================================================================
// EXTENSIONES SEGURAS PARA DOCUMENT SNAPSHOT (EVITA CRASH POR TIPOS EN FIRESTORE)
// ============================================================================
fun DocumentSnapshot.safeString(field: String, default: String = ""): String {
    return try {
        val v = this.get(field) ?: return default
        when (v) {
            is String -> v.trim()
            is Number -> v.toString()
            is Boolean -> v.toString()
            else -> v.toString().trim()
        }
    } catch (_: Exception) {
        default
    }
}

fun DocumentSnapshot.safeBool(field: String, default: Boolean = false): Boolean {
    return try {
        val v = this.get(field) ?: return default
        when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> {
                val s = v.trim().uppercase(Locale.ROOT)
                s == "TRUE" || s == "1" || s == "SI" || s == "SÍ" || s == "S"
            }
            else -> default
        }
    } catch (_: Exception) {
        default
    }
}

// ============================================================================
// 1. PLAYED SOUNDS TRACKER & TIME VALIDATION (PARIDAD EXACTA SENTINEL ONE V2)
// ============================================================================
object PlayedSoundsTracker {
    private const val PREFS_NAME = "SisBomPlayedSounds"
    private const val KEY_IDS = "played_ids"

    @Synchronized
    fun hasPlayed(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        return set.contains(id)
    }

    @Synchronized
    fun markPlayed(context: Context, id: String) {
        if (id.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.size > 300) {
            val toKeep = set.toList().takeLast(150).toMutableSet()
            toKeep.add(id)
            prefs.edit().putStringSet(KEY_IDS, toKeep).apply()
        } else {
            set.add(id)
            prefs.edit().putStringSet(KEY_IDS, set).apply()
        }
    }
}

object TimeValidation {
    private const val MAX_AGE_MS = 5 * 60 * 1000L // 5 minutos máximo

    fun isTooOld(
        fechaDespacho: String?,
        horaDespacho: String?,
        createdAt: Any?,
        timestampMs: Long? = null
    ): Boolean {
        val now = System.currentTimeMillis()

        if (timestampMs != null && timestampMs > 1_000_000_000_000L) {
            return (now - timestampMs) > MAX_AGE_MS
        }

        when (createdAt) {
            is Long -> if (createdAt > 1_000_000_000_000L) return (now - createdAt) > MAX_AGE_MS
            is Number -> {
                val v = createdAt.toLong()
                if (v > 1_000_000_000_000L) return (now - v) > MAX_AGE_MS
            }
            is com.google.firebase.Timestamp -> {
                return (now - createdAt.toDate().time) > MAX_AGE_MS
            }
            is String -> {
                val asLong = createdAt.trim().toLongOrNull()
                if (asLong != null && asLong > 1_000_000_000_000L) {
                    return (now - asLong) > MAX_AGE_MS
                }
                try {
                    val sdfIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val cleanIso = createdAt.substringBefore(".").substringBefore("Z")
                    val parsed = sdfIso.parse(cleanIso)
                    if (parsed != null) {
                        return (now - parsed.time) > MAX_AGE_MS
                    }
                } catch (_: Exception) {}
            }
        }

        val horaLong = horaDespacho?.trim()?.toLongOrNull()
        if (horaLong != null && horaLong > 1_000_000_000_000L) {
            return (now - horaLong) > MAX_AGE_MS
        }

        if (!fechaDespacho.isNullOrBlank() && !horaDespacho.isNullOrBlank()) {
            val cleanFecha = fechaDespacho.trim()
            val cleanHora = horaDespacho.trim()
            val formats = listOf(
                "dd/MM/yyyy HH:mm:ss",
                "dd/MM/yyyy HH:mm",
                "dd-MM-yyyy HH:mm:ss",
                "dd-MM-yyyy HH:mm",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm"
            )
            for (fmt in formats) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.getDefault())
                    val date = sdf.parse("$cleanFecha $cleanHora")
                    if (date != null) {
                        return (now - date.time) > MAX_AGE_MS
                    }
                } catch (_: Exception) {}
            }
        }

        return false
    }
}

// ============================================================================
// 2. SOUND PLAYER CON OVERRIDE DE VOLUMEN AL 100% PARA BOMBEROS EN 0-9
// ============================================================================
object SoundPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingResId: Int? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val restoreHandler = Handler(Looper.getMainLooper())
    private var restoreRunnable: Runnable? = null
    private var savedAlarmVol: Int? = null
    private var savedMusicVol: Int? = null
    private var savedNotifVol: Int? = null
    private var savedRingVol: Int? = null
    private var savedRingerMode: Int? = null

    @Synchronized
    fun play(context: Context, resId: Int, forceMaxVolume: Boolean = false) {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Si ya está sonando exactamente este mismo tono de alarma, NO cortarlo ni reiniciarlo
        try {
            val activeMp = mediaPlayer
            if (activeMp != null && activeMp.isPlaying && currentPlayingResId == resId) {
                if (forceMaxVolume) {
                    enforceMaxVolumes(audioManager)
                }
                return
            }
        } catch (_: Exception) {}

        stopAudioOnly(appContext)

        if (forceMaxVolume) {
            saveAndEnforceMaxVolumes(audioManager)
            requestExclusiveAlarmAudioFocus(audioManager)
        }

        try {
            val afd = appContext.resources.openRawResourceFd(resId) ?: return
            val mp = MediaPlayer()
            val usage = if (forceMaxVolume) {
                AudioAttributes.USAGE_ALARM
            } else {
                AudioAttributes.USAGE_NOTIFICATION
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            try {
                mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
            } catch (_: Exception) {}
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.isLooping = false
            mp.setVolume(1.0f, 1.0f)
            mp.setOnCompletionListener { player ->
                try {
                    player.release()
                } catch (_: Exception) {}
                synchronized(SoundPlayer) {
                    if (mediaPlayer === player) {
                        mediaPlayer = null
                        currentPlayingResId = null
                    }
                }
                if (forceMaxVolume) {
                    abandonAlarmAudioFocus(audioManager)
                    restoreVolumes(appContext)
                }
            }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
            currentPlayingResId = resId

            if (forceMaxVolume) {
                restoreRunnable?.let { restoreHandler.removeCallbacks(it) }
                val r = Runnable {
                    abandonAlarmAudioFocus(audioManager)
                    restoreVolumes(appContext)
                }
                restoreRunnable = r
                restoreHandler.postDelayed(r, 15_000L)
            }
        } catch (e: Exception) {
            Log.e("SoundPlayer", "Error reproduciendo recurso de audio: ${e.message}")
            if (forceMaxVolume) {
                abandonAlarmAudioFocus(audioManager)
                restoreVolumes(appContext)
            }
        }
    }

    private fun saveAndEnforceMaxVolumes(audioManager: AudioManager) {
        try {
            if (savedAlarmVol == null) savedAlarmVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            if (savedMusicVol == null) savedMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (savedNotifVol == null) savedNotifVol = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            if (savedRingVol == null) savedRingVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            if (savedRingerMode == null) savedRingerMode = audioManager.ringerMode
            enforceMaxVolumes(audioManager)
        } catch (e: Exception) {
            Log.w("SoundPlayer", "No se pudo guardar volumen previo: ${e.message}")
        }
    }

    private fun enforceMaxVolumes(audioManager: AudioManager) {
        try {
            try {
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }
            } catch (_: SecurityException) {}

            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)

            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotif, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)
            } catch (_: SecurityException) {}
        } catch (_: Exception) {}
    }

    private fun requestExclusiveAlarmAudioFocus(audioManager: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val alarmAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(alarmAttrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { /* Mantener reproducción sin importar foco externo */ }
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (_: Exception) {}
    }

    private fun abandonAlarmAudioFocus(audioManager: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun vibrateStrong(context: Context, timings: LongArray) {
        try {
            val appContext = context.applicationContext
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return

            val alarmAudioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = IntArray(timings.size) { index ->
                    if (index % 2 == 1) 255 else 0
                }
                val effect = try {
                    if (vibrator.hasAmplitudeControl()) {
                        VibrationEffect.createWaveform(timings, amplitudes, -1)
                    } else {
                        VibrationEffect.createWaveform(timings, -1)
                    }
                } catch (_: Exception) {
                    VibrationEffect.createWaveform(timings, -1)
                }
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, alarmAudioAttrs)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1, alarmAudioAttrs)
            }
        } catch (e: Exception) {
            Log.w("SoundPlayer", "Error activando vibración fuerte: ${e.message}")
        }
    }

    @Synchronized
    fun playByName(context: Context, rawName: String, forceMaxVolume: Boolean = true) {
        val cleanName = rawName.trim().lowercase(Locale.ROOT)
            .removeSuffix(".mp3")
            .replace("-", "_")
            .replace(" ", "_")
        val resId = context.resources.getIdentifier(cleanName, "raw", context.packageName)
        val finalResId = if (resId != 0) resId else R.raw.despacho
        play(context, finalResId, forceMaxVolume)
    }

    @Synchronized
    fun stopAudioOnly(context: Context? = null) {
        restoreRunnable?.let { restoreHandler.removeCallbacks(it) }
        restoreRunnable = null
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        currentPlayingResId = null
        if (context != null) {
            try {
                val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                abandonAlarmAudioFocus(audioManager)
            } catch (_: Exception) {}
            restoreVolumes(context.applicationContext)
        }
    }

    @Synchronized
    fun stop(context: Context? = null) {
        stopAudioOnly(context)
        if (context != null) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vibrator.cancel()
            } catch (_: Exception) {}
            restoreVolumes(context.applicationContext)
        }
    }

    @Synchronized
    private fun restoreVolumes(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedAlarmVol?.let { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
            savedMusicVol?.let { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
            try {
                savedNotifVol?.let { audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, it, 0) }
                savedRingVol?.let { audioManager.setStreamVolume(AudioManager.STREAM_RING, it, 0) }
                savedRingerMode?.let { audioManager.ringerMode = it }
            } catch (_: SecurityException) {}
        } catch (_: Exception) {}
        savedAlarmVol = null
        savedMusicVol = null
        savedNotifVol = null
        savedRingVol = null
        savedRingerMode = null
    }
}

// ============================================================================
// 3. NOTIFICATION HELPER (CANALES, REGLAS 0-9 / 0-8, ESCALAMIENTO Y REPETICIÓN 60s)
// ============================================================================
object NotificationHelper {
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val pendingRepeatRunnables = mutableMapOf<String, Runnable>()

    fun getSoundResForClave(context: Context, clave: String?): Int {
        if (clave.isNullOrBlank()) return R.raw.despacho
        val c = clave.uppercase(Locale.ROOT).trim()
        return when {
            c.contains("FORESTAL") -> R.raw.c10_2forestal
            c.contains("10-30") -> R.raw.c10_30
            c.contains("9-0") -> R.raw.c9_0
            c.contains("LLAMADO") || c.contains("COMANDANCIA") -> R.raw.c_llamadocomandancia
            c.startsWith("10-10") -> R.raw.c10_10
            c.startsWith("10-12") -> R.raw.c10_12
            c.startsWith("10-15") -> R.raw.c10_15
            c.startsWith("10-0") -> R.raw.c10_0
            c.startsWith("10-1") -> R.raw.c10_1
            c.startsWith("10-2") -> R.raw.c10_2
            c.startsWith("10-3") -> R.raw.c10_3
            c.startsWith("10-4") -> R.raw.c10_4
            c.startsWith("10-5") -> R.raw.c10_5
            c.startsWith("10-6") -> R.raw.c10_6
            c.startsWith("10-7") -> R.raw.c10_7
            c.startsWith("10-8") -> R.raw.c10_8
            c.startsWith("10-9") -> R.raw.c10_9
            else -> R.raw.despacho
        }
    }

    fun cancelRepeatForDispatch(dispatchId: String) {
        if (dispatchId.isBlank()) return
        synchronized(pendingRepeatRunnables) {
            val keysToRemove = pendingRepeatRunnables.keys.filter { it == dispatchId || it.startsWith("${dispatchId}_") }
            for (k in keysToRemove) {
                pendingRepeatRunnables.remove(k)?.let { repeatHandler.removeCallbacks(it) }
            }
        }
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val userEstado = (prefs.getString("USER_ESTADO", "0-9") ?: "0-9").trim().uppercase(Locale.ROOT)
        val isUnavailable = prefs.getString("IS_UNAVAILABLE", "false") == "true"
        val isAirplane = prefs.getBoolean("MODO_AVION", false) || prefs.getBoolean("SILENCIO_ABSOLUTO", false)
        val is09 = userEstado == "0-9" && !isUnavailable && !isAirplane

        val alarmSoundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.despacho}")
        val alarmAudioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // Canal usado por la Cloud Function FCM cuando envía el bloque notification en segundo plano
        // El sonido se gestiona 100% por SoundPlayer para respetar si ya asistió, 0-8 o app en primer plano
        val fcmUrgentChannel = NotificationChannel(
            "sisbom_dispatch_urgent_v1",
            "Despachos Urgentes Push",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Canal prioritario FCM para despachos de emergencia"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1200, 400, 1200, 400, 1200, 400, 1200)
            setSound(null, null)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val criticalChannel = NotificationChannel(
            "sisbom_alertas_critical_v10",
            "Emergencias y Despachos Críticos",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Canal prioritario para despachos de emergencia"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1200, 400, 1200, 400, 1200, 400, 1200)
            setSound(null, null)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val silentDispatchChannel = NotificationChannel(
            "sisbom_dispatch_silent_v1",
            "Despachos en 0-8 (Solo Vibración)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notificaciones de despacho cuando el bombero se encuentra en 0-8"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 400, 1000, 400, 1000)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val g3Channel = NotificationChannel(
            "sisbom_alertas_g3_v1",
            "Alertas Grado 3 y Orden del Día",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertas críticas Grado 3 y Orden del Día"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 400, 1000, 400, 1000)
            setSound(null, null)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val g2Channel = NotificationChannel(
            "sisbom_alertas_g2_v1",
            "Alertas Grado 2",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertas importantes Grado 2"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val g1Channel = NotificationChannel(
            "sisbom_alertas_g1_v1",
            "Alertas Grado 1",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alertas informativas Grado 1"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300)
        }

        val chatChannel = NotificationChannel(
            "sisbom_chat_v3",
            "Mensajes de Chat",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Mensajes directos y grupales de SENTINEL ONE"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 250)
        }

        val reminderChannel = NotificationChannel(
            "sisbom_recordatorio_v3",
            "Recordatorios de Disponibilidad",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Recordatorio nocturno de estado 0-8 / 0-9"
            enableVibration(true)
        }

        manager.createNotificationChannels(
            listOf(
                fcmUrgentChannel,
                criticalChannel,
                silentDispatchChannel,
                g3Channel,
                g2Channel,
                g1Channel,
                chatChannel,
                reminderChannel
            )
        )
    }

    fun sendNotification(
        context: Context,
        title: String,
        body: String,
        isCritical: Boolean,
        customSoundRes: Int? = null,
        chatId: String? = null,
        dispatchId: String? = null,
        alertId: String? = null,
        doorRequestId: String? = null,
        forceMaxVolume: Boolean? = null,
        forceVibrateOnly: Boolean = false,
        alertGrade: Int? = null,
        skipSoundBecauseAlreadyPlayed: Boolean = false,
        claveForRepeat: String? = null
    ) {
        val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val isAirplaneMode = prefs.getBoolean("MODO_AVION", false) || prefs.getBoolean("SILENCIO_ABSOLUTO", false)
        if (isAirplaneMode) {
            Log.d("FCM", "Ignorando notificación porque Modo Avión (0-8 Absoluto) está activo")
            return
        }

        val isCentral = prefs.getBoolean("IS_CENTRAL_ACTIVE", false)
        if (isCentral && doorRequestId == null) {
            Log.d("FCM", "Ignorando notificación normal porque está en Modo Central activo")
            return
        }

        val userRango = prefs.getString("USER_RANGO", "") ?: ""
        val isReadOnlyUser = userRango.trim().equals("BOMBERO HONORARIO", ignoreCase = true)
        if (isReadOnlyUser && dispatchId != null) {
            return
        }

        val userEstado = (prefs.getString("USER_ESTADO", "0-8") ?: "0-8").trim().uppercase(Locale.ROOT)
        val estadoEspecial = (prefs.getString("USER_ESTADO_ESPECIAL", "") ?: "").trim().uppercase(Locale.ROOT)
        val isUnavailable = prefs.getString("IS_UNAVAILABLE", "false") == "true"
        val is09 = userEstado == "0-9" && !isUnavailable
        val is08 = !is09 || userEstado == "0-8" || userEstado == "10-8"

        val isAbsoluteSilentStatus =
            estadoEspecial in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO") ||
            userEstado in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO")
        val isPartialSilentStatus = estadoEspecial == "COMISION_SERVICIO"

        if (isAbsoluteSilentStatus) {
            return
        }

        val declinedIds = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet()) ?: emptySet()
        val attendedIds = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet()) ?: emptySet()
        val userEnServicio = (prefs.getString("USER_EN_SERVICIO", "") ?: "").trim()
        val hasDeclinedThisDispatch =
            dispatchId != null && (declinedIds.contains(dispatchId) || userEnServicio == "-$dispatchId")
        val isAttendingThisDispatch =
            dispatchId != null && (attendedIds.contains(dispatchId) || (userEnServicio.isNotEmpty() && userEnServicio == dispatchId))

        val claveUpper = (claveForRepeat ?: title).uppercase(Locale.ROOT).trim()
        val isEscalation =
            claveUpper.contains("10-30") ||
            claveUpper.contains("10_30") ||
            claveUpper.contains("FORESTAL")
        val is1210or66 = title.contains("12-10") || title.contains("6-6") || body.contains("12-10") || body.contains("6-6")

        // REGLA ESTRICTA DE ASISTENCIA:
        // Si el bombero YA presionó "ASISTIR" en este despacho, jamás debe volver a sonar ni reenviar notificación para ese despacho.
        if (dispatchId != null && isAttendingThisDispatch) {
            return
        }
        // Si el bombero presionó "NO ASISTIR", no debe volver a sonar salvo que escale a 10-30 / Alarma Forestal.
        if (dispatchId != null && hasDeclinedThisDispatch && !isEscalation) {
            return
        }

        ensureChannels(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // REGLA V2 EXACTA:
        // - En 0-9: Despacho suena fuerte a volumen 100% + vibración fuerte + pantalla completa.
        // - En 0-8: Despacho normal (ej. 10-4, 10-0) NUNCA suena sirena (playLoud = false), SOLO vibra fuerte (effectiveVibrateOnly = true) y NO abre pantalla completa.
        // - Escalamiento (10-30 / FORESTAL): Suena fuerte c10_30 / c10_2forestal para 0-9 y para 0-8 normal (salvo Modo Avión 0-8 Absoluto o si ya asiste).
        var playLoud = false
        var effectiveVibrateOnly = false

        if (doorRequestId != null) {
            playLoud = isCentral
        } else if (is1210or66) {
            // Solicitudes 12-10 (Conductor) y 6-6 (Personal): suenan con tono de la clave forzando volumen al 100% y restaurando
            playLoud = is09 && !hasDeclinedThisDispatch
            effectiveVibrateOnly = !is09 || hasDeclinedThisDispatch
        } else if (isEscalation && dispatchId != null) {
            if (!isAttendingThisDispatch) {
                playLoud = true
            }
        } else if (dispatchId != null) {
            if (is09 && !isPartialSilentStatus && !hasDeclinedThisDispatch && !isAttendingThisDispatch) {
                playLoud = true
            } else if (is08 && !isPartialSilentStatus && !hasDeclinedThisDispatch && !isAttendingThisDispatch) {
                playLoud = false
                effectiveVibrateOnly = true
            }
        } else if (alertGrade == 3) {
            if (is09) {
                playLoud = true
            } else {
                playLoud = false
                effectiveVibrateOnly = true
            }
        } else if (forceVibrateOnly) {
            playLoud = false
            effectiveVibrateOnly = true
        } else if (forceMaxVolume == true && is09) {
            playLoud = true
        }

        val effectiveForceMaxVolume = playLoud

        val channelId = when {
            isReadOnlyUser || isPartialSilentStatus -> "sisbom_alertas_g1_v1"
            chatId != null -> "sisbom_chat_v3"
            alertGrade == 1 -> "sisbom_alertas_g1_v1"
            alertGrade == 2 -> "sisbom_alertas_g2_v1"
            alertGrade == 3 -> if (is08) "sisbom_alertas_g2_v1" else "sisbom_alertas_g3_v1"
            effectiveVibrateOnly || (dispatchId != null && is08 && !isEscalation) -> "sisbom_dispatch_silent_v1"
            isCritical || playLoud -> "sisbom_alertas_critical_v10"
            else -> "sisbom_recordatorio_v3"
        }

        // REGLA DEL USUARIO:
        // - Si la notificación Push o despacho llega con la app en segundo plano / bloqueada y el usuario está 0-9 (o es escalamiento):
        //   suena el tono de la clave al 100%, vibra fuerte y abre inmediatamente la pantalla completa sobre otras pantallas.
        // - Si la app YA está abierta en primer plano (isAppInForeground == true):
        //   NO debe sonar el audio (salvo timbre de puerta o chat), SOLO debe vibrar fuerte y mostrar la pantalla sobre pantalla.
        val isUserLookingAtApp = MainActivity.isAppInForeground
        val shouldTriggerFeedback = !skipSoundBecauseAlreadyPlayed && !isReadOnlyUser
        val shouldPlayAudioTone = shouldTriggerFeedback && (!isUserLookingAtApp || doorRequestId != null || chatId != null)

        if (shouldTriggerFeedback) {
            try {
                // Encender la pantalla inmediatamente si llega una alarma o despacho
                if (playLoud || effectiveVibrateOnly) {
                    try {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        @Suppress("DEPRECATION")
                        val screenWake = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                            "SentinelOneV3::PushWakeScreen"
                        )
                        screenWake.acquire(12_000L)
                    } catch (_: Exception) {}
                }

                if (shouldPlayAudioTone) {
                    if (playLoud) {
                        val soundRes = customSoundRes ?: when {
                            dispatchId != null -> getSoundResForClave(context, claveForRepeat ?: title)
                            alertGrade == 2 || alertGrade == 3 -> R.raw.alertas
                            else -> R.raw.despacho
                        }
                        SoundPlayer.play(context, soundRes, forceMaxVolume = true)
                    } else if (!effectiveVibrateOnly && dispatchId == null) {
                        if (alertGrade == 2 || (alertGrade == 3 && is08)) {
                            SoundPlayer.play(context, R.raw.alertas, forceMaxVolume = false)
                        } else if (alertGrade == 1 || chatId != null) {
                            try {
                                val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                                val r = RingtoneManager.getRingtone(context, defaultUri)
                                r.play()
                            } catch (_: Exception) {}
                        }
                    }
                }

                val shouldVibrate = playLoud || is1210or66 || effectiveVibrateOnly || alertGrade != null || chatId != null
                if (shouldVibrate) {
                    val vibrationPattern = when {
                        playLoud -> longArrayOf(0, 1500, 300, 1500, 300, 1500, 300, 1500, 300, 1500)
                        effectiveVibrateOnly || alertGrade == 3 -> longArrayOf(0, 1200, 300, 1200, 300, 1200, 300, 1200)
                        alertGrade == 2 -> longArrayOf(0, 600, 250, 600)
                        else -> longArrayOf(0, 350, 150, 350)
                    }
                    SoundPlayer.vibrateStrong(context, vibrationPattern)
                }
            } catch (e: Exception) {
                Log.e("FCM", "Error reproduciendo alerta sonora/vibración: ${e.message}")
            }
        }

        // Si es un despacho (o escalamiento) con sonido fuerte activo en segundo plano, programar repetición a los 60 segundos si no ha respondido
        if (dispatchId != null && playLoud && !isUserLookingAtApp && (is09 || isEscalation) && !skipSoundBecauseAlreadyPlayed && !isReadOnlyUser && !isPartialSilentStatus) {
            scheduleRepeatAlert(context.applicationContext, dispatchId, claveForRepeat ?: title)
        }

        val shouldShowFullScreen = dispatchId != null && playLoud && (is09 || isEscalation) && !isCentral

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (chatId != null) putExtra("CHAT_ID", chatId)
            if (dispatchId != null) {
                putExtra("DISPATCH_ID", dispatchId)
                putExtra("SHOW_FULLSCREEN_ALERT", shouldShowFullScreen)
                if (shouldShowFullScreen) {
                    putExtra("AUTO_LAUNCHED_FROM_ALARM", true)
                }
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIconBitmap = try {
            val clientFile = File(context.filesDir, "client_logo.png")
            if (clientFile.exists() && clientFile.length() > 0) {
                BitmapFactory.decodeFile(clientFile.absolutePath)
            } else {
                BitmapFactory.decodeResource(context.resources, R.drawable.logo)
            }
        } catch (_: Throwable) {
            null
        }

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.logo)
            .apply {
                if (largeIconBitmap != null) {
                    setLargeIcon(largeIconBitmap)
                }
            }
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val notifId = when {
            dispatchId != null -> dispatchId.hashCode()
            alertId != null -> alertId.hashCode()
            chatId != null -> chatId.hashCode()
            doorRequestId != null -> doorRequestId.hashCode()
            else -> System.currentTimeMillis().toInt()
        }

        if (isCritical || alertGrade == 3 || effectiveVibrateOnly) {
            notificationBuilder.setPriority(NotificationCompat.PRIORITY_MAX)
            notificationBuilder.setCategory(NotificationCompat.CATEGORY_ALARM)
            if (shouldShowFullScreen) {
                notificationBuilder.setFullScreenIntent(pendingIntent, true)
            }
        } else {
            notificationBuilder.setPriority(NotificationCompat.PRIORITY_HIGH)
            notificationBuilder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
        }

        // Acciones interactivas en la notificación
        if (!isReadOnlyUser) {
            if (chatId != null) {
                val remoteInput = RemoteInput.Builder("key_text_reply")
                    .setLabel("Escribir respuesta...")
                    .build()
                val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = "ACTION_REPLY_CHAT"
                    putExtra("CHAT_ID", chatId)
                    putExtra("NOTIF_ID", notifId)
                }
                val replyPendingIntent = PendingIntent.getBroadcast(
                    context,
                    notifId + 1,
                    replyIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val action = NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    "Responder",
                    replyPendingIntent
                ).addRemoteInput(remoteInput).build()
                notificationBuilder.addAction(action)
            } else if (dispatchId != null) {
                val attendIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = "ACTION_ATTEND_DISPATCH"
                    putExtra("DISPATCH_ID", dispatchId)
                    putExtra("NOTIF_ID", notifId)
                }
                val attendPendingIntent = PendingIntent.getBroadcast(
                    context,
                    notifId + 2,
                    attendIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                notificationBuilder.addAction(
                    android.R.drawable.ic_menu_directions,
                    "ASISTIR",
                    attendPendingIntent
                )
            } else if (alertId != null) {
                val ackIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = "ACTION_ACK_ALERT"
                    putExtra("ALERT_ID", alertId)
                    putExtra("NOTIF_ID", notifId)
                }
                val ackPendingIntent = PendingIntent.getBroadcast(
                    context,
                    notifId + 3,
                    ackIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                notificationBuilder.addAction(
                    android.R.drawable.checkbox_on_background,
                    "Dar Conforme",
                    ackPendingIntent
                )

                val pinIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = "ACTION_PIN_ALERT"
                    putExtra("ALERT_ID", alertId)
                    putExtra("NOTIF_ID", notifId)
                }
                val pinPendingIntent = PendingIntent.getBroadcast(
                    context,
                    notifId + 4,
                    pinIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                notificationBuilder.addAction(
                    android.R.drawable.ic_menu_myplaces,
                    "Anclar",
                    pinPendingIntent
                )
            } else if (doorRequestId != null) {
                val openDoorIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = "ACTION_OPEN_DOOR"
                    putExtra("DOOR_REQUEST_ID", doorRequestId)
                    putExtra("NOTIF_ID", notifId)
                }
                val openDoorPendingIntent = PendingIntent.getBroadcast(
                    context,
                    notifId + 5,
                    openDoorIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                notificationBuilder.addAction(
                    android.R.drawable.ic_lock_lock,
                    "ABRIR PUERTA",
                    openDoorPendingIntent
                )
            }
        }

        notificationManager.notify(notifId, notificationBuilder.build())
    }

    private fun scheduleRepeatAlert(context: Context, dispatchId: String, clave: String) {
        cancelRepeatForDispatch(dispatchId)
        val runnable = Runnable {
            synchronized(pendingRepeatRunnables) {
                pendingRepeatRunnables.remove(dispatchId)
            }
            val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("MODO_AVION", false) || prefs.getBoolean("IS_CENTRAL_ACTIVE", false)) return@Runnable
            val userId = prefs.getString("USER_ID", null) ?: return@Runnable
            val userEstado = prefs.getString("USER_ESTADO", "0-8") ?: "0-8"
            val estadoEspecial = prefs.getString("USER_ESTADO_ESPECIAL", "") ?: ""
            if (userEstado != "0-9") return@Runnable
            if (estadoEspecial in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO", "COMISION_SERVICIO")) return@Runnable

            val declinedIds = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet()) ?: emptySet()
            val attendedIds = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet()) ?: emptySet()
            val userEnServicio = (prefs.getString("USER_EN_SERVICIO", "") ?: "").trim()
            if (declinedIds.contains(dispatchId) || attendedIds.contains(dispatchId) || userEnServicio == dispatchId || userEnServicio == "-$dispatchId") return@Runnable
            if (MainActivity.isAppInForeground) return@Runnable

            val db = MainActivity.getActiveFirestore(context)
            db.collection("despachos").document(dispatchId).get().addOnSuccessListener { doc ->
                try {
                    if (doc != null && doc.exists()) {
                        val opFinal = doc.safeString("operadorFinal")
                        val estadoDoc = doc.safeString("estado").uppercase(Locale.ROOT)
                        val cerrado = doc.safeBool("cerrado") || estadoDoc == "CERRADO"
                        if (opFinal.isNotEmpty() || cerrado) return@addOnSuccessListener

                        val asistentes = (doc.get("asistentes") as? List<*>) ?: emptyList<Any>()
                        val noAsistentes = (doc.get("noAsistentes") as? List<*>) ?: emptyList<Any>()

                        val alreadyResponded = asistentes.any {
                            when (it) {
                                is String -> it == userId
                                is Map<*, *> -> (it["id"] ?: it["idRegistro"] ?: it["bomberoId"])?.toString() == userId
                                else -> false
                            }
                        } || noAsistentes.any {
                            when (it) {
                                is String -> it == userId
                                is Map<*, *> -> (it["id"] ?: it["idRegistro"] ?: it["bomberoId"])?.toString() == userId
                                else -> false
                            }
                        }

                        if (!alreadyResponded) {
                            db.collection("personal").document(userId).get().addOnSuccessListener { userDoc ->
                                try {
                                    val enServicio = userDoc?.safeString("enServicio") ?: ""
                                    if (enServicio != dispatchId && enServicio != "-$dispatchId") {
                                        val soundRes = getSoundResForClave(context, clave)
                                        SoundPlayer.play(context, soundRes, forceMaxVolume = true)
                                        SoundPlayer.vibrateStrong(
                                            context,
                                            longArrayOf(0, 1500, 300, 1500, 300, 1500, 300, 1500, 300, 1500)
                                        )
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        synchronized(pendingRepeatRunnables) {
            pendingRepeatRunnables[dispatchId] = runnable
        }
        repeatHandler.postDelayed(runnable, 60_000L)
    }

    fun scheduleRepeat1210or66(
        context: Context,
        dispatchId: String,
        unitName: String,
        is1210: Boolean,
        clave: String
    ) {
        val repeatKey = "${dispatchId}_${unitName}_${if (is1210) "1210" else "66"}"
        synchronized(pendingRepeatRunnables) {
            pendingRepeatRunnables.remove(repeatKey)?.let { repeatHandler.removeCallbacks(it) }
        }
        val runnable = Runnable {
            synchronized(pendingRepeatRunnables) {
                pendingRepeatRunnables.remove(repeatKey)
            }
            val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("MODO_AVION", false) || prefs.getBoolean("IS_CENTRAL_ACTIVE", false)) return@Runnable
            val userId = prefs.getString("USER_ID", null) ?: return@Runnable
            val userEstado = prefs.getString("USER_ESTADO", "0-8") ?: "0-8"
            val estadoEspecial = prefs.getString("USER_ESTADO_ESPECIAL", "") ?: ""
            val isConductor = prefs.getBoolean("USER_CONDUCTOR", false)
            val attendedIds = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet()) ?: emptySet()
            val declinedIds = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet()) ?: emptySet()
            val userEnServicio = (prefs.getString("USER_EN_SERVICIO", "") ?: "").trim()
            if (attendedIds.contains(dispatchId) || declinedIds.contains(dispatchId) || userEnServicio == dispatchId || userEnServicio == "-$dispatchId") return@Runnable
            if (MainActivity.isAppInForeground) return@Runnable
            if (userEstado != "0-9") return@Runnable
            if (is1210 && !isConductor) return@Runnable
            if (estadoEspecial in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO", "COMISION_SERVICIO")) return@Runnable

            val db = MainActivity.getActiveFirestore(context)
            db.collection("despachos").document(dispatchId).get().addOnSuccessListener { doc ->
                try {
                    if (doc != null && doc.exists()) {
                        val opFinal = doc.safeString("operadorFinal")
                        val estadoDoc = doc.safeString("estado").uppercase(Locale.ROOT)
                        if (opFinal.isNotEmpty() || estadoDoc == "CERRADO" || doc.safeBool("cerrado")) return@addOnSuccessListener

                        val uServicio = (doc.get("unidadesEnServicio") ?: doc.get("unidades")) as? Map<*, *>
                        val uData = uServicio?.get(unitName) as? Map<*, *> ?: return@addOnSuccessListener
                        val estadoUnit = uData["estado"]?.toString() ?: ""
                        if (estadoUnit in listOf("6-9", "6-10")) return@addOnSuccessListener

                        val claveSoundRes = NotificationHelper.getSoundResForClave(context, clave)
                        val strongPattern = longArrayOf(0, 1500, 300, 1500, 300, 1500, 300, 1500, 300, 1500)
                        if (is1210) {
                            val cond = uData["conductor"]?.toString() ?: ""
                            if (cond.isEmpty() || cond == "Sin Conductor") {
                                SoundPlayer.play(context, claveSoundRes, forceMaxVolume = true)
                                SoundPlayer.vibrateStrong(context, strongPattern)
                            }
                        } else {
                            val numB = (uData["numBomberos"] as? Number)?.toInt() ?: 0
                            if (numB == 0) {
                                SoundPlayer.play(context, claveSoundRes, forceMaxVolume = true)
                                SoundPlayer.vibrateStrong(context, strongPattern)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        synchronized(pendingRepeatRunnables) {
            pendingRepeatRunnables[repeatKey] = runnable
        }
        repeatHandler.postDelayed(runnable, 60_000L)
    }
}

// ============================================================================
// 4. FIREBASE MESSAGING SERVICE (RECEPCIÓN PUSH FCM EN SEGUNDO PLANO / APP CERRADA)
// ============================================================================
class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        MainActivity.initializeDynamicFirebase(applicationContext)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        MainActivity.initializeDynamicFirebase(applicationContext)
        val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("MODO_AVION", false) || prefs.getBoolean("SILENCIO_ABSOLUTO", false)) return

        val data = remoteMessage.data
        val type = data["type"]
        val payloadId = data["payloadId"] ?: data["dispatchId"] ?: data["idServicio"]
        val clave = data["clave"]
        val gradoAlertaStr = data["gradoAlerta"]
        val senderId = data["senderId"]
        val userId = prefs.getString("USER_ID", null)

        if (senderId != null && senderId == userId) {
            return
        }

        if (type == "STATUS_CHANGE") {
            val newStatus = data["newStatus"]
            if (!newStatus.isNullOrBlank()) {
                val isUnavailable = (newStatus.trim().uppercase(Locale.ROOT) != "0-9")
                prefs.edit()
                    .putString("USER_ESTADO", newStatus.trim())
                    .putString("IS_UNAVAILABLE", isUnavailable.toString())
                    .apply()
            }
            return
        }

        if (type == "DOOR_REQUEST") {
            val isCentral = prefs.getBoolean("IS_CENTRAL_ACTIVE", false)
            if (!isCentral) return
            val title = remoteMessage.notification?.title ?: data["title"] ?: "SOLICITUD DE APERTURA"
            val body = remoteMessage.notification?.body ?: data["body"] ?: "Solicitan abrir el portón del cuartel"
            NotificationHelper.sendNotification(
                context = applicationContext,
                title = title,
                body = body,
                isCritical = true,
                customSoundRes = R.raw.alerta,
                doorRequestId = payloadId ?: "door_${System.currentTimeMillis()}",
                forceMaxVolume = true
            )
            return
        }

        if (type == "DISPATCH" || type == "DISPATCH_UPDATE") {
            // Si el usuario se encuentra de Operador Central activo, NO hacer sonar el teléfono ni abrir pantalla de asistencia
            if (prefs.getBoolean("IS_CENTRAL_ACTIVE", false)) {
                if (!payloadId.isNullOrBlank()) {
                    PlayedSoundsTracker.markPlayed(applicationContext, payloadId)
                }
                return
            }
            val fechaDespacho = data["fechaDespacho"]
            val horaDespacho = data["horaDespacho"]
            val createdAt = data["createdAt"] ?: data["timestamp"]
            if (TimeValidation.isTooOld(fechaDespacho, horaDespacho, createdAt)) {
                Log.w("FCM", "Descarte de despacho antiguo (> 5 min): $payloadId")
                return
            }
        }

        val title = remoteMessage.notification?.title ?: data["title"] ?: "SENTINEL ONE"
        val body = remoteMessage.notification?.body ?: data["body"] ?: "Nueva notificación"
        val is1210or66Push = title.contains("12-10") || title.contains("6-6") || body.contains("12-10") || body.contains("6-6")

        val isCritical = type == "DISPATCH" || type == "DISPATCH_UPDATE" || type == "ALERT" || type == "ORDEN"
        val customSoundRes = when {
            is1210or66Push -> NotificationHelper.getSoundResForClave(applicationContext, clave ?: title)
            type == "DISPATCH" || type == "DISPATCH_UPDATE" -> NotificationHelper.getSoundResForClave(applicationContext, clave ?: title)
            type == "ALERT" || type == "ORDEN" -> R.raw.alerta
            else -> null
        }

        val chatId = if (type == "CHAT") payloadId else null
        val dispatchId = if (type == "DISPATCH" || type == "DISPATCH_UPDATE") payloadId else null
        val alertId = if (type == "ALERT" || type == "ORDEN") payloadId else null
        val alertGrade = if (type == "ORDEN") 3 else gradoAlertaStr?.toIntOrNull()

        val isEscalation = type == "DISPATCH_UPDATE" &&
            (clave == "10-30" || clave == "ALARMA FORESTAL" || (clave ?: "").contains("10-30") || (clave ?: "").contains("FORESTAL"))

        val attendedIds = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet()) ?: emptySet()
        val declinedIds = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet()) ?: emptySet()
        val userEnServicio = (prefs.getString("USER_EN_SERVICIO", "") ?: "").trim()
        val isAttendingThisDispatch =
            dispatchId != null && (attendedIds.contains(dispatchId) || (userEnServicio.isNotEmpty() && userEnServicio == dispatchId))
        val hasDeclinedThisDispatch =
            dispatchId != null && (declinedIds.contains(dispatchId) || userEnServicio == "-$dispatchId")

        // Si el bombero YA presionó "ASISTIR" en este despacho, jamás volver a sonar ni reabrir el overlay
        if (dispatchId != null && isAttendingThisDispatch) {
            return
        }
        // Si es solo una actualización de datos del despacho (ej. cambio de pre-despacho a activa o dirección) y NO es escalamiento ni 12-10/6-6, no volver a sonar
        if (type == "DISPATCH_UPDATE" && !isEscalation && !is1210or66Push) {
            return
        }
        if (dispatchId != null && hasDeclinedThisDispatch && !isEscalation) {
            return
        }

        val soundTrackKey = when (type) {
            "DISPATCH" -> dispatchId
            "DISPATCH_UPDATE" -> when {
                dispatchId != null && is1210or66Push -> "${dispatchId}_req_${data["createdAt"] ?: title}"
                dispatchId != null && isEscalation -> "${dispatchId}_${clave}"
                else -> dispatchId
            }
            "ALERT", "ORDEN" -> alertId
            else -> null
        }
        val alreadyPlayed = if (soundTrackKey != null) {
            val played = PlayedSoundsTracker.hasPlayed(applicationContext, soundTrackKey) ||
                (dispatchId != null && !isEscalation && !is1210or66Push && PlayedSoundsTracker.hasPlayed(applicationContext, dispatchId))
            if (!played) PlayedSoundsTracker.markPlayed(applicationContext, soundTrackKey)
            played
        } else false

        NotificationHelper.sendNotification(
            context = applicationContext,
            title = title,
            body = body,
            isCritical = isCritical,
            customSoundRes = customSoundRes,
            chatId = chatId,
            dispatchId = dispatchId,
            alertId = alertId,
            forceMaxVolume = when {
                is1210or66Push -> true
                isEscalation -> true
                else -> null
            },
            alertGrade = alertGrade,
            skipSoundBecauseAlreadyPlayed = alreadyPlayed,
            claveForRepeat = clave
        )

        // Despertar pantalla completa automáticamente si es despacho nuevo (en 0-9) o escalamiento, y no ha respondido aún
        if ((type == "DISPATCH" || isEscalation) && !isAttendingThisDispatch && (!hasDeclinedThisDispatch || isEscalation) && !alreadyPlayed) {
            val userEstado = (prefs.getString("USER_ESTADO", "0-8") ?: "0-8").trim().uppercase(Locale.ROOT)
            val isUnavailable = prefs.getString("IS_UNAVAILABLE", "false") == "true"
            val is09 = userEstado == "0-9" && !isUnavailable
            val isCentral = prefs.getBoolean("IS_CENTRAL_ACTIVE", false)
            val userRango = prefs.getString("USER_RANGO", "") ?: ""
            val isReadOnlyUser = userRango.trim().equals("BOMBERO HONORARIO", ignoreCase = true)
            val estadoEspecial = (prefs.getString("USER_ESTADO_ESPECIAL", "") ?: "").trim().uppercase(Locale.ROOT)
            val isSilentStatus =
                estadoEspecial in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO", "COMISION_SERVICIO") ||
                userEstado in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO")

            if (!isCentral && !isReadOnlyUser && !isSilentStatus && (is09 || isEscalation)) {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                        "SentinelOneV3::FCMWakeScreen"
                    )
                    wl.acquire(12_000L)
                } catch (_: Exception) {}

                try {
                    val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        if (dispatchId != null) {
                            putExtra("DISPATCH_ID", dispatchId)
                            putExtra("SHOW_FULLSCREEN_ALERT", true)
                            putExtra("AUTO_LAUNCHED_FROM_ALARM", true)
                        }
                    }
                    startActivity(fullScreenIntent)
                } catch (e: Exception) {
                    Log.w("FCM", "No se pudo abrir MainActivity en pantalla completa: ${e.message}")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        MainActivity.initializeDynamicFirebase(applicationContext)
        val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("FCM_TOKEN", token).apply()
        val userId = prefs.getString("USER_ID", null)
        if (!userId.isNullOrBlank()) {
            MainActivity.registerFcmTokenInFirestore(applicationContext, userId, token)
        }
    }
}

// ============================================================================
// 5. SERVICIO FOREGROUND EN TIEMPO REAL (DESPACHOS, ESCALAMIENTOS, 12-10/6-6 Y TELEMETRÍA GPS)
// ============================================================================
class DispatchForegroundService : Service() {
    private var despachosListener: ListenerRegistration? = null
    private var userDocListener: ListenerRegistration? = null
    private var serviceWakeLock: PowerManager.WakeLock? = null
    private val notifiedDispatches = mutableSetOf<String>()
    private val lastUnitConductorReq = mutableMapOf<String, Long>()
    private val lastUnitPersonalReq = mutableMapOf<String, Long>()
    private val lastDispatchClaves = mutableMapOf<String, String>()
    private var isInitialLoad = true

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isTrackingLocation = false

    override fun onCreate() {
        super.onCreate()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SentinelOneV3::ServiceWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
        MainActivity.initializeDynamicFirebase(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundServiceNotification()
        authenticateAndListen()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "sisbom_foreground_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Monitoreo de Emergencias SENTINEL ONE",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene la conexión activa con la Central de Alarmas"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.logo)
            .setContentTitle("SENTINEL ONE")
            .setContentText("Servicio de despacho y alertas activo")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val fgType = if (hasLoc) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            try {
                startForeground(1001, notification, fgType)
            } catch (_: Exception) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(1001, notification)
        }
    }

    private fun authenticateAndListen() {
        val auth = MainActivity.getActiveAuth(applicationContext)
        if (auth.currentUser != null) {
            listenToUserAndDispatches()
            return
        }
        val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val fireUserJson = prefs.getString("fire_user", null)
        var email = ""
        var password = ""
        if (!fireUserJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(fireUserJson)
                val idReg = obj.optString("idRegistro", obj.optString("id", "")).trim()
                val pass = obj.optString("password", obj.optString("claveAcceso", obj.optString("clave", ""))).trim()
                if (idReg.isNotEmpty() && pass.isNotEmpty()) {
                    email = "${idReg}@sisbom.com"
                    password = "${pass}_secure_sisbom"
                }
            } catch (_: Exception) {}
        }
        if (email.isNotEmpty() && password.isNotEmpty()) {
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { listenToUserAndDispatches() }
                .addOnFailureListener {
                    auth.signInAnonymously()
                        .addOnSuccessListener { listenToUserAndDispatches() }
                        .addOnFailureListener { listenToUserAndDispatches() }
                }
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { listenToUserAndDispatches() }
                .addOnFailureListener { listenToUserAndDispatches() }
        }
    }

    private fun listenToUserAndDispatches() {
        val db = MainActivity.getActiveFirestore(applicationContext)
        val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("USER_ID", null)

        if (!userId.isNullOrBlank()) {
            userDocListener?.remove()
            userDocListener = db.collection("personal").document(userId)
                .addSnapshotListener { doc, err ->
                    try {
                        if (err != null || doc == null || !doc.exists()) return@addSnapshotListener
                        val estadoRaw = doc.safeString("estado")
                        val estado = if (estadoRaw.isNotEmpty()) {
                            estadoRaw
                        } else if (doc.safeBool("disponible")) {
                            "0-9"
                        } else {
                            "0-8"
                        }
                        val enServicio = doc.safeString("enServicio")
                        val idRadial = doc.safeString("idRadial")
                        val conductor = doc.safeBool("conductor") || doc.safeBool("esConductor")
                        val rango = doc.safeString("rango").ifEmpty { doc.safeString("cargo") }
                        val estadoEspecial = doc.safeString("estadoEspecial")

                        val isUnavailable = (estado.trim().uppercase(Locale.ROOT) != "0-9")
                        val editPrefs = prefs.edit()
                            .putString("USER_ESTADO", estado)
                            .putString("IS_UNAVAILABLE", isUnavailable.toString())
                            .putString("USER_RANGO", rango)
                            .putString("USER_ESTADO_ESPECIAL", estadoEspecial)
                            .putBoolean("USER_CONDUCTOR", conductor)
                            .putString("USER_EN_SERVICIO", enServicio)
                        if (idRadial.isNotEmpty()) {
                            editPrefs.putString("USER_ID_RADIAL", idRadial)
                        }
                        editPrefs.apply()
                        NotificationHelper.ensureChannels(applicationContext)

                        val isDispatchService = enServicio.isNotEmpty() &&
                            enServicio != "0" &&
                            !enServicio.startsWith("-") &&
                            enServicio != "EN EL CUARTEL" &&
                            enServicio != "EN LA COMPAÑIA" &&
                            enServicio != "EN LA COMPAÑÍA" &&
                            !enServicio.startsWith("UNIDAD:")

                        if (isDispatchService) {
                            startLocationTracking(userId)
                        } else {
                            stopLocationTracking()
                        }
                    } catch (ex: Exception) {
                        Log.w("DispatchService", "Error procesando documento personal: ${ex.message}")
                    }
                }
        }

        despachosListener?.remove()
        despachosListener = db.collection("despachos")
            .addSnapshotListener { snapshots, e ->
                try {
                    if (e != null || snapshots == null) return@addSnapshotListener

                    val activeDocs = snapshots.documents.filter { doc ->
                        val opFinal = doc.safeString("operadorFinal")
                        val finalizadoAt = (doc.get("finalizadoAt") as? Number)?.toLong() ?: 0L
                        val estado = doc.safeString("estado").uppercase(Locale.ROOT)
                        val cerrado = doc.safeBool("cerrado") ||
                            finalizadoAt > 0L ||
                            estado == "CERRADO" ||
                            estado == "CERRADA" ||
                            estado == "FINALIZADO" ||
                            estado == "FINALIZADA" ||
                            estado == "CANCELADO" ||
                            estado == "CANCELADA"
                        opFinal.isEmpty() && !cerrado
                    }

                    if (isInitialLoad) {
                        for (doc in activeDocs) {
                            notifiedDispatches.add(doc.id)
                            lastDispatchClaves[doc.id] = doc.safeString("clave")
                            val uServicio = (doc.get("unidadesEnServicio") ?: doc.get("unidades")) as? Map<*, *>
                            uServicio?.forEach { (k, v) ->
                                val uName = k as? String ?: return@forEach
                                val uData = v as? Map<*, *> ?: return@forEach
                                val cTs = (uData["solicitudConductorTimestamp"] as? Number)?.toLong() ?: 0L
                                val pTs = (uData["solicitudPersonalTimestamp"] as? Number)?.toLong() ?: 0L
                                lastUnitConductorReq["${doc.id}_$uName"] = cTs
                                lastUnitPersonalReq["${doc.id}_$uName"] = pTs
                            }

                            // Si el servicio inicia en SEGUNDO PLANO (ej. despertado tras un Push FCM cuando estaba cerrado)
                            // y hay un despacho nuevo (< 5 min) que aún no ha sonado, hacerlo sonar y abrir la pantalla sobre otras apps de inmediato.
                            // En cambio, si el usuario YA abrió la app manualmente (isAppInForeground == true), marcarlo como reproducido para que NO suene al abrir la app.
                            if (MainActivity.isAppInForeground || prefs.getBoolean("IS_CENTRAL_ACTIVE", false)) {
                                PlayedSoundsTracker.markPlayed(applicationContext, doc.id)
                            } else {
                                val id = doc.id
                                val clave = doc.safeString("clave", "Emergencia").ifEmpty { "Emergencia" }
                                val estadoDoc = doc.safeString("estado").uppercase(Locale.ROOT)
                                val fallbackDir = if (estadoDoc == "PRE-DESPACHO") "Pre-despacho en preparación..." else "Sin dirección"
                                val direccion = doc.safeString("direccion").ifEmpty { doc.safeString("lugar", fallbackDir) }.ifEmpty { fallbackDir }
                                val fechaDespacho = doc.safeString("fechaDespacho").ifEmpty { doc.safeString("fecha") }
                                val horaDespacho = doc.safeString("horaDespacho").ifEmpty { doc.safeString("hora") }
                                val createdAt = doc.get("timestamp") ?: doc.get("horaCreacion") ?: doc.get("createdAt")
                                val attendedInit = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet()) ?: emptySet()
                                val declinedInit = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet()) ?: emptySet()
                                val enServicioInit = (prefs.getString("USER_EN_SERVICIO", "") ?: "").trim()
                                val alreadyRespondedInit = attendedInit.contains(id) || declinedInit.contains(id) || enServicioInit == id || enServicioInit == "-$id"
                                val alreadyPlayed = PlayedSoundsTracker.hasPlayed(applicationContext, id) || alreadyRespondedInit

                                if (!alreadyPlayed && !TimeValidation.isTooOld(fechaDespacho, horaDespacho, createdAt)) {
                                    PlayedSoundsTracker.markPlayed(applicationContext, id)
                                    val unidadesRaw = doc.get("carros") ?: doc.get("unidades")
                                    val unidadesList = when (unidadesRaw) {
                                        is List<*> -> unidadesRaw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                                        is Map<*, *> -> unidadesRaw.keys.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                                        is String -> unidadesRaw.split(",", "/").map { it.trim() }.filter { it.isNotEmpty() }
                                        else -> emptyList()
                                    }
                                    val unidadesStr = unidadesList.joinToString(" / ")
                                    val soundRes = NotificationHelper.getSoundResForClave(applicationContext, clave)
                                    val bodyText = if (unidadesStr.isNotEmpty()) "$direccion\nUnidades: $unidadesStr" else direccion
                                    NotificationHelper.sendNotification(
                                        context = applicationContext,
                                        title = "DESPACHO: $clave",
                                        body = bodyText,
                                        isCritical = true,
                                        customSoundRes = soundRes,
                                        dispatchId = id,
                                        skipSoundBecauseAlreadyPlayed = false,
                                        claveForRepeat = clave
                                    )

                                    val userEstadoInit = (prefs.getString("USER_ESTADO", "0-8") ?: "0-8").trim().uppercase(Locale.ROOT)
                                    val isCentralInit = prefs.getBoolean("IS_CENTRAL_ACTIVE", false)
                                    val userRangoInit = prefs.getString("USER_RANGO", "") ?: ""
                                    val isReadOnlyInit = userRangoInit.trim().equals("BOMBERO HONORARIO", ignoreCase = true)
                                    val estadoEspecialInit = prefs.getString("USER_ESTADO_ESPECIAL", "") ?: ""
                                    val isSilentInit = estadoEspecialInit in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO", "COMISION_SERVICIO")

                                    if (!isCentralInit && !isReadOnlyInit && !isSilentInit && userEstadoInit == "0-9") {
                                        try {
                                            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                                            @Suppress("DEPRECATION")
                                            val wl = pm.newWakeLock(
                                                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                                                "SentinelOneV3::ServiceInitWake"
                                            )
                                            wl.acquire(12_000L)
                                        } catch (_: Exception) {}
                                        try {
                                            val fullScreenIntent = Intent(this@DispatchForegroundService, MainActivity::class.java).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                putExtra("DISPATCH_ID", id)
                                                putExtra("SHOW_FULLSCREEN_ALERT", true)
                                                putExtra("AUTO_LAUNCHED_FROM_ALARM", true)
                                            }
                                            startActivity(fullScreenIntent)
                                        } catch (_: Exception) {}
                                    }
                                } else {
                                    PlayedSoundsTracker.markPlayed(applicationContext, id)
                                }
                            }
                        }
                        isInitialLoad = false
                        return@addSnapshotListener
                    }

                    if (prefs.getBoolean("MODO_AVION", false) || prefs.getBoolean("SILENCIO_ABSOLUTO", false)) {
                        return@addSnapshotListener
                    }

                    // Si el usuario es el Operador Central activo, marcar despachos como reproducidos sin sonar ni levantar pantalla de asistencia
                    if (prefs.getBoolean("IS_CENTRAL_ACTIVE", false)) {
                        for (doc in activeDocs) {
                            val id = doc.id
                            notifiedDispatches.add(id)
                            PlayedSoundsTracker.markPlayed(applicationContext, id)
                        }
                        return@addSnapshotListener
                    }

                    val currentUserId = prefs.getString("USER_ID", null)
                    val userEstado = prefs.getString("USER_ESTADO", "0-8") ?: "0-8"
                    val isConductor = prefs.getBoolean("USER_CONDUCTOR", false)
                    val enServicio = (prefs.getString("USER_EN_SERVICIO", "") ?: "").trim()
                    val attendedIds = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet()) ?: emptySet()
                    val declinedIds = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet()) ?: emptySet()

                    for (doc in activeDocs) {
                        val id = doc.id
                        val clave = doc.safeString("clave", "Emergencia").ifEmpty { "Emergencia" }
                        val estadoDoc = doc.safeString("estado").uppercase(Locale.ROOT)
                        val fallbackDir = if (estadoDoc == "PRE-DESPACHO") "Pre-despacho en preparación..." else "Sin dirección"
                        val direccion = doc.safeString("direccion").ifEmpty { doc.safeString("lugar", fallbackDir) }.ifEmpty { fallbackDir }
                        val fechaDespacho = doc.safeString("fechaDespacho").ifEmpty { doc.safeString("fecha") }
                        val horaDespacho = doc.safeString("horaDespacho").ifEmpty { doc.safeString("hora") }
                        val createdAt = doc.get("timestamp") ?: doc.get("horaCreacion") ?: doc.get("createdAt")
                        val unidadesRaw = doc.get("carros") ?: doc.get("unidades")
                        val unidadesList = when (unidadesRaw) {
                            is List<*> -> unidadesRaw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                            is Map<*, *> -> unidadesRaw.keys.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                            is String -> unidadesRaw.split(",", "/").map { it.trim() }.filter { it.isNotEmpty() }
                            else -> emptyList()
                        }
                        val unidadesStr = unidadesList.joinToString(" / ")

                        val asistentes = (doc.get("asistentes") as? List<*>) ?: emptyList<Any>()
                        val noAsistentes = (doc.get("noAsistentes") as? List<*>) ?: emptyList<Any>()
                        val isAlreadyAttending = attendedIds.contains(id) || enServicio == id || (currentUserId != null && asistentes.any {
                            when (it) {
                                is String -> it == currentUserId
                                is Map<*, *> -> (it["id"] ?: it["idRegistro"] ?: it["bomberoId"])?.toString() == currentUserId
                                else -> false
                            }
                        })
                        val hasAlreadyDeclined = declinedIds.contains(id) || enServicio == "-$id" || (currentUserId != null && noAsistentes.any {
                            when (it) {
                                is String -> it == currentUserId
                                is Map<*, *> -> (it["id"] ?: it["idRegistro"] ?: it["bomberoId"])?.toString() == currentUserId
                                else -> false
                            }
                        })

                    // 1. Detectar nuevo despacho
                    if (!notifiedDispatches.contains(id)) {
                        notifiedDispatches.add(id)
                        lastDispatchClaves[id] = clave

                        if (TimeValidation.isTooOld(fechaDespacho, horaDespacho, createdAt) || isAlreadyAttending || hasAlreadyDeclined) {
                            PlayedSoundsTracker.markPlayed(applicationContext, id)
                            continue
                        }

                        val alreadyPlayed = PlayedSoundsTracker.hasPlayed(applicationContext, id)
                        if (!alreadyPlayed) {
                            PlayedSoundsTracker.markPlayed(applicationContext, id)
                        }

                        val soundRes = NotificationHelper.getSoundResForClave(applicationContext, clave)
                        val bodyText = if (unidadesStr.isNotEmpty()) "$direccion\nUnidades: $unidadesStr" else direccion
                        NotificationHelper.sendNotification(
                            context = applicationContext,
                            title = "DESPACHO: $clave",
                            body = bodyText,
                            isCritical = true,
                            customSoundRes = soundRes,
                            dispatchId = id,
                            skipSoundBecauseAlreadyPlayed = alreadyPlayed,
                            claveForRepeat = clave
                        )

                        // Levantar pantalla completa si el usuario está en 0-9 y aún no ha sonado/respondido
                        val isCentral = prefs.getBoolean("IS_CENTRAL_ACTIVE", false)
                        val userRango = prefs.getString("USER_RANGO", "") ?: ""
                        val isReadOnlyUser = userRango.trim().equals("BOMBERO HONORARIO", ignoreCase = true)
                        val estadoEspecial = prefs.getString("USER_ESTADO_ESPECIAL", "") ?: ""
                        val isSilentStatus = estadoEspecial in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO", "COMISION_SERVICIO")

                        if (!alreadyPlayed && !isCentral && !isReadOnlyUser && !isSilentStatus && userEstado == "0-9") {
                            try {
                                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                                @Suppress("DEPRECATION")
                                val wl = pm.newWakeLock(
                                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                                    "SentinelOneV3::ServiceLiveWake"
                                )
                                wl.acquire(12_000L)
                            } catch (_: Exception) {}
                            try {
                                val fullScreenIntent = Intent(this@DispatchForegroundService, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    putExtra("DISPATCH_ID", id)
                                    putExtra("SHOW_FULLSCREEN_ALERT", true)
                                    putExtra("AUTO_LAUNCHED_FROM_ALARM", true)
                                }
                                startActivity(fullScreenIntent)
                            } catch (_: Exception) {}
                        }
                    } else {
                        // 2. Detectar escalamiento (10-0 -> 10-30, 10-2 -> ALARMA FORESTAL)
                        val oldClave = lastDispatchClaves[id] ?: ""
                        if (oldClave.isNotEmpty() && oldClave != clave) {
                            lastDispatchClaves[id] = clave
                            val isEscalation = (oldClave.startsWith("10-0") && clave.startsWith("10-30")) ||
                                (oldClave.startsWith("10-2") && clave.contains("FORESTAL")) ||
                                clave.startsWith("10-30") || clave.contains("FORESTAL")
                            if (isEscalation && !isAlreadyAttending) {
                                val escKey = "${id}_${clave}"
                                val alreadyPlayed = PlayedSoundsTracker.hasPlayed(applicationContext, escKey)
                                if (!alreadyPlayed) {
                                    PlayedSoundsTracker.markPlayed(applicationContext, escKey)
                                }
                                val soundRes = NotificationHelper.getSoundResForClave(applicationContext, clave)
                                NotificationHelper.sendNotification(
                                    context = applicationContext,
                                    title = "CAMBIO DE CLAVE A $clave",
                                    body = "$direccion ($unidadesStr) - ¡SE REQUIERE PERSONAL!",
                                    isCritical = true,
                                    customSoundRes = soundRes,
                                    dispatchId = id,
                                    forceMaxVolume = true,
                                    skipSoundBecauseAlreadyPlayed = alreadyPlayed,
                                    claveForRepeat = clave
                                )
                                val isCentralEsc = prefs.getBoolean("IS_CENTRAL_ACTIVE", false)
                                val userRangoEsc = prefs.getString("USER_RANGO", "") ?: ""
                                val isReadOnlyEsc = userRangoEsc.trim().equals("BOMBERO HONORARIO", ignoreCase = true)
                                val estadoEspecialEsc = prefs.getString("USER_ESTADO_ESPECIAL", "") ?: ""
                                val isSilentEsc = estadoEspecialEsc in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO", "COMISION_SERVICIO")
                                if (!alreadyPlayed && !isCentralEsc && !isReadOnlyEsc && !isSilentEsc) {
                                    try {
                                        val fullScreenIntent = Intent(this@DispatchForegroundService, MainActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                            putExtra("DISPATCH_ID", id)
                                            putExtra("SHOW_FULLSCREEN_ALERT", true)
                                            putExtra("AUTO_LAUNCHED_FROM_ALARM", true)
                                        }
                                        startActivity(fullScreenIntent)
                                    } catch (_: Exception) {}
                                }
                            }
                        } else {
                            lastDispatchClaves[id] = clave
                        }
                    }

                    // 3. Detectar solicitudes 12-10 (Conductor) y 6-6 (Personal)
                    val uServicio = (doc.get("unidadesEnServicio") ?: doc.get("unidades")) as? Map<*, *>
                    uServicio?.forEach { (k, v) ->
                        val uName = k as? String ?: return@forEach
                        val uData = v as? Map<*, *> ?: return@forEach
                        val cTs = (uData["solicitudConductorTimestamp"] as? Number)?.toLong() ?: 0L
                        val pTs = (uData["solicitudPersonalTimestamp"] as? Number)?.toLong() ?: 0L
                        val key = "${id}_$uName"

                        val prevCTs = lastUnitConductorReq[key] ?: 0L
                        if (cTs > prevCTs && cTs > 0L) {
                            lastUnitConductorReq[key] = cTs
                            if (isConductor && !isAlreadyAttending && !hasAlreadyDeclined) {
                                val trackKey = "${key}_1210_$cTs"
                                val alreadyPlayed = PlayedSoundsTracker.hasPlayed(applicationContext, trackKey)
                                if (!alreadyPlayed) PlayedSoundsTracker.markPlayed(applicationContext, trackKey)
                                val claveSoundRes = NotificationHelper.getSoundResForClave(applicationContext, clave)
                                NotificationHelper.sendNotification(
                                    context = applicationContext,
                                    title = "SOLICITUD 12-10 ($uName)",
                                    body = "Se solicita conductor para unidad $uName en $direccion",
                                    isCritical = true,
                                    customSoundRes = claveSoundRes,
                                    dispatchId = id,
                                    forceMaxVolume = true,
                                    forceVibrateOnly = (userEstado == "0-8"),
                                    skipSoundBecauseAlreadyPlayed = alreadyPlayed
                                )
                                if (userEstado == "0-9") {
                                    NotificationHelper.scheduleRepeat1210or66(applicationContext, id, uName, true, clave)
                                }
                            }
                        } else if (prevCTs == 0L) {
                            lastUnitConductorReq[key] = cTs
                        }

                        val prevPTs = lastUnitPersonalReq[key] ?: 0L
                        if (pTs > prevPTs && pTs > 0L) {
                            lastUnitPersonalReq[key] = pTs
                            if (!isAlreadyAttending && !hasAlreadyDeclined) {
                                val trackKey = "${key}_66_$pTs"
                                val alreadyPlayed = PlayedSoundsTracker.hasPlayed(applicationContext, trackKey)
                                if (!alreadyPlayed) PlayedSoundsTracker.markPlayed(applicationContext, trackKey)
                                val claveSoundRes = NotificationHelper.getSoundResForClave(applicationContext, clave)
                                NotificationHelper.sendNotification(
                                    context = applicationContext,
                                    title = "SOLICITUD 6-6 ($uName)",
                                    body = "Se solicita personal para unidad $uName en $direccion",
                                    isCritical = true,
                                    customSoundRes = claveSoundRes,
                                    dispatchId = id,
                                    forceMaxVolume = true,
                                    forceVibrateOnly = (userEstado == "0-8"),
                                    skipSoundBecauseAlreadyPlayed = alreadyPlayed
                                )
                                if (userEstado == "0-9") {
                                    NotificationHelper.scheduleRepeat1210or66(applicationContext, id, uName, false, clave)
                                }
                            }
                        } else if (prevPTs == 0L) {
                            lastUnitPersonalReq[key] = pTs
                        }
                    }
                }
                } catch (ex: Exception) {
                    Log.w("DispatchService", "Error procesando despachos: ${ex.message}")
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking(userId: String) {
        if (isTrackingLocation) return
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        isTrackingLocation = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 6_000L)
            .setMinUpdateIntervalMillis(3_000L)
            .setMinUpdateDistanceMeters(0f)
            .build()

        val publishLocToRtdb: (android.location.Location) -> Unit = { loc ->
            val now = System.currentTimeMillis()
            try {
                val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                val nombre = prefs.getString("USER_NOMBRE", "") ?: ""
                val rango = prefs.getString("USER_RANGO", "") ?: ""
                val compania = prefs.getString("USER_COMPANIA", "1") ?: "1"
                val idRadial = prefs.getString("USER_ID_RADIAL", userId) ?: userId
                val enServicio = (prefs.getString("USER_EN_SERVICIO", "") ?: "").trim()

                val rtdb = MainActivity.getActiveRtdb(applicationContext)
                val payload = mapOf(
                    "id" to userId,
                    "idRegistro" to userId,
                    "idRadial" to idRadial.ifEmpty { userId },
                    "nombre" to nombre,
                    "rango" to rango,
                    "compania" to compania,
                    "idServicio" to enServicio,
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "heading" to loc.bearing.toDouble(),
                    "speed" to loc.speed.toDouble(),
                    "accuracy" to loc.accuracy.toDouble(),
                    "timestamp" to now
                )
                rtdb?.getReference("telemetria/bomberos/$userId")?.setValue(payload)
            } catch (_: Exception) {}
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                publishLocToRtdb(loc)
            }
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null) {
                    publishLocToRtdb(lastLoc)
                }
            }
            fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: Exception) {
            isTrackingLocation = false
        }
    }

    private fun stopLocationTracking() {
        if (!isTrackingLocation) return
        locationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (_: Exception) {}
        }
        locationCallback = null
        isTrackingLocation = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        if (despachosListener == null) {
            authenticateAndListen()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val userId = prefs.getString("USER_ID", null)
            if (!userId.isNullOrBlank()) {
                val restartIntent = Intent(applicationContext, DispatchForegroundService::class.java)
                val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        applicationContext,
                        1002,
                        restartIntent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    PendingIntent.getService(
                        applicationContext,
                        1002,
                        restartIntent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 1500L,
                    pendingIntent
                )
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
            }
        } catch (_: Exception) {}
        despachosListener?.remove()
        userDocListener?.remove()
        stopLocationTracking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ============================================================================
// 6. RECEIVERS DE ACCIONES DE NOTIFICACIÓN Y RECORDATORIOS DIARIOS (08:00, 18:30, 22:00)
// ============================================================================
class DailyReminderReceiver : BroadcastReceiver() {
    companion object {
        fun scheduleAllReminders(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val reminders = listOf(
                    Triple(101, 8, 0),   // 08:00 -> Recordatorio cambio de estado (sonido normal + push)
                    Triple(102, 18, 30), // 18:30 -> Recordatorio cambio de estado (sonido normal + push)
                    Triple(103, 22, 0)   // 22:00 -> Si está 0-8, mensaje nocturno (solo push silencioso, sin sonido ni alerta)
                )
                // Cancelar alarma antigua de las 23:00 si existía
                try {
                    val legacyPi = PendingIntent.getBroadcast(
                        context, 2300, Intent(context, DailyReminderReceiver::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
                    )
                    if (legacyPi != null) alarmManager.cancel(legacyPi)
                } catch (_: Exception) {}

                val now = System.currentTimeMillis()
                for ((reqCode, hour, minute) in reminders) {
                    val intent = Intent(context, DailyReminderReceiver::class.java).apply {
                        putExtra("REMINDER_CODE", reqCode)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        reqCode,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val calendar = Calendar.getInstance().apply {
                        timeInMillis = now
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        if (timeInMillis <= now + 5000L) {
                            add(Calendar.DAY_OF_YEAR, 1)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                    } else {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                    }
                }
            } catch (_: Exception) {}
        }

        fun postNormalStateReminder(context: Context, notifId: Int) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = "sisbom_recordatorio_estado_normal_v4"
                val defaultSoundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val existing = nm.getNotificationChannel(channelId)
                    if (existing == null) {
                        val audioAttributes = AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                        val ch = NotificationChannel(
                            channelId,
                            "Recordatorios de Estado (08:00 y 18:30)",
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description = "Recordatorios diarios para actualizar estado 0-8 / 0-9"
                            setSound(defaultSoundUri, audioAttributes)
                            enableVibration(true)
                        }
                        nm.createNotificationChannel(ch)
                    }
                }
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pi = PendingIntent.getActivity(
                    context, notifId, openIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val title = "Actualizar Estado 🚨"
                val body = "Recuerda actualizar tu estado (0-8 o 0-9) en la app."
                val builder = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
                    .setSound(defaultSoundUri)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                nm.notify(notifId, builder.build())
            } catch (_: Exception) {}
        }

        fun postSilentNight2200Push(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = "sisbom_recordatorio_nocturno_silent_v4"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val existing = nm.getNotificationChannel(channelId)
                    if (existing == null) {
                        val ch = NotificationChannel(
                            channelId,
                            "Recordatorio Nocturno 22:00 (Silencioso)",
                            NotificationManager.IMPORTANCE_LOW
                        ).apply {
                            description = "Mensaje nocturno de disponibilidad sin sonido ni vibración"
                            setSound(null, null)
                            enableVibration(false)
                            setShowBadge(false)
                        }
                        nm.createNotificationChannel(ch)
                    }
                }
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pi = PendingIntent.getActivity(
                    context, 2200, openIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val title = "Recordatorio de Disponibilidad"
                val body = "¿Pasarás la noche en la comuna? Recuerda actualizar tu estado a 0-9 si estás disponible para emergencias."
                val builder = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSilent(true)
                    .setSound(null)
                    .setVibrate(null)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                nm.notify(2200, builder.build())
            } catch (_: Exception) {}
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        MainActivity.initializeDynamicFirebase(context.applicationContext)
        // Re-programar siempre los 3 horarios para el día siguiente
        scheduleAllReminders(context.applicationContext)

        val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("USER_ID", null)

        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (!userId.isNullOrBlank()) {
                try {
                    val svcIntent = Intent(context.applicationContext, DispatchForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.applicationContext.startForegroundService(svcIntent)
                    } else {
                        context.applicationContext.startService(svcIntent)
                    }
                    MainActivity.registerFcmTokenInFirestore(context.applicationContext, userId)
                } catch (_: Exception) {}
            }
            return
        }

        // En 0-8 Absoluto (Modo Avión) no se envía ningún recordatorio ni push
        if (prefs.getBoolean("MODO_AVION", false) || prefs.getBoolean("SILENCIO_ABSOLUTO", false)) return
        if (userId.isNullOrBlank()) return

        val reminderCode = intent.getIntExtra("REMINDER_CODE", 0)
        if (reminderCode == 101 || reminderCode == 102) {
            // 08:00 y 18:30 -> Sonido normal de teléfono + notificación push
            postNormalStateReminder(context, reminderCode)
            return
        }

        if (reminderCode == 103) {
            // 22:00 -> Si está en 0-8, enviar mensaje nocturno silencioso (sin sonido ni vibración)
            val db = MainActivity.getActiveFirestore(context)
            db.collection("personal").document(userId).get()
                .addOnSuccessListener { doc ->
                    try {
                        val estado = if (doc != null && doc.exists()) {
                            doc.safeString("estado", "0-8").ifEmpty { "0-8" }
                        } else {
                            prefs.getString("USER_ESTADO", "0-8") ?: "0-8"
                        }
                        val estadoEspecial = if (doc != null && doc.exists()) doc.safeString("estadoEspecial") else ""
                        if (estado == "0-8" && estadoEspecial.isEmpty()) {
                            postSilentNight2200Push(context)
                        }
                    } catch (_: Exception) {}
                }
                .addOnFailureListener {
                    val cachedEstado = prefs.getString("USER_ESTADO", "0-8") ?: "0-8"
                    val cachedEspecial = prefs.getString("USER_ESTADO_ESPECIAL", "") ?: ""
                    if (cachedEstado == "0-8" && cachedEspecial.isEmpty()) {
                        postSilentNight2200Push(context)
                    }
                }
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notifId = intent.getIntExtra("NOTIF_ID", 0)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("USER_ID", null) ?: return
        val userRango = prefs.getString("USER_RANGO", "") ?: ""
        if (userRango.trim().equals("BOMBERO HONORARIO", ignoreCase = true)) {
            notificationManager.cancel(notifId)
            return
        }

        val db = MainActivity.getActiveFirestore(context)
        val now = System.currentTimeMillis()
        val sdfHora = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sdfFecha = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        when (action) {
            "ACTION_REPLY_CHAT" -> {
                val chatId = intent.getStringExtra("CHAT_ID") ?: return
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInput?.getCharSequence("key_text_reply")?.toString()?.trim()
                if (!replyText.isNullOrEmpty()) {
                    val userName = prefs.getString("USER_NOMBRE", "Bombero") ?: "Bombero"
                    val msgData = hashMapOf(
                        "senderId" to userId,
                        "senderName" to userName,
                        "text" to replyText,
                        "timestamp" to now,
                        "timeStr" to sdfHora.format(Date(now)),
                        "dateStr" to sdfFecha.format(Date(now)),
                        "readBy" to listOf(userId)
                    )
                    db.collection("chats").document(chatId).collection("mensajes").add(msgData)
                    db.collection("chats").document(chatId).update(
                        mapOf(
                            "lastMessage" to replyText,
                            "lastMessageTime" to sdfHora.format(Date(now)),
                            "updatedAt" to now,
                            "unreadBy" to emptyList<String>()
                        )
                    )
                    notificationManager.cancel(notifId)
                }
            }
            "ACTION_ATTEND_DISPATCH" -> {
                val dispatchId = intent.getStringExtra("DISPATCH_ID") ?: return
                SoundPlayer.stop(context)
                NotificationHelper.cancelRepeatForDispatch(dispatchId)
                PlayedSoundsTracker.markPlayed(context, dispatchId)

                val attendedSet = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet())?.toMutableSet() ?: mutableSetOf()
                val declinedSet = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet())?.toMutableSet() ?: mutableSetOf()
                attendedSet.add(dispatchId)
                declinedSet.remove(dispatchId)
                prefs.edit()
                    .putStringSet("ATTENDED_DISPATCH_IDS", attendedSet)
                    .putStringSet("DECLINED_DISPATCH_IDS", declinedSet)
                    .putString("USER_EN_SERVICIO", dispatchId)
                    .apply()

                db.collection("personal").document(userId).set(
                    mapOf(
                        "enServicio" to dispatchId,
                        "_metaTimestamp" to now
                    ),
                    SetOptions.merge()
                )
                notificationManager.cancel(notifId)
            }
            "ACTION_ACK_ALERT" -> {
                val alertId = intent.getStringExtra("ALERT_ID") ?: return
                SoundPlayer.stop(context)
                db.collection("alertas").document(alertId).update("leidoPor", FieldValue.arrayUnion(userId))
                notificationManager.cancel(notifId)
            }
            "ACTION_PIN_ALERT" -> {
                val alertId = intent.getStringExtra("ALERT_ID") ?: return
                SoundPlayer.stop(context)
                db.collection("alertas").document(alertId).update(
                    mapOf(
                        "leidoPor" to FieldValue.arrayUnion(userId),
                        "ancladoPor" to FieldValue.arrayUnion(userId)
                    )
                )
                notificationManager.cancel(notifId)
            }
            "ACTION_OPEN_DOOR" -> {
                val doorRequestId = intent.getStringExtra("DOOR_REQUEST_ID") ?: return
                SoundPlayer.stop(context)
                val userName = prefs.getString("USER_NOMBRE", "Operador Central") ?: "Operador Central"
                db.collection("solicitudes_puerta").document(doorRequestId).update(
                    mapOf(
                        "estado" to "APROBADA",
                        "autorizadoPorId" to userId,
                        "autorizadoPorNombre" to userName,
                        "horaAutorizacion" to sdfHora.format(Date(now))
                    )
                )
                db.collection("configuracion").document("porton").set(
                    mapOf(
                        "comando" to "ABRIR",
                        "timestamp" to FieldValue.serverTimestamp(),
                        "autorizadoPor" to userName
                    ),
                    SetOptions.merge()
                )
                notificationManager.cancel(notifId)
            }
        }
    }
}

// ============================================================================
// 7. MAIN ACTIVITY & PUENTE JS <-> NATIVO ANDROID (SENTINEL ONE V3)
// ============================================================================
class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var pendingIntentJson: String? = null

    companion object {
        @Volatile
        var isAppInForeground: Boolean = false
        @Volatile
        private var dynamicFirebaseApp: FirebaseApp? = null
        @Volatile
        private var currentTenantProjectId: String? = null

        fun initializeDynamicFirebase(context: Context, configJsonStr: String? = null) {
            try {
                val appContext = context.applicationContext
                val prefs = appContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                val jsonStr = configJsonStr
                    ?: prefs.getString("DYNAMIC_FIREBASE_CONFIG", null)
                    ?: prefs.getString("saas_firebase_config", null)
                if (jsonStr.isNullOrBlank()) {
                    if (FirebaseApp.getApps(appContext).isEmpty()) {
                        FirebaseApp.initializeApp(appContext)
                    }
                    dynamicFirebaseApp = FirebaseApp.getInstance()
                    return
                }

                if (configJsonStr != null) {
                    prefs.edit()
                        .putString("DYNAMIC_FIREBASE_CONFIG", configJsonStr)
                        .putString("saas_firebase_config", configJsonStr)
                        .apply()
                }

                val json = JSONObject(jsonStr)
                val apiKey = json.optString("apiKey", "")
                val projectId = json.optString("projectId", "")
                val applicationId = json.optString("appId", "").ifBlank { "1:306101642399:android:8951568bc9434d1191aeaa" }
                val messagingSenderId = json.optString("messagingSenderId", "306101642399")
                val storageBucket = json.optString("storageBucket", "")
                val databaseUrl = json.optString("databaseURL", "").ifBlank {
                    if (projectId.isNotBlank()) "https://$projectId-default-rtdb.firebaseio.com" else ""
                }

                if (apiKey.isBlank() || projectId.isBlank()) return

                val builder = FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setProjectId(projectId)
                    .setApplicationId(applicationId)
                    .setGcmSenderId(messagingSenderId)
                if (storageBucket.isNotBlank()) builder.setStorageBucket(storageBucket)
                if (databaseUrl.isNotBlank()) builder.setDatabaseUrl(databaseUrl)
                val options = builder.build()

                // 1. Inicializar también la instancia DEFAULT (igual que SENTINEL ONE V2) para que FirebaseMessaging.getInstance()
                // y los servicios en segundo plano cuando la app se cierra de Recientes reciban las notificaciones del Cuerpo activo
                if (currentTenantProjectId != projectId || FirebaseApp.getApps(appContext).isEmpty()) {
                    try {
                        val defaultApp = FirebaseApp.getInstance()
                        if (defaultApp.options.projectId != projectId) {
                            defaultApp.delete()
                            FirebaseApp.initializeApp(appContext, options)
                        }
                    } catch (_: Exception) {
                        try {
                            FirebaseApp.initializeApp(appContext, options)
                        } catch (_: Exception) {}
                    }
                    currentTenantProjectId = projectId
                }

                // 2. Mantener también la instancia nombrada por seguridad
                val appName = "tenant_$projectId"
                val existing = FirebaseApp.getApps(appContext).firstOrNull { it.name == appName }
                dynamicFirebaseApp = existing ?: FirebaseApp.initializeApp(appContext, options, appName)
                Log.d("SENTINEL_FIREBASE", "Firebase dinámico inicializado para tenant: $projectId")
            } catch (e: Exception) {
                Log.w("SENTINEL_FIREBASE", "Usando FirebaseApp por defecto: ${e.message}")
            }
        }

        fun getActiveFirestore(context: Context): FirebaseFirestore {
            initializeDynamicFirebase(context)
            val app = dynamicFirebaseApp
            return if (app != null) FirebaseFirestore.getInstance(app) else FirebaseFirestore.getInstance()
        }

        fun getActiveAuth(context: Context): FirebaseAuth {
            initializeDynamicFirebase(context)
            val app = dynamicFirebaseApp
            return if (app != null) FirebaseAuth.getInstance(app) else FirebaseAuth.getInstance()
        }

        fun getActiveRtdb(context: Context): FirebaseDatabase? {
            initializeDynamicFirebase(context)
            return try {
                val app = dynamicFirebaseApp
                if (app != null) FirebaseDatabase.getInstance(app) else FirebaseDatabase.getInstance()
            } catch (_: Exception) {
                null
            }
        }

        fun registerFcmTokenInFirestore(context: Context, userId: String, tokenOverride: String? = null) {
            if (userId.isBlank()) return
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val isConductor = prefs.getBoolean("USER_CONDUCTOR", false)
            val isCentral = prefs.getBoolean("IS_CENTRAL_ACTIVE", false)

            val applyToken: (String) -> Unit = { token ->
                prefs.edit().putString("FCM_TOKEN", token).apply()
                val topics = mutableListOf(
                    "all",
                    "despachos",
                    "alertas",
                    "alertas_generales",
                    "usuario_$userId",
                    "personal_$userId"
                )
                if (isConductor) topics.add("conductores")
                if (isCentral) topics.add("central_operador")

                val fm = FirebaseMessaging.getInstance()
                topics.forEach { t -> fm.subscribeToTopic(t) }
                if (!isConductor) fm.unsubscribeFromTopic("conductores")
                if (!isCentral) fm.unsubscribeFromTopic("central_operador")

                val db = getActiveFirestore(appContext)
                db.collection("personal").document(userId).set(
                    mapOf(
                        "deviceId" to token,
                        "_metaTimestamp" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
                db.collection("fcm_tokens").whereEqualTo("userId", userId).get()
                    .addOnSuccessListener { snap ->
                        try {
                            for (doc in snap.documents) {
                                if (doc.id != token) {
                                    doc.reference.delete()
                                }
                            }
                        } catch (_: Exception) {}
                    }
                db.collection("fcm_tokens").document(token).set(
                    mapOf(
                        "token" to token,
                        "platform" to "android",
                        "userId" to userId,
                        "topics" to topics,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }

            if (!tokenOverride.isNullOrBlank()) {
                applyToken(tokenOverride)
            } else {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        applyToken(token)
                    }
                }
            }
        }
    }

    private var pendingBackgroundLocationAfterForeground = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (pendingBackgroundLocationAfterForeground && fineGranted) {
            pendingBackgroundLocationAfterForeground = false
            requestBackgroundLocationStep()
        }
        notifyWebViewPermissionsChanged()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        notifyWebViewPermissionsChanged()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Permitir despertar la pantalla sobre bloqueo en caso de despacho
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val barBgColor = android.graphics.Color.parseColor("#060911")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(barBgColor),
            navigationBarStyle = SystemBarStyle.dark(barBgColor)
        )

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SentinelOneV3::EmergencyWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(15 * 60 * 1000L)
        }

        initializeDynamicFirebase(this)
        NotificationHelper.ensureChannels(this)
        captureIncomingIntent(intent)

        // Si ya hay sesión activa, asegurar que el servicio en segundo plano esté corriendo
        val prefs = getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val savedUserId = prefs.getString("USER_ID", null)
        val locationDisclosed = prefs.getBoolean("LOCATION_DISCLOSED_V3", false)
        if (!savedUserId.isNullOrBlank()) {
            if (locationDisclosed) {
                requestNotificationPermissionOnly()
            }
            startDispatchForegroundServiceSafely()
            scheduleDailyReminder()
            registerFcmTokenInFirestore(this, savedUserId)
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/") { path ->
                val cleanPath = path.trimStart('/').ifEmpty { "index.html" }
                val candidatePaths = listOf(
                    "www/$cleanPath",
                    cleanPath.removePrefix("assets/"),
                    cleanPath
                )
                for (assetPath in candidatePaths) {
                    try {
                        val stream = assets.open(assetPath)
                        val mimeType = when {
                            assetPath.endsWith(".html", true) -> "text/html"
                            assetPath.endsWith(".js", true) -> "application/javascript"
                            assetPath.endsWith(".css", true) -> "text/css"
                            assetPath.endsWith(".json", true) -> "application/json"
                            assetPath.endsWith(".png", true) -> "image/png"
                            assetPath.endsWith(".jpg", true) || assetPath.endsWith(".jpeg", true) -> "image/jpeg"
                            assetPath.endsWith(".gif", true) -> "image/gif"
                            assetPath.endsWith(".svg", true) -> "image/svg+xml"
                            assetPath.endsWith(".ico", true) -> "image/x-icon"
                            assetPath.endsWith(".mp3", true) -> "audio/mpeg"
                            assetPath.endsWith(".ttf", true) -> "font/ttf"
                            assetPath.endsWith(".woff", true) -> "font/woff"
                            assetPath.endsWith(".woff2", true) -> "font/woff2"
                            else -> "application/octet-stream"
                        }
                        return@addPathHandler WebResourceResponse(mimeType, "UTF-8", stream)
                    } catch (_: Exception) {}
                }
                null
            }
            .build()

        val wv = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#060911"))

            // Regla 8 AGENTS.md: Prohibición absoluta del roller de selección de texto y pantalla fija
            isLongClickable = false
            isHapticFeedbackEnabled = false
            setOnLongClickListener { true }
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                setGeolocationEnabled(true)
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                textZoom = 100
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }

            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidApp")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return null
                    return assetLoader.shouldInterceptRequest(url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    return handleExternalUri(uri)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url.isNullOrBlank()) return false
                    return handleExternalUri(Uri.parse(url))
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    dispatchPendingIntentToWebView()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }

            loadUrl("https://appassets.androidplatform.net/index.html")
        }

        webView = wv
        setContentView(wv)
        ViewCompat.setOnApplyWindowInsetsListener(wv) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, 0, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view != null && view.canGoBack()) {
                    view.goBack()
                } else {
                    moveTaskToBack(true)
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureIncomingIntent(intent)
        dispatchPendingIntentToWebView()
    }

    private fun handleExternalUri(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase()
        val host = (uri.host ?: "").lowercase()

        // Permitir únicamente el host interno del bundle de la app
        if ((scheme == "https" || scheme == "http") && host == "appassets.androidplatform.net") {
            return false
        }
        if (scheme == "about" || scheme == "data" || scheme == "blob") {
            return false
        }

        try {
            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(viewIntent)
        } catch (_: Exception) {
            // Si falla geo: (ej. sin app de mapas asociada a geo:), abrir Google Maps Web en navegador externo
            if (scheme == "geo") {
                try {
                    val raw = uri.schemeSpecificPart ?: ""
                    val coords = raw.substringBefore("?").trim()
                    val fallbackUrl = "https://www.google.com/maps/search/?api=1&query=${Uri.encode(coords)}"
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(fallbackIntent)
                } catch (_: Exception) {}
            }
        }
        return true
    }

    // Silenciar alarma inmediatamente al presionar botones físicos de volumen
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    SoundPlayer.stop(this)
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.dispatchEvent(new CustomEvent('sentinelStopSound'));",
                            null
                        )
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun captureIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val dispatchId = intent.getStringExtra("DISPATCH_ID")
        val showFullscreen = intent.getBooleanExtra("SHOW_FULLSCREEN_ALERT", false)
        val chatId = intent.getStringExtra("CHAT_ID")

        if (!dispatchId.isNullOrBlank() || !chatId.isNullOrBlank() || showFullscreen) {
            val json = JSONObject().apply {
                put("dispatchId", dispatchId ?: "")
                put("showFullscreenAlert", showFullscreen)
                put("chatId", chatId ?: "")
                put("timestamp", System.currentTimeMillis())
            }
            pendingIntentJson = json.toString()
        }
    }

    private fun dispatchPendingIntentToWebView() {
        val jsonStr = pendingIntentJson ?: return
        val view = webView ?: return
        view.post {
            val js = """
                (function() {
                    try {
                        var payload = $jsonStr;
                        window.__SENTINEL_PENDING_INTENT__ = payload;
                        window.dispatchEvent(new CustomEvent('sentinelNativeIntent', { detail: payload }));
                    } catch (e) {}
                })();
            """.trimIndent()
            view.evaluateJavascript(js, null)
        }
    }

    private fun startDispatchForegroundServiceSafely() {
        try {
            val serviceIntent = Intent(this, DispatchForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "No se pudo iniciar DispatchForegroundService: ${e.message}")
        }
    }

    private fun stopDispatchForegroundServiceSafely() {
        try {
            stopService(Intent(this, DispatchForegroundService::class.java))
        } catch (_: Exception) {}
    }

    private fun scheduleDailyReminder() {
        DailyReminderReceiver.scheduleAllReminders(this)
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        val autoLaunched = intent?.getBooleanExtra("AUTO_LAUNCHED_FROM_ALARM", false) == true
        if (autoLaunched) {
            intent?.removeExtra("AUTO_LAUNCHED_FROM_ALARM")
        } else {
            SoundPlayer.stopAudioOnly(this)
        }
        notifyWebViewPermissionsChanged()
    }

    override fun onPause() {
        isAppInForeground = false
        super.onPause()
    }

    private fun notifyWebViewPermissionsChanged() {
        val view = webView ?: return
        view.post {
            view.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('sentinelPermissionsChanged'));",
                null
            )
        }
    }

    private fun requestNotificationPermissionOnly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    private fun requestBackgroundLocationStep() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                try {
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun requestLocationAlwaysFlow() {
        val prefs = getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("LOCATION_DISCLOSED_V3", true).apply()

        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            pendingBackgroundLocationAfterForeground = true
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                requestBackgroundLocationStep()
                return
            }
        }

        requestNotificationPermissionOnly()
        notifyWebViewPermissionsChanged()
    }

    private fun requestNecessaryPermissions() {
        requestLocationAlwaysFlow()
    }

    override fun onDestroy() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    inner class WebAppInterface(private val context: Context) {

        private fun getLogoCacheFile(url: String): File {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray(Charsets.UTF_8))
            val hashHex = digest.joinToString("") { "%02x".format(it) }.take(32)
            val dir = File(context.filesDir, "sentinel_logos")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "logo_$hashHex.b64")
        }

        @JavascriptInterface
        fun getCachedLogoDataUri(url: String?): String {
            if (url.isNullOrBlank()) return ""
            val clean = url.trim()
            if (clean.startsWith("data:")) return clean
            return try {
                val file = getLogoCacheFile(clean)
                if (file.exists() && file.length() > 0) {
                    file.readText(Charsets.UTF_8)
                } else {
                    ""
                }
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun downloadAndCacheLogoDataUri(url: String?): String {
            if (url.isNullOrBlank()) return ""
            val clean = url.trim()
            if (clean.startsWith("data:")) return clean

            val existing = getCachedLogoDataUri(clean)
            if (existing.isNotEmpty()) return existing

            return try {
                val conn = (URL(clean).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept", "image/*,*/*;q=0.8")
                }
                conn.connect()
                if (conn.responseCode in 200..299) {
                    val contentType = (conn.contentType ?: "image/png").substringBefore(";").trim().ifEmpty { "image/png" }
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        try {
                            File(context.filesDir, "client_logo.png").writeBytes(bytes)
                        } catch (_: Exception) {}
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val dataUri = "data:$contentType;base64,$b64"
                        getLogoCacheFile(clean).writeText(dataUri, Charsets.UTF_8)
                        dataUri
                    } else ""
                } else ""
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun syncFirebaseConfig(configJson: String?) {
            if (configJson.isNullOrBlank()) return
            initializeDynamicFirebase(context, configJson)
        }

        @JavascriptInterface
        fun syncUserSession(userJson: String?) {
            if (userJson.isNullOrBlank()) return
            try {
                val obj = JSONObject(userJson)
                val userId = obj.optString("idRegistro", obj.optString("id", "")).trim()
                if (userId.isEmpty()) return

                val idRadial = obj.optString("idRadial", userId).trim()
                val nombre = obj.optString("nombre", "Bombero")
                val rango = obj.optString("rango", obj.optString("cargo", "Bombero"))
                val compania = obj.optString("compania", "1")
                val estado = obj.optString("estado", if (obj.optBoolean("disponible", false)) "0-9" else "0-8").trim()
                val conductor = obj.optBoolean("conductor", obj.optBoolean("esConductor", false))
                val estadoEspecial = obj.optString("estadoEspecial", "").trim()
                val enServicio = obj.optString("enServicio", "").trim()
                val isUnavailable = (estado.uppercase(Locale.ROOT) != "0-9")

                val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                val attendedSet = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet())?.toMutableSet() ?: mutableSetOf()
                val declinedSet = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet())?.toMutableSet() ?: mutableSetOf()
                if (enServicio.isNotEmpty() && enServicio != "0" && !enServicio.startsWith("-")) {
                    attendedSet.add(enServicio)
                    declinedSet.remove(enServicio)
                    PlayedSoundsTracker.markPlayed(context, enServicio)
                    NotificationHelper.cancelRepeatForDispatch(enServicio)
                } else if (enServicio.startsWith("-")) {
                    val dId = enServicio.removePrefix("-").trim()
                    if (dId.isNotEmpty()) {
                        declinedSet.add(dId)
                        attendedSet.remove(dId)
                        PlayedSoundsTracker.markPlayed(context, dId)
                        NotificationHelper.cancelRepeatForDispatch(dId)
                    }
                }
                val editor = prefs.edit()
                    .putString("USER_ID", userId)
                    .putString("USER_ID_RADIAL", idRadial)
                    .putString("USER_NOMBRE", nombre)
                    .putString("USER_RANGO", rango)
                    .putString("USER_COMPANIA", compania)
                    .putString("USER_ESTADO", estado)
                    .putString("IS_UNAVAILABLE", isUnavailable.toString())
                    .putBoolean("USER_CONDUCTOR", conductor)
                    .putString("USER_ESTADO_ESPECIAL", estadoEspecial)
                    .putString("USER_EN_SERVICIO", enServicio)
                    .putStringSet("ATTENDED_DISPATCH_IDS", attendedSet)
                    .putStringSet("DECLINED_DISPATCH_IDS", declinedSet)
                    .putString("fire_user", userJson)

                if (estado.uppercase(Locale.ROOT) == "0-9") {
                    editor.putBoolean("MODO_AVION", false)
                    editor.putBoolean("SILENCIO_ABSOLUTO", false)
                }
                editor.apply()

                registerFcmTokenInFirestore(context, userId)
                NotificationHelper.ensureChannels(context)
                startDispatchForegroundServiceSafely()
                scheduleDailyReminder()
            } catch (e: Exception) {
                Log.w("WebAppInterface", "Error en syncUserSession: ${e.message}")
            }
        }

        @JavascriptInterface
        fun clearUserSession() {
            try {
                val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                val oldUserId = prefs.getString("USER_ID", null)
                prefs.edit()
                    .remove("USER_ID")
                    .remove("USER_NOMBRE")
                    .remove("USER_RANGO")
                    .remove("USER_ESTADO")
                    .remove("USER_EN_SERVICIO")
                    .apply()

                if (!oldUserId.isNullOrBlank()) {
                    val fm = FirebaseMessaging.getInstance()
                    fm.unsubscribeFromTopic("usuario_$oldUserId")
                    fm.unsubscribeFromTopic("personal_$oldUserId")
                    fm.unsubscribeFromTopic("conductores")
                    fm.unsubscribeFromTopic("central_operador")
                }
                SoundPlayer.stop(context)
                stopDispatchForegroundServiceSafely()
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun setCentralOperatorMode(active: Boolean) {
            val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("IS_CENTRAL_ACTIVE", active).apply()
            if (active) {
                SoundPlayer.stop(context)
            }
            val fm = FirebaseMessaging.getInstance()
            if (active) {
                fm.subscribeToTopic("central_operador")
            } else {
                fm.unsubscribeFromTopic("central_operador")
            }
        }

        @JavascriptInterface
        fun setAirplaneMode(active: Boolean) {
            val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
                .putBoolean("MODO_AVION", active)
                .putBoolean("SILENCIO_ABSOLUTO", active)
            if (active) {
                editor.putString("USER_ESTADO", "0-8")
                editor.putString("IS_UNAVAILABLE", "true")
            }
            editor.apply()
            if (active) {
                SoundPlayer.stop(context)
                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancelAll()
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun acknowledgeDispatch(dispatchId: String?, declined: Boolean) {
            SoundPlayer.stop(context)
            if (dispatchId.isNullOrBlank()) return
            val cleanId = dispatchId.trim()
            PlayedSoundsTracker.markPlayed(context, cleanId)
            NotificationHelper.cancelRepeatForDispatch(cleanId)

            val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val declinedSet = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet())?.toMutableSet() ?: mutableSetOf()
            val attendedSet = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet())?.toMutableSet() ?: mutableSetOf()
            val editor = prefs.edit()
            if (declined) {
                declinedSet.add(cleanId)
                attendedSet.remove(cleanId)
                editor.putString("USER_EN_SERVICIO", "-$cleanId")
            } else {
                attendedSet.add(cleanId)
                declinedSet.remove(cleanId)
                editor.putString("USER_EN_SERVICIO", cleanId)
            }
            editor.putStringSet("DECLINED_DISPATCH_IDS", declinedSet)
            editor.putStringSet("ATTENDED_DISPATCH_IDS", attendedSet)
            editor.apply()

            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(cleanId.hashCode())
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun getPendingIntentJson(): String {
            return pendingIntentJson ?: ""
        }

        @JavascriptInterface
        fun clearPendingIntent() {
            pendingIntentJson = null
        }

        @JavascriptInterface
        fun playTone(toneName: String) {
            val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val is09 = (prefs.getString("USER_ESTADO", "0-9") ?: "0-9") == "0-9"
            SoundPlayer.playByName(context, toneName, forceMaxVolume = is09)
        }

        @JavascriptInterface
        fun playSound(toneName: String) {
            playTone(toneName)
        }

        @JavascriptInterface
        fun playToneWithVolume(toneName: String, forceMaxVolume: Boolean) {
            SoundPlayer.playByName(context, toneName, forceMaxVolume = forceMaxVolume)
        }

        @JavascriptInterface
        fun stopTone() {
            SoundPlayer.stop(context)
        }

        @JavascriptInterface
        fun stopSound() {
            SoundPlayer.stop(context)
        }

        @JavascriptInterface
        fun triggerVibration(isCritical: Boolean) {
            if (isCritical) {
                vibrateEmergency()
            } else {
                vibratePattern("0,400,200,400")
            }
        }

        @JavascriptInterface
        fun wakeScreenForEmergency() {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SentinelOneV3::WakeScreen"
                )
                wl.acquire(10_000L)
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun showNotification(title: String, body: String, toneName: String?) {
            showLocalNotification(title, body, toneName)
        }

        @JavascriptInterface
        fun vibratePattern(patternCsv: String) {
            try {
                val parts = patternCsv.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
                val timings = if (parts.isNotEmpty()) parts else longArrayOf(0, 800, 250, 800)
                SoundPlayer.vibrateStrong(context, timings)
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun vibrateEmergency() {
            SoundPlayer.vibrateStrong(
                context,
                longArrayOf(0, 1500, 300, 1500, 300, 1500, 300, 1500, 300, 1500)
            )
        }

        @JavascriptInterface
        fun showLocalNotification(title: String, body: String, toneName: String?) {
            val cleanName = (toneName ?: "despacho").trim().lowercase(Locale.ROOT)
                .removeSuffix(".mp3")
                .replace("-", "_")
                .replace(" ", "_")
            val resId = context.resources.getIdentifier(cleanName, "raw", context.packageName)
            val customRes = if (resId != 0) resId else R.raw.despacho
            NotificationHelper.sendNotification(
                context = context,
                title = title,
                body = body,
                isCritical = true,
                customSoundRes = customRes
            )
        }

        @JavascriptInterface
        fun getPermissionsStatusJson(): String {
            return try {
                val fineLocation = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    fineLocation
                }

                val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else {
                    true
                }

                val batteryUnrestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.isIgnoringBatteryOptimizations(context.packageName)
                } else {
                    true
                }

                val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                JSONObject().apply {
                    put("fineLocation", fineLocation)
                    put("backgroundLocation", backgroundLocation)
                    put("overlay", overlay)
                    put("batteryUnrestricted", batteryUnrestricted)
                    put("notifications", notifications)
                }.toString()
            } catch (_: Exception) {
                "{}"
            }
        }

        @JavascriptInterface
        fun requestLocationAlways() {
            runOnUiThread {
                requestLocationAlwaysFlow()
            }
        }

        @JavascriptInterface
        fun requestBatteryUnrestricted() {
            requestIgnoreBatteryOptimizations()
        }

        @JavascriptInterface
        fun closeApp() {
            runOnUiThread {
                try {
                    finishAffinity()
                } catch (_: Exception) {
                    finish()
                }
            }
        }

        @JavascriptInterface
        fun openAppSettings() {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun requestIgnoreBatteryOptimizations() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun requestOverlayPermission() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun hasOverlayPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        @JavascriptInterface
        fun isIgnoringBatteryOptimizations(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        }

        @JavascriptInterface
        fun openExternalUrl(url: String?) {
            if (url.isNullOrBlank()) return
            runOnUiThread {
                try {
                    handleExternalUri(Uri.parse(url.trim()))
                } catch (_: Exception) {}
            }
        }
    }
}
