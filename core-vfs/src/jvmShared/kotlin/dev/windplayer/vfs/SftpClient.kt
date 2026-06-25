package dev.windplayer.vfs

import java.util.logging.Logger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.RemoteResourceInfo

private val LOG = Logger.getLogger("dev.windplayer.vfs.SftpClient")

private val X25519_KEX = setOf("curve25519-sha256", "curve25519-sha256@libssh.org")

/**
 * Android's built-in BouncyCastle provider does not implement the X25519 key
 * agreement algorithm, causing SSHJ to throw:
 *   `no such algorithm: X25519 for provider BC`
 *
 * Build an SSHJ [Config] that drops the X25519 KEX factories on Android while
 * keeping the full desktop algorithm set unchanged. ECDH/DH-based KEX remain
 * available and are widely supported by SFTP servers.
 */
internal fun createSshjConfig(): Config {
    val config = DefaultConfig()
    if (isAndroidRuntime()) {
        config.keyExchangeFactories = config.keyExchangeFactories
            .filter { it.name !in X25519_KEX }
    }
    return config
}

class SftpClient : VfsClient {

    override val protocol = VfsProtocol.SFTP

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    private var config: ServerConfig? = null

    override suspend fun connect(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            this@SftpClient.config = config
            val client = SSHClient(createSshjConfig())
            client.addHostKeyVerifier(KnownHostsManager.verifier)
            client.connect(config.bareHost, config.defaultPort())
            if (config.username.isNotBlank()) {
                client.authPassword(config.username, config.password)
            }
            sftpClient = client.newSFTPClient()
            sshClient = client
            LOG.info("connected to ${config.bareHost}:${config.defaultPort()}")
            true
        } catch (e: Exception) {
            LOG.warning("connect failed: ${e.message}")
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
            LOG.warning("listDirectory failed: ${e.message}")
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
        LOG.info("downloaded $remotePath -> $localPath")
    }

    override suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val sftp = sftpClient ?: return@withContext false
            sftp.rm(remotePath)
            LOG.info("deleted $remotePath")
            true
        } catch (e: Exception) {
            LOG.warning("delete failed: ${e.message}")
            false
        }
    }

    override suspend fun renameFile(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val sftp = sftpClient ?: return@withContext false
            sftp.rename(oldPath, newPath)
            LOG.info("renamed $oldPath -> $newPath")
            true
        } catch (e: Exception) {
            LOG.warning("rename failed: ${e.message}")
            false
        }
    }

    override suspend fun moveFile(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        // SFTP rename works as a move when source and dest are on the same filesystem.
        renameFile(oldPath, newPath)
    }

    override fun isConnected(): Boolean {
        return sshClient?.isConnected == true && sftpClient != null
    }
}
