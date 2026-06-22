package dev.windplayer.vfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.transport.verification.PromiscuousVerifier

class SftpClient : VfsClient {

    override val protocol = VfsProtocol.SFTP

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    private var config: ServerConfig? = null

    override suspend fun connect(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            this@SftpClient.config = config
            val client = SSHClient()
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connect(config.bareHost, config.defaultPort())
            if (config.username.isNotBlank()) {
                client.authPassword(config.username, config.password)
            }
            sftpClient = client.newSFTPClient()
            sshClient = client
            println("[SftpClient] connected to ${config.bareHost}:${config.defaultPort()}")
            true
        } catch (e: Exception) {
            println("[SftpClient] connect failed: ${e.message}")
            disconnect()
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            sftpClient?.close()
            sshClient?.disconnect()
        } catch (_: Exception) {}
        sftpClient = null
        sshClient = null
    }

    override suspend fun listDirectory(path: String): List<FileNode> = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw IllegalStateException("Not connected")
        val cfg = config ?: throw IllegalStateException("No config")
        try {
            sftp.ls(path).map { info ->
                FileNode(
                    name = info.name,
                    path = if (path.endsWith("/")) "$path${info.name}" else "$path/${info.name}",
                    isDirectory = info.isDirectory,
                    size = if (info.isDirectory) 0 else info.attributes.size,
                    lastModified = info.attributes.mtime.toLong() * 1000L,
                    protocol = VfsProtocol.SFTP
                )
            }.filter { it.name != "." && it.name != ".." }
                .sortedWith(FileNodeComparator)
        } catch (e: Exception) {
            println("[SftpClient] listDirectory failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun resolveUrl(path: String): String {
        val cfg = config ?: throw IllegalStateException("No config")
        return buildUrlWithCredentials(
            scheme = "sftp",
            username = cfg.username,
            password = cfg.password,
            host = cfg.bareHost,
            port = cfg.defaultPort(),
            defaultPort = 22,
            path = path
        )
    }

    override suspend fun downloadFile(remotePath: String, localPath: String) = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw IllegalStateException("Not connected")
        sftp.get(remotePath, localPath)
        println("[SftpClient] downloaded $remotePath -> $localPath")
    }

    override fun isConnected(): Boolean {
        return sshClient?.isConnected == true && sftpClient != null
    }
}
