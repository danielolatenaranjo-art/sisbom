package com.sisbom.misisbom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalDarkMode = compositionLocalOf { false }

// Colores del tema SisBom (Alineados con MiSisBom.html)
val BomberosRed = Color(0xFFB91C1C)
val BomberosRedLight = Color(0xFFEF4444)
val AlertAmber = Color(0xFFF59E0B)
val AlertAmberLight = Color(0xFFFBBF24)
val GoGreen = Color(0xFF10B981)
val GoGreenLight = Color(0xFF34D399)
val InfoBlue = Color(0xFF3B82F6)
val NavyDark = Color(0xFF0F172A)
val NavyDeep = Color(0xFF020617)

val LightBg = Color(0xFFF8FAFC)
val DarkBg = Color(0xFF050508)

val LightCardSurface = Color(0xFFFFFFFF)      // rgba(255, 255, 255, 0.45)
val DarkCardSurface = Color(0xFF120A0A)       // rgba(15, 23, 42, 0.45)
val LightCardBorder = Color(0xFFE2E8F0)       // rgba(255, 255, 255, 0.4)
val DarkCardBorder = Color(0x1AEF4444)        // rgba(255, 255, 255, 0.05)

// Compatibilidad con el código existente
val BgCream = Color(0xFFF8FAFC)
val BgCreamSecondary = Color(0xFFFFF0EC)
val TextDark = Color(0xFF1E293B)
val TextSecondary = Color(0xFF64748B)
val TextSecondaryDark = Color(0xFF94A3B8)
val CardSurface = Color.White
val CardBorder = Color(0xFFEDE8E3)
val SlateLight = Color(0xFFF8FAFC)
val SlateBorder = Color(0x33CCCCCC)

@Composable
fun SisBomBackground(
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = LocalDarkMode.current
    val redGlowAlpha = if (isDark) 0.08f else 0.12f
    val amberGlowAlpha = if (isDark) 0.05f else 0.10f

    val modifier = if (isDark) {
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F0101), // Almost black at the top
                        Color(0xFF280202), // Very dark red in the middle
                        Color(0xFF4A0303)  // Deep blood red at the bottom
                    )
                )
            )
    } else {
        Modifier
            .fillMaxSize()
            .background(LightBg)
    }

    Box(
        modifier = modifier
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(
                id = if (isDark) R.drawable.escudo_bg_dark else R.drawable.escudo_bg_light
            ),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            alpha = if (isDark) 0.5f else 0.15f
        )
        // Dibuja las auras luminosas (glows) en los extremos
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Glow rojo superior derecho
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(BomberosRed.copy(alpha = redGlowAlpha), Color.Transparent),
                    center = Offset(size.width, 0f),
                    radius = size.width * 0.8f
                ),
                center = Offset(size.width, 0f),
                radius = size.width * 0.8f
            )
            // Glow ámbar inferior izquierdo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(AlertAmber.copy(alpha = amberGlowAlpha), Color.Transparent),
                    center = Offset(0f, size.height),
                    radius = size.width * 0.8f
                ),
                center = Offset(0f, size.height),
                radius = size.width * 0.8f
            )
        }

        val context = androidx.compose.ui.platform.LocalContext.current
        val logoModel = androidx.compose.runtime.remember {
            val file = java.io.File(context.filesDir, "client_logo.png")
            if (file.exists() && file.length() > 0) {
                file
            } else {
                val url = context.getSharedPreferences("SisBomPrefs", android.content.Context.MODE_PRIVATE)
                    .getString("saas_logo_url", "") ?: ""
                url.ifEmpty { R.drawable.logo }
            }
        }

        // Marca de agua del logo difuminada en la esquina inferior derecha
        coil.compose.AsyncImage(
            model = logoModel,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(240.dp)
                .offset(x = 32.dp, y = 32.dp)
                .alpha(if (isDark) 0.18f else 0.22f)
        )

        // Contenido de la pantalla
        Box(
            modifier = Modifier.fillMaxSize(),
            content = content
        )
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = LocalDarkMode.current,
    borderColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val containerColor = if (isDarkTheme) DarkCardSurface else LightCardSurface
    val finalBorderColor = if (borderColor != Color.Unspecified) {
        borderColor
    } else {
        if (isDarkTheme) DarkCardBorder else LightCardBorder
    }

    val finalModifier = if (onClick != null) {
        modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    } else {
        modifier.clip(RoundedCornerShape(20.dp))
    }

    Card(
        modifier = finalModifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, finalBorderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun AttendanceCircle(
    percentage: Float,
    size: Dp = 120.dp,
    strokeWidth: Dp = 12.dp,
    isDarkTheme: Boolean = false
) {
    val trackColor = if (isDarkTheme) Color(0xFF334155).copy(alpha = 0.3f) else Color(0xFFE2E8F0)
    val progressColor = if (percentage >= 50f) GoGreen else BomberosRed

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Track de fondo
            drawCircle(
                color = trackColor,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            )
            // Barra de progreso
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = (percentage * 3.6f),
                useCenter = false,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val formattedPct = String.format(java.util.Locale.US, "%.2f", percentage)
            Text(
                text = "$formattedPct%",
                fontSize = if (size < 100.dp) 12.sp else 20.sp,
                fontWeight = FontWeight.Black,
                color = if (isDarkTheme) Color.White else Color(0xFF1E293B)
            )
            if (size >= 100.dp) {
                Text(
                    text = "Asistencia",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                    modifier = Modifier.alpha(0.8f)
                )
            }
        }
    }
}

@Composable
fun SyncIndicatorDot(
    isSyncing: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SyncPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    val color = if (isSyncing) GoGreen else Color.Gray

    Box(
        modifier = modifier
            .size(8.dp)
            .alpha(if (isSyncing) alpha else 1f)
            .background(color, RoundedCornerShape(50))
    )
}

// Modelo de mensaje de chat para el diálogo de sala de comunicación
data class ChatMsgItem(
    val senderName: String,
    val senderId: String,
    val message: String,
    val time: String,
    val isMe: Boolean
)

@Composable
fun ChatBubble(
    senderName: String,
    message: String,
    time: String,
    isMe: Boolean,
    isDarkTheme: Boolean = false
) {
    val alignment = if (isMe) Alignment.End else Alignment.Start
    val bubbleColor = if (isMe) BomberosRed else {
        if (isDarkTheme) Color(0xFF1E293B).copy(alpha = 0.8f) else Color.White
    }
    val textColor = if (isMe) Color.White else {
        if (isDarkTheme) Color.White else Color(0xFF1E293B)
    }
    val borderStroke = if (isMe) null else {
        androidx.compose.foundation.BorderStroke(1.dp, if (isDarkTheme) Color(0xFF334155).copy(alpha = 0.5f) else Color(0xFFE2E8F0))
    }

    val cardShape = if (isMe) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 0.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 16.dp)
    }

    Column(
        horizontalAlignment = alignment,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        if (!isMe) {
            Text(
                text = senderName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        Card(
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            border = borderStroke,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.width(if (message.length > 30) 260.dp else Dp.Unspecified)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message,
                    color = textColor,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = time,
                    color = if (isMe) Color.White.copy(alpha = 0.6f) else Color.Gray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

object SoundPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null

    fun playSound(context: Context, soundName: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = null

            // Forzar volumen al 100% en todos los canales de audio (Alarma, Notificación y Multimedia)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                try {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    val maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
                    val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotif, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                } catch (_: Exception) {}
            }

            val cleanName = soundName.substringBefore(".").trim().lowercase()
            val resId = context.resources.getIdentifier(cleanName, "raw", context.packageName)

            if (resId != 0) {
                mediaPlayer = MediaPlayer().apply {
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setAudioAttributes(attributes)
                    val afd = context.resources.openRawResourceFd(resId)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    setVolume(1.0f, 1.0f)
                    prepare()
                    setOnCompletionListener {
                        it.release()
                        if (mediaPlayer == it) {
                            mediaPlayer = null
                        }
                    }
                    start()
                }
            } else {
                if (toneGenerator == null) {
                    toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                }
                val toneType = when {
                    cleanName.startsWith("c10") -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
                    cleanName.contains("alerta") -> ToneGenerator.TONE_PROP_BEEP2
                    else -> ToneGenerator.TONE_PROP_BEEP
                }
                toneGenerator?.startTone(toneType, 400)
            }

            triggerVibration(context, cleanName)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerVibration(context: Context, soundName: String) {
        val isLong = soundName.startsWith("c10") || soundName == "despacho" || soundName == "importante"
        triggerVibration(context, isLong)
    }

    fun triggerVibration(context: Context, isLong: Boolean) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = if (isLong) {
                longArrayOf(0, 1200, 400, 1200, 400, 1200, 400, 1200)
            } else {
                longArrayOf(0, 400, 200, 400)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }

    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    fun stop(context: Context? = null) {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
            toneGenerator?.release()
            toneGenerator = null

            context?.let { ctx ->
                val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.cancel()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        stop()
    }
}

fun decodeBase64ToBitmap(base64Str: String): android.graphics.Bitmap? {
    return try {
        val pureBase64 = if (base64Str.startsWith("data:") && base64Str.contains(",")) {
            base64Str.substring(base64Str.indexOf(",") + 1)
        } else {
            base64Str
        }.trim()

        var cleanedBase64 = pureBase64
        if (cleanedBase64.contains("%")) {
            try {
                cleanedBase64 = java.net.URLDecoder.decode(cleanedBase64, "UTF-8")
            } catch (_: Exception) {}
        }

        val decodedBytes = android.util.Base64.decode(cleanedBase64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
