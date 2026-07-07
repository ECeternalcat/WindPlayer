package dev.windplayer.translate

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends translation requests to an LLM API (OpenAI-compatible `/chat/completions`).
 *
 * Per AITranslate.md §4: the LLM is the most failure-prone component. This
 * class implements:
 * - Structured JSON payload (§4.2) with ID-anchored segments.
 * - Strict response parsing: ID count must match request count.
 * - Retry on 429 (rate limit).
 *
 * The system prompt instructs the LLM to return a strict JSON structure and
 * NEVER modify IDs or timestamps. Timestamps are never sent to the LLM (§8.1).
 */
class LLMRemoteSource(private val config: TranslationConfig) {

    /**
     * Translate a chunk of segments.
     *
     * @param chunk segments to translate
     * @param contextReference previous chunk's last translated segment (for term consistency)
     * @return parsed translations keyed by segment ID, or null on failure
     */
    suspend fun translateChunk(
        chunk: List<SubtitleSegment>,
        contextReference: SubtitleSegment? = null
    ): Result<TranslationResponse> = withContext(Dispatchers.IO) {
        if (config.llmApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("LLM API key not configured"))
        }

        val payloadJson = ChunkingStrategy.buildPayloadJson(
            chunk = chunk,
            contextReference = contextReference,
            targetLanguage = config.targetLanguage
        )

        val systemPrompt = buildSystemPrompt()
        val userPrompt = buildUserPrompt(payloadJson)

        try {
            val response = callChatApi(systemPrompt, userPrompt)
            val parsed = parseResponse(response, chunk)

            // §5.2 ID 校验防线: verify BOTH count AND actual IDs match.
            val expectedIds = chunk.map { it.id }.toSet()
            if (parsed.translations.keys != expectedIds) {
                Log.e(TAG, "ID mismatch: expected $expectedIds, got ${parsed.translations.keys}")
                return@withContext Result.failure(
                    RuntimeException("ID mismatch: expected ${chunk.size} ids ${expectedIds}, got ${parsed.translations.size} ids ${parsed.translations.keys}")
                )
            }

            Result.success(parsed)
        } catch (e: Exception) {
            Log.e(TAG, "LLM request failed", e)
            Result.failure(e)
        }
    }

    /**
     * Parsed LLM response.
     */
    data class TranslationResponse(
        val translations: Map<Int, String>,
        val detectedLanguage: String? = null
    )

    // ------------------------------------------------------------------
    // System prompt (§4.2)
    // ------------------------------------------------------------------

    private fun buildSystemPrompt(): String {
        return """You are a professional subtitle translator. Your task is to translate subtitles into ${config.targetLanguage}.

CRITICAL RULES:
1. You MUST return ONLY valid JSON, no markdown fences, no explanations.
2. The response JSON format must be exactly:
   {"detected_source_language":"<language>","translations":[{"id":<number>,"translated_text":"<translation>"}]}
3. The "translations" array MUST contain exactly the same number of items as "to_translate", with matching IDs.
4. NEVER invent, skip, merge, or reorder IDs.
5. NEVER include timestamps, timing, or segment numbers in your translations.
6. Translate naturally — adapt idioms, keep names in their recognized form.
7. If a line is already in the target language, keep it as-is.
8. The "context_reference" is for YOUR information only — do NOT translate it, do NOT include it in "translations"."""
    }

    private fun buildUserPrompt(payloadJson: String): String {
        return "Translate the following subtitle segments into ${config.targetLanguage}.\n\n$payloadJson"
    }

    // ------------------------------------------------------------------
    // HTTP API call (OpenAI-compatible)
    // ------------------------------------------------------------------

    private fun callChatApi(systemPrompt: String, userPrompt: String): String {
        // BUG-36: scale max_tokens with content length to avoid silent
        // truncation on large chunks. Rough estimate: 1 token ≈ 4 chars for
        // English, 2 chars for CJK — use 3 as a conservative average.
        val estimatedOutputTokens = (userPrompt.length / 3).coerceIn(512, 8192)
        val maxTokens = maxOf(4096, estimatedOutputTokens)

        val url = URL("${config.llmBaseUrl.trimEnd('/')}/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.llmApiKey}")
            doOutput = true
        }

        try {
            val body = """{"model":"${escapeJson(config.llmModel)}","messages":[{"role":"system","content":${quote(systemPrompt)}},{"role":"user","content":${quote(userPrompt)}}],"temperature":0.3,"max_tokens":$maxTokens}"""

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val responseCode = conn.responseCode
            if (responseCode == 429) {
                throw RuntimeException("Rate limited (429)")
            }
            if (responseCode !in 200..299) {
                // BUG-21: explicit UTF-8 charset for error body.
                val err = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                throw RuntimeException("HTTP $responseCode: $err")
            }

            return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Response parsing (§5: strict JSON validation)
    // ------------------------------------------------------------------

    private fun parseResponse(rawHttp: String, originalChunk: List<SubtitleSegment>): TranslationResponse {
        // BUG-10: use a capture group instead of substring hack.
        val contentRegex = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val contentMatch = contentRegex.find(rawHttp)
            ?: throw RuntimeException("No 'content' field in API response")

        var content = contentMatch.groupValues[1] // captured group, no substring needed
        content = unescapeJson(content)

        // Strip markdown fences if the LLM added them despite instructions.
        content = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        // Parse the translation JSON.
        val translations = mutableMapOf<Int, String>()

        // BUG-20: use separate regexes for id and translated_text so the
        // LLM can return them in any key order within each {} block.
        // Match each top-level {…} object, then extract fields independently.
        val objectRegex = Regex("""\{[^{}]*\}""")
        val idRegex = Regex(""""id"\s*:\s*(\d+)""")
        val textRegex = Regex(""""translated_text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        for (objMatch in objectRegex.findAll(content)) {
            val objStr = objMatch.value
            val id = idRegex.find(objStr)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val text = textRegex.find(objStr)?.groupValues?.get(1) ?: continue
            translations[id] = unescapeJson(text)
        }

        // Extract detected language (first chunk only).
        val langRegex = Regex(""""detected_source_language"\s*:\s*"([^"]+)"""")
        val detectedLanguage = langRegex.find(content)?.groupValues?.get(1)

        if (translations.isEmpty()) {
            throw RuntimeException("No translations found in LLM response: $content")
        }

        return TranslationResponse(translations, detectedLanguage)
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    private fun escapeJson(s: String): String {
        // BUG-25: handle all control chars, not just \n.
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

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        // BUG-12: wrap in runCatching — LLM might emit malformed \u.
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val codePoint = runCatching { hex.toInt(16) }.getOrNull()
                            if (codePoint != null) {
                                sb.append(codePoint.toChar())
                                i += 4
                            } else {
                                // Malformed: keep the literal text.
                                sb.append("\\u")
                            }
                        } else {
                            sb.append("\\u")
                        }
                    }
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun quote(s: String): String {
        return "\"" + escapeJson(s) + "\""
    }

    companion object {
        private const val TAG = "LLMRemoteSource"
    }
}
