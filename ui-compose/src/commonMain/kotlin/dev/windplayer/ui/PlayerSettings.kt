package dev.windplayer.ui

enum class ThemeMode { LIGHT, DARK, SYSTEM }

data class PlayerSettings(
    val defaultVolume: Int = 100,
    val hwdecAuto: Boolean = true,
    val subFontSize: Int = 55,
    val subBorderSize: Int = 3,
    val autoPlayNext: Boolean = true,
    val language: String = "en",
    val themeMode: ThemeMode = ThemeMode.SYSTEM
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
