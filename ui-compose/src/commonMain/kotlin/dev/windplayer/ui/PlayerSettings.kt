package dev.windplayer.ui

enum class ThemeMode { LIGHT, DARK, SYSTEM }

enum class AccentColor { WINDPLAYER, AUTO }

/**
 * Controls how AI-generated bilingual subtitles are displayed.
 *
 * - [TRANSLATED_ONLY]: single subtitle track, translated text (or source
 *   text if no translation).
 * - [DUAL_SEPARATED]: two tracks — translated on bottom (`sid`), original
 *   on top (`secondary-sid`). Uses mpv's native secondary subtitle feature.
 * - [DUAL_STACKED]: single track with two lines per cue (translated + original
 *   stacked). A pre-generated `wp_xx_dual.srt` file is used.
 */
enum class SubtitleDisplayMode {
    TRANSLATED_ONLY,
    DUAL_SEPARATED,
    DUAL_STACKED
}

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
    val subtitleDisplayMode: SubtitleDisplayMode = SubtitleDisplayMode.DUAL_SEPARATED,

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
