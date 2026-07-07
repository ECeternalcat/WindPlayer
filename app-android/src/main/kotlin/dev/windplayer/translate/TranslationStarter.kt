package dev.windplayer.translate

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Compose-friendly trigger for the translation pipeline.
 *
 * Screens call [showChoice]; MobileApp observes [pendingRequest] and renders
 * the Compose choice sheet. This avoids native AlertDialog and keeps all UI
 * in Compose / WindColors design system.
 */
object TranslationStarter {

    data class Params(
        val videoTitle: String,
        val sourceUrl: String,
        val duration: Double
    )

    private val _pendingRequest = MutableStateFlow<Params?>(null)
    val pendingRequest: StateFlow<Params?> = _pendingRequest.asStateFlow()

    /**
     * Request the choice sheet to appear. Returns false (with Toast) if a
     * task is already running.
     */
    fun showChoice(
        context: Context,
        videoTitle: String,
        sourceUrl: String,
        duration: Double
    ): Boolean {
        if (TranslateService.isRunning) {
            Toast.makeText(context, "A task is already running", Toast.LENGTH_SHORT).show()
            return false
        }
        _pendingRequest.value = Params(videoTitle, sourceUrl, duration)
        return true
    }

    /** Called by the sheet when the user dismisses it. */
    fun consume() {
        _pendingRequest.value = null
    }
}
