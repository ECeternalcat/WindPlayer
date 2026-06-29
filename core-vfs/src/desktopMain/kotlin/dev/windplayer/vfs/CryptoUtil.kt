package dev.windplayer.vfs

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64
import java.util.logging.Logger

private val LOG = Logger.getLogger("dev.windplayer.vfs.CryptoUtil")

/**
 * Server-password encryption using Windows DPAPI when available, with graceful
 * fallback to a labelled-plaintext scheme on other platforms.
 *
 * Stored format (a prefix selects the encoding):
 *   - `dpapi:<base64>`       — DPAPI-encrypted, tied to the current Windows user.
 *   - `plain:<text>`         — explicit plaintext marker (non-Windows fallback).
 *   - `<text>` (no prefix)   — legacy plaintext from before P1-5; [decrypt] still
 *                              returns it as-is so old config keeps working. The
 *                              next save will upgrade it to `dpapi:` / `plain:`.
 *
 * The legacy branch lets the upgrade path be transparent: open the app, your
 * existing servers still work, and the next config write encrypts them.
 */
internal object CryptoUtil {

    private const val PREFIX_DPAPI = "dpapi:"
    private const val PREFIX_PLAIN = "plain:"

    private val isWindows: Boolean =
        System.getProperty("os.name").startsWith("Windows")

    /** Encrypt a plaintext password for at-rest storage. Returns `""` for empty input. */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        if (!isWindows) return PREFIX_PLAIN + plaintext
        // H12: fail-closed. Previously a DPAPI failure fell back to PREFIX_PLAIN,
        // silently storing the password unencrypted in servers.properties — a
        // security downgrade with no user signal. Now the exception propagates
        // to saveConfig(), which catches it and logs "saveConfig failed" — the
        // password stays in-memory only and never reaches disk. The user can
        // retry; the in-memory server list still works until the app closes.
        val cipher = Crypt32Util.cryptProtectData(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX_DPAPI + Base64.getEncoder().encodeToString(cipher)
    }

    /**
     * Decrypt a stored password. Returns `""` if the value cannot be recovered
     * (e.g. encrypted on a different Windows account). Legacy plaintext values
     * are returned as-is so old configs keep working.
     */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return when {
            stored.startsWith(PREFIX_DPAPI) -> {
                if (!isWindows) {
                    LOG.warning("Encrypted password cannot be decrypted on non-Windows platform")
                    return ""
                }
                try {
                    val cipher = Base64.getDecoder().decode(stored.removePrefix(PREFIX_DPAPI))
                    String(Crypt32Util.cryptUnprotectData(cipher), Charsets.UTF_8)
                } catch (e: Throwable) {
                    LOG.warning("DPAPI decrypt failed: ${e.message}")
                    ""
                }
            }
            stored.startsWith(PREFIX_PLAIN) -> stored.removePrefix(PREFIX_PLAIN)
            else -> stored // legacy plaintext — transparent passthrough
        }
    }
}
