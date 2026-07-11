package dev.windplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import dev.windplayer.ui.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToSvgPainter

/**
 * Phosphor icon names used by the desktop UI. Each name corresponds to a
 * `composeResources/drawable/<name>.svg` file shipped in this source set.
 *
 * Kept in `desktopMain` because the Android UI uses Material's built-in icons
 * instead. There is no `expect/actual` layer.
 */
object PhosphorIcons {
    // Values mirror the SVG filenames in composeResources/drawable/ (without
    // extension). Hyphens were replaced with underscores during the Compose
    // resources migration because resource keys must be valid identifier-style.
    const val ARROW_LEFT = "arrow_left"
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val LIST = "list"
    const val SPEAKER_HIGH = "speaker_high"
    const val SPEAKER_SLASH = "speaker_slash"
    const val CORNERS_OUT = "corners_out"
    const val CORNERS_IN = "corners_in"
    const val LIGHTNING = "lightning"
    const val GAUGE = "gauge"
    const val FOLDER = "folder"
    const val VIDEO = "video"
    const val SUBTITLES = "subtitles"
    const val FILE = "file"
    const val PLUS = "plus"
    const val MONITOR = "monitor"
    const val X = "x"
    const val CHECK = "check"
    const val GEAR = "gear"
    const val CLOCK = "clock"
    const val QUEUE = "queue"
    const val STAR = "star"
    const val MAGNIFYING_GLASS = "magnifying_glass"
    const val DOTS_THREE = "dots_three"
    const val CARET_DOWN = "caret_down"
    const val WARNING = "warning"
    const val ARROW_RIGHT = "arrow_right"
}

/**
 * Sentinel returned while an icon is still loading from the resource bundle.
 * On desktop, SVG files are <2 KB each and read from the local JAR, so the
 * load typically completes within a single frame — the blank flash is
 * imperceptible. This is the trade-off for using the non-deprecated
 * `decodeToSvgPainter` + `Res.readBytes` path instead of the deprecated
 * `painterResource(String)` (the generated `Res.drawable.xxx` accessors are
 * not visible under the new AGP KMP plugin — see build.gradle.kts note).
 */
private val BlankPainter: Painter = ColorPainter(Color.Transparent)

/**
 * Load a Phosphor SVG icon by name via the Compose resources library.
 *
 * Uses `Res.readBytes("drawable/$name.svg")` + `decodeToSvgPainter` — the
 * CMP 1.11 non-deprecated path. The read is suspend (the resource library
 * requires it for multiplatform parity), so we use [produceState] to bridge
 * into the Compose snapshot model. Desktop resource reads are sub-millisecond
 * so no loading indicator is shown.
 */
@Composable
fun iconPainter(name: String): Painter {
    val density = LocalDensity.current
    val painterState = produceState<Painter>(BlankPainter, name, density) {
        value = withContext(Dispatchers.IO) {
            try {
                val bytes = Res.readBytes("drawable/$name.svg")
                bytes.decodeToSvgPainter(density)
            } catch (e: Exception) {
                error("Icon not found: '$name' (drawable/$name.svg): ${e.message}")
            }
        }
    }
    return painterState.value
}





