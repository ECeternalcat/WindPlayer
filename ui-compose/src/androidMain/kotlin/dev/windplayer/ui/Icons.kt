package dev.windplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter

@Composable
actual fun iconPainter(name: String): Painter {
    return remember(name) { ColorPainter(Color.Transparent) }
}
