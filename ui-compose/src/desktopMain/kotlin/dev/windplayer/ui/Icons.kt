package dev.windplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter

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
 * Load a Phosphor SVG icon from `resources/icons/<name>.svg`. Cached per [name]
 * via [remember].
 */
@Composable
fun iconPainter(name: String): Painter {
    val density = LocalDensity.current
    return remember(name) {
        val stream = Thread.currentThread().contextClassLoader
            .getResourceAsStream("icons/$name.svg")
            ?: throw IllegalArgumentException("Icon not found: $name")
        loadSvgPainter(stream, density)
    }
}
