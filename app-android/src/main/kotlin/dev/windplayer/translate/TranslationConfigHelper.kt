package dev.windplayer.translate

import android.content.Context

/**
 * SharedPreferences-backed persistence for [TranslationConfig].
 */
object TranslationConfigHelper {
    private const val PREFS_NAME = "windplayer_translate"

    private const val KEY_WHISPER_MODEL = "whisper_model"
    private const val KEY_LLM_BASE_URL = "llm_base_url"
    private const val KEY_LLM_API_KEY = "llm_api_key"
    private const val KEY_LLM_MODEL = "llm_model"
    private const val KEY_TARGET_LANG = "target_lang"
    private const val KEY_MAX_LINES = "max_lines"
    private const val KEY_MAX_CHARS = "max_chars"
    private const val KEY_AUTO_MOUNT = "auto_mount"

    fun load(context: Context): TranslationConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return TranslationConfig(
            whisperModel = prefs.getString(KEY_WHISPER_MODEL, null) ?: WhisperModelWhiteList.DEFAULT,
            llmBaseUrl = prefs.getString(KEY_LLM_BASE_URL, null) ?: "https://api.openai.com/v1",
            llmApiKey = prefs.getString(KEY_LLM_API_KEY, null) ?: "",
            llmModel = prefs.getString(KEY_LLM_MODEL, null) ?: "gpt-4o-mini",
            targetLanguage = prefs.getString(KEY_TARGET_LANG, null) ?: "中文",
            maxLinesPerChunk = prefs.getInt(KEY_MAX_LINES, 40),
            maxCharsPerChunk = prefs.getInt(KEY_MAX_CHARS, 1500),
            autoMountSubtitle = prefs.getBoolean(KEY_AUTO_MOUNT, true)
        )
    }

    fun save(context: Context, config: TranslationConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WHISPER_MODEL, config.whisperModel)
            .putString(KEY_LLM_BASE_URL, config.llmBaseUrl)
            .putString(KEY_LLM_API_KEY, config.llmApiKey)
            .putString(KEY_LLM_MODEL, config.llmModel)
            .putString(KEY_TARGET_LANG, config.targetLanguage)
            .putInt(KEY_MAX_LINES, config.maxLinesPerChunk)
            .putInt(KEY_MAX_CHARS, config.maxCharsPerChunk)
            .putBoolean(KEY_AUTO_MOUNT, config.autoMountSubtitle)
            .apply()
    }
}
