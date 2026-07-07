package dev.windplayer.translate

import android.content.Context

/**
 * Supported target languages for LLM translation.
 */
object TranslateLanguages {
    data class Lang(val code: String, val nativeName: String, val englishName: String)

    val ALL: List<Lang> = listOf(
        Lang("zh", "中文", "Chinese"),
        Lang("en", "English", "English"),
        Lang("ja", "日本語", "Japanese"),
        Lang("ko", "한국어", "Korean"),
        Lang("es", "Español", "Spanish"),
        Lang("fr", "Français", "French"),
        Lang("de", "Deutsch", "German"),
        Lang("pt", "Português", "Portuguese"),
        Lang("ru", "Русский", "Russian"),
        Lang("ar", "العربية", "Arabic"),
        Lang("it", "Italiano", "Italian"),
        Lang("tr", "Türkçe", "Turkish"),
        Lang("vi", "Tiếng Việt", "Vietnamese"),
        Lang("th", "ไทย", "Thai"),
        Lang("id", "Bahasa Indonesia", "Indonesian"),
        Lang("ms", "Bahasa Melayu", "Malay"),
        Lang("hi", "हिन्दी", "Hindi"),
        Lang("pl", "Polski", "Polish"),
        Lang("nl", "Nederlands", "Dutch"),
        Lang("sv", "Svenska", "Swedish"),
        Lang("fi", "Suomi", "Finnish"),
        Lang("da", "Dansk", "Danish"),
        Lang("no", "Norsk", "Norwegian"),
        Lang("cs", "Čeština", "Czech"),
        Lang("uk", "Українська", "Ukrainian"),
        Lang("ro", "Română", "Romanian"),
        Lang("hu", "Magyar", "Hungarian"),
        Lang("el", "Ελληνικά", "Greek"),
        Lang("he", "עברית", "Hebrew"),
        Lang("bn", "বাংলা", "Bengali"),
        Lang("fa", "فارسی", "Persian"),
        Lang("fil", "Filipino", "Filipino"),
        Lang("ta", "தமிழ்", "Tamil"),
        Lang("te", "తెలుగు", "Telugu"),
        Lang("ur", "اردو", "Urdu"),
        Lang("sw", "Kiswahili", "Swahili"),
        Lang("hr", "Hrvatski", "Croatian"),
        Lang("bg", "Български", "Bulgarian"),
        Lang("sk", "Slovenčina", "Slovak"),
        Lang("lt", "Lietuvių", "Lithuanian"),
        Lang("lv", "Latviešu", "Latvian"),
        Lang("et", "Eesti", "Estonian"),
        Lang("sl", "Slovenščina", "Slovenian"),
        Lang("ca", "Català", "Catalan"),
        Lang("gl", "Galego", "Galician"),
        Lang("eu", "Euskara", "Basque"),
    )

    /** Find by native name (stored in config.targetLanguage). */
    fun findByName(name: String): Lang? = ALL.find { it.nativeName == name }

    // ---- Recent languages ----
    private const val PREFS = "windplayer_translate"
    private const val KEY_RECENT = "recent_languages"

    fun loadRecent(context: Context): List<Lang> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT, "") ?: ""
        return raw.split(",").mapNotNull { code -> ALL.find { it.code == code } }
    }

    fun addRecent(context: Context, lang: Lang) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // BUG-27: getString with non-null default never returns null.
        val current = prefs.getString(KEY_RECENT, "")!!.split(",").filter { it.isNotBlank() }
        val updated = (listOf(lang.code) + current.filter { it != lang.code }).distinct().take(6)
        prefs.edit().putString(KEY_RECENT, updated.joinToString(",")).apply()
    }
}
