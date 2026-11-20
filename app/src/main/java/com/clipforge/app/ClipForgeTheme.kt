package com.clipforge.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Consistent dark creator-studio design system for the Android editor. */
internal object ClipForgePalette {
    val Background = Color(0xFF090D15)
    val Surface = Color(0xFF141D2B)
    val SurfaceRaised = Color(0xFF202D40)
    val Accent = Color(0xFF67E0D2)
    val AccentSoft = Color(0xFFA3B7CF)
    val Text = Color(0xFFF5F8FC)
    val Muted = Color(0xFF9BAFC4)
    val Border = Color(0xFF304054)
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
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp)
        ),
        content = content
    )
}
