package dev.windplayer.translate

/**
 * User-facing configuration for the AI translation pipeline.
 *
 * Stored in SharedPreferences / Properties, edited from the Settings screen.
 */
data class TranslationConfig(
    // ---- ASR (Whisper) ----
    /** Which Whisper model to use. Must be in [WhisperModelWhiteList]. */
    val whisperModel: String = WhisperModelWhiteList.DEFAULT,

    // ---- LLM Translation ----
    /** API base URL, e.g. "https://api.openai.com/v1" or a custom proxy. */
    val llmBaseUrl: String = "https://api.openai.com/v1",
    /** API key (Bearer token). */
    val llmApiKey: String = "",
    /** Model name, e.g. "gpt-4o-mini". */
    val llmModel: String = "gpt-4o-mini",
    /** Target language for translation, e.g. "中文" or "English". */
    val targetLanguage: String = "中文",

    // ---- Chunking (§4.1) ----
    /** Max lines per chunk sent to LLM. */
    val maxLinesPerChunk: Int = 40,
    /** Max characters per chunk sent to LLM. */
    val maxCharsPerChunk: Int = 1500,

    // ---- Behavior ----
    /** If true, auto-mount the generated .srt after completion. */
    val autoMountSubtitle: Boolean = true
)

/**
 * Whitelisted Whisper GGML models (AITranslate.md §3.1).
 *
 * Only quantized models are allowed — never ship unquantized versions
 * (they're too large for mobile).
 */
object WhisperModelWhiteList {
    const val TINY = "ggml-tiny.bin"
    const val BASE = "ggml-base.bin"
    const val SMALL_Q8_0 = "ggml-small-q8_0.bin"
    const val TURBO_Q5_0 = "ggml-large-v3-turbo-q5_0.bin"

    val DEFAULT = TINY

    /** All allowed models with display names + sizes (approximate). */
    val ALL = listOf(
        ModelInfo(TINY, "Tiny (75MB)", "Fastest, lowest accuracy"),
        ModelInfo(BASE, "Base (142MB)", "Balanced for mobile"),
        ModelInfo(SMALL_Q8_0, "Small q8_0 (244MB)", "Good accuracy, moderate size"),
        ModelInfo(TURBO_Q5_0, "Turbo q5_0 (574MB)", "Best accuracy, larger download")
    )

    /** HuggingFace direct URL for a model file. */
    fun hfUrl(modelFile: String): String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$modelFile"

    data class ModelInfo(
        val fileName: String,
        val displayName: String,
        val description: String
    )
}
