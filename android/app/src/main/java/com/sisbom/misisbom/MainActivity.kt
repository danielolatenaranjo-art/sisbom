package com.sisbom.misisbom

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.print.PrintAttributes
import android.print.PrintManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Rational
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
import android.widget.Toast
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import java.io.FileInputStream
import java.io.FileOutputStream
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
    @Volatile
    var lastAlarmStartMs: Long = 0L
    private var alarmWakeLock: PowerManager.WakeLock? = null

    fun isAlarmProtectedFromAutoStop(windowMs: Long = 25_000L): Boolean {
        val elapsed = System.currentTimeMillis() - lastAlarmStartMs
        if (elapsed in 0..windowMs) return true
        return try {
            mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun play(context: Context, resId: Int, forceMaxVolume: Boolean = false) {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        lastAlarmStartMs = System.currentTimeMillis()

        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (alarmWakeLock == null) {
                alarmWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SentinelOneV3::SoundPlayerWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            alarmWakeLock?.acquire(22_000L)
        } catch (_: Exception) {}

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
        lastAlarmStartMs = System.currentTimeMillis()

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
            .removeSuffix(".wav")
            .replace("-", "_")
            .replace(" ", "_")
        if (cleanName == "evacuacion") {
            playEvacuationMaxVolume(context)
            return
        }
        val resId = context.resources.getIdentifier(cleanName, "raw", context.packageName)
        val finalResId = if (resId != 0) resId else R.raw.despacho
        play(context, finalResId, forceMaxVolume)
    }

    @Synchronized
    fun playEvacuationMaxVolume(context: Context) {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        lastAlarmStartMs = System.currentTimeMillis()

        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (alarmWakeLock == null) {
                alarmWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SentinelOneV3::SoundPlayerWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            alarmWakeLock?.acquire(90_000L)
        } catch (_: Exception) {}

        val evacResId = appContext.resources.getIdentifier("evacuacion", "raw", appContext.packageName)
            .let { if (it != 0) it else R.raw.alertas }

        try {
            val activeMp = mediaPlayer
            if (activeMp != null && activeMp.isPlaying && currentPlayingResId == evacResId && activeMp.isLooping) {
                enforceMaxVolumes(audioManager)
                return
            }
        } catch (_: Exception) {}

        stopAudioOnly(appContext)
        lastAlarmStartMs = System.currentTimeMillis()
        saveAndEnforceMaxVolumes(audioManager)
        requestExclusiveAlarmAudioFocus(audioManager)

        try {
            val afd = appContext.resources.openRawResourceFd(evacResId) ?: return
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            try {
                mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
            } catch (_: Exception) {}
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.isLooping = true
            mp.setVolume(1.0f, 1.0f)
            mp.prepare()
            mp.start()
            mediaPlayer = mp
            currentPlayingResId = evacResId
            vibrateStrong(
                appContext,
                longArrayOf(0, 1500, 250, 1500, 250, 1500, 250, 1500, 250, 1500)
            )
            restoreRunnable?.let { restoreHandler.removeCallbacks(it) }
            val r = Runnable {
                try {
                    stopAudioOnly(appContext)
                } catch (_: Exception) {}
            }
            restoreRunnable = r
            restoreHandler.postDelayed(r, 90_000L)
        } catch (e: Exception) {
            Log.e("SoundPlayer", "Error reproduciendo alarma de evacuación: ${e.message}")
            abandonAlarmAudioFocus(audioManager)
            restoreVolumes(appContext)
        }
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
        lastAlarmStartMs = 0L
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

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, this)
            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
            inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, this)
        }
    }

    fun decodeSampledBitmapFromResource(
        res: android.content.res.Resources,
        resId: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeResource(res, resId, this)
            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
            inJustDecodeBounds = false
            BitmapFactory.decodeResource(res, resId, this)
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
        claveForRepeat: String? = null,
        bypassForegroundMute: Boolean = false
    ) {
        try {
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

        val userEstado = (prefs.getString("USER_ESTADO", "0-9") ?: "0-9").trim().uppercase(Locale.ROOT)
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

        if (forceVibrateOnly) {
            playLoud = false
            effectiveVibrateOnly = true
        } else if (doorRequestId != null) {
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
            if (is09 || bypassForegroundMute) {
                playLoud = true
            } else {
                playLoud = false
                effectiveVibrateOnly = true
            }
        } else if (forceMaxVolume == true && (is09 || bypassForegroundMute)) {
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
        val shouldPlayAudioTone = shouldTriggerFeedback && (!isUserLookingAtApp || bypassForegroundMute || doorRequestId != null || chatId != null || alertGrade == 1)

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

        // Programar siempre el segundo tono a los 60 segundos del primer tono si el bombero está en 0-9 (o escalamiento) y no ha respondido
        if (dispatchId != null && playLoud && (is09 || isEscalation) && !skipSoundBecauseAlreadyPlayed && !isReadOnlyUser && !isPartialSilentStatus) {
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
                decodeSampledBitmapFromFile(clientFile.absolutePath, 192, 192)
            } else {
                decodeSampledBitmapFromResource(context.resources, R.drawable.logo, 192, 192)
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
                if (!isCritical && !playLoud && !effectiveVibrateOnly && alertGrade != 3 && alertGrade != 2) {
                    setSilent(true)
                }
            }
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
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
                    setPackage(context.packageName)
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
                    setPackage(context.packageName)
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
                    setPackage(context.packageName)
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
                    setPackage(context.packageName)
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
                    setPackage(context.packageName)
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
        } catch (t: Throwable) {
            Log.e("FCM", "Error en sendNotification: ${t.message}")
        }
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
        try {
        if (MainActivity.getEcosystemAppMode(applicationContext) != "ONE") return
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
            val newStatus = (data["newStatus"] ?: "").trim()
            if (newStatus.isNotEmpty()) {
                val isUnavailable = (newStatus.uppercase(Locale.ROOT) != "0-9")
                val editor = prefs.edit()
                    .putString("USER_ESTADO", newStatus)
                    .putString("IS_UNAVAILABLE", isUnavailable.toString())

                val cachedUser = prefs.getString("fire_user", null)
                if (!cachedUser.isNullOrBlank()) {
                    try {
                        val json = JSONObject(cachedUser)
                        json.put("estado", newStatus)
                        editor.putString("fire_user", json.toString())
                    } catch (_: Exception) {}
                }
                editor.apply()
            }

            val lastSelfStatus = prefs.getString("LAST_SELF_STATUS_CHANGE", "") ?: ""
            val lastSelfTime = prefs.getLong("LAST_SELF_STATUS_TIME", 0L)
            val nowMs = System.currentTimeMillis()
            val isSelfChange = newStatus.isNotEmpty() &&
                newStatus.equals(lastSelfStatus.trim(), ignoreCase = true) &&
                (nowMs - lastSelfTime < 20_000L)

            val lastNotifStatus = prefs.getString("LAST_NOTIFIED_STATUS_CHANGE", "") ?: ""
            val lastNotifTime = prefs.getLong("LAST_NOTIFIED_STATUS_TIME", 0L)
            val alreadyNotified = newStatus.isNotEmpty() &&
                newStatus.equals(lastNotifStatus.trim(), ignoreCase = true) &&
                (nowMs - lastNotifTime < 10_000L)

            if (!isSelfChange && !alreadyNotified) {
                prefs.edit()
                    .putString("LAST_NOTIFIED_STATUS_CHANGE", newStatus.uppercase(Locale.ROOT))
                    .putLong("LAST_NOTIFIED_STATUS_TIME", nowMs)
                    .apply()
                val statusTitle = remoteMessage.notification?.title ?: data["title"] ?: "ESTADO ACTUALIZADO"
                val statusBody = remoteMessage.notification?.body ?: data["body"]
                    ?: "Tu estado ha sido cambiado a $newStatus por la Central"
                NotificationHelper.sendNotification(
                    context = applicationContext,
                    title = statusTitle,
                    body = statusBody,
                    isCritical = false,
                    alertGrade = 1
                )
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

        if (type == "SCI_ALERT" || type == "SCI_CHAT") {
            val idRadial = (prefs.getString("USER_ID_RADIAL", "") ?: "").trim()
            val modoAdmin = prefs.getBoolean("USER_MODO_ADMIN", false)
            if (idRadial != "1" && !modoAdmin) return
            val sciTitle = remoteMessage.notification?.title ?: data["title"] ?: "🚨 ALERTA SCI INTER-CUERPOS"
            val sciBody = remoteMessage.notification?.body ?: data["body"] ?: "Nueva solicitud de apoyo SCI"
            val sciAlertId = if (type == "SCI_ALERT" && !payloadId.isNullOrBlank()) "sci_req_$payloadId" else "sci_msg_${System.currentTimeMillis()}"
            val alreadyPlayedSci = PlayedSoundsTracker.hasPlayed(applicationContext, sciAlertId)
            if (!alreadyPlayedSci) {
                PlayedSoundsTracker.markPlayed(applicationContext, sciAlertId)
            }
            NotificationHelper.sendNotification(
                context = applicationContext,
                title = sciTitle,
                body = sciBody,
                isCritical = true,
                customSoundRes = R.raw.alertas,
                alertId = sciAlertId,
                forceMaxVolume = true,
                alertGrade = 3,
                skipSoundBecauseAlreadyPlayed = alreadyPlayedSci,
                bypassForegroundMute = true
            )
            return
        }

        if (type == "EVACUATION_ALERT") {
            if (prefs.getBoolean("IS_CENTRAL_ACTIVE", false)) return
            val targetDispatchId = (data["dispatchId"] ?: payloadId ?: "").trim()
            val attendedSet = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet()) ?: emptySet()
            val declinedSet = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet()) ?: emptySet()
            val curSvc = (prefs.getString("USER_EN_SERVICIO", "") ?: "").trim()
            val isInTargetIncident = targetDispatchId.isNotEmpty() &&
                !declinedSet.contains(targetDispatchId) &&
                curSvc != "-$targetDispatchId" &&
                curSvc != "0" &&
                (curSvc == targetDispatchId || attendedSet.contains(targetDispatchId))
            if (!isInTargetIncident) return

            val evacTitle = remoteMessage.notification?.title ?: data["title"] ?: "🚨 ¡EVACUACIÓN INMEDIATA!"
            val evacBody = remoteMessage.notification?.body ?: data["instrucciones"] ?: data["body"]
                ?: "ABANDONAR ZONA DE RIESGO Y DIRIGIRSE AL PUNTO DE EVACUACIÓN"
            val evacRes = applicationContext.resources.getIdentifier("evacuacion", "raw", applicationContext.packageName)
                .let { if (it != 0) it else R.raw.alertas }
            SoundPlayer.playEvacuationMaxVolume(applicationContext)
            NotificationHelper.sendNotification(
                context = applicationContext,
                title = evacTitle,
                body = evacBody,
                isCritical = true,
                customSoundRes = evacRes,
                alertId = "evac_${targetDispatchId}_${data["triggeredAt"] ?: System.currentTimeMillis()}",
                forceMaxVolume = true,
                alertGrade = 3,
                skipSoundBecauseAlreadyPlayed = true,
                bypassForegroundMute = true
            )
            try {
                val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(fullScreenIntent)
            } catch (_: Exception) {}
            return
        }

        var isLateDispatch = false
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
                Log.w("FCM", "Despacho recibido tardíamente (> 5 min): solo vibrar y mostrar notificación: $payloadId")
                isLateDispatch = true
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
            val played = PlayedSoundsTracker.hasPlayed(applicationContext, soundTrackKey)
            if (!played) PlayedSoundsTracker.markPlayed(applicationContext, soundTrackKey)
            played
        } else false

        val effectiveTitle = if (isLateDispatch) "⚠️ NOTIFICACIÓN TARDÍA POR MALA COBERTURA TELEFÓNICA" else title
        val effectiveBody = if (isLateDispatch) "$title — $body" else body

        NotificationHelper.sendNotification(
            context = applicationContext,
            title = effectiveTitle,
            body = effectiveBody,
            isCritical = isCritical && !isLateDispatch,
            customSoundRes = customSoundRes,
            chatId = chatId,
            dispatchId = dispatchId,
            alertId = alertId,
            forceMaxVolume = when {
                isLateDispatch -> false
                is1210or66Push -> true
                isEscalation -> true
                else -> null
            },
            forceVibrateOnly = isLateDispatch,
            alertGrade = alertGrade,
            skipSoundBecauseAlreadyPlayed = alreadyPlayed,
            claveForRepeat = clave
        )

        // Despertar pantalla completa automáticamente si es despacho nuevo (< 5 min, en 0-9) o escalamiento, y no ha respondido aún
        if (!isLateDispatch && (type == "DISPATCH" || isEscalation) && !isAttendingThisDispatch && (!hasDeclinedThisDispatch || isEscalation) && !alreadyPlayed) {
            val userEstado = (prefs.getString("USER_ESTADO", "0-9") ?: "0-9").trim().uppercase(Locale.ROOT)
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
        } catch (t: Throwable) {
            Log.e("FCM", "Error en onMessageReceived: ${t.message}")
        }
    }

    override fun onNewToken(token: String) {
        try {
            if (MainActivity.getEcosystemAppMode(applicationContext) != "ONE") return
            MainActivity.initializeDynamicFirebase(applicationContext)
            val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("FCM_TOKEN", token).apply()
            val userId = prefs.getString("USER_ID", null)
            if (!userId.isNullOrBlank()) {
                MainActivity.registerFcmTokenInFirestore(applicationContext, userId, token)
            }
        } catch (t: Throwable) {
            Log.w("FCM", "Error en onNewToken: ${t.message}")
        }
    }
}

// ============================================================================
// 5. SERVICIO FOREGROUND EN TIEMPO REAL (DESPACHOS, ESCALAMIENTOS, 12-10/6-6 Y TELEMETRÍA GPS)
// ============================================================================
class DispatchForegroundService : Service() {
    companion object {
        @Volatile
        var instance: DispatchForegroundService? = null

        fun isUserInActiveEmergencyDispatch(context: Context): Boolean {
            return try {
                val appCtx = context.applicationContext
                if (MainActivity.getEcosystemAppMode(appCtx) != "ONE") return false
                val prefs = appCtx.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                val userId = (prefs.getString("USER_ID", "") ?: "").trim()
                val estado = (prefs.getString("USER_ESTADO", "0-8") ?: "0-8").trim().uppercase(Locale.ROOT)
                val enServicio = (prefs.getString("USER_EN_SERVICIO", "0") ?: "0").trim()
                val isAirplane = prefs.getBoolean("MODO_AVION", false) || prefs.getBoolean("SILENCIO_ABSOLUTO", false)
                !isAirplane &&
                    userId.isNotEmpty() &&
                    estado == "0-9" &&
                    enServicio.isNotEmpty() &&
                    enServicio != "0" &&
                    !enServicio.startsWith("-") &&
                    enServicio != "EN EL CUARTEL" &&
                    enServicio != "EN LA COMPAÑIA" &&
                    enServicio != "EN LA COMPAÑÍA" &&
                    !enServicio.startsWith("UNIDAD:")
            } catch (_: Throwable) {
                false
            }
        }

        fun hasValidLocationPermissionForCurrentState(context: Context): Boolean {
            return try {
                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasFine && !hasCoarse) return false
                if (!MainActivity.isAppInForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun canStartDispatchForegroundServiceNow(context: Context): Boolean {
            return try {
                val appCtx = context.applicationContext
                if (MainActivity.getEcosystemAppMode(appCtx) != "ONE") return false
                val prefs = appCtx.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                val userId = (prefs.getString("USER_ID", "") ?: "").trim()
                if (userId.isEmpty()) return false
                if (isUserInActiveEmergencyDispatch(appCtx) && hasValidLocationPermissionForCurrentState(appCtx)) {
                    return true
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MainActivity.isAppInForeground
                } else {
                    true
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun evaluateLocationTrackingNow(context: Context) {
            val appCtx = context.applicationContext
            if (MainActivity.getEcosystemAppMode(appCtx) != "ONE") return
            Handler(Looper.getMainLooper()).post {
                try {
                    val prefs = appCtx.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                    val userId = (prefs.getString("USER_ID", "") ?: "").trim()
                    val isDispatchService = isUserInActiveEmergencyDispatch(appCtx)

                    if (isDispatchService && hasValidLocationPermissionForCurrentState(appCtx)) {
                        val activeSvc = instance
                        if (activeSvc != null) {
                            activeSvc.startLocationTracking(userId)
                        } else if (canStartDispatchForegroundServiceNow(appCtx)) {
                            try {
                                val svcIntent = Intent(appCtx, DispatchForegroundService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    ContextCompat.startForegroundService(appCtx, svcIntent)
                                } else {
                                    appCtx.startService(svcIntent)
                                }
                            } catch (_: Throwable) {}
                        }
                    } else {
                        instance?.stopLocationTracking()
                        if (userId.isNotEmpty()) {
                            try {
                                MainActivity.getActiveRtdb(appCtx)
                                    ?.getReference("telemetria/bomberos/$userId")
                                    ?.removeValue()
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private var despachosListener: ListenerRegistration? = null
    private var userDocListener: ListenerRegistration? = null
    private var sciIncidentesListener: ListenerRegistration? = null
    private val sciSubcolListeners = mutableMapOf<String, ListenerRegistration>()
    private var serviceWakeLock: PowerManager.WakeLock? = null
    private val notifiedDispatches = mutableSetOf<String>()
    private val lastUnitConductorReq = mutableMapOf<String, Long>()
    private val lastUnitPersonalReq = mutableMapOf<String, Long>()
    private val lastDispatchClaves = mutableMapOf<String, String>()
    private val lastDispatchEstados = mutableMapOf<String, String>()
    private var isInitialLoad = true

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isTrackingLocation = false

    override fun onCreate() {
        super.onCreate()
        if (MainActivity.getEcosystemAppMode(applicationContext) != "ONE") {
            try { stopSelf() } catch (_: Throwable) {}
            return
        }
        instance = this
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SentinelOneV3::ServiceWakeLock").apply {
                setReferenceCounted(false)
                acquire(15 * 60 * 1000L)
            }
        } catch (_: Throwable) {}
        MainActivity.initializeDynamicFirebase(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (isUserInActiveEmergencyDispatch(applicationContext) && hasValidLocationPermissionForCurrentState(this)) {
            isTrackingLocation = true
        }
        if (!startForegroundServiceNotification()) {
            return
        }
        try {
            authenticateAndListen()
        } catch (t: Throwable) {
            Log.w("DispatchService", "Error iniciando listeners: ${t.message}")
        }
    }

    private fun startForegroundServiceNotification(): Boolean {
        return try {
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
                manager?.createNotificationChannel(channel)
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
                val hasLoc = hasValidLocationPermissionForCurrentState(this)
                val fgType = if (hasLoc && isTrackingLocation) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(1001, notification, fgType)
            } else {
                startForeground(1001, notification)
            }
            true
        } catch (t: Throwable) {
            Log.w("DispatchService", "No se pudo iniciar ForegroundService (${t.javaClass.simpleName}): ${t.message}")
            isTrackingLocation = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Throwable) {}
            try { stopSelf() } catch (_: Throwable) {}
            false
        }
    }

    private fun authenticateAndListen() {
        val auth = MainActivity.getActiveAuth(applicationContext)
        if (auth.currentUser != null && auth.currentUser?.isAnonymous == false) {
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
                val pass = obj.optString("password", obj.optString("claveAcceso", obj.optString("clave", obj.optString("contrasena", "")))).trim()
                if (idReg.isNotEmpty() && pass.isNotEmpty()) {
                    email = if (idReg.contains("@")) idReg else "${idReg}@sisbom.com"
                    password = if (pass.endsWith("_secure_sisbom")) pass else "${pass}_secure_sisbom"
                }
            } catch (_: Exception) {}
        }
        if (email.isNotEmpty() && password.isNotEmpty()) {
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { listenToUserAndDispatches() }
                .addOnFailureListener {
                    auth.signInWithEmailAndPassword("v304.app@sisbom.com", "SentinelOne_V304_Auth!2026")
                        .addOnSuccessListener { listenToUserAndDispatches() }
                        .addOnFailureListener { listenToUserAndDispatches() }
                }
        } else {
            auth.signInWithEmailAndPassword("v304.app@sisbom.com", "SentinelOne_V304_Auth!2026")
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
            var isFirstUserSnapshot = true
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
                        val modoAdmin = doc.safeBool("modoAdmin") || doc.safeBool("ModoAdmin")
                        val rango = doc.safeString("rango").ifEmpty { doc.safeString("cargo") }
                        val estadoEspecial = doc.safeString("estadoEspecial")

                        val prevEstado = (prefs.getString("USER_ESTADO", "") ?: "").trim().uppercase(Locale.ROOT)
                        val cleanNewEstado = estado.trim().uppercase(Locale.ROOT)
                        val changedByCentral = doc.safeBool("_changedByCentral")
                        val isUnavailable = (cleanNewEstado != "0-9")
                        val editPrefs = prefs.edit()
                            .putString("USER_ESTADO", estado)
                            .putString("IS_UNAVAILABLE", isUnavailable.toString())
                            .putString("USER_RANGO", rango)
                            .putString("USER_ESTADO_ESPECIAL", estadoEspecial)
                            .putBoolean("USER_CONDUCTOR", conductor)
                            .putBoolean("USER_MODO_ADMIN", modoAdmin)
                            .putString("USER_EN_SERVICIO", enServicio)
                        if (idRadial.isNotEmpty()) {
                            editPrefs.putString("USER_ID_RADIAL", idRadial)
                        }
                        editPrefs.apply()
                        NotificationHelper.ensureChannels(applicationContext)

                        if (!isFirstUserSnapshot && changedByCentral && prevEstado.isNotEmpty() && cleanNewEstado.isNotEmpty() && prevEstado != cleanNewEstado) {
                            val nowMs = System.currentTimeMillis()
                            val lastSelfStatus = (prefs.getString("LAST_SELF_STATUS_CHANGE", "") ?: "").trim().uppercase(Locale.ROOT)
                            val lastSelfTime = prefs.getLong("LAST_SELF_STATUS_TIME", 0L)
                            val isSelfChange = (cleanNewEstado == lastSelfStatus) && (nowMs - lastSelfTime < 20_000L)

                            val lastNotifStatus = (prefs.getString("LAST_NOTIFIED_STATUS_CHANGE", "") ?: "").trim().uppercase(Locale.ROOT)
                            val lastNotifTime = prefs.getLong("LAST_NOTIFIED_STATUS_TIME", 0L)
                            val alreadyNotified = (cleanNewEstado == lastNotifStatus) && (nowMs - lastNotifTime < 10_000L)

                            if (!isSelfChange && !alreadyNotified && !prefs.getBoolean("MODO_AVION", false) && !prefs.getBoolean("SILENCIO_ABSOLUTO", false)) {
                                prefs.edit()
                                    .putString("LAST_NOTIFIED_STATUS_CHANGE", cleanNewEstado)
                                    .putLong("LAST_NOTIFIED_STATUS_TIME", nowMs)
                                    .apply()
                                NotificationHelper.sendNotification(
                                    context = applicationContext,
                                    title = "ESTADO ACTUALIZADO",
                                    body = "Tu estado ha sido cambiado a $cleanNewEstado por la Central",
                                    isCritical = false,
                                    alertGrade = 1
                                )
                            }
                        }
                        isFirstUserSnapshot = false

                        val isDispatchService = cleanNewEstado == "0-9" &&
                            !prefs.getBoolean("MODO_AVION", false) &&
                            !prefs.getBoolean("SILENCIO_ABSOLUTO", false) &&
                            enServicio.isNotEmpty() &&
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
                            try {
                                MainActivity.getActiveRtdb(applicationContext)
                                    ?.getReference("telemetria/bomberos/$userId")
                                    ?.removeValue()
                            } catch (_: Exception) {}
                        }
                        listenToCentralSciAlertsIfNeeded()
                    } catch (ex: Exception) {
                        Log.w("DispatchService", "Error procesando documento personal: ${ex.message}")
                    }
                }
        }

        despachosListener?.remove()
        despachosListener = db.collection("despachos")
            .whereIn("estado", listOf("activa", "ACTIVA", "activo", "ACTIVO", "pre-despacho", "PRE-DESPACHO", "en curso", "EN CURSO"))
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

                    MainActivity.emitNativeDispatchesSnapshot(activeDocs)
                    if (activeDocs.isEmpty()) {
                        val curSvc = (prefs.getString("USER_EN_SERVICIO", "0") ?: "0").trim()
                        if (curSvc.isNotEmpty() && curSvc != "0") {
                            prefs.edit().putString("USER_EN_SERVICIO", "0").apply()
                        }
                        stopLocationTracking()
                        try {
                            val rtdb = MainActivity.getActiveRtdb(applicationContext)
                            rtdb?.getReference("telemetria/bomberos")?.removeValue()
                            rtdb?.getReference("telemetria/alertantes")?.removeValue()
                        } catch (_: Exception) {}
                    }

                    if (isInitialLoad) {
                        for (doc in activeDocs) {
                            notifiedDispatches.add(doc.id)
                            lastDispatchClaves[doc.id] = doc.safeString("clave")
                            lastDispatchEstados[doc.id] = doc.safeString("estado").uppercase(Locale.ROOT)
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

                                val isLateInit = TimeValidation.isTooOld(fechaDespacho, horaDespacho, createdAt)
                                if (!alreadyPlayed) {
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
                                    val baseBody = if (unidadesStr.isNotEmpty()) "$direccion\nUnidades: $unidadesStr" else direccion
                                    val notifTitle = if (isLateInit) "⚠️ NOTIFICACIÓN TARDÍA POR MALA COBERTURA TELEFÓNICA" else "DESPACHO: $clave"
                                    val bodyText = if (isLateInit) "DESPACHO $clave — $baseBody" else baseBody
                                    NotificationHelper.sendNotification(
                                        context = applicationContext,
                                        title = notifTitle,
                                        body = bodyText,
                                        isCritical = !isLateInit,
                                        customSoundRes = soundRes,
                                        dispatchId = id,
                                        forceVibrateOnly = isLateInit,
                                        skipSoundBecauseAlreadyPlayed = false,
                                        claveForRepeat = clave
                                    )

                                    val userEstadoInit = (prefs.getString("USER_ESTADO", "0-9") ?: "0-9").trim().uppercase(Locale.ROOT)
                                    val isCentralInit = prefs.getBoolean("IS_CENTRAL_ACTIVE", false)
                                    val userRangoInit = prefs.getString("USER_RANGO", "") ?: ""
                                    val isReadOnlyInit = userRangoInit.trim().equals("BOMBERO HONORARIO", ignoreCase = true)
                                    val estadoEspecialInit = prefs.getString("USER_ESTADO_ESPECIAL", "") ?: ""
                                    val isSilentInit = estadoEspecialInit in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO", "COMISION_SERVICIO")

                                    if (!isLateInit && !isCentralInit && !isReadOnlyInit && !isSilentInit && userEstadoInit == "0-9") {
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
                    val userEstado = prefs.getString("USER_ESTADO", "0-9") ?: "0-9"
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

                    // 1. Detectar nuevo despacho (Primer tono + programación del segundo tono a los 60s; si llega > 5 min por mala señal, solo vibrar y mostrar notificación)
                    if (!notifiedDispatches.contains(id)) {
                        notifiedDispatches.add(id)
                        lastDispatchClaves[id] = clave
                        lastDispatchEstados[id] = estadoDoc
                        val trackKey = id

                        if (isAlreadyAttending || hasAlreadyDeclined) {
                            PlayedSoundsTracker.markPlayed(applicationContext, trackKey)
                            continue
                        }

                        val isLateLive = TimeValidation.isTooOld(fechaDespacho, horaDespacho, createdAt)
                        val alreadyPlayed = PlayedSoundsTracker.hasPlayed(applicationContext, trackKey)
                        if (!alreadyPlayed) {
                            PlayedSoundsTracker.markPlayed(applicationContext, trackKey)
                        }

                        val soundRes = NotificationHelper.getSoundResForClave(applicationContext, clave)
                        val baseBody = if (unidadesStr.isNotEmpty()) "$direccion\nUnidades: $unidadesStr" else direccion
                        val notifTitle = if (isLateLive) "⚠️ NOTIFICACIÓN TARDÍA POR MALA COBERTURA TELEFÓNICA" else "DESPACHO: $clave"
                        val bodyText = if (isLateLive) "DESPACHO $clave — $baseBody" else baseBody
                        NotificationHelper.sendNotification(
                            context = applicationContext,
                            title = notifTitle,
                            body = bodyText,
                            isCritical = !isLateLive,
                            customSoundRes = soundRes,
                            dispatchId = id,
                            forceVibrateOnly = isLateLive,
                            skipSoundBecauseAlreadyPlayed = alreadyPlayed,
                            claveForRepeat = clave
                        )

                        // Levantar pantalla completa si el usuario está en 0-9, el despacho no es antiguo (> 5 min) y aún no ha sonado/respondido
                        val isCentral = prefs.getBoolean("IS_CENTRAL_ACTIVE", false)
                        val userRango = prefs.getString("USER_RANGO", "") ?: ""
                        val isReadOnlyUser = userRango.trim().equals("BOMBERO HONORARIO", ignoreCase = true)
                        val estadoEspecial = prefs.getString("USER_ESTADO_ESPECIAL", "") ?: ""
                        val isSilentStatus = estadoEspecial in listOf("CDS", "SUSPENDIDO", "LICENCIA", "SILENCIO_ABSOLUTO", "PERMISO", "COMISION_SERVICIO")

                        if (!isLateLive && !alreadyPlayed && !isCentral && !isReadOnlyUser && !isSilentStatus && userEstado == "0-9") {
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
                        // 1B. Transición de PRE-DESPACHO -> ACTIVA / EN CURSO:
                        // NO debe volver a sonar ni reiniciar el contador de 60 segundos; solo actualiza el texto del banner en silencio.
                        val prevEstado = lastDispatchEstados[id] ?: ""
                        lastDispatchEstados[id] = estadoDoc
                        val isPreToActive = prevEstado == "PRE-DESPACHO" && (estadoDoc == "ACTIVA" || estadoDoc == "ACTIVO" || estadoDoc == "EN CURSO")
                        if (isPreToActive && !isAlreadyAttending && !hasAlreadyDeclined) {
                            PlayedSoundsTracker.markPlayed(applicationContext, id)
                            val soundRes = NotificationHelper.getSoundResForClave(applicationContext, clave)
                            val bodyText = if (unidadesStr.isNotEmpty()) "$direccion\nUnidades: $unidadesStr" else direccion
                            NotificationHelper.sendNotification(
                                context = applicationContext,
                                title = "DESPACHO: $clave",
                                body = bodyText,
                                isCritical = true,
                                customSoundRes = soundRes,
                                dispatchId = id,
                                skipSoundBecauseAlreadyPlayed = true,
                                claveForRepeat = clave
                            )
                        }

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

                    // 4. Detectar orden canónica `evacuar` exclusivamente para bomberos que están EN el incidente
                    val evacMap = doc.get("evacuar") as? Map<*, *>
                    val evacActive = (evacMap?.get("evacuacion") as? Boolean) == true
                    val evacTs = ((evacMap?.get("triggeredAt") ?: evacMap?.get("updatedAt")) as? Number)?.toLong() ?: 1L
                    val isInThisIncident = !hasAlreadyDeclined &&
                        enServicio != "-$id" &&
                        enServicio != "0" &&
                        (isAlreadyAttending || enServicio == id)
                    if (evacActive && isInThisIncident) {
                        val evacTrackKey = "evac_${id}_$evacTs"
                        if (!PlayedSoundsTracker.hasPlayed(applicationContext, evacTrackKey)) {
                            PlayedSoundsTracker.markPlayed(applicationContext, evacTrackKey)
                            val instrucciones = (evacMap?.get("instrucciones")?.toString() ?: "")
                                .trim()
                                .ifEmpty { "ABANDONAR ZONA DE RIESGO DE INMEDIATO Y DIRIGIRSE AL PUNTO DE EVACUACIÓN" }
                            SoundPlayer.playEvacuationMaxVolume(applicationContext)
                            NotificationHelper.sendNotification(
                                context = applicationContext,
                                title = "🚨 ¡EVACUACIÓN INMEDIATA! [$clave]",
                                body = instrucciones,
                                isCritical = true,
                                alertId = evacTrackKey,
                                forceMaxVolume = true,
                                alertGrade = 3,
                                skipSoundBecauseAlreadyPlayed = true,
                                bypassForegroundMute = true
                            )
                        }
                    }
                }
                } catch (ex: Exception) {
                    Log.w("DispatchService", "Error procesando despachos: ${ex.message}")
                }
            }
    }

    private fun listenToCentralSciAlertsIfNeeded() {
        val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val idRadial = (prefs.getString("USER_ID_RADIAL", "") ?: "").trim()
        val modoAdmin = prefs.getBoolean("USER_MODO_ADMIN", false)
        val licenseKey = (prefs.getString("LICENSE_KEY", "") ?: "").trim().uppercase(Locale.ROOT)
        if ((idRadial != "1" && !modoAdmin) || licenseKey.isEmpty()) {
            sciIncidentesListener?.remove()
            sciIncidentesListener = null
            sciSubcolListeners.values.forEach { it.remove() }
            sciSubcolListeners.clear()
            return
        }
        if (sciIncidentesListener != null) return

        val centralDb = MainActivity.getCentralFirestore(applicationContext) ?: return
        sciIncidentesListener = centralDb.collection("sci_incidentes")
            .addSnapshotListener { snapshots, err ->
                try {
                    if (err != null || snapshots == null) return@addSnapshotListener
                    val currentLic = (prefs.getString("LICENSE_KEY", "") ?: "").trim().uppercase(Locale.ROOT)
                    if (currentLic.isEmpty()) return@addSnapshotListener

                    val activeIds = mutableSetOf<String>()
                    for (incDoc in snapshots.documents) {
                        val estadoInc = incDoc.safeString("estado").uppercase(Locale.ROOT)
                        val activoFlag = incDoc.get("activo") as? Boolean
                        val cerrado = activoFlag == false ||
                            incDoc.safeBool("cerrado") ||
                            estadoInc == "FINALIZADO" ||
                            estadoInc == "FINALIZADA" ||
                            estadoInc == "CERRADO" ||
                            estadoInc == "CERRADA" ||
                            estadoInc == "CANCELADO" ||
                            estadoInc == "CANCELADA"
                        if (cerrado) continue

                        val origenLic = incDoc.safeString("licenciaSolicitante")
                            .ifEmpty { incDoc.safeString("licenciaOrigen") }
                            .ifEmpty { incDoc.safeString("cuerpoSolicitanteLicencia") }
                            .trim().uppercase(Locale.ROOT)
                        if (origenLic == currentLic) continue

                        activeIds.add(incDoc.id)
                        if (!sciSubcolListeners.containsKey(incDoc.id)) {
                            val subListener = centralDb.collection("sci_incidentes")
                                .document(incDoc.id)
                                .collection("solicitudes")
                                .addSnapshotListener { solSnap, solErr ->
                                    try {
                                        if (solErr != null || solSnap == null) return@addSnapshotListener
                                        val myLic = (prefs.getString("LICENSE_KEY", "") ?: "").trim().uppercase(Locale.ROOT)
                                        val isAirplane = prefs.getBoolean("MODO_AVION", false) || prefs.getBoolean("SILENCIO_ABSOLUTO", false)
                                        if (myLic.isEmpty() || isAirplane) return@addSnapshotListener

                                        for (solDoc in solSnap.documents) {
                                            val destLic = solDoc.safeString("licenciaApoyo")
                                                .ifEmpty { solDoc.safeString("licenciaDestino") }
                                                .ifEmpty { solDoc.safeString("cuerpoReceptorLicencia") }
                                                .ifEmpty { solDoc.safeString("idCuerpo") }
                                                .trim().uppercase(Locale.ROOT)
                                            if (destLic != myLic) continue

                                            val estSol = solDoc.safeString("estado").uppercase(Locale.ROOT)
                                            if (estSol == "RECHAZADA" || estSol == "RECHAZADO" || estSol == "CANCELADA" || estSol == "CANCELADO" || estSol == "CERRADO" || estSol == "CERRADA") {
                                                continue
                                            }
                                            if (estSol == "PENDIENTE" || estSol == "ALERTA" || estSol == "SOLICITADA" || estSol.isEmpty()) {
                                                val alertId = "sci_req_${solDoc.id}"
                                                if (!PlayedSoundsTracker.hasPlayed(applicationContext, alertId)) {
                                                    PlayedSoundsTracker.markPlayed(applicationContext, alertId)
                                                    val cSol = solDoc.safeString("cuerpoSolicitante")
                                                        .ifEmpty { solDoc.safeString("cuerpoSolicitanteNombre") }
                                                        .ifEmpty { incDoc.safeString("cuerpoSolicitante") }
                                                        .ifEmpty { incDoc.safeString("cuerpoSolicitanteNombre") }
                                                        .ifEmpty { "Cuerpo Vecino" }
                                                    val uSol = solDoc.safeString("idCarroSolicitado")
                                                        .ifEmpty { solDoc.safeString("unidad") }
                                                        .trim()
                                                    val claveInc = solDoc.safeString("claveEmergencia")
                                                        .ifEmpty { incDoc.safeString("claveEmergencia") }
                                                        .ifEmpty { incDoc.safeString("clave") }
                                                        .ifEmpty { "SCI" }
                                                    val dirInc = solDoc.safeString("direccionEmergencia")
                                                        .ifEmpty { incDoc.safeString("direccionEmergencia") }
                                                        .ifEmpty { incDoc.safeString("direccion") }
                                                        .ifEmpty { incDoc.safeString("lugar") }
                                                    val tipoSol = solDoc.safeString("tipoSolicitud").uppercase(Locale.ROOT)
                                                    val isGeneralAlert = tipoSol == "ALERTA_PREVENTIVA" || uSol.isEmpty() || uSol.uppercase(Locale.ROOT) == "ALERTA GENERAL" || uSol.uppercase(Locale.ROOT) == "CUALQUIERA"
                                                    val title = if (!isGeneralAlert) {
                                                        "🚨 SOLICITUD SCI [$uSol] [${solDoc.id}]"
                                                    } else {
                                                        "🚨 ALERTA PREVENTIVA SCI [${solDoc.id}]"
                                                    }
                                                    val body = if (!isGeneralAlert) {
                                                        "$cSol solicita despacho de $uSol ($claveInc $dirInc)".trim()
                                                    } else {
                                                        "$cSol solicita apoyo inter-cuerpos ($claveInc $dirInc)".trim()
                                                    }
                                                    NotificationHelper.sendNotification(
                                                        context = applicationContext,
                                                        title = title,
                                                        body = body,
                                                        isCritical = true,
                                                        customSoundRes = R.raw.alertas,
                                                        alertId = alertId,
                                                        forceMaxVolume = true,
                                                        alertGrade = 3,
                                                        bypassForegroundMute = true
                                                    )
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            sciSubcolListeners[incDoc.id] = subListener
                        }
                    }

                    val toRemove = sciSubcolListeners.keys.filter { !activeIds.contains(it) }
                    for (oldId in toRemove) {
                        sciSubcolListeners.remove(oldId)?.remove()
                    }
                } catch (_: Exception) {}
            }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking(userId: String) {
        val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val estadoInit = (prefs.getString("USER_ESTADO", "0-8") ?: "0-8").trim().uppercase(Locale.ROOT)
        val svcInit = (prefs.getString("USER_EN_SERVICIO", "0") ?: "0").trim()
        if (prefs.getBoolean("MODO_AVION", false) ||
            prefs.getBoolean("SILENCIO_ABSOLUTO", false) ||
            estadoInit != "0-9" ||
            svcInit.isEmpty() ||
            svcInit == "0" ||
            svcInit.startsWith("-") ||
            svcInit == "EN EL CUARTEL" ||
            svcInit == "EN LA COMPAÑIA" ||
            svcInit == "EN LA COMPAÑÍA" ||
            svcInit.startsWith("UNIDAD:")
        ) {
            stopLocationTracking()
            try {
                MainActivity.getActiveRtdb(applicationContext)
                    ?.getReference("telemetria/bomberos/$userId")
                    ?.removeValue()
            } catch (_: Exception) {}
            return
        }

        if (isTrackingLocation) return
        if (!hasValidLocationPermissionForCurrentState(this)) return

        isTrackingLocation = true
        if (!startForegroundServiceNotification()) {
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 6_000L)
            .setMinUpdateIntervalMillis(3_000L)
            .setMinUpdateDistanceMeters(0f)
            .build()

        val publishLocToRtdb: (android.location.Location) -> Unit = { loc ->
            val now = System.currentTimeMillis()
            try {
                val curPrefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                val curEstado = (curPrefs.getString("USER_ESTADO", "0-8") ?: "0-8").trim().uppercase(Locale.ROOT)
                val enServicio = (curPrefs.getString("USER_EN_SERVICIO", "0") ?: "0").trim()
                if (curPrefs.getBoolean("MODO_AVION", false) ||
                    curPrefs.getBoolean("SILENCIO_ABSOLUTO", false) ||
                    curEstado != "0-9" ||
                    enServicio.isEmpty() ||
                    enServicio == "0" ||
                    enServicio.startsWith("-") ||
                    enServicio == "EN EL CUARTEL" ||
                    enServicio == "EN LA COMPAÑIA" ||
                    enServicio == "EN LA COMPAÑÍA" ||
                    enServicio.startsWith("UNIDAD:")
                ) {
                    stopLocationTracking()
                    MainActivity.getActiveRtdb(applicationContext)
                        ?.getReference("telemetria/bomberos/$userId")
                        ?.removeValue()
                } else {
                    val nombre = curPrefs.getString("USER_NOMBRE", "") ?: ""
                    val rango = curPrefs.getString("USER_RANGO", "") ?: ""
                    val compania = curPrefs.getString("USER_COMPANIA", "1") ?: "1"
                    val idRadial = curPrefs.getString("USER_ID_RADIAL", userId) ?: userId

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
                }
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
            startForegroundServiceNotification()
        }
    }

    fun stopLocationTracking(allowForegroundServiceUpdate: Boolean = true) {
        val wasTracking = isTrackingLocation
        locationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (_: Exception) {}
        }
        locationCallback = null
        isTrackingLocation = false
        try {
            val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val uid = (prefs.getString("USER_ID", "") ?: "").trim()
            if (uid.isNotEmpty()) {
                MainActivity.getActiveRtdb(applicationContext)
                    ?.getReference("telemetria/bomberos/$uid")
                    ?.removeValue()
            }
        } catch (_: Exception) {}
        if (wasTracking && allowForegroundServiceUpdate) {
            if (Build.VERSION.SDK_INT >= 35 && !MainActivity.isAppInForeground) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                } catch (_: Throwable) {}
            } else {
                try {
                    startForegroundServiceNotification()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun handleForegroundServiceTimeout(startId: Int, fgsType: Int) {
        Log.w("DispatchService", "onTimeout recibido (startId=$startId, fgsType=$fgsType). Deteniendo servicio.")
        try { stopLocationTracking(allowForegroundServiceUpdate = false) } catch (_: Throwable) {}
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
            }
        } catch (_: Throwable) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {}
        try { stopSelf(startId) } catch (_: Throwable) {
            try { stopSelf() } catch (_: Throwable) {}
        }
    }

    override fun onTimeout(startId: Int) {
        handleForegroundServiceTimeout(startId, 0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundServiceTimeout(startId, fgsType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (MainActivity.getEcosystemAppMode(applicationContext) != "ONE") {
            try { stopSelf() } catch (_: Throwable) {}
            return START_NOT_STICKY
        }
        if (!startForegroundServiceNotification()) {
            return START_NOT_STICKY
        }
        try {
            if (despachosListener == null) {
                authenticateAndListen()
            } else {
                listenToCentralSciAlertsIfNeeded()
            }
        } catch (t: Throwable) {
            Log.w("DispatchService", "Error en onStartCommand: ${t.message}")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isTrackingLocation) START_STICKY else START_NOT_STICKY
        } else {
            START_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            if (MainActivity.getEcosystemAppMode(applicationContext) != "ONE") return
            val canRestart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                isUserInActiveEmergencyDispatch(applicationContext) && hasValidLocationPermissionForCurrentState(applicationContext)
            } else {
                val prefs = applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                !prefs.getString("USER_ID", null).isNullOrBlank()
            }
            if (canRestart) {
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
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
            }
        } catch (_: Exception) {}
        despachosListener?.remove()
        userDocListener?.remove()
        sciIncidentesListener?.remove()
        sciSubcolListeners.values.forEach { it.remove() }
        sciSubcolListeners.clear()
        stopLocationTracking()
        if (instance === this) {
            instance = null
        }
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
        try {
        val appCtx = context.applicationContext
        val ecoMode = MainActivity.getEcosystemAppMode(appCtx)
        val isBootOrUpdate = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (isBootOrUpdate) {
            MainActivity.applyLauncherAliasForMode(appCtx, ecoMode)
            if (ecoMode == "NAV") {
                val navPrefs = appCtx.getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
                val navFbCfg = navPrefs.getString("saas_firebase_config", "") ?: ""
                val navUnit = (navPrefs.getString("selected_unit_id", "") ?: "").trim()
                if (navFbCfg.isNotEmpty()) {
                    MainActivity.initializeDynamicFirebase(appCtx, navFbCfg)
                }
                if (navUnit.isNotEmpty()) {
                    NavGpsTrackingService.startService(appCtx)
                }
                return
            }
            if (ecoMode != "ONE") return
        }

        if (ecoMode != "ONE") return

        MainActivity.initializeDynamicFirebase(appCtx)
        // Re-programar siempre los 3 horarios para el día siguiente
        scheduleAllReminders(appCtx)

        val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("USER_ID", null)

        if (isBootOrUpdate) {
            if (!userId.isNullOrBlank()) {
                try {
                    if (DispatchForegroundService.canStartDispatchForegroundServiceNow(appCtx)) {
                        val svcIntent = Intent(appCtx, DispatchForegroundService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appCtx.startForegroundService(svcIntent)
                        } else {
                            appCtx.startService(svcIntent)
                        }
                    }
                } catch (_: Throwable) {}
                try {
                    MainActivity.registerFcmTokenInFirestore(appCtx, userId)
                } catch (_: Throwable) {}
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
        } catch (t: Throwable) {
            Log.w("DailyReminder", "Error en onReceive: ${t.message}")
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
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
                DispatchForegroundService.evaluateLocationTrackingNow(context)
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
        } catch (t: Throwable) {
            Log.w("NotifAction", "Error en NotificationActionReceiver: ${t.message}")
        }
    }
}

// ============================================================================
// 6.9. APPLICATION: INICIALIZACIÓN TEMPRANA DE FIREBASE DINÁMICO DEL CLIENTE
// ============================================================================
class SisBomApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val isBgThread = try {
                Looper.getMainLooper()?.thread !== thread
            } catch (_: Throwable) {
                false
            }
            if (isBgThread) {
                Log.e("SentinelCrashShield", "Excepción contenida en hilo secundario (${thread.name})", throwable)
                try {
                    val trace = Log.getStackTraceString(throwable).take(8000)
                    getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_bg_crash_trace", trace)
                        .putLong("last_bg_crash_ts", System.currentTimeMillis())
                        .apply()
                } catch (_: Throwable) {}
                return@setDefaultUncaughtExceptionHandler
            }
            prevHandler?.uncaughtException(thread, throwable)
        }
        try {
            MainActivity.initializeDynamicFirebase(this)
        } catch (t: Throwable) {
            Log.w("SentinelCrashShield", "Error en initializeDynamicFirebase: ${t.message}")
        }
    }
}

// ============================================================================
// 7. MAIN ACTIVITY & PUENTE JS <-> NATIVO ANDROID (SENTINEL ONE V3)
// ============================================================================
class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var rootContainerRef: android.widget.FrameLayout? = null
    @Volatile
    private var lastSafeTopCss: Int = 38
    @Volatile
    private var lastSafeBottomCss: Int = 16
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var pendingIntentJson: String? = null
    private val bgFgsStopHandler = Handler(Looper.getMainLooper())
    private val bgFgsStopRunnable = Runnable {
        try {
            if (Build.VERSION.SDK_INT >= 35 &&
                !isAppInForeground &&
                getEcosystemAppMode(applicationContext) == "ONE" &&
                !DispatchForegroundService.isUserInActiveEmergencyDispatch(applicationContext)
            ) {
                stopDispatchForegroundServiceSafely()
            }
        } catch (_: Throwable) {}
    }

    companion object {
        @Volatile
        var isAppInForeground: Boolean = false
        @Volatile
        private var mainInstanceRef: java.lang.ref.WeakReference<MainActivity>? = null
        private var navDispatchWakeLock: PowerManager.WakeLock? = null
        @Volatile
        private var dynamicFirebaseApp: FirebaseApp? = null
        @Volatile
        private var currentTenantProjectId: String? = null
        @Volatile
        private var activeWebView: WebView? = null
        @Volatile
        private var lastNativeDispatchesMsgJson: String? = null

        fun normalizeEcosystemMode(raw: String?): String {
            return when ((raw ?: "ONE").trim().uppercase(Locale.ROOT)) {
                "NAV", "SENTINELNAV" -> "NAV"
                "MED", "SENTINELMED" -> "MED"
                "CREW", "SENTINELCREW" -> "CREW"
                else -> "ONE"
            }
        }

        fun getEcosystemAppMode(context: Context): String {
            return try {
                val prefs = context.applicationContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                normalizeEcosystemMode(prefs.getString("APP_ECOSYSTEM_MODE", "ONE"))
            } catch (_: Exception) {
                "ONE"
            }
        }

        fun getEcosystemEntryUrl(mode: String): String {
            return when (normalizeEcosystemMode(mode)) {
                "NAV" -> "https://appassets.androidplatform.net/nav/index.html"
                "MED" -> "https://appassets.androidplatform.net/med/index.html"
                "CREW" -> "https://appassets.androidplatform.net/crew/index.html"
                else -> "https://appassets.androidplatform.net/index.html"
            }
        }

        fun getEcosystemDisplayLabel(mode: String): String {
            return when (normalizeEcosystemMode(mode)) {
                "NAV" -> "SENTINEL NAV"
                "MED" -> "SENTINEL MED"
                "CREW" -> "SENTINEL CREW"
                else -> "SENTINEL ONE"
            }
        }

        fun applyLauncherAliasForMode(context: Context, rawMode: String) {
            try {
                val appCtx = context.applicationContext
                val pm = appCtx.packageManager
                val pkg = appCtx.packageName
                val mode = normalizeEcosystemMode(rawMode)
                val aliasByMode = mapOf(
                    "ONE" to "$pkg.LauncherOne",
                    "NAV" to "$pkg.LauncherNav",
                    "MED" to "$pkg.LauncherMed",
                    "CREW" to "$pkg.LauncherCrew"
                )
                val targetClass = aliasByMode[mode] ?: "$pkg.LauncherOne"

                // 1. Habilitar primero el alias destino antes de deshabilitar los demás
                val targetComp = ComponentName(pkg, targetClass)
                val currentTargetState = pm.getComponentEnabledSetting(targetComp)
                val isDefaultOneAlreadyEnabled = (mode == "ONE" && currentTargetState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                if (currentTargetState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED && !isDefaultOneAlreadyEnabled) {
                    pm.setComponentEnabledSetting(
                        targetComp,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }

                // 2. Deshabilitar los otros 3 aliases solo si no están ya deshabilitados
                for ((otherMode, className) in aliasByMode) {
                    if (otherMode == mode) continue
                    val comp = ComponentName(pkg, className)
                    val st = pm.getComponentEnabledSetting(comp)
                    val isDefaultAlreadyDisabled = (otherMode != "ONE" && st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                    if (st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED && !isDefaultAlreadyDisabled) {
                        pm.setComponentEnabledSetting(
                            comp,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("SENTINEL_MODE", "No se pudo cambiar alias del launcher: ${e.message}")
            }
        }

        @Suppress("DEPRECATION")
        fun acquireNavHardwareScreenWakeLock(context: Context, durationMs: Long = 60_000L) {
            try {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                val wl = navDispatchWakeLock ?: pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "SisBomCar:DispatchScreenWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }.also {
                    navDispatchWakeLock = it
                }
                wl.acquire(durationMs)
            } catch (_: Exception) {}
        }

        fun notifyNavWebViewNetworkReconnected() {
            try {
                mainInstanceRef?.get()?.let { activity ->
                    activity.runOnUiThread {
                        try {
                            activity.webView?.onResume()
                            activity.webView?.resumeTimers()
                            activity.webView?.evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('sentinel-network-reconnected'));",
                                null
                            )
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        fun pushNativeNavFirestoreSyncToWebView(jsonPayload: String) {
            try {
                val safeQuoted = JSONObject.quote(jsonPayload)
                mainInstanceRef?.get()?.let { activity ->
                    activity.runOnUiThread {
                        try {
                            activity.webView?.evaluateJavascript(
                                "if(window.__onSentinelNativeDispatchSync){window.__onSentinelNativeDispatchSync($safeQuoted);}",
                                null
                            )
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        fun wakeUpNavScreenAndStayAwake(context: Context? = null) {
            val ctx = context ?: mainInstanceRef?.get()
            if (ctx != null) {
                acquireNavHardwareScreenWakeLock(ctx, 60_000L)
            }
            try {
                mainInstanceRef?.get()?.let { activity ->
                    activity.runOnUiThread {
                        activity.wakeUpNavScreenInternal()
                        activity.updateNavScreenAwakeState(true)
                        try {
                            val lp = activity.window.attributes
                            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                            activity.window.attributes = lp
                            activity.webView?.onResume()
                            activity.webView?.resumeTimers()
                            activity.webView?.evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('sentinel-native-wakeup'));",
                                null
                            )
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}

            try {
                ctx?.let { c ->
                    val intent = Intent(c, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    c.startActivity(intent)
                }
            } catch (_: Exception) {}
        }

        private fun anyToJsonValue(value: Any?): Any {
            return when (value) {
                null -> JSONObject.NULL
                is Map<*, *> -> {
                    val obj = JSONObject()
                    for ((k, v) in value) {
                        val key = k?.toString() ?: continue
                        obj.put(key, anyToJsonValue(v))
                    }
                    obj
                }
                is Iterable<*> -> {
                    val arr = org.json.JSONArray()
                    for (item in value) {
                        arr.put(anyToJsonValue(item))
                    }
                    arr
                }
                is com.google.firebase.Timestamp -> value.toDate().time
                is Number, is Boolean, is String -> value
                else -> value.toString()
            }
        }

        fun emitNativeDispatchesSnapshot(docs: List<com.google.firebase.firestore.DocumentSnapshot>) {
            try {
                val arr = org.json.JSONArray()
                for (d in docs) {
                    val item = JSONObject()
                    item.put("id", d.id)
                    item.put("data", anyToJsonValue(d.data ?: emptyMap<String, Any>()))
                    item.put("hasPendingWrites", false)
                    arr.put(item)
                }
                val msg = JSONObject()
                msg.put("collection", "despachos")
                msg.put("payload", arr)
                val msgStr = msg.toString()
                lastNativeDispatchesMsgJson = msgStr
                val wv = activeWebView ?: return
                wv.post {
                    try {
                        wv.evaluateJavascript(
                            "try { var msg = $msgStr; window.__SENTINEL_NATIVE_SNAPSHOTS__ = window.__SENTINEL_NATIVE_SNAPSHOTS__ || {}; window.__SENTINEL_NATIVE_SNAPSHOTS__[msg.collection] = msg.payload; window.dispatchEvent(new CustomEvent('sentinelNativeFirestoreSync', { detail: msg })); } catch(e) {}",
                            null
                        )
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        private val knownAndroidAppIdsByProject = mapOf(
            "sisbom-central" to "1:432594591351:android:99a442ea6ae05276beb072",
            "pruebas-sisbom" to "1:306101642399:android:7e02cfca71bdccd391aeaa",
            "sisbom-de5f8" to "1:39449538456:android:b2f806e0a0e9fc927485ed"
        )

        private fun resolveValidAndroidAppId(projectId: String, json: JSONObject): String {
            val explicitAndroid = json.optString("androidAppId", "")
            if (explicitAndroid.contains(":android:")) return explicitAndroid
            knownAndroidAppIdsByProject[projectId]?.let { return it }
            val rawAppId = json.optString("appId", "")
            if (rawAppId.contains(":android:")) return rawAppId
            val senderId = json.optString("messagingSenderId", "432594591351")
            return "1:$senderId:android:99a442ea6ae05276beb072"
        }

        @SuppressLint("HardwareIds")
        fun getHardwareDeviceId(context: Context): String {
            return try {
                val aid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
                if (aid.isNotBlank()) "android-hw-$aid" else "android-hw-unknown"
            } catch (_: Exception) {
                "android-hw-unknown"
            }
        }

        private fun unlinkPreviousProjectSessionAsync(prevPid: String, prevApiKey: String, prevUid: String) {
            if (prevPid.isBlank() || prevApiKey.isBlank() || prevUid.isBlank()) return
            Thread {
                try {
                    val authUrl = URL("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$prevApiKey")
                    val authConn = (authUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    val authPayload = JSONObject().apply {
                        put("email", "v304.app@sisbom.com")
                        put("password", "SentinelOne_V304_Auth!2026")
                        put("returnSecureToken", true)
                    }
                    authConn.outputStream.use { it.write(authPayload.toString().toByteArray(Charsets.UTF_8)) }
                    if (authConn.responseCode !in 200..299) return@Thread
                    val authResp = JSONObject(authConn.inputStream.bufferedReader().readText())
                    val idToken = authResp.optString("idToken", "")
                    if (idToken.isBlank()) return@Thread

                    val encodedUid = Uri.encode(prevUid)
                    val patchUrl = URL("https://firestore.googleapis.com/v1/projects/$prevPid/databases/(default)/documents/personal/$encodedUid?updateMask.fieldPaths=deviceId&updateMask.fieldPaths=_metaTimestamp")
                    val patchConn = (patchUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PATCH"
                        doOutput = true
                        setRequestProperty("Authorization", "Bearer $idToken")
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    val nowMs = System.currentTimeMillis()
                    val fieldsObj = JSONObject().apply {
                        put("deviceId", JSONObject().put("stringValue", ""))
                        put("_metaTimestamp", JSONObject().put("integerValue", nowMs.toString()))
                    }
                    val patchBody = JSONObject().put("fields", fieldsObj)
                    patchConn.outputStream.use { it.write(patchBody.toString().toByteArray(Charsets.UTF_8)) }
                    patchConn.responseCode
                } catch (_: Exception) {}
            }.start()
        }

        fun initializeDynamicFirebase(context: Context, configJsonStr: String? = null) {
            try {
                val appContext = context.applicationContext
                val prefs = appContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                var jsonStr = configJsonStr
                    ?: prefs.getString("DYNAMIC_FIREBASE_CONFIG", null)
                    ?: prefs.getString("saas_firebase_config", null)
                if (jsonStr.isNullOrBlank()) {
                    val saasClientRaw = prefs.getString("WEBSTORAGE_@sentinel_saas_client", null)
                        ?: prefs.getString("WEBSTORAGE_@sentinel_one_v3_license_cache", null)
                    if (!saasClientRaw.isNullOrBlank()) {
                        try {
                            val clientObj = JSONObject(saasClientRaw)
                            val fbCfg = clientObj.optJSONObject("firebaseConfig") ?: clientObj
                            if (fbCfg.optString("projectId", "").isNotBlank() && fbCfg.optString("apiKey", "").isNotBlank()) {
                                jsonStr = fbCfg.toString()
                            }
                        } catch (_: Exception) {}
                    }
                }
                if (jsonStr.isNullOrBlank()) {
                    // CRÍTICO: No inicializar FirebaseApp([DEFAULT]) con google-services.json antes de anexar licencia,
                    // para que cuando el usuario ingrese la licencia, [DEFAULT] y FirebaseMessaging pertenezcan al Sender ID del cliente.
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
                if (apiKey.isBlank() || projectId.isBlank()) return

                val prevPid = prefs.getString("LAST_TENANT_PROJECT_ID", "") ?: ""
                val prevApiKey = prefs.getString("LAST_TENANT_API_KEY", "") ?: ""
                val prevUid = prefs.getString("LAST_TENANT_USER_ID", "") ?: ""
                if (prevPid.isNotBlank() && prevPid != projectId && prevApiKey.isNotBlank() && prevUid.isNotBlank()) {
                    unlinkPreviousProjectSessionAsync(prevPid, prevApiKey, prevUid)
                    prefs.edit().remove("LAST_TENANT_USER_ID").apply()
                }
                prefs.edit()
                    .putString("LAST_TENANT_PROJECT_ID", projectId)
                    .putString("LAST_TENANT_API_KEY", apiKey)
                    .apply()

                val applicationId = resolveValidAndroidAppId(projectId, json)
                val messagingSenderId = json.optString("messagingSenderId", "306101642399")
                val storageBucket = json.optString("storageBucket", "")
                val databaseUrl = json.optString("databaseURL", "").ifBlank {
                    "https://$projectId-default-rtdb.firebaseio.com"
                }

                val builder = FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setProjectId(projectId)
                    .setApplicationId(applicationId)
                    .setGcmSenderId(messagingSenderId)
                if (storageBucket.isNotBlank()) builder.setStorageBucket(storageBucket)
                if (databaseUrl.isNotBlank()) builder.setDatabaseUrl(databaseUrl)
                val options = builder.build()

                // 1. Inicializar [DEFAULT] directamente con las opciones del cliente sin llamar NUNCA a defaultApp.delete()
                val hasDefaultApp = FirebaseApp.getApps(appContext).any { it.name == FirebaseApp.DEFAULT_APP_NAME }
                if (!hasDefaultApp) {
                    try {
                        FirebaseApp.initializeApp(appContext, options)
                    } catch (_: Throwable) {
                        try {
                            FirebaseApp.initializeApp(appContext)
                        } catch (_: Throwable) {}
                    }
                }
                currentTenantProjectId = projectId

                // 2. Si [DEFAULT] ya coincide con el projectId del cliente, usarlo directamente; de lo contrario usar instancia nombrada
                val defaultApp = try { FirebaseApp.getInstance() } catch (_: Exception) { null }
                dynamicFirebaseApp = if (defaultApp != null && defaultApp.options.projectId == projectId) {
                    defaultApp
                } else {
                    val appName = "tenant_$projectId"
                    val existing = FirebaseApp.getApps(appContext).firstOrNull { it.name == appName }
                    existing ?: FirebaseApp.initializeApp(appContext, options, appName)
                }
                Log.d("SENTINEL_FIREBASE", "Firebase dinámico inicializado para tenant: $projectId (sender=$messagingSenderId)")
            } catch (e: Exception) {
                Log.w("SENTINEL_FIREBASE", "Error inicializando Firebase dinámico: ${e.message}")
            }
        }

        private fun ensureFallbackFirebaseApp(context: Context): FirebaseApp {
            initializeDynamicFirebase(context)
            dynamicFirebaseApp?.let { return it }
            val appContext = context.applicationContext
            if (FirebaseApp.getApps(appContext).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
                try {
                    FirebaseApp.initializeApp(appContext)
                } catch (_: Throwable) {}
            }
            if (FirebaseApp.getApps(appContext).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
                try {
                    FirebaseApp.initializeApp(
                        appContext,
                        FirebaseOptions.Builder()
                            .setApiKey("AIzaSyBjjSBwmUevGC0vT0GdxtQUw-yvawdrH44")
                            .setProjectId("sisbom-central")
                            .setApplicationId("1:432594591351:android:99a442ea6ae05276beb072")
                            .setGcmSenderId("432594591351")
                            .setStorageBucket("sisbom-central.firebasestorage.app")
                            .build()
                    )
                } catch (_: Throwable) {}
            }
            return FirebaseApp.getInstance()
        }

        fun getSafeFirebaseMessaging(context: Context): FirebaseMessaging? {
            return try {
                val appContext = context.applicationContext
                if (FirebaseApp.getApps(appContext).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
                    initializeDynamicFirebase(appContext)
                }
                if (FirebaseApp.getApps(appContext).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
                    return null
                }
                FirebaseMessaging.getInstance()
            } catch (_: Throwable) {
                null
            }
        }

        fun getActiveFirestore(context: Context): FirebaseFirestore {
            val app = ensureFallbackFirebaseApp(context)
            return FirebaseFirestore.getInstance(app)
        }

        fun getCentralFirestore(context: Context): FirebaseFirestore? {
            return try {
                val appContext = context.applicationContext
                val appName = "sentinel_central_saas"
                val existing = FirebaseApp.getApps(appContext).firstOrNull { it.name == appName }
                val centralApp = existing ?: FirebaseApp.initializeApp(
                    appContext,
                    FirebaseOptions.Builder()
                        .setApiKey("AIzaSyBjjSBwmUevGC0vT0GdxtQUw-yvawdrH44")
                        .setProjectId("sisbom-central")
                        .setApplicationId("1:432594591351:android:99a442ea6ae05276beb072")
                        .setGcmSenderId("432594591351")
                        .setStorageBucket("sisbom-central.firebasestorage.app")
                        .build(),
                    appName
                )
                FirebaseFirestore.getInstance(centralApp)
            } catch (_: Exception) {
                null
            }
        }

        fun getActiveAuth(context: Context): FirebaseAuth {
            val app = ensureFallbackFirebaseApp(context)
            return FirebaseAuth.getInstance(app)
        }

        fun getActiveRtdb(context: Context): FirebaseDatabase? {
            return try {
                val app = ensureFallbackFirebaseApp(context)
                FirebaseDatabase.getInstance(app)
            } catch (_: Exception) {
                null
            }
        }

        @Volatile
        private var lastAndroidTokenPatchMs: Long = 0L
        @Volatile
        private var lastAndroidTokenPatchSig: String = ""

        fun registerFcmTokenInFirestore(context: Context, userId: String, tokenOverride: String? = null) {
            if (userId.isBlank()) return
            val appContext = context.applicationContext
            initializeDynamicFirebase(appContext)
            if (FirebaseApp.getApps(appContext).isEmpty()) return

            val prefs = appContext.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val isConductor = prefs.getBoolean("USER_CONDUCTOR", false)
            val isCentral = prefs.getBoolean("IS_CENTRAL_ACTIVE", false)
            val isMultiDev = prefs.getBoolean("USER_MULTI_DISPOSITIVO", false) ||
                prefs.getBoolean("USER_MODO_ADMIN", false) ||
                (prefs.getString("USER_ID_RADIAL", "")?.trim() == "1")
            val activePid = currentTenantProjectId ?: try { FirebaseApp.getInstance().options.projectId ?: "" } catch (_: Exception) { "" }

            val applyToken: (String) -> Unit = { token ->
                val edit = prefs.edit()
                    .putString("FCM_TOKEN", token)
                    .putString("LAST_TENANT_USER_ID", userId)
                if (activePid.isNotBlank()) {
                    edit.putString("FCM_TOKEN_PROJECT_ID", activePid)
                    edit.putString("LAST_TENANT_PROJECT_ID", activePid)
                }
                edit.apply()

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

                val fm = getSafeFirebaseMessaging(appContext)
                if (fm != null) {
                    topics.forEach { t -> fm.subscribeToTopic(t) }
                    if (!isConductor) fm.unsubscribeFromTopic("conductores")
                    if (!isCentral) fm.unsubscribeFromTopic("central_operador")
                }

                val comboSig = "$activePid:$userId:$token:$isMultiDev"
                val nowMs = System.currentTimeMillis()
                val alreadySynced = prefs.getString("LAST_SYNCED_FCM_COMBO", "") == comboSig
                val throttled = (lastAndroidTokenPatchSig == comboSig) && ((nowMs - lastAndroidTokenPatchMs) < 20_000L)

                if (tokenOverride != null || (!alreadySynced && !throttled)) {
                    lastAndroidTokenPatchSig = comboSig
                    lastAndroidTokenPatchMs = nowMs

                    val writeTokenDoc: () -> Unit = {
                        val db = getActiveFirestore(appContext)
                        val patchMap = mutableMapOf<String, Any>(
                            "deviceId" to token,
                            "platform" to "android",
                            "_metaTimestamp" to System.currentTimeMillis()
                        )
                        // En cuentas multiDispositivo / modoAdmin / Comandante NO borrar apnsToken para que iPhone + Android coexistan sin pisarse
                        if (!isMultiDev) {
                            patchMap["apnsToken"] = ""
                        }
                        db.collection("personal").document(userId).set(
                            patchMap,
                            SetOptions.merge()
                        ).addOnSuccessListener {
                            prefs.edit().putString("LAST_SYNCED_FCM_COMBO", comboSig).apply()
                        }
                    }

                    val auth = getActiveAuth(appContext)
                    if (auth.currentUser != null && auth.currentUser?.isAnonymous == false) {
                        writeTokenDoc()
                    } else {
                        auth.signInWithEmailAndPassword("v304.app@sisbom.com", "SentinelOne_V304_Auth!2026")
                            .addOnCompleteListener { writeTokenDoc() }
                    }
                }
            }

            val fmSafe = getSafeFirebaseMessaging(appContext) ?: return
            val storedTokenPid = prefs.getString("FCM_TOKEN_PROJECT_ID", "") ?: ""
            if (activePid.isNotBlank() && storedTokenPid != activePid && tokenOverride.isNullOrBlank()) {
                // Si el token previo fue emitido con otro SenderId (ej. sisbom-central 432594591351), regenerarlo para el proyecto activo
                fmSafe.deleteToken().addOnCompleteListener {
                    getSafeFirebaseMessaging(appContext)?.token?.addOnSuccessListener { newToken ->
                        if (!newToken.isNullOrBlank()) {
                            applyToken(newToken)
                        }
                    }
                }
                return
            }

            if (!tokenOverride.isNullOrBlank()) {
                applyToken(tokenOverride)
            } else {
                fmSafe.token.addOnSuccessListener { token ->
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
        pushNavGpsRequirementStatusToWebView()
        if (getEcosystemAppMode(this) == "NAV" && hasStrictNavGpsRequirements()) {
            NavGpsTrackingService.startService(this)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainInstanceRef = java.lang.ref.WeakReference(this)

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

        val initialEcoMode = getEcosystemAppMode(this)
        applyLauncherAliasForMode(this, initialEcoMode)
        updateTaskRecentsLabel(initialEcoMode)

        val barBgColor = android.graphics.Color.parseColor("#060911")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        applyModeWindowChrome(initialEcoMode)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SentinelOneV3::EmergencyWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(15 * 60 * 1000L)
        }

        if (initialEcoMode == "NAV") {
            val navFb = getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
                .getString("saas_firebase_config", null)
            if (!navFb.isNullOrBlank()) {
                initializeDynamicFirebase(this, navFb)
            } else {
                initializeDynamicFirebase(this)
            }
        } else {
            initializeDynamicFirebase(this)
        }
        NotificationHelper.ensureChannels(this)

        if (initialEcoMode == "ONE") {
            captureIncomingIntent(intent)
            val prefs = getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val savedUserId = prefs.getString("USER_ID", null)
            val locationDisclosed = prefs.getBoolean("LOCATION_DISCLOSED_V3", false)
            if (!savedUserId.isNullOrBlank()) {
                if (locationDisclosed) {
                    requestNotificationPermissionOnly()
                }
                scheduleDailyReminder()
                registerFcmTokenInFirestore(this, savedUserId)
            }
        } else {
            stopDispatchForegroundServiceSafely()
            if (initialEcoMode == "NAV") {
                NavGpsTrackingService.onNativeGpsFixListener = { fix ->
                    pushNativeNavGpsFixToWebView(fix)
                }
                checkAndRequestNavPermissions()
            } else {
                NavGpsTrackingService.stopService(this)
            }
        }

        val rootContainer = android.widget.FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(barBgColor)
        }
        rootContainerRef = rootContainer
        setContentView(rootContainer)
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val modeNow = getEcosystemAppMode(this@MainActivity)
            val sysBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val density = resources.displayMetrics.density.coerceAtLeast(1f)
            lastSafeTopCss = kotlin.math.ceil((sysBars.top / density).toDouble()).toInt()
            lastSafeBottomCss = kotlin.math.ceil((sysBars.bottom / density).toDouble()).toInt()
            injectSafeAreaCssInsetsToWebView()

            val keyboardBottomOnly = if (imeInsets.bottom > sysBars.bottom) imeInsets.bottom else 0
            when (modeNow) {
                "CREW" -> {
                    val bottomPad = maxOf(sysBars.bottom, imeInsets.bottom)
                    view.setPadding(sysBars.left, sysBars.top, sysBars.right, bottomPad)
                }
                "NAV" -> {
                    view.setPadding(0, 0, 0, imeInsets.bottom)
                }
                else -> {
                    // ONE y MED: 100% inmersivo edge-to-edge (las barras superior e inferior del sistema se mezclan transparentes con la interfaz)
                    view.setPadding(sysBars.left, 0, sysBars.right, keyboardBottomOnly)
                }
            }
            webView?.requestLayout()
            WindowInsetsCompat.CONSUMED
        }

        mountWebViewSafely(getEcosystemEntryUrl(initialEcoMode), 0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                val modeNow = getEcosystemAppMode(this@MainActivity)
                if (view != null) {
                    val currentUrl = view.url ?: ""
                    if (currentUrl.isNotBlank() &&
                        !currentUrl.startsWith("https://appassets.androidplatform.net") &&
                        !currentUrl.startsWith("https://appassets.local")
                    ) {
                        if (view.canGoBack()) {
                            view.goBack()
                        } else {
                            view.loadUrl(getEcosystemEntryUrl(modeNow))
                        }
                        return
                    }
                    if (modeNow == "CREW") {
                        if (view.canGoBack()) {
                            view.goBack()
                        } else {
                            moveTaskToBack(true)
                        }
                        return
                    }
                    view.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('sentinelBackButton'));",
                        null
                    )
                } else {
                    moveTaskToBack(true)
                }
            }
        })
    }

    private fun resolveLocalAssetResponse(rawPath: String): WebResourceResponse? {
        val decoded = try {
            java.net.URLDecoder.decode(rawPath.trimStart('/'), "UTF-8")
        } catch (_: Exception) {
            rawPath.trimStart('/')
        }
        val cleanPath = decoded.ifEmpty { "index.html" }
        val activeMode = getEcosystemAppMode(this@MainActivity)
        val candidatePaths = mutableListOf<String>()

        if (activeMode != "ONE") {
            val modePrefix = activeMode.lowercase(Locale.ROOT)
            if (!cleanPath.startsWith("$modePrefix/")) {
                val swappedSub = if (cleanPath.startsWith("_expo/")) {
                    "expo_bundle/" + cleanPath.removePrefix("_expo/")
                } else {
                    cleanPath
                }
                candidatePaths.add("www/$modePrefix/$swappedSub")
            }
        }

        if (cleanPath.startsWith("_expo/")) {
            candidatePaths.add("www/expo_bundle/" + cleanPath.removePrefix("_expo/"))
        }
        candidatePaths.add("www/$cleanPath")
        candidatePaths.add(cleanPath.removePrefix("assets/"))
        candidatePaths.add(cleanPath)

        for (assetPath in candidatePaths) {
            try {
                val stream = assets.open(assetPath)
                val mimeType = when {
                    assetPath.endsWith(".html", true) -> "text/html"
                    assetPath.endsWith(".js", true) || assetPath.endsWith(".mjs", true) -> "application/javascript"
                    assetPath.endsWith(".css", true) -> "text/css"
                    assetPath.endsWith(".json", true) -> "application/json"
                    assetPath.endsWith(".png", true) -> "image/png"
                    assetPath.endsWith(".jpg", true) || assetPath.endsWith(".jpeg", true) -> "image/jpeg"
                    assetPath.endsWith(".gif", true) -> "image/gif"
                    assetPath.endsWith(".svg", true) -> "image/svg+xml"
                    assetPath.endsWith(".ico", true) -> "image/x-icon"
                    assetPath.endsWith(".mp3", true) -> "audio/mpeg"
                    assetPath.endsWith(".wav", true) -> "audio/wav"
                    assetPath.endsWith(".mp4", true) -> "video/mp4"
                    assetPath.endsWith(".webm", true) -> "video/webm"
                    assetPath.endsWith(".ttf", true) -> "font/ttf"
                    assetPath.endsWith(".woff", true) -> "font/woff"
                    assetPath.endsWith(".woff2", true) -> "font/woff2"
                    else -> "application/octet-stream"
                }
                val headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "no-cache"
                )
                return WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, stream)
            } catch (_: Exception) {}
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun mountWebViewSafely(entryUrl: String, attempt: Int = 0) {
        if (isFinishing || isDestroyed) return
        val container = rootContainerRef ?: return
        try {
            val prev = webView
            if (prev != null) {
                try {
                    container.removeView(prev)
                    if (activeWebView === prev) activeWebView = null
                    prev.destroy()
                } catch (_: Throwable) {}
                webView = null
            }

            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/") { path -> resolveLocalAssetResponse(path) }
                .build()

            val wv = WebView(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.parseColor("#060911"))
                keepScreenOn = true

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
                addJavascriptInterface(SentinelAndroidBridge(this@MainActivity), "SentinelAndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            detail?.didCrash() ?: false
                        } else {
                            false
                        }
                        Log.w("MainActivity", "WebView render process gone (didCrash=$didCrash). Recuperando...")
                        try {
                            view?.let { goneView ->
                                (goneView.parent as? ViewGroup)?.removeView(goneView)
                                if (webView === goneView) webView = null
                                if (activeWebView === goneView) activeWebView = null
                                goneView.destroy()
                            }
                        } catch (_: Throwable) {}
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                mountWebViewSafely(getEcosystemEntryUrl(getEcosystemAppMode(this@MainActivity)), 0)
                            }
                        }
                        return true
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url ?: return null
                        val host = (url.host ?: "").lowercase(Locale.ROOT)
                        if (host == "appassets.local") {
                            return resolveLocalAssetResponse(url.path ?: "/index.html")
                        }
                        if (host.contains("google.com") && (url.path?.contains("/vt") == true)) {
                            val cachedTile = interceptAndCacheGoogleMapTile(url)
                            if (cachedTile != null) return cachedTile
                        }
                        return assetLoader.shouldInterceptRequest(url)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        val scheme = (uri.scheme ?: "").lowercase()
                        if (request.isForMainFrame == false && (scheme == "https" || scheme == "http")) {
                            return false
                        }
                        return handleExternalUri(uri)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        if (url.isNullOrBlank()) return false
                        return handleExternalUri(Uri.parse(url))
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        val uri = request?.url
                        if (uri != null && request.isForMainFrame) {
                            val scheme = (uri.scheme ?: "").lowercase()
                            if (scheme != "https" && scheme != "http" && scheme != "about" && scheme != "data" && scheme != "blob") {
                                handleExternalUri(uri)
                                view?.stopLoading()
                                return
                            }
                        }
                        super.onReceivedError(view, request, error)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        if (!failingUrl.isNullOrBlank()) {
                            val uri = try { Uri.parse(failingUrl) } catch (_: Exception) { null }
                            val scheme = (uri?.scheme ?: "").lowercase()
                            if (uri != null && scheme != "https" && scheme != "http" && scheme != "about" && scheme != "data" && scheme != "blob") {
                                handleExternalUri(uri)
                                view?.stopLoading()
                                return
                            }
                        }
                        super.onReceivedError(view, errorCode, description, failingUrl)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val hwId = getHardwareDeviceId(this@MainActivity)
                        val fcmTok = getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE).getString("FCM_TOKEN", "") ?: ""
                        view?.evaluateJavascript(
                            "try { window.__SENTINEL_HW_DEVICE_ID__ = '$hwId'; if ('$fcmTok') window.__SENTINEL_FCM_TOKEN__ = '$fcmTok'; } catch(e) {}",
                            null
                        )
                        injectSafeAreaCssInsetsToWebView()
                        val cachedDispatchesJson = lastNativeDispatchesMsgJson
                        if (!cachedDispatchesJson.isNullOrBlank()) {
                            view?.evaluateJavascript(
                                "try { window.dispatchEvent(new CustomEvent('sentinelNativeFirestoreSync', { detail: $cachedDispatchesJson })); } catch(e) {}",
                                null
                            )
                        }
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

                loadUrl(entryUrl)
            }

            webView = wv
            activeWebView = wv
            container.addView(wv)
            ViewCompat.requestApplyInsets(container)
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error montando WebView (intento $attempt): ${t.message}")
            if (attempt < 2) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        mountWebViewSafely(entryUrl, attempt + 1)
                    }
                }, 600L)
            }
        }
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
        val path = (uri.path ?: "").lowercase()

        // Permitir siempre el host interno del bundle y esquemas internos del WebView
        if ((scheme == "https" || scheme == "http") && host == "appassets.androidplatform.net") {
            return false
        }
        if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "file") {
            return false
        }

        // NUNCA abrir endpoints de Firebase/Google APIs ni sub-recursos en el navegador externo
        if (scheme == "https" || scheme == "http") {
            val isExplicitMapOrSocial =
                host.contains("wa.me") ||
                host.contains("whatsapp.com") ||
                host.contains("maps.apple.com") ||
                host.contains("maps.app.goo.gl") ||
                host.contains("waze.com") ||
                (host.contains("google.com") && path.contains("/maps"))
            if (!isExplicitMapOrSocial) {
                return false
            }
        }

        try {
            if (scheme == "tel") {
                val dialIntent = Intent(Intent.ACTION_DIAL, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(dialIntent)
                return true
            }

            if (scheme == "whatsapp" || host.contains("wa.me") || host.contains("whatsapp.com")) {
                var targetUri = uri
                if (scheme == "https" || scheme == "http") {
                    val phoneParam = uri.getQueryParameter("phone")
                        ?: path.removePrefix("/").trim()
                    val textParam = uri.getQueryParameter("text") ?: ""
                    val cleanDigits = phoneParam.replace(Regex("\\D"), "")
                    if (cleanDigits.isNotEmpty()) {
                        val waBuilder = StringBuilder("whatsapp://send?phone=$cleanDigits")
                        if (textParam.isNotEmpty()) {
                            waBuilder.append("&text=${Uri.encode(textParam)}")
                        }
                        targetUri = Uri.parse(waBuilder.toString())
                    }
                }
                val waIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(waIntent)
                return true
            }

            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(viewIntent)
        } catch (_: Exception) {
            if (scheme == "whatsapp" || host.contains("wa.me") || host.contains("whatsapp.com")) {
                Toast.makeText(this, "WhatsApp no está instalado en este dispositivo", Toast.LENGTH_SHORT).show()
                return true
            }
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
            if (!DispatchForegroundService.canStartDispatchForegroundServiceNow(this)) return
            val serviceIntent = Intent(this, DispatchForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (t: Throwable) {
            Log.w("MainActivity", "No se pudo iniciar DispatchForegroundService: ${t.message}")
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

    private var lastPauseTimestampMs: Long = 0L

    override fun onResume() {
        super.onResume()
        bgFgsStopHandler.removeCallbacks(bgFgsStopRunnable)
        isAppInForeground = true
        val bgSec = if (lastPauseTimestampMs > 0L) {
            ((System.currentTimeMillis() - lastPauseTimestampMs) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        lastPauseTimestampMs = 0L
        val autoLaunched = intent?.getBooleanExtra("AUTO_LAUNCHED_FROM_ALARM", false) == true
        if (autoLaunched) {
            intent?.removeExtra("AUTO_LAUNCHED_FROM_ALARM")
        } else if (!SoundPlayer.isAlarmProtectedFromAutoStop()) {
            SoundPlayer.stopAudioOnly(this)
        }
        val modeNow = getEcosystemAppMode(this)
        applyModeWindowChrome(modeNow)
        notifyWebViewPermissionsChanged()
        if (modeNow == "ONE") {
            val savedUserId = getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE).getString("USER_ID", null)
            if (!savedUserId.isNullOrBlank()) {
                startDispatchForegroundServiceSafely()
            }
        }
        if (modeNow == "NAV") {
            pushNavGpsRequirementStatusToWebView()
            if (hasStrictNavGpsRequirements()) {
                NavGpsTrackingService.startService(this)
            }
        }
        if (bgSec >= 15L) {
            webView?.post {
                webView?.evaluateJavascript(
                    "try { window.dispatchEvent(new CustomEvent('sentinelAppResume', { detail: { backgroundSeconds: $bgSec, timestamp: Date.now() } })); } catch(e) {}",
                    null
                )
            }
        }
    }

    override fun onPause() {
        isAppInForeground = false
        if (lastPauseTimestampMs == 0L) {
            lastPauseTimestampMs = System.currentTimeMillis()
        }
        super.onPause()
        val modeNow = getEcosystemAppMode(this)
        if (modeNow == "ONE" && Build.VERSION.SDK_INT >= 35) {
            bgFgsStopHandler.removeCallbacks(bgFgsStopRunnable)
            bgFgsStopHandler.postDelayed(bgFgsStopRunnable, 15_000L)
        }
        if (modeNow == "NAV") {
            try {
                webView?.onResume()
                webView?.resumeTimers()
            } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        webView?.post {
            webView?.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('sentinelPipChanged', { detail: { active: $isInPictureInPictureMode } }));",
                null
            )
        }
    }

    fun enterTacticalPipMode(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    private fun notifyWebViewPermissionsChanged() {
        val view = webView ?: return
        view.post {
            view.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('sentinelPermissionsChanged'));",
                null
            )
        }
        pushNavGpsRequirementStatusToWebView()
    }

    private fun requestNotificationPermissionOnly() {
        val areEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                val prefs = getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                val askedBefore = prefs.getBoolean("NOTIF_PERMISSION_ASKED", false)
                val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                prefs.edit().putBoolean("NOTIF_PERMISSION_ASKED", true).apply()
                if (askedBefore && !shouldShowRationale) {
                    openAppNotificationSettings()
                } else {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                return
            }
        }
        if (!areEnabled) {
            openAppNotificationSettings()
        }
    }

    private fun openAppNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallback)
            } catch (_: Exception) {}
        }
    }

    private fun openAppDetailsSettingsWithHint(hintMessage: String? = null) {
        try {
            if (!hintMessage.isNullOrBlank()) {
                Toast.makeText(this, hintMessage, Toast.LENGTH_LONG).show()
            }
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun requestBackgroundLocationStep() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                val prefs = getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
                val askedBgBefore = prefs.getBoolean("BG_LOCATION_ASKED", false)
                val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                prefs.edit().putBoolean("BG_LOCATION_ASKED", true).apply()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Toast.makeText(
                        this,
                        "Seleccione: Permisos -> Ubicación -> Permitir todo el tiempo",
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (askedBgBefore && !shouldShowRationale) {
                    openAppDetailsSettingsWithHint("En Permisos -> Ubicación, elija 'Permitir todo el tiempo'")
                    return
                }
                try {
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } catch (_: Exception) {
                    openAppDetailsSettingsWithHint("En Permisos -> Ubicación, elija 'Permitir todo el tiempo'")
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
            val askedFgBefore = prefs.getBoolean("FG_LOCATION_ASKED", false)
            val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            prefs.edit().putBoolean("FG_LOCATION_ASKED", true).apply()

            if (askedFgBefore && !shouldShowRationale) {
                openAppDetailsSettingsWithHint("En Permisos -> Ubicación, elija 'Permitir todo el tiempo'")
                return
            }

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
        bgFgsStopHandler.removeCallbacksAndMessages(null)
        if (activeWebView === webView) {
            activeWebView = null
        }
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun updateTaskRecentsLabel(mode: String) {
        try {
            val label = getEcosystemDisplayLabel(mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(label).build())
            } else {
                @Suppress("DEPRECATION")
                setTaskDescription(ActivityManager.TaskDescription(label))
            }
        } catch (_: Exception) {}
    }

    private fun injectSafeAreaCssInsetsToWebView() {
        val wv = webView ?: return
        val topPx = lastSafeTopCss
        val botPx = lastSafeBottomCss
        wv.post {
            try {
                val js = """
                    (function() {
                        try {
                            window.__SENTINEL_SAFE_INSETS__ = { top: $topPx, bottom: $botPx };
                            document.documentElement.style.setProperty('--sentinel-safe-top', '${topPx}px');
                            document.documentElement.style.setProperty('--sentinel-safe-bottom', '${botPx}px');
                            var frames = document.querySelectorAll('iframe');
                            for (var i = 0; i < frames.length; i++) {
                                try {
                                    var fd = frames[i].contentDocument;
                                    if (fd && fd.documentElement) {
                                        fd.documentElement.style.setProperty('--sentinel-safe-top', '${topPx}px');
                                        fd.documentElement.style.setProperty('--sentinel-safe-bottom', '${botPx}px');
                                    }
                                    if (frames[i].contentWindow) {
                                        frames[i].contentWindow.postMessage({ type: 'SENTINEL_SAFE_INSETS', top: $topPx, bottom: $botPx }, '*');
                                    }
                                } catch (e2) {}
                            }
                        } catch (e) {}
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js, null)
            } catch (_: Exception) {}
        }
    }

    private fun applyModeWindowChrome(mode: String) {
        try {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (normalizeEcosystemMode(mode) == "NAV") {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
    }

    private fun switchEcosystemModeOnDevice(rawMode: String) {
        val targetMode = normalizeEcosystemMode(rawMode)
        val prefs = getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
        val oldUserId = prefs.getString("USER_ID", null)
        prefs.edit()
            .putString("APP_ECOSYSTEM_MODE", targetMode)
            .remove("USER_ID")
            .remove("LAST_TENANT_USER_ID")
            .remove("USER_NOMBRE")
            .remove("USER_RANGO")
            .remove("USER_ESTADO")
            .remove("USER_EN_SERVICIO")
            .commit()

        if (targetMode != "ONE") {
            try {
                val fm = getSafeFirebaseMessaging(this)
                if (fm != null) {
                    listOf("all", "despachos", "alertas", "alertas_generales", "conductores", "central_operador").forEach {
                        fm.unsubscribeFromTopic(it)
                    }
                    if (!oldUserId.isNullOrBlank()) {
                        fm.unsubscribeFromTopic("usuario_$oldUserId")
                        fm.unsubscribeFromTopic("personal_$oldUserId")
                    }
                }
            } catch (_: Exception) {}

            try {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                for (code in listOf(101, 102, 103, 2300)) {
                    val pi = PendingIntent.getBroadcast(
                        this,
                        code,
                        Intent(this, DailyReminderReceiver::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
                    )
                    if (pi != null) alarmManager.cancel(pi)
                }
            } catch (_: Exception) {}

            DispatchForegroundService.instance?.stopLocationTracking()
            SoundPlayer.stop(this)
            stopDispatchForegroundServiceSafely()
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancelAll()
            } catch (_: Exception) {}
        }

        if (targetMode != "NAV") {
            NavGpsTrackingService.stopService(this)
        }

        applyLauncherAliasForMode(this, targetMode)

        runOnUiThread {
            updateTaskRecentsLabel(targetMode)
            applyModeWindowChrome(targetMode)
            rootContainerRef?.let { ViewCompat.requestApplyInsets(it) }

            if (targetMode == "NAV") {
                NavGpsTrackingService.onNativeGpsFixListener = { fix ->
                    pushNativeNavGpsFixToWebView(fix)
                }
                checkAndRequestNavPermissions()
            }

            webView?.let { wv ->
                wv.stopLoading()
                wv.clearHistory()
                wv.loadUrl(getEcosystemEntryUrl(targetMode))
            }
        }
    }

    private fun pushNativeNavGpsFixToWebView(fix: NavGpsFixData) {
        val wv = webView ?: return
        wv.post {
            try {
                val js = String.format(
                    Locale.US,
                    "if(window.__onSentinelNativeGps){window.__onSentinelNativeGps(%.7f,%.7f,%.2f,%.2f,%.1f,%d);}",
                    fix.lat,
                    fix.lng,
                    fix.speedKmH,
                    fix.heading,
                    fix.accuracy,
                    fix.timestamp
                )
                wv.evaluateJavascript(js, null)
            } catch (_: Exception) {}
        }
    }

    private fun isHardwareGpsProviderEnabled(): Boolean {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return false
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasStrictNavGpsRequirements(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return hasFine && hasBg && isHardwareGpsProviderEnabled()
    }

    private fun buildNavGpsRequirementsStatusJson(): String {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val gpsEnabled = isHardwareGpsProviderEnabled()
        val allMet = hasFine && hasBg && gpsEnabled
        return "{\"hasFine\":$hasFine,\"hasCoarse\":$hasCoarse,\"hasBackground\":$hasBg,\"gpsEnabled\":$gpsEnabled,\"allMet\":$allMet}"
    }

    private fun pushNavGpsRequirementStatusToWebView() {
        if (getEcosystemAppMode(this) != "NAV") return
        val wv = webView ?: return
        val json = buildNavGpsRequirementsStatusJson()
        wv.post {
            try {
                wv.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('sentinel-gps-permission-status', { detail: $json }));",
                    null
                )
            } catch (_: Exception) {}
        }
    }

    private fun checkAndRequestNavPermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted || !coarseGranted) {
            requestLocationAlwaysFlow()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                requestBackgroundLocationStep()
            }
            NavGpsTrackingService.startService(this)
        }
        pushNavGpsRequirementStatusToWebView()
    }

    private fun openDeviceLocationSettings() {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun buildCompactSecondaryBoundsBundle(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return try {
            val dm = resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            val isLandscape = w >= h
            val rect = if (isLandscape) {
                val compactW = (w * 0.25f).toInt().coerceAtLeast((180 * dm.density).toInt())
                Rect(w - compactW, 0, w, h)
            } else {
                val compactH = (h * 0.25f).toInt().coerceAtLeast((180 * dm.density).toInt())
                Rect(0, h - compactH, w, h)
            }
            val opts = ActivityOptions.makeBasic()
            opts.launchBounds = rect
            opts.toBundle()
        } catch (_: Exception) {
            null
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun launchWhatsAppSplitScreen(targetUrl: String? = null) {
        try {
            val pm = packageManager
            val waPkg = when {
                isPackageInstalled(pm, "com.whatsapp") -> "com.whatsapp"
                isPackageInstalled(pm, "com.whatsapp.w4b") -> "com.whatsapp.w4b"
                else -> null
            }
            val splitFlags = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            val optionsBundle = buildCompactSecondaryBoundsBundle()

            if (!targetUrl.isNullOrBlank() && (targetUrl.contains("wa.me") || targetUrl.contains("whatsapp"))) {
                val chatIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                    if (waPkg != null) setPackage(waPkg)
                    addFlags(splitFlags)
                }
                startActivity(chatIntent, optionsBundle)
                return
            }

            val waIntent = waPkg?.let { pm.getLaunchIntentForPackage(it) }
            if (waIntent != null) {
                waIntent.addFlags(splitFlags)
                startActivity(waIntent, optionsBundle)
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://web.whatsapp.com")).apply {
                    addFlags(splitFlags)
                }
                startActivity(webIntent, optionsBundle)
            }
        } catch (_: Exception) {}
    }

    fun updateNavScreenAwakeState(keepAwake: Boolean) {
        runOnUiThread {
            try {
                webView?.keepScreenOn = keepAwake
                if (keepAwake) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    fun wakeUpNavScreenInternal() {
        try {
            acquireNavHardwareScreenWakeLock(this, 60_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                km?.requestDismissKeyguard(this, null)
            }
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        } catch (_: Exception) {}
    }

    private fun interceptAndCacheGoogleMapTile(uri: Uri): WebResourceResponse? {
        val lyrsRaw = uri.getQueryParameter("lyrs") ?: "m"
        val lyrs = lyrsRaw.replace(Regex("[^a-zA-Z0-9_-]"), "").ifEmpty { "m" }
        val apistyleRaw = uri.getQueryParameter("apistyle") ?: ""
        val styleTag = if (apistyleRaw.isNotEmpty()) "_clean" else ""
        val tileLayerDir = "$lyrs$styleTag"
        val z = uri.getQueryParameter("z")?.replace(Regex("[^0-9]"), "") ?: return null
        val x = uri.getQueryParameter("x")?.replace(Regex("[^0-9]"), "") ?: return null
        val y = uri.getQueryParameter("y")?.replace(Regex("[^0-9]"), "") ?: return null

        val tileFile = File(filesDir, "sentinel_map_tiles/$tileLayerDir/$z/$x/$y.tile")
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "public, max-age=31536000, immutable"
        )

        if (tileFile.exists() && tileFile.length() > 64L) {
            return try {
                val mime = if (lyrs.startsWith("y") || lyrs.startsWith("s")) "image/jpeg" else "image/png"
                WebResourceResponse(mime, null, 200, "OK", headers, FileInputStream(tileFile))
            } catch (_: Exception) {
                null
            }
        }

        var conn: HttpURLConnection? = null
        return try {
            val url = URL(uri.toString())
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            }
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size > 64) {
                    try {
                        tileFile.parentFile?.mkdirs()
                        val tmpFile = File(tileFile.parentFile, "${tileFile.name}.tmp")
                        FileOutputStream(tmpFile).use { it.write(bytes) }
                        tmpFile.renameTo(tileFile)
                    } catch (_: Exception) {}
                    val mime = conn.contentType?.substringBefore(";")
                        ?: if (lyrs.startsWith("y") || lyrs.startsWith("s")) "image/jpeg" else "image/png"
                    return WebResourceResponse(mime, null, 200, "OK", headers, java.io.ByteArrayInputStream(bytes))
                }
            }
            null
        } catch (_: Exception) {
            if (tileFile.exists() && tileFile.length() > 64L) {
                try {
                    val mime = if (lyrs.startsWith("y") || lyrs.startsWith("s")) "image/jpeg" else "image/png"
                    WebResourceResponse(mime, null, 200, "OK", headers, FileInputStream(tileFile))
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    inner class WebAppInterface(private val context: Context) {

        private fun getPermanentLogoFile(): File {
            val dir = File(context.filesDir, "sentinel_logos")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "permanent_client_logo.b64")
        }

        private fun getLogoCacheFile(url: String): File {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray(Charsets.UTF_8))
            val hashHex = digest.joinToString("") { "%02x".format(it) }.take(32)
            val dir = File(context.filesDir, "sentinel_logos")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "logo_$hashHex.b64")
        }

        @JavascriptInterface
        fun savePermanentLogoDataUri(dataUri: String?) {
            if (dataUri.isNullOrBlank()) return
            val clean = dataUri.trim()
            if (!clean.startsWith("data:")) return
            try {
                getPermanentLogoFile().writeText(clean, Charsets.UTF_8)
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun getCachedLogoDataUri(url: String?): String {
            if (!url.isNullOrBlank()) {
                val clean = url.trim()
                if (clean.startsWith("data:")) {
                    savePermanentLogoDataUri(clean)
                    return clean
                }
                try {
                    val file = getLogoCacheFile(clean)
                    if (file.exists() && file.length() > 0) {
                        val txt = file.readText(Charsets.UTF_8).trim()
                        if (txt.startsWith("data:")) {
                            savePermanentLogoDataUri(txt)
                            return txt
                        }
                    }
                } catch (_: Exception) {}
            }
            return try {
                val permFile = getPermanentLogoFile()
                if (permFile.exists() && permFile.length() > 0) {
                    val saved = permFile.readText(Charsets.UTF_8).trim()
                    if (saved.startsWith("data:")) saved else ""
                } else {
                    ""
                }
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun switchEcosystemAppMode(mode: String?) {
            if (mode.isNullOrBlank()) return
            switchEcosystemModeOnDevice(mode)
        }

        @JavascriptInterface
        fun getEcosystemAppMode(): String {
            return getEcosystemAppMode(context)
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
                val modoAdmin = obj.optBoolean("modoAdmin", obj.optBoolean("ModoAdmin", false))
                val multiDispositivo = obj.optBoolean("multiDispositivo", false) || modoAdmin || (idRadial == "1")
                val estadoEspecial = obj.optString("estadoEspecial", "").trim()
                val enServicio = obj.optString("enServicio", "").trim()
                val licenseKey = obj.optString("licenseKey", "").trim().uppercase(Locale.ROOT)
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
                    .putBoolean("USER_MODO_ADMIN", modoAdmin)
                    .putBoolean("USER_MULTI_DISPOSITIVO", multiDispositivo)
                    .putString("USER_ESTADO_ESPECIAL", estadoEspecial)
                    .putString("USER_EN_SERVICIO", enServicio)
                    .putStringSet("ATTENDED_DISPATCH_IDS", attendedSet)
                    .putStringSet("DECLINED_DISPATCH_IDS", declinedSet)
                    .putString("fire_user", userJson)
                if (licenseKey.isNotEmpty()) {
                    editor.putString("LICENSE_KEY", licenseKey)
                }

                if (estado.uppercase(Locale.ROOT) == "0-9") {
                    editor.putBoolean("MODO_AVION", false)
                    editor.putBoolean("SILENCIO_ABSOLUTO", false)
                }
                editor.apply()

                registerFcmTokenInFirestore(context, userId)
                NotificationHelper.ensureChannels(context)
                startDispatchForegroundServiceSafely()
                DispatchForegroundService.evaluateLocationTrackingNow(context)
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
                    .remove("LAST_TENANT_USER_ID")
                    .remove("USER_NOMBRE")
                    .remove("USER_RANGO")
                    .remove("USER_ESTADO")
                    .remove("USER_EN_SERVICIO")
                    .apply()

                if (!oldUserId.isNullOrBlank()) {
                    val fm = getSafeFirebaseMessaging(context)
                    if (fm != null) {
                        fm.unsubscribeFromTopic("usuario_$oldUserId")
                        fm.unsubscribeFromTopic("personal_$oldUserId")
                        fm.unsubscribeFromTopic("conductores")
                        fm.unsubscribeFromTopic("central_operador")
                    }
                    try {
                        val db = getActiveFirestore(context)
                        db.collection("personal").document(oldUserId).set(
                            mapOf(
                                "deviceId" to "",
                                "_metaTimestamp" to System.currentTimeMillis()
                            ),
                            SetOptions.merge()
                        )
                    } catch (_: Exception) {}
                }
                if (!oldUserId.isNullOrBlank()) {
                    try {
                        getActiveRtdb(context)
                            ?.getReference("telemetria/bomberos/${oldUserId.trim()}")
                            ?.removeValue()
                    } catch (_: Exception) {}
                }
                DispatchForegroundService.instance?.stopLocationTracking()
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
            val fm = getSafeFirebaseMessaging(context)
            if (fm != null) {
                if (active) {
                    fm.subscribeToTopic("central_operador")
                } else {
                    fm.unsubscribeFromTopic("central_operador")
                }
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
                editor.putString("USER_EN_SERVICIO", "0")
                editor.putString("IS_UNAVAILABLE", "true")
                editor.putString("LAST_SELF_STATUS_CHANGE", "0-8")
                editor.putLong("LAST_SELF_STATUS_TIME", System.currentTimeMillis())
            }
            editor.apply()
            DispatchForegroundService.evaluateLocationTrackingNow(context)
            if (active) {
                SoundPlayer.stop(context)
                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancelAll()
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun markSelfStatusChange(newStatus: String?) {
            val clean = newStatus?.trim()?.uppercase(Locale.ROOT) ?: return
            if (clean.isEmpty()) return
            val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val edit = prefs.edit()
                .putString("USER_ESTADO", clean)
                .putString("IS_UNAVAILABLE", (clean != "0-9").toString())
                .putString("LAST_SELF_STATUS_CHANGE", clean)
                .putLong("LAST_SELF_STATUS_TIME", System.currentTimeMillis())
            if (clean != "0-9") {
                edit.putString("USER_EN_SERVICIO", "0")
            }
            edit.apply()
            DispatchForegroundService.evaluateLocationTrackingNow(context)
        }

        @JavascriptInterface
        fun acknowledgeDispatch(dispatchId: String?, declined: Boolean) {
            SoundPlayer.stop(context)
            if (dispatchId.isNullOrBlank()) return
            val cleanId = dispatchId.trim()
            val prefs = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
            val declinedSet = prefs.getStringSet("DECLINED_DISPATCH_IDS", emptySet())?.toMutableSet() ?: mutableSetOf()
            val attendedSet = prefs.getStringSet("ATTENDED_DISPATCH_IDS", emptySet())?.toMutableSet() ?: mutableSetOf()
            val editor = prefs.edit()

            if (cleanId == "0" || cleanId.equals("LIBRE", ignoreCase = true)) {
                val prevSvc = (prefs.getString("USER_EN_SERVICIO", "") ?: "").removePrefix("-").trim()
                if (prevSvc.isNotEmpty()) {
                    attendedSet.remove(prevSvc)
                    declinedSet.remove(prevSvc)
                    NotificationHelper.cancelRepeatForDispatch(prevSvc)
                }
                editor.putString("USER_EN_SERVICIO", "0")
            } else if (declined) {
                PlayedSoundsTracker.markPlayed(context, cleanId)
                NotificationHelper.cancelRepeatForDispatch(cleanId)
                declinedSet.add(cleanId)
                attendedSet.remove(cleanId)
                editor.putString("USER_EN_SERVICIO", "-$cleanId")
            } else {
                PlayedSoundsTracker.markPlayed(context, cleanId)
                NotificationHelper.cancelRepeatForDispatch(cleanId)
                attendedSet.add(cleanId)
                declinedSet.remove(cleanId)
                editor.putString("USER_EN_SERVICIO", cleanId)
            }
            editor.putStringSet("DECLINED_DISPATCH_IDS", declinedSet)
            editor.putStringSet("ATTENDED_DISPATCH_IDS", attendedSet)
            editor.apply()

            DispatchForegroundService.evaluateLocationTrackingNow(context)

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
        fun enterPipMode(): Boolean {
            var entered = false
            runOnUiThread {
                entered = enterTacticalPipMode()
            }
            return entered
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
        fun playEvacuationAlarm() {
            SoundPlayer.playEvacuationMaxVolume(context)
        }

        @JavascriptInterface
        fun stopEvacuationAlarm() {
            SoundPlayer.stop(context)
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
            val rawTone = (toneName ?: "despacho").trim().lowercase(Locale.ROOT)
                .removeSuffix(".mp3")
                .replace("-", "_")
                .replace(" ", "_")
            val cleanName = if (rawTone == "alerta") "alertas" else rawTone
            val resId = context.resources.getIdentifier(cleanName, "raw", context.packageName)
            val customRes = if (resId != 0) resId else R.raw.despacho
            val sciMatch = Regex("\\[(SCI-[A-Z0-9-]+)\\]").find(title)
            val sciFolio = sciMatch?.groupValues?.getOrNull(1)
            val alertId = if (!sciFolio.isNullOrBlank()) "sci_req_$sciFolio" else null
            if (!alertId.isNullOrBlank()) {
                PlayedSoundsTracker.markPlayed(context, alertId)
            }
            NotificationHelper.sendNotification(
                context = context,
                title = title,
                body = body,
                isCritical = true,
                customSoundRes = customRes,
                alertId = alertId,
                forceMaxVolume = true,
                alertGrade = 3,
                bypassForegroundMute = !alertId.isNullOrBlank() || title.contains("SCI") || title.contains("EVACUAC")
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

                val notificationsSysEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationsSysEnabled && ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    notificationsSysEnabled
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
        fun requestNotificationsPermission() {
            runOnUiThread {
                requestNotificationPermissionOnly()
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

        @JavascriptInterface
        fun minimizeApp() {
            runOnUiThread {
                try {
                    moveTaskToBack(true)
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun showToast(message: String?) {
            if (message.isNullOrBlank()) return
            runOnUiThread {
                try {
                    Toast.makeText(context, message.trim(), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }
    }

    inner class SentinelAndroidBridge(private val context: Context) {
        @JavascriptInterface
        fun printDocument(jobName: String?) {
            runOnUiThread {
                val wv = webView ?: return@runOnUiThread
                try {
                    val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return@runOnUiThread
                    val title = if (jobName.isNullOrBlank()) "SENTINEL_MED_Documento" else jobName
                    val adapter = wv.createPrintDocumentAdapter(title)
                    printManager.print(title, adapter, PrintAttributes.Builder().build())
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun syncNativeUnitConfig(unitId: String, licenseKey: String, firebaseConfigJson: String) {
            try {
                val prefs = context.getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("selected_unit_id", unitId)
                    .putString("saas_license_key", licenseKey)
                    .putString("saas_firebase_config", firebaseConfigJson)
                    .apply()
                if (firebaseConfigJson.isNotEmpty()) {
                    initializeDynamicFirebase(context, firebaseConfigJson)
                }
                if (unitId.isNotEmpty() && getEcosystemAppMode(context) == "NAV") {
                    NavGpsTrackingService.startService(context)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun syncNativeAuthCredentials(commanderId: String, commanderPass: String) {
            try {
                val prefs = context.getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("auth_comandante_id", commanderId)
                    .putString("auth_comandante_pass", commanderPass)
                    .apply()
                if (getEcosystemAppMode(context) == "NAV") {
                    NavGpsTrackingService.startService(context)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun getNativeAuthAndUnitJson(): String {
            return try {
                val currentUrl = run {
                    var u = ""
                    val latch = java.util.concurrent.CountDownLatch(1)
                    runOnUiThread {
                        u = webView?.url ?: ""
                        latch.countDown()
                    }
                    latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    u
                }
                if (!currentUrl.startsWith("https://appassets.androidplatform.net") &&
                    !currentUrl.startsWith("https://appassets.local")
                ) {
                    return "{}"
                }
                val prefs = context.getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
                val obj = JSONObject()
                obj.put("commanderId", prefs.getString("auth_comandante_id", "") ?: "")
                obj.put("commanderPass", prefs.getString("auth_comandante_pass", "") ?: "")
                obj.put("selectedUnitId", prefs.getString("selected_unit_id", "") ?: "")
                obj.put("licenseKey", prefs.getString("saas_license_key", "") ?: "")
                obj.put("firebaseConfigJson", prefs.getString("saas_firebase_config", "") ?: "")
                obj.toString()
            } catch (_: Exception) {
                "{}"
            }
        }

        @JavascriptInterface
        fun openWhatsAppSplitScreen() {
            runOnUiThread {
                launchWhatsAppSplitScreen(null)
            }
        }

        @JavascriptInterface
        fun openWhatsAppPhoneSplitScreen(phoneUrl: String?) {
            runOnUiThread {
                launchWhatsAppSplitScreen(phoneUrl)
            }
        }

        @JavascriptInterface
        fun setKeepScreenAwake(keepAwake: Boolean) {
            updateNavScreenAwakeState(keepAwake)
        }

        @JavascriptInterface
        fun setScreenSleepMode(sleep: Boolean) {
            runOnUiThread {
                try {
                    val lp = window.attributes
                    if (sleep) {
                        webView?.keepScreenOn = false
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        lp.screenBrightness = 0.0f
                        window.attributes = lp
                    } else {
                        acquireNavHardwareScreenWakeLock(this@MainActivity, 60_000L)
                        wakeUpNavScreenInternal()
                        webView?.keepScreenOn = true
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = lp
                        webView?.onResume()
                        webView?.resumeTimers()
                    }
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun wakeUpScreenNow() {
            runOnUiThread {
                try {
                    acquireNavHardwareScreenWakeLock(this@MainActivity, 60_000L)
                    wakeUpNavScreenInternal()
                    updateNavScreenAwakeState(true)
                    val lp = window.attributes
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = lp
                    webView?.onResume()
                    webView?.resumeTimers()
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun playEvacuationAlarm() {
            wakeUpNavScreenAndStayAwake(this@MainActivity)
            SoundPlayer.playEvacuationMaxVolume(this@MainActivity)
        }

        @JavascriptInterface
        fun stopEvacuationAlarm() {
            SoundPlayer.stop(this@MainActivity)
        }

        @JavascriptInterface
        fun getGpsRequirementsStatusJson(): String {
            return buildNavGpsRequirementsStatusJson()
        }

        @JavascriptInterface
        fun requestStrictGpsPermissions() {
            runOnUiThread {
                val hasFine = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
                val isGpsEnabled = isHardwareGpsProviderEnabled()

                if (!isGpsEnabled) {
                    openDeviceLocationSettings()
                    return@runOnUiThread
                }
                if (!hasFine) {
                    checkAndRequestNavPermissions()
                    openAppDetailsSettingsWithHint(null)
                    return@runOnUiThread
                }
                if (!hasBg && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } catch (_: Exception) {
                        openAppDetailsSettingsWithHint(null)
                    }
                    return@runOnUiThread
                }
                pushNavGpsRequirementStatusToWebView()
            }
        }

        @JavascriptInterface
        fun openAppLocationPermissionSettings() {
            runOnUiThread {
                openAppDetailsSettingsWithHint(null)
            }
        }

        @JavascriptInterface
        fun openSystemGpsSettings() {
            runOnUiThread {
                openDeviceLocationSettings()
            }
        }

        @JavascriptInterface
        fun resolveNativeStreetName(lat: Double, lng: Double) {
            Thread {
                try {
                    val geocoder = android.location.Geocoder(this@MainActivity, Locale("es", "CL"))
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocation(lat, lng, 4)
                    var bestStreet = ""
                    if (!list.isNullOrEmpty()) {
                        for (addr in list) {
                            val th = addr.thoroughfare?.trim() ?: ""
                            if (th.isNotEmpty() && !th.equals("Unnamed Road", ignoreCase = true) && !th.equals("Camino Sin Nombre", ignoreCase = true)) {
                                bestStreet = th
                                break
                            }
                            val feat = addr.featureName?.trim() ?: ""
                            if (feat.isNotEmpty() && feat.any { it.isLetter() } && !feat.contains("+") && !feat.equals(addr.locality ?: "", ignoreCase = true) && !feat.equals(addr.subLocality ?: "", ignoreCase = true)) {
                                bestStreet = feat
                                break
                            }
                            val line0 = addr.getAddressLine(0)?.split(",")?.firstOrNull()?.trim() ?: ""
                            if (line0.isNotEmpty() && line0.any { it.isLetter() } && !line0.contains("+") && !line0.equals("Unnamed Road", ignoreCase = true)) {
                                bestStreet = line0
                                break
                            }
                        }
                    }
                    if (bestStreet.isNotEmpty()) {
                        val safeJsStr = JSONObject.quote(bestStreet)
                        runOnUiThread {
                            try {
                                webView?.evaluateJavascript(
                                    "if(window.__onSentinelNativeStreetName){window.__onSentinelNativeStreetName($safeJsStr);}",
                                    null
                                )
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }.start()
        }

        @JavascriptInterface
        fun reverseGeocodeNativeSync(lat: Double, lng: Double): String {
            return try {
                val geocoder = android.location.Geocoder(this@MainActivity, Locale("es", "CL"))
                @Suppress("DEPRECATION")
                val list = geocoder.getFromLocation(lat, lng, 4)
                if (!list.isNullOrEmpty()) {
                    for (addr in list) {
                        val th = addr.thoroughfare?.trim() ?: ""
                        val num = addr.subThoroughfare?.trim() ?: ""
                        val loc = addr.locality?.trim() ?: addr.subAdminArea?.trim() ?: ""
                        if (th.isNotEmpty() && !th.equals("Unnamed Road", ignoreCase = true) && !th.equals("Camino Sin Nombre", ignoreCase = true)) {
                            val base = if (num.isNotEmpty()) "$th $num" else th
                            return if (loc.isNotEmpty()) "$base, $loc" else base
                        }
                        val line0 = addr.getAddressLine(0)?.trim() ?: ""
                        if (line0.isNotEmpty() && !line0.contains("+") && !line0.equals("Unnamed Road", ignoreCase = true)) {
                            return line0
                        }
                    }
                }
                ""
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun searchNativeGoogleLocationsJson(query: String, refLat: Double, refLng: Double): String {
            val cleanQuery = query.trim()
            if (cleanQuery.length < 2) return "[]"
            return try {
                val geocoder = android.location.Geocoder(this@MainActivity, Locale("es", "CL"))
                val arr = org.json.JSONArray()
                val seen = HashSet<String>()

                fun appendAddresses(list: List<android.location.Address>?) {
                    if (list.isNullOrEmpty()) return
                    for (addr in list) {
                        val lat = addr.latitude
                        val lng = addr.longitude
                        if (lat == 0.0 && lng == 0.0) continue
                        val key = String.format(Locale.US, "%.4f_%.4f", lat, lng)
                        if (!seen.add(key)) continue

                        val line0 = addr.getAddressLine(0)?.replace(Regex(",\\s*Chile$", RegexOption.IGNORE_CASE), "")?.trim() ?: ""
                        val feature = addr.featureName?.trim() ?: ""
                        val thoroughfare = addr.thoroughfare?.trim() ?: ""
                        val subThoroughfare = addr.subThoroughfare?.trim() ?: ""
                        val locality = addr.locality?.trim() ?: addr.subAdminArea?.trim() ?: ""

                        val streetCombined = listOf(thoroughfare, subThoroughfare).filter { it.isNotEmpty() }.joinToString(" ")
                        val mainTitle = when {
                            feature.isNotEmpty() && !feature.contains("+") && feature.any { it.isLetter() } && !feature.equals(locality, ignoreCase = true) ->
                                if (streetCombined.isNotEmpty() && !feature.equals(streetCombined, ignoreCase = true)) "$feature ($streetCombined)" else feature
                            streetCombined.isNotEmpty() -> streetCombined
                            line0.isNotEmpty() -> line0.substringBefore(",").trim()
                            else -> cleanQuery
                        }.uppercase(Locale("es", "CL"))

                        val subtitle = (if (line0.isNotEmpty()) line0 else listOf(mainTitle, locality).filter { it.isNotEmpty() }.joinToString(", "))
                            .uppercase(Locale("es", "CL"))

                        val obj = JSONObject()
                        obj.put("title", mainTitle)
                        obj.put("subtitle", subtitle)
                        obj.put("address", subtitle)
                        obj.put("lat", lat)
                        obj.put("lng", lng)
                        arr.put(obj)
                        if (arr.length() >= 8) break
                    }
                }

                val fullQueryCl = if (cleanQuery.lowercase(Locale.ROOT).contains("chile")) cleanQuery else "$cleanQuery, Chile"

                @Suppress("DEPRECATION")
                if (refLat != 0.0 && refLng != 0.0) {
                    val immediateNearList = geocoder.getFromLocationName(
                        fullQueryCl,
                        6,
                        refLat - 0.14,
                        refLng - 0.14,
                        refLat + 0.14,
                        refLng + 0.14
                    )
                    appendAddresses(immediateNearList)

                    if (arr.length() < 6) {
                        val sectorNearList = geocoder.getFromLocationName(
                            fullQueryCl,
                            6,
                            refLat - 0.35,
                            refLng - 0.35,
                            refLat + 0.35,
                            refLng + 0.35
                        )
                        appendAddresses(sectorNearList)
                    }
                }

                @Suppress("DEPRECATION")
                if (arr.length() == 0) {
                    val chileList = geocoder.getFromLocationName(fullQueryCl, 5)
                    appendAddresses(chileList)
                }

                arr.toString()
            } catch (_: Exception) {
                "[]"
            }
        }

        @JavascriptInterface
        fun getOfflineMapCacheInfo(): String {
            return try {
                val root = File(filesDir, "sentinel_map_tiles")
                if (!root.exists()) {
                    "{\"tiles\":0,\"bytes\":0}"
                } else {
                    var count = 0
                    var bytes = 0L
                    root.walkTopDown().forEach { f ->
                        if (f.isFile && f.name.endsWith(".tile")) {
                            count++
                            bytes += f.length()
                        }
                    }
                    "{\"tiles\":$count,\"bytes\":$bytes}"
                }
            } catch (_: Exception) {
                "{\"tiles\":0,\"bytes\":0}"
            }
        }
    }
}
