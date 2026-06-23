package dev.windplayer.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [ServerConfig] host/port/scheme resolution logic.
 *
 * Specifically guards the L10 fix: WebDAV `https://` host prefix detection
 * must work correctly even on non-default ports (8443, etc.), and `bareHost`
 * must strip the scheme prefix so underlying clients connect to the right
 * hostname.
 */
class ServerConfigTest {

    @Test
    fun `bareHost strips https prefix`() {
        val config = ServerConfig(
            id = "1", name = "test", protocol = VfsProtocol.WEBDAV,
            host = "https://dav.example.com", port = 443
        )
        assertEquals("dav.example.com", config.bareHost)
    }

    @Test
    fun `bareHost strips http prefix`() {
        val config = ServerConfig(
            id = "1", name = "test", protocol = VfsProtocol.WEBDAV,
            host = "http://dav.example.com", port = 80
        )
        assertEquals("dav.example.com", config.bareHost)
    }

    @Test
    fun `bareHost returns bare hostname unchanged`() {
        val config = ServerConfig(
            id = "1", name = "test", protocol = VfsProtocol.SFTP,
            host = "ssh.example.com", port = 22
        )
        assertEquals("ssh.example.com", config.bareHost)
    }

    @Test
    fun `bareHost strips trailing slash`() {
        val config = ServerConfig(
            id = "1", name = "test", protocol = VfsProtocol.WEBDAV,
            host = "https://dav.example.com/", port = 443
        )
        assertEquals("dav.example.com", config.bareHost)
    }

    // ------------------------------------------------------------------
    // httpScheme()
    // ------------------------------------------------------------------

    @Test
    fun `httpScheme detects https from host prefix`() {
        val config = ServerConfig(
            id = "1", name = "test", protocol = VfsProtocol.WEBDAV,
            host = "https://dav.example.com", port = 8443
        )
        assertEquals("https", config.httpScheme())
    }

    @Test
    fun `httpScheme detects http from host prefix`() {
        val config = ServerConfig(
            id = "1", name = "test", protocol = VfsProtocol.WEBDAV,
            host = "http://dav.example.com", port = 8080
        )
        assertEquals("http", config.httpScheme())
    }

    @Test
    fun `httpScheme falls back to http when no prefix and port 0`() {
        // No scheme prefix, port 0 (unset) → default to http (safe default).
        val config = ServerConfig(
            id = "1", name = "test", protocol = VfsProtocol.WEBDAV,
            host = "dav.example.com", port = 0
        )
        assertEquals("http", config.httpScheme())
        assertEquals(80, config.defaultPort())
    }

    @Test
    fun `httpScheme detects https from explicit port 443`() {
        val config = ServerConfig(
            id = "1", name = "test", protocol = VfsProtocol.WEBDAV,
            host = "dav.example.com", port = 443
        )
        assertEquals("https", config.httpScheme())
    }

    @Test
    fun `httpScheme falls back to http for non-443 port`() {
        val config = ServerConfig(
            id = "1", name = "test", protocol = VfsProtocol.WEBDAV,
            host = "dav.example.com", port = 8080
        )
        assertEquals("http", config.httpScheme())
    }

    // ------------------------------------------------------------------
    // defaultPort()
    // ------------------------------------------------------------------

    @Test
    fun `defaultPort SFTP returns 22 by default`() {
        val config = ServerConfig("1", "t", VfsProtocol.SFTP, "host", port = 0)
        assertEquals(22, config.defaultPort())
    }

    @Test
    fun `defaultPort SFTP respects custom port`() {
        val config = ServerConfig("1", "t", VfsProtocol.SFTP, "host", port = 2222)
        assertEquals(2222, config.defaultPort())
    }

    @Test
    fun `defaultPort FTP returns 21 by default`() {
        val config = ServerConfig("1", "t", VfsProtocol.FTP, "host", port = 0)
        assertEquals(21, config.defaultPort())
    }

    @Test
    fun `defaultPort FTP respects custom port`() {
        val config = ServerConfig("1", "t", VfsProtocol.FTP, "host", port = 2121)
        assertEquals(2121, config.defaultPort())
    }

    @Test
    fun `defaultPort WebDAV https returns 443`() {
        val config = ServerConfig("1", "t", VfsProtocol.WEBDAV, "https://host", port = 0)
        assertEquals(443, config.defaultPort())
    }

    @Test
    fun `defaultPort WebDAV plain http returns 80`() {
        val config = ServerConfig("1", "t", VfsProtocol.WEBDAV, "host", port = 0)
        assertEquals(80, config.defaultPort())
    }

    @Test
    fun `defaultPort WebDAV custom port preserved`() {
        // L10 regression: https with custom port 8443 must keep 8443, not
        // collapse to 443.
        val config = ServerConfig("1", "t", VfsProtocol.WEBDAV, "https://host", port = 8443)
        assertEquals(8443, config.defaultPort())
    }

    @Test
    fun `defaultPort LOCAL is always 0`() {
        val config = ServerConfig("1", "t", VfsProtocol.LOCAL, "host", port = 1234)
        assertEquals(0, config.defaultPort())
    }

    // ------------------------------------------------------------------
    // Integration: the L10 bug scenario
    // ------------------------------------------------------------------

    @Test
    fun `L10 regression https custom port uses https scheme`() {
        // Before the L10 fix, WebDAV over https:// on port 8443 would be
        // incorrectly treated as http because defaultPort() != 443. Verify
        // all three properties agree.
        val config = ServerConfig(
            id = "1", name = "secure-dav", protocol = VfsProtocol.WEBDAV,
            host = "https://dav.example.com", port = 8443
        )
        assertEquals("dav.example.com", config.bareHost)
        assertEquals("https", config.httpScheme())
        assertEquals(8443, config.defaultPort())
    }

    @Test
    fun `PlaybackParams defaults are empty`() {
        val params = PlaybackParams(streamUrl = "http://test", subtitleFiles = emptyList())
        assertEquals("", params.filePath)
        assertEquals(true, params.streamSessionIds.isEmpty())
        assertEquals(0.0, params.resumePosition)
        assertEquals(false, params.isLocal)
        assertEquals(null, params.serverId)
    }
}
