package com.clipforge.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Compact, high-contrast palette adapted for the ClipForge Android workflow. */
internal object ClipForgePalette {
    val Background = Color(0xFF0B0D0B)
    val Surface = Color(0xFF151915)
    val SurfaceRaised = Color(0xFF20271E)
    val Accent = Color(0xFFD8F36A)
    val AccentSoft = Color(0xFFB9C98F)
    val Text = Color(0xFFF0F2E8)
    val Muted = Color(0xFFA6AE9E)
    val Border = Color(0xFF30382C)
    val Danger = Color(0xFFF08B84)
}

@Composable
internal fun ClipForgeTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = ClipForgePalette.Accent,
        onPrimary = ClipForgePalette.Background,
        secondary = ClipForgePalette.AccentSoft,
        onSecondary = ClipForgePalette.Background,
        tertiary = ClipForgePalette.AccentSoft,
        background = ClipForgePalette.Background,
        onBackground = ClipForgePalette.Text,
        surface = ClipForgePalette.Surface,
        onSurface = ClipForgePalette.Text,
        surfaceVariant = ClipForgePalette.SurfaceRaised,
        onSurfaceVariant = ClipForgePalette.Muted,
        outline = ClipForgePalette.Border,
        error = ClipForgePalette.Danger
    )
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
