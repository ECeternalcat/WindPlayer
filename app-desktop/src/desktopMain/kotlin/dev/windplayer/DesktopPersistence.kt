package dev.windplayer

import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.ui.PlayerSettings
import dev.windplayer.ui.RecentFile
import java.awt.Rectangle
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import javax.swing.JFrame

// ------------------------------------------------------------------
// Paths
// ------------------------------------------------------------------

private val CONFIG_DIR: File by lazy {
    File(System.getProperty("user.home"), ".windplayer")
}

private val WINDOW_STATE_FILE: File by lazy { File(CONFIG_DIR, "window.properties") }
private val SETTINGS_FILE: File by lazy { File(CONFIG_DIR, "settings.properties") }
private val RECENT_FILE: File by lazy { File(CONFIG_DIR, "recent.properties") }
private val BOOKMARKS_FILE: File by lazy { File(CONFIG_DIR, "bookmarks.properties") }

private fun ensureConfigDir() {
    if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs()
}

// ------------------------------------------------------------------
// Window state
// ------------------------------------------------------------------

internal fun loadWindowState(): Rectangle? {
    return try {
        val props = Properties()
        FileInputStream(WINDOW_STATE_FILE).use { props.load(it) }
        val x = props.getProperty("x")?.toIntOrNull() ?: return null
        val y = props.getProperty("y")?.toIntOrNull() ?: return null
        val w = props.getProperty("width")?.toIntOrNull() ?: return null
        val h = props.getProperty("height")?.toIntOrNull() ?: return null
        if (w < 400 || h < 300) return null
        val bounds = Rectangle(x, y, w, h)
        val screenBounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
        if (screenBounds.intersects(bounds)) bounds else null
    } catch (_: Exception) {
        null
    }
}

internal fun saveWindowState(frame: JFrame) {
    try {
        if (frame.extendedState != JFrame.NORMAL) return
        ensureConfigDir()
        val bounds = frame.bounds
        val props = Properties()
        props.setProperty("x", bounds.x.toString())
        props.setProperty("y", bounds.y.toString())
        props.setProperty("width", bounds.width.toString())
        props.setProperty("height", bounds.height.toString())
        FileOutputStream(WINDOW_STATE_FILE).use { props.store(it, "WindPlayer Window State") }
    } catch (_: Exception) {}
}

// ------------------------------------------------------------------
// Settings
// ------------------------------------------------------------------

internal fun loadSettings(): PlayerSettings {
    return try {
        val props = Properties()
        FileInputStream(SETTINGS_FILE).use { props.load(it) }
        PlayerSettings(
            defaultVolume = props.getProperty("defaultVolume")?.toIntOrNull() ?: 100,
            hwdecAuto = props.getProperty("hwdecAuto")?.toBooleanStrictOrNull() ?: true,
            subFontSize = props.getProperty("subFontSize")?.toIntOrNull() ?: 55,
            subBorderSize = props.getProperty("subBorderSize")?.toIntOrNull() ?: 3,
            autoPlayNext = props.getProperty("autoPlayNext")?.toBooleanStrictOrNull() ?: true,
            language = props.getProperty("language") ?: "en"
        )
    } catch (_: Exception) {
        PlayerSettings()
    }
}

internal fun saveSettings(settings: PlayerSettings) {
    try {
        ensureConfigDir()
        val props = Properties()
        props.setProperty("defaultVolume", settings.defaultVolume.toString())
        props.setProperty("hwdecAuto", settings.hwdecAuto.toString())
        props.setProperty("subFontSize", settings.subFontSize.toString())
        props.setProperty("subBorderSize", settings.subBorderSize.toString())
        props.setProperty("autoPlayNext", settings.autoPlayNext.toString())
        props.setProperty("language", settings.language)
        FileOutputStream(SETTINGS_FILE).use { props.store(it, "WindPlayer Settings") }
    } catch (_: Exception) {}
}

internal fun applyMpvSettings(player: MpvPlayer, settings: PlayerSettings) {
    try {
        player.setProperty("sub-font-size", settings.subFontSize.toString())
        player.setProperty("sub-border-size", settings.subBorderSize.toString())
        player.setProperty("volume", settings.defaultVolume.toString())
        player.setProperty("hwdec", if (settings.hwdecAuto) "auto" else "no")
    } catch (_: Exception) {}
}

// ------------------------------------------------------------------
// Recent files
// ------------------------------------------------------------------

internal fun loadRecentFiles(): List<RecentFile> {
    return try {
        val props = Properties()
        FileInputStream(RECENT_FILE).use { props.load(it) }
        val count = props.getProperty("count")?.toIntOrNull() ?: 0
        (0 until count).mapNotNull { i ->
            val parts = props.getProperty("recent.$i")?.split("|") ?: return@mapNotNull null
            if (parts.size < 3) return@mapNotNull null
            RecentFile(
                name = parts[0],
                path = parts[1],
                isLocal = parts[2].toBoolean(),
                serverId = parts.getOrNull(3)?.ifBlank { null },
                timestamp = parts.getOrNull(4)?.toLongOrNull() ?: System.currentTimeMillis(),
                position = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0,
                duration = parts.getOrNull(6)?.toDoubleOrNull() ?: 0.0
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun saveRecentFiles(files: List<RecentFile>) {
    try {
        ensureConfigDir()
        val props = Properties()
        props.setProperty("count", files.size.toString())
        files.forEachIndexed { i, f ->
            props.setProperty(
                "recent.$i",
                "${f.name}|${f.path}|${f.isLocal}|${f.serverId ?: ""}|${f.timestamp}|${f.position}|${f.duration}"
            )
        }
        FileOutputStream(RECENT_FILE).use { props.store(it, "WindPlayer Recent Files") }
    } catch (_: Exception) {}
}

internal fun updateRecentFiles(
    current: List<RecentFile>,
    name: String,
    path: String,
    isLocal: Boolean,
    serverId: String?
): List<RecentFile> {
    val existing = current.find { it.path == path }
    val filtered = current.filterNot { it.path == path }
    val entry = RecentFile(
        name = name,
        path = path,
        isLocal = isLocal,
        serverId = serverId,
        timestamp = System.currentTimeMillis(),
        position = existing?.position ?: 0.0,
        duration = existing?.duration ?: 0.0
    )
    return (listOf(entry) + filtered).take(20)
}

internal fun updateRecentPosition(
    current: List<RecentFile>,
    path: String,
    position: Double,
    duration: Double
): List<RecentFile> {
    return current.map { f ->
        if (f.path == path) {
            f.copy(
                position = if (position > 0) position else f.position,
                duration = if (duration > 0) duration else f.duration
            )
        } else f
    }
}

// ------------------------------------------------------------------
// Bookmarks
// ------------------------------------------------------------------

internal fun loadBookmarks(): List<String> {
    return try {
        val props = Properties()
        FileInputStream(BOOKMARKS_FILE).use { props.load(it) }
        val count = props.getProperty("count")?.toIntOrNull() ?: 0
        (0 until count).mapNotNull { i ->
            props.getProperty("bookmark.$i")?.takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun saveBookmarks(bookmarks: List<String>) {
    try {
        ensureConfigDir()
        val props = Properties()
        props.setProperty("count", bookmarks.size.toString())
        bookmarks.forEachIndexed { i, path ->
            props.setProperty("bookmark.$i", path)
        }
        FileOutputStream(BOOKMARKS_FILE).use { props.store(it, "WindPlayer Bookmarks") }
    } catch (_: Exception) {}
}
