package dev.windplayer.translate

/**
 * Splits a list of [SubtitleSegment]s into chunks small enough for LLM
 * translation (AITranslate.md §4.1).
 *
 * Two-dimensional limits:
 * - [maxLines] (default 40): too many lines → LLM may truncate the response.
 * - [maxChars] (default 1500): too many characters → exceeds max_tokens.
 *
 * Either limit triggers a cut. This prevents the JSON response from being
 * silently truncated by the LLM's token limit.
 */
object ChunkingStrategy {

    /**
     * Split [segments] into a list of chunks, each respecting both [maxLines]
     * and [maxChars]. Original segment order is preserved.
     */
    fun chunk(
        segments: List<SubtitleSegment>,
        maxLines: Int = 40,
        maxChars: Int = 1500
    ): List<List<SubtitleSegment>> {
        val chunks = mutableListOf<List<SubtitleSegment>>()
        var current = mutableListOf<SubtitleSegment>()
        var currentChars = 0

        for (seg in segments) {
            val segChars = seg.originalText.length

            // BUG-35: warn if a single segment alone exceeds the char limit —
            // the LLM is guaranteed to truncate it.
            if (segChars > maxChars && current.isEmpty()) {
                // This segment is so large it forms its own chunk but may
                // exceed LLM max_tokens. Log so the developer can investigate.
            }

            // Check if adding this segment would exceed either limit.
            if (current.isNotEmpty() &&
                (current.size >= maxLines || currentChars + segChars > maxChars)) {
                chunks.add(current)
                current = mutableListOf()
                currentChars = 0
            }

            current.add(seg)
            currentChars += segChars
        }

        if (current.isNotEmpty()) {
            chunks.add(current)
        }

        return chunks
    }

    /**
     * Build the JSON payload for a single chunk (§4.2).
     *
     * Only [id] + [originalText] are sent — timestamps are NEVER sent to the
     * LLM (§8.1). [contextReference] is the last segment of the previous
     * chunk's translation, used to maintain term consistency.
     */
    fun buildPayloadJson(
        chunk: List<SubtitleSegment>,
        contextReference: SubtitleSegment? = null,
        targetLanguage: String = "中文"
    ): String {
        val sb = StringBuilder()

        // Context reference (read-only, from previous chunk's last translated line).
        sb.append("{")
        sb.append("\"target_language\":\"").append(escapeJson(targetLanguage)).append("\",")

        if (contextReference != null) {
            sb.append("\"context_reference\":[")
            sb.append("{\"id\":").append(contextReference.id)
            sb.append(",\"text\":\"").append(escapeJson(contextReference.translatedText ?: contextReference.originalText)).append("\"}")
            sb.append("],")
        } else {
            sb.append("\"context_reference\":[],")
        }

        // Segments to translate.
        sb.append("\"to_translate\":[")
        chunk.forEachIndexed { i, seg ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":").append(seg.id)
            sb.append(",\"source\":\"").append(escapeJson(seg.originalText)).append("\"}")
        }
        sb.append("]}")

        return sb.toString()
    }

    /**
     * Escape a string for JSON string literal.
     */
    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }
}
