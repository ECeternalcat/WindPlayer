package dev.windplayer.translate

/**
 * One subtitle line extracted from ASR or parsed from an existing subtitle file.
 *
 * Per AITranslate.md §8.3 (Time-Text Separation & ID Anchoring):
 * - [id] is the stable anchor used to map LLM output back to the timeline.
 * - [startTime] / [endTime] are pre-formatted SRT strings ("HH:mm:ss,SSS").
 * - [originalText] is the ASR-recognized or original-language text.
 * - [translatedText] is null until the LLM pipeline fills it in.
 *
 * The LLM never sees timestamps — only [id] + [originalText]. The merge engine
 * reassembles the SRT file using this data class as the single source of truth.
 */
data class SubtitleSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val translatedText: String? = null
) {
    /**
     * Formatted SRT start timecode: "HH:mm:ss,SSS" (comma per SRT spec).
     */
    val startTimeFormatted: String
        get() = formatSrtTime(startMs)

    /**
     * Formatted SRT end timecode.
     */
    val endTimeFormatted: String
        get() = formatSrtTime(endMs)

    /**
     * The text to display: translated if available, otherwise original.
     * Per §8.4: safe degradation — LLM gaps fall back to original text.
     */
    val displayText: String
        get() = translatedText ?: originalText

    companion object {
        /**
         * Format milliseconds as SRT timecode: "HH:mm:ss,SSS".
         * Note: SRT spec uses a comma before milliseconds (not a period).
         */
        fun formatSrtTime(ms: Long): String {
            // BUG-19: clamp negative values to 0 — Whisper may emit t0=0 but
            // a future parser edge case shouldn't produce "-00:00:00,-001".
            val safeMs = ms.coerceAtLeast(0L)
            val totalSec = safeMs / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            // BUG-19 (complete): use safeMs here too — previously the millisecond
            // field still read the raw `ms`, so a negative input produced a
            // legal-looking but invalid SRT timecode like "00:00:00,-500".
            val millis = safeMs % 1000
            return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
        }
    }
}

/**
 * Render one [SubtitleSegment] as a standard SRT block.
 */
fun SubtitleSegment.toSrtBlock(): String {
    return "$id\n$startTimeFormatted --> $endTimeFormatted\n$displayText\n"
}

/**
 * Render a list of [SubtitleSegment]s as a complete .srt file.
 */
fun List<SubtitleSegment>.toSrtFile(): String {
    return joinToString("\n", postfix = "\n") { it.toSrtBlock() }
}
