package dev.windplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Subtle press-scale feedback for clickable surfaces.
 *
 * Scales down to [scaleDown] (default 0.97) while pressed, then springs back.
 * Use on icon buttons, list rows, and other clickable elements that lack the
 * native ripple (desktop) or need extra tactility (Android).
 *
 * Pass the same [interactionSource] you use on the clickable modifier so the
 * pressed state stays in sync:
 *
 * ```kotlin
 * val source = remember { MutableInteractionSource() }
 * IconButton(
 *     onClick = { ... },
 *     interactionSource = source,
 *     modifier = Modifier.pressScale(source)
 * ) { ... }
 * ```
 *
 * If you don't have a source to share, omit it — a private one is created.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource? = null,
    scaleDown: Float = 0.97f
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = WindMotion.SpecSpring(),
        label = "pressScale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
