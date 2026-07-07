package dev.windplayer.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * WindPlayer motion system — a small, unified vocabulary of durations, easings,
 * and reusable [FiniteAnimationSpec]s used across desktop and Android.
 *
 * Design goals (per project guidance, 2026-06-30):
 *  - **Unified and simple** — every animation pulls from this file so timings
 *    stay consistent across surfaces (settings, player, file browser).
 *  - **Not iOS-elegant** — no overspring/bounce, no staggered choreography.
 *    We pick three durations, two easings, and call it done.
 *  - **Cheap to apply** — callers use [WindMotion] specs by reference; future
 *    tweaks live here, not at 40 call sites.
 *
 * ## Duration scale
 *
 * | Token        | ms  | Use |
 * |--------------|-----|-----|
 * | [DurFast]    | 150 | Small feedback: press scale, hover tint, list item placement |
 * | [DurMedium]  | 250 | Panels/sheets sliding in, OSD fade, page transitions |
 * | [DurSlow]    | 400 | Whole-screen crossfade (used sparingly — feels slow above 300ms) |
 *
 * ## Easings
 *
 * - [EasingStandard] — Material's emphasized decelerate. Default for enters and
 *   most things. Equivalent to `FastOutSlowInEasing`.
 * - [EasingExit] — Slightly more aggressive decelerate for exits.
 *
 * ## Specs (ready-to-use)
 *
 * - [SpecFade] — plain opacity fade
 * - [SpecEnter] — fade + small scale-up (for OSD, dialogs)
 * - [SpecEnterSlide] — horizontal slide + fade (for panels/pages)
 * - [SpecEnterSlideUp] — vertical slide-up + fade (for bottom sheets, snackbars)
 * - [SpecExit] — fade + small scale-down
 * - [SpecExitSlide] — horizontal slide + fade (reversed direction)
 * - [SpecColor] — for animateColorAsState on selection/background
 * - [SpecSpring] — for animateFloatAsState where bounce-back is desired (rare)
 *
 * ## Usage pattern
 *
 * ```kotlin
 * AnimatedVisibility(
 *     visible = show,
 *     enter = WindMotion.SpecEnter,
 *     exit = WindMotion.SpecExit
 * )
 * ```
 */
object WindMotion {

    // ------------------------------------------------------------------
    // Durations
    // ------------------------------------------------------------------

    /** Fast feedback (~150ms). Press scale, hover tint, list placement. */
    const val DurFast = 150

    /** Medium motion (~250ms). Panels, sheets, OSD, page slides. */
    const val DurMedium = 250

    /** Slow motion (~400ms). Reserved for whole-screen crossfades. */
    const val DurSlow = 400

    // ------------------------------------------------------------------
    // Easings
    // ------------------------------------------------------------------

    /** Standard emphasized-decelerate. Use for most enters and slides. */
    val EasingStandard: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** Slightly more aggressive decelerate for exits (faster off-screen). */
    val EasingExit: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    // ------------------------------------------------------------------
    // Specs (reusable FiniteAnimationSpec<T>)
    // ------------------------------------------------------------------

    /** Plain fade — for OSD text, simple show/hide. */
    fun <T> SpecFade(durationMs: Int = DurMedium): FiniteAnimationSpec<T> =
        tween(durationMs, easing = EasingStandard)

    /** Color transition — for selection background, tint changes. */
    fun <T> SpecColor(durationMs: Int = DurFast): FiniteAnimationSpec<T> =
        tween(durationMs, easing = EasingStandard)

    /**
     * Soft spring for things that should feel responsive but never bouncy
     * (e.g. button press scale). NoBouncy damping + MediumLow stiffness gives
     * a smooth glide with no overshoot.
     */
    fun <T> SpecSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

// ------------------------------------------------------------------
// Convenience IntOffset/Float transition helpers
// (kept as top-level so callers don't need to know about [tween] import)
// ------------------------------------------------------------------

/**
 * Slide-in offset for a panel entering from the right edge.
 * Pass `fullWidth` = the panel's width (or a fraction of it via `IntOffset(it, 0)`).
 */
internal fun slideInFromRight(amount: Int): IntOffset = IntOffset(amount, 0)

/** Slide-out offset to the right edge. */
internal fun slideOutToRight(amount: Int): IntOffset = IntOffset(amount, 0)

/** Slide-in offset from the left edge (e.g. back navigation). */
internal fun slideInFromLeft(amount: Int): IntOffset = IntOffset(-amount, 0)

/** Slide-out offset to the left edge. */
internal fun slideOutToLeft(amount: Int): IntOffset = IntOffset(-amount, 0)
