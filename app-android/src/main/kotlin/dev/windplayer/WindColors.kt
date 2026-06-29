package dev.windplayer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * WindPlayer mobile design tokens — Mastercard-inspired palette from
 * `Documents/DESIGN.md`. Mirrors the desktop `ui-compose` `WindColors`.
 *
 * Each colour is `mutableStateOf`, so [applyDark] recomposes every screen that
 * reads it without any `CompositionLocal` plumbing.
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

    var Danger by mutableStateOf(Color(0xFFCF4500))

    /** Ghost-watermark tone — barely-visible cream-on-cream (DESIGN.md §4). */
    var GhostWatermark by mutableStateOf(Color(0xFFE8E2DA))

    /** When non-null, overrides accent colours. */
    var accentOverride by mutableStateOf<Color?>(null)
        private set

    private var isDarkMode = false

    fun applyAccent(color: Color?) {
        accentOverride = color
        applyAccentToColors()
    }

    private fun applyAccentToColors() {
        if (accentOverride != null) {
            SignalOrange = accentOverride!!
            LightSignalOrange = accentOverride!!.copy(alpha = 0.78f)
            ClayBrown = accentOverride!!.copy(alpha = 0.55f)
            Danger = accentOverride!!
        } else {
            SignalOrange = if (isDarkMode) Color(0xFFE8511A) else Color(0xFFCF4500)
            LightSignalOrange = Color(0xFFF37338)
            ClayBrown = if (isDarkMode) Color(0xFFC76A38) else Color(0xFF9A3A0A)
            Danger = if (isDarkMode) Color(0xFFE8511A) else Color(0xFFCF4500)
        }
    }

    /** Fixed dark "media" surface — player chrome over video never flips. */
    val MediaInk = Color(0xFF141413)
    val MediaSurface = Color(0xFF2A2826)
    val MediaCream = Color(0xFFF3F0EE)
    val MediaMuted = Color(0xFFD1CDC7)
    val MediaAccent = Color(0xFFF37338)

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
            Danger = Color(0xFFE8511A)
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
            Danger = Color(0xFFCF4500)
            GhostWatermark = Color(0xFFE8E2DA)
        }
        applyAccentToColors()
    }
}

object WindRadius {
    val Chip = RoundedCornerShape(6.dp)
    val Button = RoundedCornerShape(20.dp)
    val Consent = RoundedCornerShape(24.dp)
    val Stadium = RoundedCornerShape(40.dp)
    val Pill = RoundedCornerShape(99.dp)
    val FullCircle = RoundedCornerShape(50)
}

