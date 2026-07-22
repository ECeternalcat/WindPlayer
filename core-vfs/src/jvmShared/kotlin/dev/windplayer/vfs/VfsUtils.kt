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
// ARCH-3: FileNodeComparator moved to commonMain/FileNode.kt — it has no
// JVM dependency and belongs with the FileNode type it orders.

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
    val effectivePort = if (port == defaultPort) -1 else port
    val normalizedPath = "/${path.trimStart('/')}"
    val authorityAndPath = java.net.URI(null, null, host, effectivePort, normalizedPath, null, null)
        .toASCIIString()
        .removePrefix("//")
    if (username.isBlank()) return "$scheme://$authorityAndPath"

    val encodedUser = java.net.URLEncoder.encode(username, "UTF-8").replace("+", "%20")
    val userInfo = if (password.isBlank()) {
        encodedUser
    } else {
        val encodedPassword = java.net.URLEncoder.encode(password, "UTF-8").replace("+", "%20")
        "$encodedUser:$encodedPassword"
    }
    return "$scheme://$userInfo@$authorityAndPath"
}

internal fun hostForUrl(host: String): String = if (':' in host && !host.startsWith('[')) "[$host]" else host

internal fun parseByteRange(value: String, fileSize: Long): LongRange? {
    if (fileSize < 0) return null
    val match = Regex("""bytes=(\d*)-(\d*)""").matchEntire(value) ?: return null
    val startText = match.groupValues[1]
    val endText = match.groupValues[2]
    if (startText.isEmpty() && endText.isEmpty()) return null
    if (fileSize == 0L) return null

    if (startText.isEmpty()) {
        val suffixLength = endText.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (fileSize - suffixLength).coerceAtLeast(0)..<fileSize
    }

    val start = startText.toLongOrNull() ?: return null
    val requestedEnd = if (endText.isEmpty()) fileSize - 1 else endText.toLongOrNull() ?: return null
    if (start >= fileSize || requestedEnd < start) return null
    return start..minOf(requestedEnd, fileSize - 1)
}

fun isValidRemoteBasename(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." &&
        '/' !in name && '\\' !in name && name.none { it.code < 0x20 || it.code == 0x7f }

fun remoteCacheName(serverId: String, file: FileNode): String {
    val normalizedPath = "/" + file.path.replace('\\', '/').trimStart('/').replace(Regex("/+"), "/")
    val identity = "$serverId\n$normalizedPath\n${file.size}\n${file.lastModified}"
    val hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120)
        .ifBlank { "remote-file" }
    return "${hash.take(24)}_$safeName"
}

// ------------------------------------------------------------------
// URL redaction for logging
// ------------------------------------------------------------------

/**
 * Strip `userinfo@` from a URL so it can be safely written to logs.
 *
 * Example: `webdav://user:pass@host/path` -> `webdav://host/path`.
 *
 * Use this whenever a stream/playback URL is logged. StreamProxy URLs
 * (`http://127.0.0.1:port/stream/<id>`) contain no credentials and pass
 * through unchanged.
 */
fun redactUrl(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val afterScheme = schemeEnd + 3
    val hostStart = url.indexOf('@', afterScheme)
    return if (hostStart >= 0 && hostStart < url.indexOf('/', afterScheme).let { if (it < 0) url.length else it }) {
        url.substring(0, afterScheme) + url.substring(hostStart + 1)
    } else {
        url
    }
}
