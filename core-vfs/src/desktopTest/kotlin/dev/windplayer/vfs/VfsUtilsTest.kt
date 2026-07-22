package dev.windplayer.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for the JVM-only helpers in [VfsUtils] (formatDuration, formatFileSize,
 * FileNodeComparator, buildUrlWithCredentials).
 *
 * Lives in `desktopTest` because [VfsUtils] is in `jvmShared`; desktopTest
 * transitively depends on jvmShared so it can see these declarations.
 */
class VfsUtilsTest {

    // ------------------------------------------------------------------
    // formatDuration
    // ------------------------------------------------------------------

    @Test
    fun `formatDuration handles NaN`() {
        assertEquals("00:00", formatDuration(Double.NaN))
    }

    @Test
    fun `formatDuration handles Infinite`() {
        assertEquals("00:00", formatDuration(Double.POSITIVE_INFINITY))
        assertEquals("00:00", formatDuration(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `formatDuration handles negative`() {
        assertEquals("00:00", formatDuration(-1.0))
        assertEquals("00:00", formatDuration(-100.0))
    }

    @Test
    fun `formatDuration zero`() {
        assertEquals("00:00", formatDuration(0.0))
    }

    @Test
    fun `formatDuration seconds only`() {
        assertEquals("00:05", formatDuration(5.0))
        assertEquals("00:59", formatDuration(59.0))
    }

    @Test
    fun `formatDuration minutes and seconds`() {
        assertEquals("01:00", formatDuration(60.0))
        assertEquals("01:30", formatDuration(90.0))
        assertEquals("12:34", formatDuration(754.0))
    }

    @Test
    fun `formatDuration hours minutes seconds`() {
        assertEquals("1:00:00", formatDuration(3600.0))
        assertEquals("1:23:45", formatDuration(5025.0))
        assertEquals("2:00:00", formatDuration(7200.0))
    }

    @Test
    fun `formatDuration truncates fractional seconds`() {
        // 12.9 seconds → "00:12" (truncated, not rounded)
        assertEquals("00:12", formatDuration(12.9))
    }

    // ------------------------------------------------------------------
    // formatDurationOsd
    // ------------------------------------------------------------------

    @Test
    fun `formatDurationOsd formats both halves`() {
        assertEquals("01:23 / 02:00", formatDurationOsd(83.0, 120.0))
    }

    @Test
    fun `formatDurationOsd handles hours in current but not total`() {
        assertEquals("1:00:00 / 02:00", formatDurationOsd(3600.0, 120.0))
    }

    @Test
    fun `formatDurationOsd handles NaN current`() {
        assertEquals("00:00 / 05:00", formatDurationOsd(Double.NaN, 300.0))
    }

    // ------------------------------------------------------------------
    // formatFileSize
    // ------------------------------------------------------------------

    @Test
    fun `formatFileSize returns empty for zero or negative`() {
        assertEquals("", formatFileSize(0))
        assertEquals("", formatFileSize(-1))
        assertEquals("", formatFileSize(-1024))
    }

    @Test
    fun `formatFileSize bytes`() {
        assertEquals("1.0 B", formatFileSize(1))
        assertEquals("512.0 B", formatFileSize(512))
    }

    @Test
    fun `formatFileSize kilobytes`() {
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.5 KB", formatFileSize(1536))
    }

    @Test
    fun `formatFileSize megabytes`() {
        assertEquals("1.0 MB", formatFileSize(1024 * 1024L))
        // 411 MB (the Worklog's SFTP test file size)
        assertEquals("411.0 MB", formatFileSize(411L * 1024 * 1024))
    }

    @Test
    fun `formatFileSize gigabytes`() {
        assertEquals("1.0 GB", formatFileSize(1024L * 1024 * 1024))
    }

    // ------------------------------------------------------------------
    // FileNodeComparator
    // ------------------------------------------------------------------

    @Test
    fun `FileNodeComparator directories first then alphabetical`() {
        val files = listOf(
            FileNode("b.mkv", "/b.mkv", isDirectory = false, protocol = VfsProtocol.LOCAL),
            FileNode("A_dir", "/A_dir", isDirectory = true, protocol = VfsProtocol.LOCAL),
            FileNode("a.mkv", "/a.mkv", isDirectory = false, protocol = VfsProtocol.LOCAL),
            FileNode("Z_dir", "/Z_dir", isDirectory = true, protocol = VfsProtocol.LOCAL)
        )
        val sorted = files.sortedWith(FileNodeComparator)
        assertEquals(listOf("A_dir", "Z_dir", "a.mkv", "b.mkv"), sorted.map { it.name })
    }

    @Test
    fun `FileNodeComparator case-insensitive`() {
        val files = listOf(
            FileNode("Mango.mkv", "/Mango.mkv", isDirectory = false, protocol = VfsProtocol.LOCAL),
            FileNode("apple.mkv", "/apple.mkv", isDirectory = false, protocol = VfsProtocol.LOCAL),
            FileNode("Banana.mkv", "/Banana.mkv", isDirectory = false, protocol = VfsProtocol.LOCAL)
        )
        val sorted = files.sortedWith(FileNodeComparator).map { it.name }
        assertEquals(listOf("apple.mkv", "Banana.mkv", "Mango.mkv"), sorted)
    }

    // ------------------------------------------------------------------
    // buildUrlWithCredentials
    // ------------------------------------------------------------------

    @Test
    fun `buildUrlWithCredentials no credentials`() {
        val url = buildUrlWithCredentials(
            scheme = "sftp",
            username = "",
            password = "",
            host = "example.com",
            port = 22,
            defaultPort = 22,
            path = "/video.mkv"
        )
        assertEquals("sftp://example.com/video.mkv", url)
    }

    @Test
    fun `buildUrlWithCredentials username only`() {
        val url = buildUrlWithCredentials(
            scheme = "sftp",
            username = "alice",
            password = "",
            host = "example.com",
            port = 22,
            defaultPort = 22,
            path = "/video.mkv"
        )
        assertEquals("sftp://alice@example.com/video.mkv", url)
    }

    @Test
    fun `buildUrlWithCredentials username and password`() {
        val url = buildUrlWithCredentials(
            scheme = "sftp",
            username = "alice",
            password = "secret",
            host = "example.com",
            port = 22,
            defaultPort = 22,
            path = "/video.mkv"
        )
        assertEquals("sftp://alice:secret@example.com/video.mkv", url)
    }

    @Test
    fun `buildUrlWithCredentials URL-encodes special chars in credentials`() {
        // Worklog mentioned passwords like "p@ss#word*!" containing hash and star.
        // Note: java.net.URLEncoder treats `*` as unreserved (NOT encoded) but
        // encodes `@` `#` `!`.
        val url = buildUrlWithCredentials(
            scheme = "ftp",
            username = "user@domain",
            password = "p@ss#word*!",
            host = "example.com",
            port = 21,
            defaultPort = 21,
            path = "/movie.mkv"
        )
        // @ → %40, # → %23, * → * (unreserved), ! → %21
        assertEquals("ftp://user%40domain:p%40ss%23word*%21@example.com/movie.mkv", url)
    }

    @Test
    fun `buildUrlWithCredentials omits port when equal to default`() {
        val url = buildUrlWithCredentials(
            scheme = "http",
            username = "",
            password = "",
            host = "example.com",
            port = 80,
            defaultPort = 80,
            path = "/file"
        )
        assertEquals("http://example.com/file", url)
    }

    @Test
    fun `buildUrlWithCredentials includes non-default port`() {
        val url = buildUrlWithCredentials(
            scheme = "sftp",
            username = "user",
            password = "pass",
            host = "example.com",
            port = 2222,
            defaultPort = 22,
            path = "/file.mkv"
        )
        assertEquals("sftp://user:pass@example.com:2222/file.mkv", url)
    }

    @Test
    fun `buildUrlWithCredentials strips leading slash from path`() {
        val url = buildUrlWithCredentials(
            scheme = "sftp",
            username = "",
            password = "",
            host = "example.com",
            port = 22,
            defaultPort = 22,
            path = "/deep/nested/path/file.mkv"
        )
        assertEquals("sftp://example.com/deep/nested/path/file.mkv", url)
    }

    @Test
    fun `buildUrlWithCredentials encodes path and supports IPv6`() {
        val url = buildUrlWithCredentials(
            scheme = "https", username = "", password = "", host = "2001:db8::1",
            port = 8443, defaultPort = 443, path = "/media/My Video #1.mkv"
        )
        assertEquals("https://[2001:db8::1]:8443/media/My%20Video%20%231.mkv", url)
    }

    @Test
    fun `parseByteRange rejects malformed ranges`() {
        assertEquals(null, parseByteRange("items=0-1", 10))
        assertEquals(null, parseByteRange("bytes=abc-", 10))
        assertEquals(null, parseByteRange("bytes=0-1,3-4", 10))
        assertEquals(null, parseByteRange("bytes=-0", 10))
        assertEquals(null, parseByteRange("bytes=0-", 0))
    }

    @Test
    fun `parseByteRange handles explicit open and suffix ranges`() {
        assertEquals(2L..5L, parseByteRange("bytes=2-5", 10))
        assertEquals(8L..9L, parseByteRange("bytes=-2", 10))
        assertEquals(8L..9L, parseByteRange("bytes=8-", 10))
        assertEquals(0L..9L, parseByteRange("bytes=0-99", 10))
    }

    @Test
    fun `SEC-7 buildUrlWithCredentials encodes space as percent-20 not plus`() {
        // SEC-7: URLEncoder.encode emits `+` for space (HTML form encoding),
        // but RFC 3986 userinfo requires `%20`. A password like `my pass`
        // previously became `my+pass`, which some servers decode as a literal
        // `+`, breaking auth or matching the wrong account.
        val url = buildUrlWithCredentials(
            scheme = "sftp",
            username = "alice smith",
            password = "my pass",
            host = "example.com",
            port = 22,
            defaultPort = 22,
            path = "/file.mkv"
        )
        assertEquals("sftp://alice%20smith:my%20pass@example.com/file.mkv", url)
    }

    @Test
    fun `SEC-7 buildUrlWithCredentials encodes literal plus as percent-2B`() {
        // A literal `+` in the password is encoded by URLEncoder as `%2B`,
        // which is safe under RFC 3986 (sub-delims are allowed unreserved in
        // userinfo, but the encoded form is universally accepted). The
        // post-replace of `+` → `%20` does NOT touch `%2B` because URLEncoder
        // already consumed the literal `+`.
        val url = buildUrlWithCredentials(
            scheme = "sftp",
            username = "user",
            password = "a+b",
            host = "example.com",
            port = 22,
            defaultPort = 22,
            path = "/file.mkv"
        )
        assertEquals("sftp://user:a%2Bb@example.com/file.mkv", url)
    }

    // ------------------------------------------------------------------
    // redactUrl (ARCH-15: security-relevant — guards credential leakage
    // into logs. Test the variants that may appear in production.)
    // ------------------------------------------------------------------

    @Test
    fun `redactUrl strips userinfo from URL with user and password`() {
        val redacted = redactUrl("sftp://alice:s3cr3t@host.example.com/path/movie.mkv")
        assertEquals("sftp://host.example.com/path/movie.mkv", redacted)
    }

    @Test
    fun `redactUrl strips userinfo with only username`() {
        val redacted = redactUrl("ftp://anonymous@host/file")
        assertEquals("ftp://host/file", redacted)
    }

    @Test
    fun `redactUrl leaves URL without userinfo unchanged`() {
        val url = "https://host.example.com/path/file.mkv"
        assertEquals(url, redactUrl(url))
    }

    @Test
    fun `redactUrl passes StreamProxy local URL through unchanged`() {
        // StreamProxy URLs are the only ones we log verbatim — they contain
        // no credentials, only a session UUID.
        val url = "http://127.0.0.1:54321/stream/550e8400-e29b-41d4-a716-446655440000"
        assertEquals(url, redactUrl(url))
    }

    @Test
    fun `redactUrl handles malformed URL gracefully`() {
        // No `://` — return as-is (defensive).
        assertEquals("not-a-url", redactUrl("not-a-url"))
    }

    @Test
    fun `redactUrl does not mistake path @ for userinfo`() {
        // `@` in the path portion must not be stripped as userinfo.
        val url = "https://host.example.com/path/with/@/file.mkv"
        // The userinfo stripper only looks BEFORE the first `/` after `://`.
        // The first `/` is right after host, before any `@`, so nothing strips.
        assertEquals(url, redactUrl(url))
    }
}
