package dev.windplayer.ui

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Best-effort detection of the OS dark-mode preference for desktop JVM.
 *
 * Compose Desktop has no built-in system-theme API, so we query the platform
 * directly: Windows registry (`AppsUseLightTheme`), macOS `defaults`, and the
 * GTK theme name on Linux. Falls back to `false` (light) when undeterminable.
 * The result is cached for the process lifetime — changing the OS theme while
 * the app is running requires a restart or a settings toggle to re-resolve.
 */
object DesktopSystemTheme {
    @Volatile private var cached: Boolean? = null

    fun isSystemDark(): Boolean {
        cached?.let { return it }
        val result = detect()
        cached = result
        return result
    }

    private fun detect(): Boolean = try {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("win") -> windows()
            os.contains("mac") || os.contains("darwin") -> macos()
            os.contains("nix") || os.contains("nux") || os.contains("aix") -> linux()
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    /** Windows: `AppsUseLightTheme` = 0 means dark. */
    private fun windows(): Boolean {
        // M17: redirectErrorStream(true) so stderr doesn't fill the OS pipe
        // buffer and block waitFor() indefinitely. Also destroy() the process
        // to release native handles.
        val p = ProcessBuilder("reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme"
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        p.destroy()
        // "0x1" or "0x0" follows the value name.
        return out.substringAfter("AppsUseLightTheme", "")
            .substringAfter("REG_DWORD", "")
            .contains("0x0", ignoreCase = true)
    }

    /** macOS: `AppleInterfaceStyle` = "Dark" when dark mode is on. */
    private fun macos(): Boolean {
        val p = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        p.destroy()
        return out.equals("Dark", ignoreCase = true)
    }

    /** Linux: GTK theme name containing "dark" indicates dark. */
    private fun linux(): Boolean {
        val gtk = System.getenv("GTK_THEME") ?: return false
        return gtk.contains("dark", ignoreCase = true)
    }
}
