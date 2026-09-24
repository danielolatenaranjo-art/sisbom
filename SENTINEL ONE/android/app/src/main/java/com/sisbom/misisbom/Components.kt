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

// Colores de compatibilidad
val BgCream = Color(0xFFF8FAFC)
val BgCreamSecondary = Color(0xFFFFF0EC)
val TextDark = Color(0xFF1E293B)
val TextSecondary = Color(0xFF64748B)
val TextSecondaryDark = Color(0xFF94A3B8)
val CardSurface = Color.White
val CardBorder = Color(0xFFEDE8E3)
val SlateLight = Color(0xFFF8FAFC)
val SlateBorder = Color(0x33CCCCCC)

// Tactical Emergency Clave Colors & Indicators
fun getClaveTacticalColor(clave: String): Color {
    val cleanKey = clave.trim().uppercase()
    return when {
        cleanKey == "10-0" || cleanKey.startsWith("10-0") || cleanKey.contains("10-30") || cleanKey.contains("10_30") -> Color(0xFFDC2626) // Crimson Fire
        cleanKey == "10-1" -> Color(0xFFEA580C) // Orange Vehicle Fire
        cleanKey == "10-2" || cleanKey.contains("FORESTAL") || cleanKey.contains("PASTIZAL") -> Color(0xFFD97706) // Amber Wildfire
        cleanKey == "10-3" || cleanKey == "10-8" -> Color(0xFF0284C7) // Rescue Cyan/Blue
        cleanKey == "10-4" -> Color(0xFFE11D48) // Vehicle Accident Rose/Red
        cleanKey == "10-5" || cleanKey.contains("HAZMAT") -> Color(0xFF7C3AED) // Hazmat Purple
        cleanKey == "10-6" || cleanKey.contains("GAS") -> Color(0xFF0D9488) // Gas Teal
        cleanKey == "10-7" || cleanKey.contains("ELECTR") -> Color(0xFFEAB308) // Electrical Yellow
        cleanKey == "10-9" -> Color(0xFFC2410C) // Other Service Amber
        cleanKey == "10-10" -> Color(0xFF0369A1) // Debris / Collapse Slate-Blue
        cleanKey == "10-11" -> Color(0xFF2563EB) // Special Service Blue
        cleanKey == "10-12" -> Color(0xFF4F46E5) // Mutual Aid Indigo
        cleanKey == "10-14" -> Color(0xFF059669) // Emerald
        cleanKey.contains("9-0") || cleanKey.contains("COMANDANCIA") -> Color(0xFFDC2626)
        else -> Color(0xFFDC2626)
    }
}

fun getClavePinHex(clave: String): String {
    val cleanKey = clave.trim().uppercase()
    return when {
        cleanKey == "10-0" || cleanKey.startsWith("10-0") || cleanKey.contains("10-30") || cleanKey.contains("10_30") -> "#DC2626"
        cleanKey == "10-1" -> "#EA580C"
        cleanKey == "10-2" || cleanKey.contains("FORESTAL") || cleanKey.contains("PASTIZAL") -> "#D97706"
        cleanKey == "10-3" || cleanKey == "10-8" -> "#0284C7"
        cleanKey == "10-4" -> "#E11D48"
        cleanKey == "10-5" || cleanKey.contains("HAZMAT") -> "#7C3AED"
        cleanKey == "10-6" || cleanKey.contains("GAS") -> "#0D9488"
        cleanKey == "10-7" || cleanKey.contains("ELECTR") -> "#EAB308"
        cleanKey == "10-9" -> "#C2410C"
        cleanKey == "10-10" -> "#0369A1"
        cleanKey == "10-11" -> "#2563EB"
        cleanKey == "10-12" -> "#4F46E5"
        cleanKey == "10-14" -> "#059669"
        else -> "#DC2626"
    }
}

@Composable
fun TacticalRadarScanner(
    clave: String,
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
    compact: Boolean = false
) {
    val claveColor = getClaveTacticalColor(clave)
    val infiniteTransition = rememberInfiniteTransition(label = "RadarTransition")
    
    // Rotating radar beam angle
    val radarAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarAngle"
    )

    // Pulsing core ping
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 120.dp else 165.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDark) Color(0xFF030712) else Color(0xFF0F172A))
            .border(1.dp, if (isDark) Color(0x33EF4444) else Color(0x33DC2626), RoundedCornerShape(16.dp))
    ) {
        // Radar Canvas Grid & Sweep
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = Math.min(size.width, size.height) * 0.45f

            // Concentric range circles
            val ringCount = 3
            for (i in 1..ringCount) {
                val r = maxRadius * (i.toFloat() / ringCount)
                drawCircle(
                    color = claveColor.copy(alpha = 0.18f),
                    radius = r,
                    center = center,
                    style = Stroke(width = 1.2f)
                )
            }

            // Crosshair lines
            drawLine(
                color = claveColor.copy(alpha = 0.22f),
                start = Offset(center.x - maxRadius, center.y),
                end = Offset(center.x + maxRadius, center.y),
                strokeWidth = 1f
            )
            drawLine(
                color = claveColor.copy(alpha = 0.22f),
                start = Offset(center.x, center.y - maxRadius),
                end = Offset(center.x, center.y + maxRadius),
                strokeWidth = 1f
            )

            // Radar Sweep Sector
            drawArc(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.85f to Color.Transparent,
                    0.95f to claveColor.copy(alpha = 0.15f),
                    1f to claveColor.copy(alpha = 0.55f),
                    center = center
                ),
                startAngle = radarAngle - 90f,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                size = androidx.compose.ui.geometry.Size(maxRadius * 2, maxRadius * 2)
            )

            // Pulsing target beacon
            drawCircle(
                color = claveColor.copy(alpha = pulseAlpha * 0.35f),
                radius = 16f * pulseScale,
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 4.5f,
                center = center
            )
        }

        // Top Status Badge
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color(0xFFF59E0B), RoundedCornerShape(50))
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "FASE 1: SIN GPS",
                color = Color(0xFFFDE68A),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
        }

        // Center / Bottom Tactical Description
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🛰️ RASTREANDO GEORREFERENCIA...",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "Central despachando coordenadas y pre-informe",
                color = Color(0xFF94A3B8),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PulsingPerimeterBorder(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFEF4444),
    strokeWidth: Dp = 3.5.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PerimeterTransition")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PerimeterAlpha"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val strokePx = strokeWidth.toPx()
        // Draw pulsing outer glow
        drawRoundRect(
            color = color.copy(alpha = alpha * 0.4f),
            topLeft = Offset(strokePx / 2f, strokePx / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - strokePx, size.height - strokePx),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx(), 24.dp.toPx()),
            style = Stroke(width = strokePx * 2f)
        )
        // Draw solid crisp inner stroke
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(strokePx / 2f, strokePx / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - strokePx, size.height - strokePx),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx(), 24.dp.toPx()),
            style = Stroke(width = strokePx)
        )
    }
}

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
        val prefs = androidx.compose.runtime.remember {
            context.getSharedPreferences("SisBomPrefs", android.content.Context.MODE_PRIVATE)
        }
        val logoModel = androidx.compose.runtime.remember(
            prefs.getString("saas_license_key", ""),
            prefs.getString("saas_logo_url", "")
        ) {
            val file = java.io.File(context.filesDir, "client_logo.png")
            if (file.exists() && file.length() > 0) {
                file
            } else {
                val url = prefs.getString("saas_logo_url", "") ?: ""
                if (url.isNotEmpty()) {
                    url
                } else {
                    val key = prefs.getString("saas_license_key", "") ?: ""
                    var resId = 0
                    if (key.isNotEmpty()) {
                        val formattedKey = key.lowercase().replace("-", "_")
                        resId = context.resources.getIdentifier("logo_$formattedKey", "drawable", context.packageName)
                        if (resId == 0) {
                            val stripped = formattedKey.replace("sb_", "")
                            resId = context.resources.getIdentifier("logo_$stripped", "drawable", context.packageName)
                            if (resId == 0) {
                                resId = context.resources.getIdentifier("logo_sb_$stripped", "drawable", context.packageName)
                            }
                        }
                    }
                    if (resId != 0) resId else R.drawable.logo
                }
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
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            try {
                try {
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.stop()
                    }
                    mediaPlayer?.reset()
                    mediaPlayer?.release()
                } catch (_: Exception) {}
                mediaPlayer = null

                // Forzar volumen al 100% en todos los canales de audio (Alarma, Notificación y Multimedia)
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager != null) {
                    try {
                        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    } catch (_: Exception) {}
                    try {
                        val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                    } catch (_: Exception) {}
                    try {
                        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                    } catch (_: Exception) {}
                    try {
                        val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                        audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)
                    } catch (_: Exception) {}
                    try {
                        val maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
                        audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotif, 0)
                    } catch (_: Exception) {}
                }

                val cleanName = soundName.substringBefore(".").trim().lowercase()
                val resId = context.resources.getIdentifier(cleanName, "raw", context.packageName)

                if (resId != 0) {
                    val afd = try {
                        context.resources.openRawResourceFd(resId)
                    } catch (_: Exception) {
                        null
                    }

                    if (afd != null) {
                        try {
                            val mp = MediaPlayer()
                            val attributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                            mp.setAudioAttributes(attributes)
                            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            mp.setVolume(1.0f, 1.0f)
                            mp.prepare()
                            mp.setOnCompletionListener {
                                try {
                                    it.release()
                                } catch (_: Exception) {}
                                if (mediaPlayer == it) {
                                    mediaPlayer = null
                                }
                            }
                            mediaPlayer = mp
                            mp.start()
                        } finally {
                            try {
                                afd.close()
                            } catch (_: Exception) {}
                        }
                    } else {
                        val mp = MediaPlayer.create(context, resId)
                        mediaPlayer = mp
                        mp?.setVolume(1.0f, 1.0f)
                        mp?.setOnCompletionListener {
                            try { it.release() } catch (_: Exception) {}
                            if (mediaPlayer == it) mediaPlayer = null
                        }
                        mp?.start()
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
                try {
                    if (toneGenerator == null) {
                        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    }
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1200)
                } catch (_: Exception) {}
                triggerVibration(context, true)
            }
        }
    }

    private fun triggerVibration(context: Context, soundName: String) {
        val isLong = soundName.startsWith("c10") || soundName == "despacho" || soundName == "importante"
        triggerVibration(context, isLong)
    }

    fun triggerVibration(context: Context, isLong: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = if (isLong) {
                longArrayOf(0, 1200, 400, 1200, 400, 1200, 400, 1200)
            } else {
                longArrayOf(0, 400, 200, 400)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), audioAttributes)
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
