package dev.windplayer.vfs

import java.io.File
import java.util.logging.Logger

private val LOG = Logger.getLogger("dev.windplayer.vfs.FilePermissions")

/**
 * Best-effort chmod to owner-only (0600). Silently no-ops on non-POSIX FS
 * (Windows) where the JDK does not honor PosixFilePermissions.
 *
 * Used for any local file that may contain credentials or host metadata:
 * `known_hosts`, `servers.properties`, etc. Centralised so new sensitive
 * files don't have to re-implement the same try/catch.
 */
internal fun restrictFilePermissions(file: File) {
    try {
        val path = file.toPath()
        // Only attempt on POSIX FS; throws on Windows which we swallow.
        java.nio.file.Files.readAttributes(
            path,
            java.nio.file.attribute.PosixFileAttributes::class.java
        )
        java.nio.file.Files.setPosixFilePermissions(
            path,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
        )
    } catch (_: Exception) {
        // Non-POSIX filesystem or unsupported — no-op.
    }
}
