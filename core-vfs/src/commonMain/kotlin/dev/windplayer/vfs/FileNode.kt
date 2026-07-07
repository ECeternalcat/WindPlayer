package dev.windplayer.vfs

data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val protocol: VfsProtocol
)

enum class VfsProtocol {
    LOCAL, SFTP, WEBDAV, FTP
}

val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm",
    "ts", "m2ts", "mpg", "mpeg", "m4v", "3gp", "ogv", "rmvb", "rm"
)

val SUBTITLE_EXTENSIONS = setOf(
    "ass", "srt", "ssa", "sub", "vtt", "idx", "sup"
)

fun FileNode.isVideo(): Boolean {
    if (isDirectory) return false
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTENSIONS
}

fun FileNode.isSubtitle(): Boolean {
    if (isDirectory) return false
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in SUBTITLE_EXTENSIONS
}

/**
 * Default [FileNode] ordering used by every VFS client and SAF listing.
 *
 * Directories first (descending `isDirectory`), then alphabetical by lowercased name.
 *
 * ARCH-3: lives in `commonMain` (pure Kotlin, no JVM dependency) so commonTest
 * can reference it without leaking the jvmShared source set. The previous
 * placement in `jvmShared/VfsUtils.kt` worked only because both declared
 * targets (desktop, android) inherit jvmShared; a non-JVM target (iOS/JS)
 * would break the commonTest compilation.
 */
val FileNodeComparator: Comparator<FileNode> =
    compareByDescending<FileNode> { it.isDirectory }.thenBy { it.name.lowercase() }
