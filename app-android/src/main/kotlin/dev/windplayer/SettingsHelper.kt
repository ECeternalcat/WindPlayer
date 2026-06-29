package dev.windplayer

import android.content.Context
import dev.windplayer.ui.AccentColor
import dev.windplayer.ui.PlayerSettings
import dev.windplayer.ui.ThemeMode

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
            language = p.getString("language", "en") ?: "en",
            themeMode = runCatching {
                ThemeMode.valueOf(p.getString("themeMode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
            }.getOrNull() ?: ThemeMode.SYSTEM,
            accentColor = runCatching {
                AccentColor.valueOf(p.getString("accentColor", AccentColor.WINDPLAYER.name) ?: AccentColor.WINDPLAYER.name)
            }.getOrNull() ?: AccentColor.WINDPLAYER,
            defaultSpeed = p.getString("defaultSpeed", "1.0")?.toDoubleOrNull() ?: 1.0,
            resumePlayback = p.getBoolean("resumePlayback", true),
            seekStepShort = p.getInt("seekStepShort", 5),
            seekStepLong = p.getInt("seekStepLong", 30),
            gpuApi = p.getString("gpuApi", "auto") ?: "auto",
            deinterlace = p.getBoolean("deinterlace", false),
            videoAspect = p.getString("videoAspect", "-1") ?: "-1",
            audioChannels = p.getString("audioChannels", "auto") ?: "auto",
            pitchCorrection = p.getBoolean("pitchCorrection", true),
            subColor = p.getString("subColor", "#FFFFFF") ?: "#FFFFFF",
            subBackColor = p.getString("subBackColor", "#00000000") ?: "#00000000",
            subFontFamily = p.getString("subFontFamily", "sans-serif") ?: "sans-serif",
            subAlignY = p.getString("subAlignY", "bottom") ?: "bottom",
            cacheSize = p.getInt("cacheSize", 150),
            userAgent = p.getString("userAgent", "") ?: "",
            screenshotFormat = p.getString("screenshotFormat", "png") ?: "png",
            screenshotJpegQuality = p.getInt("screenshotJpegQuality", 90),
            screenshotSubtitles = p.getBoolean("screenshotSubtitles", true)
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
            putString("themeMode", settings.themeMode.name)
            putString("accentColor", settings.accentColor.name)
            putString("defaultSpeed", settings.defaultSpeed.toString())
            putBoolean("resumePlayback", settings.resumePlayback)
            putInt("seekStepShort", settings.seekStepShort)
            putInt("seekStepLong", settings.seekStepLong)
            putString("gpuApi", settings.gpuApi)
            putBoolean("deinterlace", settings.deinterlace)
            putString("videoAspect", settings.videoAspect)
            putString("audioChannels", settings.audioChannels)
            putBoolean("pitchCorrection", settings.pitchCorrection)
            putString("subColor", settings.subColor)
            putString("subBackColor", settings.subBackColor)
            putString("subFontFamily", settings.subFontFamily)
            putString("subAlignY", settings.subAlignY)
            putInt("cacheSize", settings.cacheSize)
            putString("userAgent", settings.userAgent)
            putString("screenshotFormat", settings.screenshotFormat)
            putInt("screenshotJpegQuality", settings.screenshotJpegQuality)
            putBoolean("screenshotSubtitles", settings.screenshotSubtitles)
        }.apply()
    }
}
