package dev.windplayer

import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.FileNodeComparator
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.VfsProtocol
import dev.windplayer.vfs.VfsClient

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

    private fun createClient(server: ServerConfig): VfsClient {
        return when (server.protocol) {
            VfsProtocol.SFTP -> dev.windplayer.vfs.SftpClient()
            VfsProtocol.WEBDAV -> dev.windplayer.vfs.WebdavClient()
            VfsProtocol.FTP -> dev.windplayer.vfs.FtpClient()
            VfsProtocol.LOCAL -> throw IllegalArgumentException("Use SAF for local files")
        }
    }
}
