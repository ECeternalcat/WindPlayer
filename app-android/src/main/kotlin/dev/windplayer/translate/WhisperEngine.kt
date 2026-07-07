package dev.windplayer.translate

import android.util.Log
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin Android wrapper around the Whisper JNI AAR (`com.whispercpp.whisper`).
 *
 * Lifecycle:
 * 1. [loadModel] — open a GGML model file and create the native context.
 * 2. [transcribe] — feed 16kHz mono f32 audio and get back timed [SubtitleSegment]s.
 * 3. [release] — free the native context. Safe to call multiple times.
 *
 * Per AITranslate.md §3.2: the caller is responsible for providing 16kHz mono
 * f32 audio (extracted via mpv's audio filter). This class never touches FFmpeg.
 *
 * Per AITranslate.md §8.2: segment timestamps from whisper are in 10ms units
 * and are converted to milliseconds here. Anti-hallucination filtering strips
 * `[MUSIC]` / `(silence)` / empty segments.
 */
class WhisperEngine {

    private var context: WhisperContext? = null
    private val loaded = AtomicBoolean(false)

    /**
     * Load a Whisper model from a local file path.
     * Must be called before [transcribe].
     */
    fun loadModel(modelPath: String) {
        if (loaded.get()) {
            Log.w(TAG, "loadModel called but a model is already loaded — replacing")
            release()
        }
        val ctx = WhisperContext.createContextFromFile(modelPath)
        context = ctx
        loaded.set(true)
        Log.i(TAG, "Model loaded from $modelPath")
    }

    val isLoaded: Boolean get() = loaded.get() && context != null

    /**
     * Run Whisper inference on [audioData] (16kHz mono f32le).
     *
     * Returns a list of [SubtitleSegment]s with anti-hallucination filtering
     * applied. Each segment's [SubtitleSegment.startMs] / [endMs] are in
     * milliseconds (converted from whisper's 10ms tick units per §8.2).
     *
     * [translateMode] = true tells Whisper to translate to English instead of
     * transcribing the source language. We default to false — our LLM pipeline
     * does the translation, not Whisper.
     */
    suspend fun transcribe(
        audioData: FloatArray,
        translateMode: Boolean = false
    ): Result<List<SubtitleSegment>> = withContext(Dispatchers.Default) {
        val ctx = context ?: return@withContext Result.failure(
            IllegalStateException("Whisper model not loaded")
        )
        try {
            Log.i(TAG, "Transcribing ${audioData.size} samples (${audioData.size / 16000.0}s)")
            val raw = ctx.transcribeData(audioData, translateMode)
            val segments = parseTranscriptionOutput(raw)
            Log.i(TAG, "Parsed ${segments.size} segments (after anti-hallucination filter)")
            Result.success(segments)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            Result.failure(e)
        }
    }

    /**
     * Free the native Whisper context. Idempotent.
     */
    fun release() {
        if (!loaded.getAndSet(false)) return
        try {
            // BUG-32: dispatch the JNI release to IO thread instead of
            // blocking the caller (potentially main thread in onDestroy).
            // The native free is fast (<10ms) but some JNI implementations
            // assert thread context.
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { context?.release() }
        } catch (e: Exception) {
            Log.w(TAG, "release() failed: ${e.message}")
        }
        context = null
    }

    // ------------------------------------------------------------------
    // Output parsing (§8.2 anti-hallucination + §8.3 time-text separation)
    // ------------------------------------------------------------------

    /**
     * Parse whisper.cpp's formatted output string into structured segments.
     *
     * Whisper output format (from `WhisperContext.transcribeData`):
     * ```
     * 0  [00:00:00.000 --> 00:00:04.000]  Hello world.
     * 1  [00:00:04.000 --> 00:00:08.000]  This is a test.
     * ```
     *
     * Anti-hallucination filter (§8.2):
     * - Strip text inside `[…]` and `(…)` (e.g. `[MUSIC]`, `(silence)`).
     * - Discard segments whose text is empty after filtering.
     * - Discard duplicate/repeated segments (Whisper's "stuttering" hallucination).
     */
    private fun parseTranscriptionOutput(raw: String): List<SubtitleSegment> {
        val segments = mutableListOf<SubtitleSegment>()
        var lastText = ""

        // Regex: optional index, [start --> end], text
        // Whisper uses "." before milliseconds (not "," like SRT).
        val lineRegex = Regex(
            // optional segment index + whitespace
            """^\d*\s*""" +
            // [HH:mm:ss.mmm --> HH:mm:ss.mmm]
            """\[(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})\.(\d{3})\]\s*""" +
            // rest of line = text
            """(.*)$"""
        )

        for (line in raw.lineSequence()) {
            val match = lineRegex.find(line.trim()) ?: continue

            val (h1, m1, s1, ms1, h2, m2, s2, ms2, text) = match.destructured
            val startMs = toMs(h1, m1, s1, ms1)
            val endMs = toMs(h2, m2, s2, ms2)

            // Anti-hallucination: strip [TAG] and (tag) markers.
            val cleaned = text
                .replace(Regex("""\[[^\]]*\]"""), "")
                .replace(Regex("""\([^)]*\)"""), "")
                .trim()

            // Skip empty segments after cleaning (pure music/silence markers).
            if (cleaned.isEmpty()) {
                Log.d(TAG, "Skipping empty segment (hallucination filter): $text")
                continue
            }

            // Skip "stuttering" duplicates (Whisper repeats the same text).
            if (cleaned.equals(lastText, ignoreCase = true)) {
                Log.d(TAG, "Skipping duplicate segment: $cleaned")
                continue
            }

            segments.add(
                SubtitleSegment(
                    id = segments.size,
                    startMs = startMs,
                    endMs = endMs,
                    originalText = cleaned
                )
            )
            lastText = cleaned
        }

        return segments
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        return h.toLong() * 3600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + ms.toLong()
    }

    companion object {
        private const val TAG = "WhisperEngine"
    }
}
