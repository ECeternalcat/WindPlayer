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
}
