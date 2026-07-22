package dev.windplayer.translate

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SharedPreferences-backed persistence for [TranslationConfig].
 */
object TranslationConfigHelper {
    private const val TAG = "TranslationConfig"
    private const val PREFS_NAME = "windplayer_translate"
    private const val SECURE_PREFS_NAME = "windplayer_translate_secure"

    private const val KEY_WHISPER_MODEL = "whisper_model"
    private const val KEY_LLM_BASE_URL = "llm_base_url"
    private const val KEY_LLM_API_KEY = "llm_api_key"
    private const val KEY_LLM_MODEL = "llm_model"
    private const val KEY_TARGET_LANG = "target_lang"
    private const val KEY_MAX_LINES = "max_lines"
    private const val KEY_MAX_CHARS = "max_chars"
    private const val KEY_AUTO_MOUNT = "auto_mount"
    @Volatile private var cachedSecurePrefs: SharedPreferences? = null
    private val securePrefsLock = Any()

    private fun securePrefs(context: Context): SharedPreferences? {
        cachedSecurePrefs?.let { return it }
        synchronized(securePrefsLock) {
            cachedSecurePrefs?.let { return it }
            return try {
                val appContext = context.applicationContext
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { cachedSecurePrefs = it }
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted API key storage unavailable", e)
                null
            }
        }
    }

    private fun loadApiKey(context: Context, plainPrefs: SharedPreferences): String {
        val encrypted = securePrefs(context) ?: return ""
        encrypted.getString(KEY_LLM_API_KEY, null)?.let {
            if (plainPrefs.contains(KEY_LLM_API_KEY)) {
                plainPrefs.edit().remove(KEY_LLM_API_KEY).apply()
            }
            return it
        }
        val legacyKey = plainPrefs.getString(KEY_LLM_API_KEY, null) ?: return ""
        if (encrypted.edit().putString(KEY_LLM_API_KEY, legacyKey).commit()) {
            plainPrefs.edit().remove(KEY_LLM_API_KEY).apply()
            Log.i(TAG, "Migrated plaintext API key to encrypted storage")
        }
        return legacyKey
    }

    fun load(context: Context): TranslationConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return TranslationConfig(
            whisperModel = prefs.getString(KEY_WHISPER_MODEL, null) ?: WhisperModelWhiteList.DEFAULT,
            llmBaseUrl = prefs.getString(KEY_LLM_BASE_URL, null) ?: "https://api.openai.com/v1",
            llmApiKey = loadApiKey(context, prefs),
            llmModel = prefs.getString(KEY_LLM_MODEL, null) ?: "gpt-4o-mini",
            targetLanguage = prefs.getString(KEY_TARGET_LANG, null) ?: "中文",
            maxLinesPerChunk = prefs.getInt(KEY_MAX_LINES, 40),
            maxCharsPerChunk = prefs.getInt(KEY_MAX_CHARS, 1500),
            autoMountSubtitle = prefs.getBoolean(KEY_AUTO_MOUNT, true)
        )
    }

    fun save(context: Context, config: TranslationConfig) {
        val plainPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        plainPrefs
            .edit()
            .putString(KEY_WHISPER_MODEL, config.whisperModel)
            .putString(KEY_LLM_BASE_URL, config.llmBaseUrl)
            .putString(KEY_LLM_MODEL, config.llmModel)
            .putString(KEY_TARGET_LANG, config.targetLanguage)
            .putInt(KEY_MAX_LINES, config.maxLinesPerChunk)
            .putInt(KEY_MAX_CHARS, config.maxCharsPerChunk)
            .putBoolean(KEY_AUTO_MOUNT, config.autoMountSubtitle)
            .apply()
        val encrypted = securePrefs(context)
        if (encrypted != null && encrypted.edit().putString(KEY_LLM_API_KEY, config.llmApiKey).commit()) {
            plainPrefs.edit().remove(KEY_LLM_API_KEY).apply()
        }
    }
}
