package com.sisbom.sisbomtel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object TelColors {
    val BackgroundDark = Color(0xFF020617)
    val SurfaceDark = Color(0xFF0F172A)
    val CardBg = Color(0xFF1E293B)
    val CardBorder = Color(0xFF334155)

    val PrimaryRed = Color(0xFFDC2626)
    val PrimaryRedDark = Color(0xFF991B1B)
    val PrimaryRedGlow = Color(0xFFEF4444)

    val AccentCyan = Color(0xFF06B6D4)
    val AccentGreen = Color(0xFF22C55E)
    val AccentAmber = Color(0xFFF59E0B)
    val AccentBlue = Color(0xFF3B82F6)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

fun Modifier.telCard(
    cornerRadius: Dp = 16.dp,
    borderColor: Color = TelColors.CardBorder,
    bgAlpha: Float = 0.85f
): Modifier = this
    .background(TelColors.CardBg.copy(alpha = bgAlpha), RoundedCornerShape(cornerRadius))
    .border(1.dp, borderColor, RoundedCornerShape(cornerRadius))
