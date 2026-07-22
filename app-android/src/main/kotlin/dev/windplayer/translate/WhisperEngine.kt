package dev.windplayer.translate

import android.util.Log
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal data class WhisperWindow(val startSample: Long, val endSample: Long)

internal fun planWhisperWindows(
    totalSamples: Long,
    chunkSeconds: Int = 120,
    overlapSeconds: Int = 10,
    sampleRate: Int = 16_000
): List<WhisperWindow> {
    require(chunkSeconds > overlapSeconds && overlapSeconds >= 0)
    if (totalSamples <= 0) return emptyList()
    val chunk = chunkSeconds.toLong() * sampleRate
    val step = chunk - overlapSeconds.toLong() * sampleRate
    return buildList {
        var start = 0L
        while (start < totalSamples) {
            add(WhisperWindow(start, minOf(start + chunk, totalSamples)))
            start += step
        }
    }
}

internal fun mergeWhisperSegments(
    chunks: List<Pair<Long, List<SubtitleSegment>>>,
    overlapSeconds: Int = 10
): List<SubtitleSegment> {
    val merged = mutableListOf<SubtitleSegment>()
    for ((offsetMs, localSegments) in chunks) {
        val ownershipStart = if (merged.isEmpty()) 0L else offsetMs + overlapSeconds * 500L
        for (local in localSegments) {
            val segment = local.copy(startMs = local.startMs + offsetMs, endMs = local.endMs + offsetMs)
            if (segment.endMs <= ownershipStart) continue
            val duplicateIndex = merged.indexOfLast { existing ->
                minOf(existing.endMs, segment.endMs) > maxOf(existing.startMs, segment.startMs) &&
                    whisperTextSimilarity(existing.originalText, segment.originalText) >= 0.85
            }
            if (duplicateIndex >= 0) {
                if (segment.originalText.length > merged[duplicateIndex].originalText.length) {
                    merged[duplicateIndex] = segment
                }
            } else {
                merged.add(segment)
            }
        }
    }
    return merged.sortedWith(compareBy<SubtitleSegment> { it.startMs }.thenBy { it.endMs })
        .mapIndexed { index, segment -> segment.copy(id = index) }
}

private fun whisperTextSimilarity(a: String, b: String): Double {
    val left = a.lowercase().replace(Regex("[\\p{Punct}\\s]+"), "")
    val right = b.lowercase().replace(Regex("[\\p{Punct}\\s]+"), "")
    if (left == right) return 1.0
    if (left.isEmpty() || right.isEmpty()) return 0.0
    var previous = IntArray(right.length + 1) { it }
    for (i in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = i + 1
        for (j in right.indices) current[j + 1] = minOf(
            current[j] + 1, previous[j + 1] + 1,
            previous[j] + if (left[i] == right[j]) 0 else 1
        )
        previous = current
    }
    return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
}

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
     * Whisper's English-translation mode is intentionally NOT exposed:
     * our LLM pipeline does the translation, and the underlying whisper native
     * call (fullTranscribe) doesn't take a translate flag — translation is
     * decided by the model + whisper.cpp build config.
     */
    suspend fun transcribe(
        audioData: FloatArray
    ): Result<List<SubtitleSegment>> = withContext(Dispatchers.Default) {
        val ctx = context ?: return@withContext Result.failure(
            IllegalStateException("Whisper model not loaded")
        )
        try {
            Log.i(TAG, "Transcribing ${audioData.size} samples (${audioData.size / 16000.0}s)")
            // The second boolean arg to `transcribeData` controls whether the
            // native side emits "[HH:mm:ss,mmm --> HH:mm:ss,mmm]:" timestamps.
            // Without it the output is bare text with no timing, and
            // [parseTranscriptionOutput] returns 0 segments (BUG-WHISPER-1).
            // The old code named this arg `translateMode` and defaulted it to
            // false, which silently disabled timestamps — so transcription
            // always produced 0 segments on real videos.
            val raw = ctx.transcribeData(audioData, /* printTimestamp = */ true)
            // Log the raw output so future format drift in whisper.cpp is
            // diagnosable from logcat without a new build.
            Log.i(TAG, "Raw whisper output (${raw.length} chars):")
            raw.lineSequence().take(5).forEach { Log.i(TAG, "  | $it") }
            if (raw.length > 500) Log.i(TAG, "  ... (${raw.lineSequence().count() - 5} more lines)")
            val segments = parseTranscriptionOutput(raw)
            Log.i(TAG, "Parsed ${segments.size} segments (after anti-hallucination filter)")
            Result.success(segments)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            Result.failure(e)
        }
    }

    suspend fun transcribeChunked(
        audio: ExtractedAudio,
        chunkSeconds: Int = 120,
        overlapSeconds: Int = 10,
        onProgress: (Float) -> Unit = {}
    ): Result<List<SubtitleSegment>> = withContext(Dispatchers.Default) {
        require(chunkSeconds > overlapSeconds && overlapSeconds >= 0)
        val windows = planWhisperWindows(audio.totalSamples, chunkSeconds, overlapSeconds)
        val chunks = mutableListOf<Pair<Long, List<SubtitleSegment>>>()
        try {
            for ((index, window) in windows.withIndex()) {
                currentCoroutineContext().ensureActive()
                val local = transcribe(audio.readChunk(window.startSample, (window.endSample - window.startSample).toInt())).getOrThrow()
                chunks += window.startSample * 1000L / 16_000L to local
                onProgress((index + 1).toFloat() / windows.size)
            }
            Result.success(mergeWhisperSegments(chunks, overlapSeconds))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Chunked transcription failed", e)
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
     * Whisper output format (verified by decompiling whisper-android.aar's
     * `WhisperContext.transcribeData`):
     * ```
     * [00:00:00,000 --> 00:00:04,000]:Hello world.
     * [00:00:04,000 --> 00:00:08,000]:This is a test.
     * ```
     *
     * Key facts the previous parser got wrong (BUG-WHISPER-2):
     *  - whisper.cpp's `toTimestamp` uses `,` (comma) as the millisecond
     *    separator by default, not `.`. The old regex required `\.` and so
     *    matched zero lines.
     *  - There is no segment index prefix (the old regex allowed it but it's
     *    never present).
     *  - The closing `]` is immediately followed by `:` then the text — no
     *    space. The old regex's `\]\s*` left the `:` as a stale prefix on
     *    every caption.
     *
     * Anti-hallucination filter (§8.2):
     * - Strip text inside `[…]` and `(…)` (e.g. `[MUSIC]`, `(silence)`).
     * - Discard segments whose text is empty after filtering.
     * - Discard duplicate/repeated segments (Whisper's "stuttering" hallucination).
     */
    private fun parseTranscriptionOutput(raw: String): List<SubtitleSegment> {
        val segments = mutableListOf<SubtitleSegment>()
        var lastText = ""

        // Regex: [start --> end]:text
        // - Timestamp separator may be `.` or `,` (whisper.cpp build-dependent)
        // - `:` after `]` is the whisper.cpp convention; tolerate its absence
        //   so the regex also matches older `[00:00:00.000 --> ...]  text` form
        val lineRegex = Regex(
            // [HH:mm:ss[.,]mmm --> HH:mm:ss[.,]mmm]
            """\[(\d{2}):(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[.,](\d{3})\]""" +
            // optional `:` separator (whisper.cpp emits it) + any whitespace
            """:?\s*""" +
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

        // Post-process segment timings to fix two common Whisper issues:
        // 1. VAD detects speech slightly before actual onset → subtitles appear
        //    a few frames too early.
        // 2. Segments spanning silence (endMs extends past speech into the gap
        //    before the next segment) → text from later appears to overlap with
        //    the tail of earlier speech.
        return fixSegmentTimings(segments)
    }

    /**
     * Heuristic timing post-processing for Whisper segments.
     *
     * Two fixes:
     *
     * **A. Start-time offset** (`VAD_OFFSET_MS`):
     * Whisper's VAD triggers a few frames before actual speech onset.
     * Shifting [SubtitleSegment.startMs] forward by 150 ms aligns subtitles
     * closer to the first audible syllable. The effect is subtle (≈3-4 frames
     * at 24 fps) but noticeable to users who watch lips/scene cuts closely.
     *
     * **B. Silence-spanning trim**:
     * Whisper often extends a segment's `endMs` across intervening silence to
     * the start of the next segment (instead of clamping it to where speech
     * actually stops). The result: a 5-second speech followed by 10 seconds
     * of silence gets a single segment `[00:00 → 00:15]` — text "floats" past
     * the speaker's actual last word.
     *
     * Fix: estimate the plausible speech duration from text length (CJK ~3.5
     * chars/sec, Latin ~2.5 words/sec), and if `actualDuration > estimated × 3`,
     * clamp `endMs` to `startMs + estimated + buffer`.
     *
     * The ×3 threshold is deliberately conservative — only segments that are
     * grossly too long are touched. Normal segments with accurate Whisper
     * timestamps pass through unchanged.
     */
    private fun fixSegmentTimings(segments: List<SubtitleSegment>): List<SubtitleSegment> {
        if (segments.isEmpty()) return segments

        val vadOffsetMs = 150L        // compensate VAD early-detection
        val minDurationMs = 1500L     // minimum caption display time
        val cjkCharsPerSec = 3.5      // conservative CJK reading speed
        val latinWordsPerSec = 2.5    // conservative English reading speed
        val trimBufferMs = 1000L      // extra slack when clamping endMs

        val fixed = segments.map { seg ->
            val newStart = seg.startMs + vadOffsetMs

            // Estimate plausible speech duration from text content.
            val text = seg.originalText
            val cjkCount = text.count { it.code in 0x4E00..0x9FFF }
            val latinWordCount = text.split(Regex("[\\s\\p{Punct}]+"))
                .count { it.isNotEmpty() && it.any { c -> c.code < 0x4E00 } }
            val estimatedMs = maxOf(
                minDurationMs,
                (cjkCount / cjkCharsPerSec * 1000).toLong(),
                (latinWordCount / latinWordsPerSec * 1000).toLong()
            )

            // Only trim if actual duration is grossly larger than estimate.
            val actualDuration = seg.endMs - seg.startMs
            val newEnd = if (actualDuration > estimatedMs * 3) {
                newStart + estimatedMs + trimBufferMs
            } else {
                seg.endMs
            }

            seg.copy(startMs = newStart, endMs = maxOf(newEnd, newStart + minDurationMs))
        }.toMutableList()

        // Ensure no overlap: each segment starts at least 50ms after previous ends.
        for (i in 1 until fixed.size) {
            if (fixed[i].startMs < fixed[i - 1].endMs + 50) {
                fixed[i] = fixed[i].copy(startMs = fixed[i - 1].endMs + 50)
            }
        }

        return fixed
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        return h.toLong() * 3600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + ms.toLong()
    }

    companion object {
        private const val TAG = "WhisperEngine"
    }
}
