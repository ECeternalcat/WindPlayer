package dev.windplayer.translate

/**
 * Reassembles translated chunks back into a complete subtitle timeline.
 *
 * Per AITranslate.md §8.4 (Force-Align Merge Strategy):
 * 1. Iterate the local timeline (never trust LLM ordering).
 * 2. Look up each segment by [id] in the LLM response.
 * 3. If a translation is missing (truncation / format corruption),
 *    fall back to the [originalText] — safe degradation.
 * 4. Assemble the SRT file.
 *
 * This guarantees the timeline skeleton is unbreakable regardless of how
 * badly the LLM behaves.
 */
object SubtitleMergeEngine {

    /**
     * Result of merging LLM translations back into the timeline.
     */
    data class MergeResult(
        val segments: List<SubtitleSegment>,
        val untranslatedCount: Int,
        val detectedLanguage: String?
    )

    /**
     * Merge [originalSegments] with [translations] (id → translated text).
     *
     * @param originalSegments the complete ASR timeline (source of truth)
     * @param translations map of segment id → translated text
     * @param detectedLanguage source language detected by the LLM (first chunk)
     */
    fun merge(
        originalSegments: List<SubtitleSegment>,
        translations: Map<Int, String>,
        detectedLanguage: String? = null
    ): MergeResult {
        var untranslated = 0
        val merged = originalSegments.map { seg ->
            val translated = translations[seg.id]
            if (translated.isNullOrBlank()) {
                untranslated++
                seg // translatedText stays null → displayText falls back to original
            } else {
                seg.copy(translatedText = translated)
            }
        }

        return MergeResult(
            segments = merged,
            untranslatedCount = untranslated,
            detectedLanguage = detectedLanguage
        )
    }

    /**
     * Write the merged segments to a .srt file string.
     * Re-numbers IDs sequentially (1-based per SRT convention).
     */
    fun toSrtContent(segments: List<SubtitleSegment>): String {
        val sb = StringBuilder()
        segments.forEachIndexed { index, seg ->
            sb.append(index + 1).append("\n")
            sb.append(seg.startTimeFormatted).append(" --> ").append(seg.endTimeFormatted).append("\n")
            sb.append(seg.displayText).append("\n")
            sb.append("\n")
        }
        // BUG-37: trim trailing blank lines — some strict SRT parsers warn
        // on the extra empty block at the end.
        return sb.toString().trimEnd('\n') + "\n"
    }
}
