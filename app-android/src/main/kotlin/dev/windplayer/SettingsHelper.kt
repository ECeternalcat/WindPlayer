package dev.windplayer

import android.content.Context
import dev.windplayer.ui.PlayerSettings

object SettingsHelper {
    private const val PREFS = "windplayer_settings"

    fun load(context: Context): PlayerSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PlayerSettings(
            defaultVolume = p.getInt("defaultVolume", 100),
            hwdecAuto = p.getBoolean("hwdecAuto", true),
            subFontSize = p.getInt("subFontSize", 55),
            subBorderSize = p.getInt("subBorderSize", 3),
            autoPlayNext = p.getBoolean("autoPlayNext", true),
            language = p.getString("language", "en") ?: "en"
        )
    }

    fun save(context: Context, settings: PlayerSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt("defaultVolume", settings.defaultVolume)
            putBoolean("hwdecAuto", settings.hwdecAuto)
            putInt("subFontSize", settings.subFontSize)
            putInt("subBorderSize", settings.subBorderSize)
            putBoolean("autoPlayNext", settings.autoPlayNext)
            putString("language", settings.language)
        }.apply()
    }
}
