package dev.windplayer.vfs

import java.util.logging.Logger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.FileOutputStream

private val LOG = Logger.getLogger("dev.windplayer.vfs.FtpClient")

class FtpClient : VfsClient {

    override val protocol = VfsProtocol.FTP

    private var ftpClient: FTPClient? = null
    private var config: ServerConfig? = null

    override suspend fun connect(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            this@FtpClient.config = config
            // H18: prefer FTPS (FTP over TLS) when the user opts in. FTPSClient
            // extends FTPClient, so the rest of the API is identical. We use
            // *explicit* FTPS (AUTH TLS on the standard control port 21/990),
            // which is what ~all modern FTPS servers support.
            val client: FTPClient = if (config.useTls) {
                FTPSClient("TLS", false).also {
                    LOG.info("using FTPS (explicit TLS) for ${config.bareHost}")
                }
            } else {
                FTPClient()
            }
            client.connect(config.bareHost, config.defaultPort())
            val loggedIn = if (config.username.isNotBlank()) {
                client.login(config.username, config.password)
            } else {
                client.login("anonymous", "")
            }
            if (!loggedIn) {
                LOG.warning("login failed")
                client.disconnect()
                return@withContext false
            }
            // After login, FTPS must execute PROT P to protect the data channel
            // (the control channel is already encrypted by AUTH TLS). For plain
            // FTP this is a no-op / would throw, so guard with a type check.
            if (client is FTPSClient) {
                try {
                    client.execPBSZ(0)
                    client.execPROT("P")
                } catch (e: Exception) {
                    LOG.warning("FTPS data channel protection (PROT P) failed — data channel may be cleartext: ${e.message}")
                }
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            ftpClient = client
            LOG.info("connected to ${config.bareHost}:${config.defaultPort()} (TLS=${config.useTls})")
            true
        } catch (e: Exception) {
            LOG.warning("connect failed: ${e.message}")
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
            LOG.warning("listDirectory failed: ${e.message}")
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
        LOG.info("downloaded $remotePath -> $localPath")
    }

    override suspend fun deleteFile(remotePath: String): Boolean {
        return false
    }

    override suspend fun renameFile(oldPath: String, newPath: String): Boolean {
        return false
    }

    override suspend fun moveFile(oldPath: String, newPath: String): Boolean {
        return false
    }

    override fun isConnected(): Boolean {
        return ftpClient?.isConnected == true
    }
}
