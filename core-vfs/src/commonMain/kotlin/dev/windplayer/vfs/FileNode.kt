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

fun FileNode.videoBaseName(): String {
    return name.substringBeforeLast('.')
}

fun findSidecarSubtitles(videoNode: FileNode, allFiles: List<FileNode>): List<FileNode> {
    val baseName = videoNode.videoBaseName()
    return allFiles.filter { file ->
        !file.isDirectory && file.isSubtitle() &&
            file.name.lowercase().startsWith("$baseName.".lowercase())
    }
}
