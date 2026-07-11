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
     * Uses [SubtitleSegment.displayText] (translated text if available,
     * otherwise original).
     */
    fun toSrtContent(segments: List<SubtitleSegment>): String {
        return renderSrt(segments) { it.displayText }
    }

    /**
     * Write segments using **only** [SubtitleSegment.originalText],
     * ignoring any translation. Used for the source-language SRT file
     * that accompanies a translated SRT in dual-subtitle mode.
     */
    fun toSourceSrtContent(segments: List<SubtitleSegment>): String {
        return renderSrt(segments) { it.originalText }
    }

    /**
     * Write segments as a **two-line stacked** SRT: translated text on the
     * first line, original text on the second line. If a segment has no
     * translation, only the original line is emitted.
     *
     * This produces a single SRT file that mpv renders as two lines stacked
     * at the bottom of the screen — the "dual stacked" display mode from
     * [Documents/Dual-Subtitle-Plan.md] §3.
     */
    fun toDualSrtContent(segments: List<SubtitleSegment>): String {
        return renderSrt(segments) { seg ->
            buildString {
                val translated = seg.translatedText
                if (!translated.isNullOrBlank()) {
                    append(translated)
                    append("\n")
                }
                append(seg.originalText)
            }
        }
    }

    /**
     * Internal: render any segment-to-text mapping as a valid SRT file.
     */
    private inline fun renderSrt(
        segments: List<SubtitleSegment>,
        textFn: (SubtitleSegment) -> String
    ): String {
        val sb = StringBuilder()
        segments.forEachIndexed { index, seg ->
            sb.append(index + 1).append("\n")
            sb.append(seg.startTimeFormatted).append(" --> ").append(seg.endTimeFormatted).append("\n")
            sb.append(textFn(seg)).append("\n")
            sb.append("\n")
        }
        return sb.toString().trimEnd('\n') + "\n"
    }
}
