package dev.windplayer

import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.FileNodeComparator
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.VfsProtocol
import dev.windplayer.vfs.VfsClient
import java.io.File
import dev.windplayer.vfs.isValidRemoteBasename
import dev.windplayer.vfs.remoteCacheName

object MobileVfsManager {

    suspend fun listDirectory(server: ServerConfig, path: String): List<FileNode> {
        val client = createClient(server)
        try {
            client.connect(server)
            return client.listDirectory(path).sortedWith(FileNodeComparator)
        } finally {
            try { client.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Build the stream URL mpv will open. Connects first to populate the client's
     * internal `config` (needed by `resolveUrl`), then disconnects — mpv opens its
     * own connection from the returned URL, so we don't need to keep ours alive.
     */
    suspend fun resolveUrl(server: ServerConfig, path: String): String {
        val client = createClient(server)
        client.connect(server)
        return try {
            client.resolveUrl(path)
        } finally {
            try { client.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Download a small auxiliary file (subtitle / cover) from [server] into a
     * cache directory. Returns the local [File] on success, null on failure.
     *
     * Uses a separate short-lived connection so it doesn't interfere with an
     * in-flight StreamProxy session for the main video.
     */
    suspend fun downloadAuxFile(server: ServerConfig, file: FileNode, cacheDir: File): File? {
        val serverIdentity = server.id.ifBlank {
            "${server.protocol}:${server.bareHost}:${server.defaultPort()}"
        }
        val localFile = File(cacheDir, remoteCacheName(serverIdentity, file))
        // M22: verify cached file isn't a zero-byte leftover from an interrupted
        // download. Without this, a corrupted cache entry permanently prevents
        // the subtitle from loading.
        if (localFile.exists() && localFile.length() > 0) return localFile
        val client = createClient(server)
        return try {
            cacheDir.mkdirs()
            client.connect(server)
            client.downloadFile(file.path, localFile.absolutePath)
            localFile
        } catch (_: Exception) {
            try { if (localFile.exists()) localFile.delete() } catch (_: Exception) {}
            null
        } finally {
            try { client.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Reduce an untrusted remote filename to a safe single-component cache name.
     * Strips path separators, `..`, and any character outside [A-Za-z0-9._-].
     */
    private fun sanitizeCacheName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .removePrefix("..").removeSuffix("..")
        val safe = cleaned.ifBlank { "aux_${name.hashCode().toString(16)}" }
        return File(safe).name
    }

    /**
     * Delete a file on a remote server.
     */
    suspend fun deleteRemoteFile(server: ServerConfig, path: String): Boolean {
        val client = createClient(server)
        return try {
            client.connect(server)
            client.deleteFile(path)
        } catch (_: Exception) {
            false
        } finally {
            try { client.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun renameRemoteFile(server: ServerConfig, oldPath: String, newName: String): Boolean {
        if (!isValidRemoteBasename(newName)) return false
        val client = createClient(server)
        return try {
            client.connect(server)
            val dir = oldPath.substringBeforeLast('/').ifBlank { "/" }
            val newPath = if (dir.endsWith("/")) "$dir$newName" else "$dir/$newName"
            client.renameFile(oldPath, newPath)
        } catch (_: Exception) {
            false
        } finally {
            try { client.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun moveRemoteFile(server: ServerConfig, oldPath: String, destDir: String): Boolean {
        val client = createClient(server)
        return try {
            client.connect(server)
            val fileName = oldPath.substringAfterLast('/')
            val cleanDest = destDir.trimEnd('/')
            val newPath = "$cleanDest/$fileName"
            client.moveFile(oldPath, newPath)
        } catch (_: Exception) {
            false
        } finally {
            try { client.disconnect() } catch (_: Exception) {}
        }
    }

    private fun createClient(server: ServerConfig): VfsClient {
        return when (server.protocol) {
            VfsProtocol.SFTP -> dev.windplayer.vfs.SftpClient()
            VfsProtocol.WEBDAV -> dev.windplayer.vfs.WebdavClient()
            VfsProtocol.FTP -> dev.windplayer.vfs.FtpClient()
            VfsProtocol.LOCAL -> throw IllegalArgumentException("Use SAF for local files")
        }
    }
}
