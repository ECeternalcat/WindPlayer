package dev.windplayer.vfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.FileOutputStream

class FtpClient : VfsClient {

    override val protocol = VfsProtocol.FTP

    private var ftpClient: FTPClient? = null
    private var config: ServerConfig? = null

    override suspend fun connect(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            this@FtpClient.config = config
            val client = FTPClient()
            client.connect(config.bareHost, config.defaultPort())
            val loggedIn = if (config.username.isNotBlank()) {
                client.login(config.username, config.password)
            } else {
                client.login("anonymous", "")
            }
            if (!loggedIn) {
                println("[FtpClient] login failed")
                client.disconnect()
                return@withContext false
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            ftpClient = client
            println("[FtpClient] connected to ${config.bareHost}:${config.defaultPort()}")
            true
        } catch (e: Exception) {
            println("[FtpClient] connect failed: ${e.message}")
            disconnect()
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            ftpClient?.logout()
            ftpClient?.disconnect()
        } catch (_: Exception) {}
        ftpClient = null
    }

    override suspend fun listDirectory(path: String): List<FileNode> = withContext(Dispatchers.IO) {
        val client = ftpClient ?: throw IllegalStateException("Not connected")
        try {
            val files = client.listFiles(path)
            files.filter { it.name != "." && it.name != ".." }.map { file: FTPFile ->
                FileNode(
                    name = file.name,
                    path = if (path.endsWith("/")) "$path${file.name}" else "$path/${file.name}",
                    isDirectory = file.isDirectory,
                    size = if (file.isDirectory) 0 else file.size,
                    lastModified = file.timestamp?.timeInMillis ?: 0,
                    protocol = VfsProtocol.FTP
                )
            }.sortedWith(FileNodeComparator)
        } catch (e: Exception) {
            println("[FtpClient] listDirectory failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun resolveUrl(path: String): String {
        val cfg = config ?: throw IllegalStateException("No config")
        return buildUrlWithCredentials(
            scheme = "ftp",
            username = cfg.username,
            password = cfg.password,
            host = cfg.bareHost,
            port = cfg.defaultPort(),
            defaultPort = 21,
            path = path
        )
    }

    override suspend fun downloadFile(remotePath: String, localPath: String) = withContext(Dispatchers.IO) {
        val client = ftpClient ?: throw IllegalStateException("Not connected")
        val outputStream = FileOutputStream(localPath)
        val success = client.retrieveFile(remotePath, outputStream)
        outputStream.close()
        if (!success) {
            File(localPath).delete()
            throw RuntimeException("FTP download failed for $remotePath")
        }
        println("[FtpClient] downloaded $remotePath -> $localPath")
    }

    override fun isConnected(): Boolean {
        return ftpClient?.isConnected == true
    }
}
