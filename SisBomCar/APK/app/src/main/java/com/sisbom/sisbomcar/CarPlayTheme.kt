package com.sisbom.sisbomcar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object CarPlayColors {
    val Background = Color(0xFF070B14)
    val BackgroundSecondary = Color(0xFF0F172A)
    val DockBackground = Color(0xFF090D1A)

    val CardSurface = Color(0xFF131D31)
    val CardBorder = Color(0xFF334155)

    val PrimaryBlue = Color(0xFF0284C7)
    val AccentCyan = Color(0xFF38BDF8)
    val PrimaryGreen = Color(0xFF10B981)
    val PrimaryRed = Color(0xFFEF4444)
    val PrimaryAmber = Color(0xFFF59E0B)
    val PrimaryPurple = Color(0xFF8B5CF6)
    val PrimaryTeal = Color(0xFF0D9488)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)

    val ButtonPrimary = Color(0xFF0284C7)
    val ButtonSuccess = Color(0xFF16A34A)
    val ButtonDanger = Color(0xFFDC2626)
    val ButtonWarning = Color(0xFFD97706)
}

private val DarkColorScheme = darkColorScheme(
    primary = CarPlayColors.PrimaryBlue,
    secondary = CarPlayColors.AccentCyan,
    tertiary = CarPlayColors.PrimaryGreen,
    background = CarPlayColors.Background,
    surface = CarPlayColors.CardSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = CarPlayColors.TextPrimary,
    onSurface = CarPlayColors.TextPrimary
)

@Composable
fun CarPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

fun Modifier.carPlayCard(
    cornerRadius: Dp = 18.dp,
    borderAlpha: Float = 0.18f,
    bgAlpha: Float = 0.88f
): Modifier {
    val safeAlpha = bgAlpha.coerceIn(0f, 1f)
    val safeAlpha2 = (bgAlpha + 0.08f).coerceIn(0f, 1f)
    val safeBorderAlpha = borderAlpha.coerceIn(0f, 1f)
    return this
        .clip(RoundedCornerShape(cornerRadius))
        .background(
            Brush.verticalGradient(
                listOf(
                    Color(0xFF162036).copy(alpha = safeAlpha),
                    Color(0xFF0A101D).copy(alpha = safeAlpha2)
                )
            )
        )
        .border(
            width = 1.dp,
            color = Color.White.copy(alpha = safeBorderAlpha),
            shape = RoundedCornerShape(cornerRadius)
        )
}
