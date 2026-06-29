package dev.windplayer.ui

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import java.util.logging.Logger

private val LOG = Logger.getLogger("dev.windplayer.ui.WindowsAccentColor")

private val HKEY_CURRENT_USER: Int = 0x80000001.toInt() // HKCU registry handle
private const val KEY_READ = 0x20019
private const val REG_DWORD = 4

private interface Advapi32 : Library {
    companion object {
        val INSTANCE: Advapi32 by lazy { Native.load("advapi32", Advapi32::class.java) }
    }
    // Must use the 'W' (Unicode) suffix explicitly — advapi32.dll exports
    // RegOpenKeyExW / RegQueryValueExW, not the bare names.
    fun RegOpenKeyExW(hKey: Int, subKey: WString, reserved: Int, access: Int, phkResult: IntArray): Int
    fun RegQueryValueExW(hKey: Int, lpValueName: WString, lpReserved: Int?, lpType: IntArray, lpData: ByteArray?, lpcbData: IntArray): Int
    fun RegCloseKey(hKey: Int): Int
}

/**
 * Reads the Windows system accent color from
 * `HKCU\Software\Microsoft\Windows\DWM\AccentColor` (ARGB DWORD).
 *
 * Falls back to `ColorizationColor` when AccentColor is white/invalid
 * (happens when "Show accent color on taskbar" is off).
 *
 * Returns the color as 0xAARRGGBB Int, or null if unavailable.
 */
internal fun readWindowsAccentColor(): Int? {
    return try {
        val accent = readDword("Software\\Microsoft\\Windows\\DWM", "AccentColor")
        if (accent != null && accent != 0xFFFFFFFF.toInt() && accent != -1) {
            accent
        } else {
            readDword("Software\\Microsoft\\Windows\\DWM", "ColorizationColor") ?: accent
        }
    } catch (e: Exception) {
        LOG.warning("Failed to read Windows accent color: ${e.message}")
        null
    }
}

private fun readDword(path: String, valueName: String): Int? {
    val handle = IntArray(1)
    if (Advapi32.INSTANCE.RegOpenKeyExW(HKEY_CURRENT_USER, WString(path), 0, KEY_READ, handle) != 0) return null
    try {
        val type = IntArray(1)
        val data = ByteArray(4)
        val size = IntArray(1).apply { this[0] = 4 }
        if (Advapi32.INSTANCE.RegQueryValueExW(handle[0], WString(valueName), null, type, data, size) != 0) return null
        if (type[0] != REG_DWORD) return null
        return (data[0].toInt() and 0xFF) or
               ((data[1].toInt() and 0xFF) shl 8) or
               ((data[2].toInt() and 0xFF) shl 16) or
               ((data[3].toInt() and 0xFF) shl 24)
    } finally {
        Advapi32.INSTANCE.RegCloseKey(handle[0])
    }
}
