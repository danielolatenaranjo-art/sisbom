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
    borderAlpha: Float = 0.25f,
    bgAlpha: Float = 0.85f,
    accentBorderColor: Color? = null
): Modifier {
    val topAlpha = (bgAlpha * 0.95f).coerceIn(0f, 1f)
    val midAlpha = bgAlpha.coerceIn(0f, 1f)
    val botAlpha = (bgAlpha * 1.08f).coerceIn(0f, 1f)

    val borderBrush = if (accentBorderColor != null) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.55f),
                accentBorderColor.copy(alpha = 0.65f),
                accentBorderColor.copy(alpha = 0.20f),
                Color.Transparent
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = (borderAlpha * 1.8f).coerceIn(0.20f, 0.65f)),
                Color.White.copy(alpha = borderAlpha.coerceIn(0.10f, 0.35f)),
                Color(0xFF38BDF8).copy(alpha = (borderAlpha * 0.6f).coerceIn(0.04f, 0.22f)),
                Color.White.copy(alpha = (borderAlpha * 0.2f).coerceIn(0.01f, 0.12f))
            )
        )
    }

    return this
        .clip(RoundedCornerShape(cornerRadius))
        .background(
            Brush.verticalGradient(
                listOf(
                    Color(0xFF1E293B).copy(alpha = topAlpha),
                    Color(0xFF0F172A).copy(alpha = midAlpha),
                    Color(0xFF030712).copy(alpha = botAlpha)
                )
            )
        )
        .border(
            width = 1.2.dp,
            brush = borderBrush,
            shape = RoundedCornerShape(cornerRadius)
        )
}

fun Modifier.carPlayGlassCard(
    cornerRadius: Dp = 20.dp,
    tintColor: Color = Color(0xFF0F172A),
    tintAlpha: Float = 0.84f,
    borderColor: Color = Color.White.copy(alpha = 0.25f)
): Modifier {
    return this
        .clip(RoundedCornerShape(cornerRadius))
        .background(
            Brush.verticalGradient(
                listOf(
                    tintColor.copy(alpha = (tintAlpha * 0.90f).coerceIn(0f, 1f)),
                    tintColor.copy(alpha = tintAlpha.coerceIn(0f, 1f)),
                    Color(0xFF020617).copy(alpha = (tintAlpha * 1.12f).coerceIn(0f, 1f))
                )
            )
        )
        .border(
            width = 1.2.dp,
            brush = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.48f),
                    borderColor,
                    borderColor.copy(alpha = 0.12f),
                    Color.Transparent
                )
            ),
            shape = RoundedCornerShape(cornerRadius)
        )
}

fun Modifier.carPlayGlassButton(
    cornerRadius: Dp = 16.dp,
    baseColor: Color = CarPlayColors.PrimaryBlue,
    isHighlighted: Boolean = true,
    alpha: Float = 0.90f
): Modifier {
    val topColor = if (isHighlighted) baseColor.copy(alpha = (alpha * 1.05f).coerceIn(0f, 1f)) else Color(0xFF1E293B).copy(alpha = alpha)
    val midColor = if (isHighlighted) baseColor.copy(alpha = alpha) else Color(0xFF0F172A).copy(alpha = alpha)
    val botColor = if (isHighlighted) Color(0xFF020617).copy(alpha = (alpha * 0.85f).coerceIn(0f, 1f)) else Color(0xFF030712).copy(alpha = alpha)

    return this
        .clip(RoundedCornerShape(cornerRadius))
        .background(
            Brush.verticalGradient(
                listOf(
                    topColor,
                    midColor,
                    botColor
                )
            )
        )
        .border(
            width = 1.3.dp,
            brush = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (isHighlighted) 0.65f else 0.40f),
                    baseColor.copy(alpha = if (isHighlighted) 0.80f else 0.45f),
                    baseColor.copy(alpha = 0.20f),
                    Color.Transparent
                )
            ),
            shape = RoundedCornerShape(cornerRadius)
        )
}

