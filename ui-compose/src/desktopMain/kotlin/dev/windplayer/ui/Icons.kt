package dev.windplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

/**
 * Phosphor icon names used by the desktop UI. Each name corresponds to a
 * `resources/icons/<name>.svg` file shipped in this source set.
 *
 * Kept in `desktopMain` because the Android UI uses Material's built-in icons
 * instead. There is no `expect/actual` layer.
 */
object PhosphorIcons {
    const val ARROW_LEFT = "arrow-left"
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val LIST = "list"
    const val SPEAKER_HIGH = "speaker-high"
    const val SPEAKER_SLASH = "speaker-slash"
    const val CORNERS_OUT = "corners-out"
    const val CORNERS_IN = "corners-in"
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
    const val MAGNIFYING_GLASS = "magnifying-glass"
    const val DOTS_THREE = "dots-three"
    const val CARET_DOWN = "caret-down"
    const val WARNING = "warning"
    const val ARROW_RIGHT = "arrow-right"
}

/**
 * Load a Phosphor SVG icon from `resources/icons/<name>.svg`.
 *
 * Replaces the long-deprecated `loadSvgPainter` from older Compose Multiplatform
 * releases. CMP 1.11 also deprecates `painterResource(String)` in favour of the
 * full Compose resources library (`composeResources/files/...` + generated `Res`
 * accessors), but migrating the desktop-only icon set to that system requires
 * restructuring the resources directory and adding `compose.components.resources`
 * — a much larger change for a single warning. We suppress here until the
 * project adopts Compose resources wholesale (which should also subsume the
 * Sofia Sans font loading in WindTheme.kt).
 */
@Suppress("DEPRECATION")
@Composable
fun iconPainter(name: String): Painter = painterResource("icons/$name.svg")


