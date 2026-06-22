package dev.windplayer

import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.VfsProtocol
import dev.windplayer.vfs.VfsClient

object MobileVfsManager {

    suspend fun listDirectory(server: ServerConfig, path: String): List<FileNode> {
        val client = createClient(server)
        try {
            client.connect(server)
            val files = client.listDirectory(path)
            return files.sortedWith(
                compareBy<FileNode> { !it.isDirectory }.thenBy { it.name.lowercase() }
            )
        } finally {
            try { client.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun resolveUrl(server: ServerConfig, path: String): String {
        val client = createClient(server)
        client.connect(server)
        try {
            return client.resolveUrl(path)
        } finally {
            // Don't disconnect — mpv needs the connection alive
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
