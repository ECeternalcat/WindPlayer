package dev.windplayer.vfs

/**
 * Shared utilities used across desktop and Android UI layers.
 *
 * Placed in `core-vfs/commonMain` so both platforms can call them without duplication.
 */

// ------------------------------------------------------------------
// Time formatting
// ------------------------------------------------------------------

/**
 * Format a playback position/duration (in seconds) as `H:MM:SS` (≥1h) or `MM:SS`.
 *
 * Handles NaN, Infinite, and negative values by returning `"00:00"`.
 */
fun formatDuration(seconds: Double): String {
    if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) return "00:00"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * Format a `current / total` playback pair for OSD display.
 *
 * Both halves adapt independently to `H:MM:SS` or `MM:SS`. Output example:
 * - `01:23 / 02:00`
 * - `0:01:23 / 1:55:00`
 */
fun formatDurationOsd(current: Double, total: Double): String {
    return "${formatDuration(current)} / ${formatDuration(total)}"
}

// ------------------------------------------------------------------
// File ordering
// ------------------------------------------------------------------

/**
 * Default [FileNode] ordering used by every VFS client and SAF listing.
 *
 * Directories first (descending `isDirectory`), then alphabetical by lowercased name.
 */
val FileNodeComparator: Comparator<FileNode> =
    compareByDescending<FileNode> { it.isDirectory }.thenBy { it.name.lowercase() }

// ------------------------------------------------------------------
// Formatting helpers (JVM-only — use java.lang.String.format)
// ------------------------------------------------------------------

/**
 * Format a byte count as `N.NN UNIT` (B / KB / MB / GB / TB). Returns `""` for
 * non-positive input.
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}

// ------------------------------------------------------------------
// URL builder for streaming protocols (SFTP / FTP / WebDAV)
// ------------------------------------------------------------------

/**
 * Build a URL of the form `scheme://[user[:pass]@]host[:port]/path` with URL-encoded
 * credentials. The port is omitted when it equals [defaultPort].
 *
 * Used by [SftpClient.resolveUrl], [FtpClient.resolveUrl], [WebdavClient.resolveUrl]
 * to avoid duplicated encoder/host/port logic.
 */
fun buildUrlWithCredentials(
    scheme: String,
    username: String,
    password: String,
    host: String,
    port: Int,
    defaultPort: Int,
    path: String
): String {
    val userInfo = if (username.isNotBlank()) {
        val encodedUser = java.net.URLEncoder.encode(username, "UTF-8")
        if (password.isNotBlank()) {
            val encodedPass = java.net.URLEncoder.encode(password, "UTF-8")
            "$encodedUser:$encodedPass@$host"
        } else {
            "$encodedUser@$host"
        }
    } else {
        host
    }
    val portPart = if (port != defaultPort) ":$port" else ""
    val cleanPath = if (path.startsWith("/")) path.removePrefix("/") else path
    return "$scheme://$userInfo$portPart/$cleanPath"
}
