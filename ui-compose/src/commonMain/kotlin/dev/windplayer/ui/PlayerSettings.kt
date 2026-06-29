package dev.windplayer.ui

enum class ThemeMode { LIGHT, DARK, SYSTEM }

enum class AccentColor { WINDPLAYER, AUTO }

data class PlayerSettings(
    // --- 现有 ---
    val defaultVolume: Int = 100,
    val hwdecAuto: Boolean = true,
    val subFontSize: Int = 55,
    val subBorderSize: Int = 3,
    val autoPlayNext: Boolean = true,
    val language: String = "en",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: AccentColor = AccentColor.WINDPLAYER,

    // --- 播放 ---
    val defaultSpeed: Double = 1.0,
    val resumePlayback: Boolean = true,
    val seekStepShort: Int = 5,
    val seekStepLong: Int = 30,

    // --- 视频 ---
    val gpuApi: String = "auto",
    val deinterlace: Boolean = false,
    val videoAspect: String = "-1",

    // --- 音频 ---
    val audioChannels: String = "auto",
    val pitchCorrection: Boolean = true,

    // --- 字幕 ---
    val subColor: String = "#FFFFFF",
    val subBackColor: String = "#00000000",
    val subFontFamily: String = "sans-serif",
    val subAlignY: String = "bottom",

    // --- 网络 ---
    val cacheSize: Int = 150,
    val userAgent: String = "",

    // --- 截图 ---
    val screenshotFormat: String = "png",
    val screenshotJpegQuality: Int = 90,
    val screenshotSubtitles: Boolean = true
) {
    companion object {
        val DEFAULT = PlayerSettings()
    }
}

data class RecentFile(
    val name: String,
    val path: String,
    val isLocal: Boolean,
    val serverId: String?,
    val timestamp: Long,
    val position: Double = 0.0,
    val duration: Double = 0.0
)
