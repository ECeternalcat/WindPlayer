package dev.windplayer.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WindPlayer design system — adapted from the Mastercard-inspired spec in
 * `Documents/DESIGN.md`.
 *
 * The canvas is a warm putty cream (never pure white), shapes favour oversized
 * radii (pill / stadium / circle), and the only aggressive accent is Signal
 * Orange, reserved for destructive / legal-style actions. Headlines use Ink
 * Black at weight 500 with tight negative tracking; body copy at weight 400.
 *
 * Every colour is a `mutableStateOf` so flipping [applyDark] recomposes every
 * screen that reads it — no `CompositionLocal` plumbing required.
 */
object WindColors {
    var CanvasCream by mutableStateOf(Color(0xFFF3F0EE))
    var LiftedCream by mutableStateOf(Color(0xFFFCFBFA))
    var White by mutableStateOf(Color(0xFFFFFFFF))

    var Ink by mutableStateOf(Color(0xFF141413))
    var Charcoal by mutableStateOf(Color(0xFF262627))
    var Slate by mutableStateOf(Color(0xFF696969))
    var Granite by mutableStateOf(Color(0xFF555555))
    var DustTaupe by mutableStateOf(Color(0xFFD1CDC7))
    var Hairline by mutableStateOf(Color(0xFFE2DDD5))

    var SignalOrange by mutableStateOf(Color(0xFFCF4500))
    var LightSignalOrange by mutableStateOf(Color(0xFFF37338))
    var ClayBrown by mutableStateOf(Color(0xFF9A3A0A))

    var LinkBlue by mutableStateOf(Color(0xFF3860BE))

    /** Ghost-watermark tone — barely-visible cream-on-cream (DESIGN.md §4). */
    var GhostWatermark by mutableStateOf(Color(0xFFE8E2DA))

    /** When non-null, overrides SignalOrange/LightSignalOrange/ClayBrown. */
    var accentOverride by mutableStateOf<Color?>(null)
        private set

    private var isDarkMode = false

    /**
     * Override the accent colour (Signal Orange → dynamic/system primary).
     * Pass `null` to restore the default WindPlayer orange.
     */
    fun applyAccent(color: Color?) {
        accentOverride = color
        applyAccentToColors()
    }

    private fun applyAccentToColors() {
        if (accentOverride != null) {
            SignalOrange = accentOverride!!
            LightSignalOrange = accentOverride!!.copy(alpha = 0.78f)
            ClayBrown = accentOverride!!.copy(alpha = 0.55f)
        } else {
            // Restore default orange based on current theme
            SignalOrange = if (isDarkMode) Color(0xFFE8511A) else Color(0xFFCF4500)
            LightSignalOrange = Color(0xFFF37338)
            ClayBrown = if (isDarkMode) Color(0xFFC76A38) else Color(0xFF9A3A0A)
        }
    }

    /**
     * Fixed dark "media" surface — player chrome sits over video and must stay
     * dark regardless of the app theme. These never flip.
     */
    val MediaInk = Color(0xFF141413)
    val MediaSurface = Color(0xFF2A2826)
    val MediaCream = Color(0xFFF3F0EE)
    val MediaMuted = Color(0xFFD1CDC7)
    val MediaAccent = Color(0xFFF37338)

    /** Swap the whole palette. Idempotent — reassigning the same value is a no-op. */
    fun applyDark(dark: Boolean) {
        isDarkMode = dark
        if (dark) {
            CanvasCream = Color(0xFF141413)
            LiftedCream = Color(0xFF1F1D1C)
            White = Color(0xFF282624)
            Ink = Color(0xFFF3F0EE)
            Charcoal = Color(0xFFD1CDC7)
            Slate = Color(0xFFA8A29A)
            Granite = Color(0xFF8A857E)
            DustTaupe = Color(0xFF6E6A64)
            Hairline = Color(0xFF3A3735)
            SignalOrange = Color(0xFFE8511A)
            LightSignalOrange = Color(0xFFF37338)
            ClayBrown = Color(0xFFC76A38)
            LinkBlue = Color(0xFF7A93D8)
            GhostWatermark = Color(0xFF2A2826)
        } else {
            CanvasCream = Color(0xFFF3F0EE)
            LiftedCream = Color(0xFFFCFBFA)
            White = Color(0xFFFFFFFF)
            Ink = Color(0xFF141413)
            Charcoal = Color(0xFF262627)
            Slate = Color(0xFF696969)
            Granite = Color(0xFF555555)
            DustTaupe = Color(0xFFD1CDC7)
            Hairline = Color(0xFFE2DDD5)
            SignalOrange = Color(0xFFCF4500)
            LightSignalOrange = Color(0xFFF37338)
            ClayBrown = Color(0xFF9A3A0A)
            LinkBlue = Color(0xFF3860BE)
            GhostWatermark = Color(0xFFE8E2DA)
        }
        // Re-apply accent override if active (applyDark sets defaults above).
        applyAccentToColors()
    }
}

/**
 * Border-radius scale from DESIGN.md §5 — small (≤6), medium-large (20–40),
 * or full-pill (99+). The 8–16px middle ground is intentionally absent.
 */
object WindRadius {
    val Chip = RoundedCornerShape(6.dp)
    val Button = RoundedCornerShape(20.dp)
    val Consent = RoundedCornerShape(24.dp)
    val Stadium = RoundedCornerShape(40.dp)
    val Pill = RoundedCornerShape(99.dp)
    val FullCircle = RoundedCornerShape(50)
}

/**
 * Soft atmospheric shadows (low opacity, large spread) — never hard-edged.
 */
object WindElevation {
    val NavShadow = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.04f)
    val CardShadow = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f)
}

val WindTypographyRaw = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.72).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.48).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.4).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.32).sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.56.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.48.sp
    )
)

/**
 * Sofia Sans (Google Fonts, OFL) — DESIGN.md's recommended open-source stand-in
 * for the proprietary MarkForMC. Static 400/500/700 instances loaded from the
 * `resources/fonts/` classpath.
 */
val SofiaSansFamily = FontFamily(
    Font("fonts/SofiaSans-Regular.ttf", FontWeight.Normal),
    Font("fonts/SofiaSans-Medium.ttf", FontWeight.Medium),
    Font("fonts/SofiaSans-Bold.ttf", FontWeight.Bold)
)

/** Apply a FontFamily to every role of a Typography (keeps all other props). */
private fun Typography.withFamily(family: FontFamily): Typography {
    val m = TextStyle(fontFamily = family)
    return Typography(
        displayLarge = displayLarge.merge(m), displayMedium = displayMedium.merge(m), displaySmall = displaySmall.merge(m),
        headlineLarge = headlineLarge.merge(m), headlineMedium = headlineMedium.merge(m), headlineSmall = headlineSmall.merge(m),
        titleLarge = titleLarge.merge(m), titleMedium = titleMedium.merge(m), titleSmall = titleSmall.merge(m),
        bodyLarge = bodyLarge.merge(m), bodyMedium = bodyMedium.merge(m), bodySmall = bodySmall.merge(m),
        labelLarge = labelLarge.merge(m), labelMedium = labelMedium.merge(m), labelSmall = labelSmall.merge(m)
    )
}

val WindTypography = WindTypographyRaw.withFamily(SofiaSansFamily)

val WindShapes = Shapes(
    extraSmall = WindRadius.Chip,
    small = WindRadius.Button,
    medium = WindRadius.Consent,
    large = WindRadius.Stadium,
    extraLarge = WindRadius.Pill
)

/**
 * Material3 colour scheme derived directly from the palette above. Built from
 * fixed hex so it has no ordering dependency on [WindColors.applyDark].
 */
fun windColorScheme(isDark: Boolean): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = Color(0xFFF3F0EE),
        onPrimary = Color(0xFF141413),
        primaryContainer = Color(0xFFF3F0EE),
        onPrimaryContainer = Color(0xFF141413),
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
        primaryContainer = Color(0xFF141413),
        onPrimaryContainer = Color(0xFFF3F0EE),
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

/**
 * Auto color scheme — M3 baseline palette as desktop fallback.
 * Future: extract from Windows registry accent color (phase 2).
 * Keeps our cream background/surface, only swaps the primary/accent.
 */
fun windColorSchemeAuto(isDark: Boolean): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = Color(0xFFEADDFF),
        secondary = Color(0xFFCCC2DC),
        onSecondary = Color(0xFF332D41),
        tertiary = Color(0xFFEFB8C8),
        onTertiary = Color(0xFF492532),
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
        primary = Color(0xFF6750A4),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF7D5260),
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

@Composable
fun WindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = windColorScheme(false),
        typography = WindTypography,
        shapes = WindShapes,
        content = content
    )
}
