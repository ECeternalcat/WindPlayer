package dev.windplayer

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Material3 colour scheme mirroring the desktop `windColorScheme`. */
fun androidColorScheme(isDark: Boolean): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = Color(0xFFF3F0EE),
        onPrimary = Color(0xFF141413),
        secondary = Color(0xFFC76A38),
        onSecondary = Color(0xFF141413),
        tertiary = Color(0xFFE8511A),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFF141413),
        onBackground = Color(0xFFF3F0EE),
        surface = Color(0xFF1F1D1C),
        onSurface = Color(0xFFF3F0EE),
        surfaceVariant = Color(0xFF282624),
        onSurfaceVariant = Color(0xFFA8A29A),
        surfaceContainer = Color(0xFF282624),
        surfaceContainerHigh = Color(0xFF312E2C),
        outline = Color(0xFF3A3735),
        outlineVariant = Color(0xFF6E6A64),
        error = Color(0xFFE8511A),
        onError = Color(0xFFFFFFFF)
    )
} else {
    lightColorScheme(
        primary = Color(0xFF141413),
        onPrimary = Color(0xFFF3F0EE),
        secondary = Color(0xFF9A3A0A),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFFCF4500),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFF3F0EE),
        onBackground = Color(0xFF141413),
        surface = Color(0xFFFCFBFA),
        onSurface = Color(0xFF141413),
        surfaceVariant = Color(0xFFFFFFFF),
        onSurfaceVariant = Color(0xFF696969),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFFFFFFF),
        outline = Color(0xFFE2DDD5),
        outlineVariant = Color(0xFFD1CDC7),
        error = Color(0xFFCF4500),
        onError = Color(0xFFFFFFFF)
    )
}
