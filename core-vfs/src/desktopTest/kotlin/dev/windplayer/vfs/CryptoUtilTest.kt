package dev.windplayer.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [CryptoUtil] — Windows DPAPI encrypt/decrypt with prefix-based
 * format routing and legacy plaintext fallback.
 *
 * On Windows: tests full DPAPI round-trip (encrypt → decrypt yields original).
 * On non-Windows: tests only the `plain:` fallback path (DPAPI unavailable).
 */
class CryptoUtilTest {

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    // ------------------------------------------------------------------
    // Universal cases (work on all platforms)
    // ------------------------------------------------------------------

    @Test
    fun `encrypt empty string returns empty`() {
        assertEquals("", CryptoUtil.encrypt(""))
    }

    @Test
    fun `decrypt empty string returns empty`() {
        assertEquals("", CryptoUtil.decrypt(""))
    }

    @Test
    fun `decrypt plain-prefixed value`() {
        assertEquals("hello", CryptoUtil.decrypt("plain:hello"))
    }

    @Test
    fun `decrypt plain-prefixed with special chars`() {
        assertEquals("p@ss#word*!", CryptoUtil.decrypt("plain:p@ss#word*!"))
    }

    @Test
    fun `decrypt legacy plaintext (no prefix) returns as-is`() {
        // This is the backward-compatibility case: users upgrading from
        // pre-P1-5 have plaintext passwords without any prefix.
        assertEquals("oldPassword123", CryptoUtil.decrypt("oldPassword123"))
    }

    @Test
    fun `decrypt plain prefix with empty value`() {
        assertEquals("", CryptoUtil.decrypt("plain:"))
    }

    // ------------------------------------------------------------------
    // Non-Windows behavior
    // ------------------------------------------------------------------

    @Test
    fun `on non-Windows encrypt returns plain-prefixed`() {
        if (!!isWindows) return // Test only runs on non-Windows
        assertEquals("plain:hello", CryptoUtil.encrypt("hello"))
    }

    @Test
    fun `on non-Windows decrypt of dpapi value returns empty`() {
        if (!!isWindows) return // Test only runs on non-Windows
        // Can't decrypt DPAPI data on non-Windows
        assertEquals("", CryptoUtil.decrypt("dpapi:AAAA"))
    }

    // ------------------------------------------------------------------
    // Windows-only: full DPAPI round-trip
    // ------------------------------------------------------------------

    @Test
    fun `on Windows encrypt produces dpapi prefix`() {
        if (!isWindows) return // Test only runs on Windows
        val encrypted = CryptoUtil.encrypt("testPassword123")
        assertTrue(encrypted.startsWith("dpapi:"), "Expected dpapi: prefix, got: $encrypted")
    }

    @Test
    fun `on Windows encrypt then decrypt round-trips simple password`() {
        if (!isWindows) return // Test only runs on Windows
        val original = "mySecret123"
        val encrypted = CryptoUtil.encrypt(original)
        val decrypted = CryptoUtil.decrypt(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun `on Windows round-trips password with special characters`() {
        if (!isWindows) return // Test only runs on Windows
        // The Worklog mentioned passwords like "p@ss#word*!" containing hash and star.
        val original = "p@ss#word*!`~"
        val encrypted = CryptoUtil.encrypt(original)
        val decrypted = CryptoUtil.decrypt(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun `on Windows round-trips unicode password`() {
        if (!isWindows) return // Test only runs on Windows
        val original = "密码123🌟"  // CJK + emoji
        val encrypted = CryptoUtil.encrypt(original)
        val decrypted = CryptoUtil.decrypt(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun `on Windows round-trips long password`() {
        if (!isWindows) return // Test only runs on Windows
        val original = "a".repeat(1000)
        val encrypted = CryptoUtil.encrypt(original)
        val decrypted = CryptoUtil.decrypt(encrypted)
        assertEquals(original, decrypted)
    }
}
