package dev.windplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

@Composable
expect fun iconPainter(name: String): Painter

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
}
