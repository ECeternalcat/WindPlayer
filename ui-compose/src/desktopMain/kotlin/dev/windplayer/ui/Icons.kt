package dev.windplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter

@Composable
actual fun iconPainter(name: String): Painter {
    val density = LocalDensity.current
    return remember(name) {
        val stream = Thread.currentThread().contextClassLoader
            .getResourceAsStream("icons/$name.svg")
            ?: throw IllegalArgumentException("Icon not found: $name")
        loadSvgPainter(stream, density)
    }
}
