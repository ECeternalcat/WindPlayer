package dev.windplayer.vfs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID

class VfsManager {

    private val clients = mutableMapOf<String, VfsClient>()
    private val _servers = mutableListOf<ServerConfig>()
    val servers: List<ServerConfig> get() = _servers.toList()

    private val localClient = LocalClient()
    private val streamProxy = StreamProxy()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val configDir = File(System.getProperty("user.home"), ".windplayer")
    private val configFile = File(configDir, "servers.properties")
    private val cacheDir = File(configDir, "cache")

    init {
        configDir.mkdirs()
        cacheDir.mkdirs()
        loadConfig()
    }

    fun addServer(config: ServerConfig) {
        val newConfig = if (config.id.isBlank()) config.copy(id = UUID.randomUUID().toString()) else config
        _servers.removeIf { it.id == newConfig.id }
        _servers.add(newConfig)
        saveConfig()
    }

    /**
     * Remove a saved server and disconnect any active session asynchronously.
     * The disconnect runs on IO dispatcher without blocking the caller.
     */
    fun removeServer(id: String) {
        _servers.removeIf { it.id == id }
        clients.remove(id)?.let { client ->
            ioScope.launch { runCatching { client.disconnect() } }
        }
        saveConfig()
    }

    fun getServer(id: String): ServerConfig? = _servers.find { it.id == id }

    suspend fun connectServer(serverId: String): Result<Unit> {
        val config = getServer(serverId) ?: return Result.failure(IllegalArgumentException("Server not found"))
        val client = createClient(config.protocol)
        val connected = client.connect(config)
        return if (connected) {
            clients[serverId] = client
            println("[VfsManager] connected to ${config.name}")
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException("Failed to connect to ${config.name}"))
        }
    }

    suspend fun disconnectServer(serverId: String) {
        clients[serverId]?.disconnect()
        clients.remove(serverId)
    }

    fun isServerConnected(serverId: String): Boolean {
        return clients[serverId]?.isConnected() == true
    }

    suspend fun listServerDirectory(serverId: String, path: String): Result<List<FileNode>> {
        val client = clients[serverId]
            ?: return Result.failure(IllegalStateException("Not connected"))
        return try {
            val files = client.listDirectory(path)
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listLocalDirectory(path: String): List<FileNode> {
        return localClient.listDirectory(path)
    }

    fun listLocalRoots(): List<FileNode> = LocalClient.listRoots()

    fun homeDirectory(): String = LocalClient.homeDirectory()

    fun deleteLocalFile(path: String): Boolean {
        return try { File(path).delete() } catch (_: Exception) { false }
    }

    fun renameLocalFile(oldPath: String, newName: String): Boolean {
        return try {
            val oldFile = File(oldPath)
            val newFile = File(oldFile.parentFile, newName)
            oldFile.renameTo(newFile)
        } catch (_: Exception) { false }
    }

    suspend fun preparePlayback(
        serverId: String,
        videoNode: FileNode
    ): Result<PlaybackParams> = withContext(Dispatchers.IO) {
        try {
            val config = getServer(serverId)
                ?: return@withContext Result.failure(IllegalArgumentException("Server not found"))
            val client = clients[serverId]
                ?: return@withContext Result.failure(IllegalStateException("Not connected"))

            val sessionIds = mutableListOf<String>()

            val streamUrl = when (config.protocol) {
                VfsProtocol.SFTP -> streamProxy.createStreamUrl(config, videoNode.path).also {
                    sessionIds.add(it.substringAfterLast('/'))
                }
                else -> client.resolveUrl(videoNode.path)
            }

            val dirPath = videoNode.path.substringBeforeLast('/')
            val siblings = try { client.listDirectory(dirPath) } catch (_: Exception) { emptyList() }
            val matched = matchExternalTracks(videoNode, siblings)

            val subtitleFiles = mutableListOf<String>()
            val externalAudioUrls = mutableListOf<String>()
            var needsDualStream = false

            for (track in matched) {
                when (track.type) {
                    MatchedTrackType.SUBTITLE -> {
                        val localPath = downloadSubtitle(client, track.file)
                        if (localPath != null) {
                            subtitleFiles.add(localPath)
                            println("[VfsManager] matched subtitle: ${track.file.name}")
                        }
                    }
                    MatchedTrackType.AUDIO -> {
                        val audioUrl = when (config.protocol) {
                            VfsProtocol.SFTP -> streamProxy.createStreamUrl(config, track.file.path).also {
                                sessionIds.add(it.substringAfterLast('/'))
                            }
                            else -> client.resolveUrl(track.file.path)
                        }
                        externalAudioUrls.add(audioUrl)
                        needsDualStream = true
                        println("[VfsManager] matched audio: ${track.file.name}")
                    }
                }
            }

            val mpvOptions = if (needsDualStream) mapOf(
                "cache" to "yes",
                "demuxer-max-bytes" to "500M",
                "demuxer-max-back-bytes" to "100M"
            ) else emptyMap()

            println("[VfsManager] playback stream: $streamUrl (sessions: $sessionIds)")
            Result.success(PlaybackParams(
                streamUrl = streamUrl,
                subtitleFiles = subtitleFiles,
                externalAudioUrls = externalAudioUrls,
                mpvOptions = mpvOptions,
                serverId = serverId,
                dirPath = dirPath,
                filePath = videoNode.path,
                streamSessionIds = sessionIds.toList()
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Release all resources (StreamProxy sessions) associated with a previously
     * prepared playback. Call when playback stops or switches to another file.
     */
    fun releasePlayback(params: PlaybackParams) {
        for (id in params.streamSessionIds) {
            runCatching { streamProxy.closeSession(id) }
        }
    }

    /**
     * Shutdown all VFS resources. Call once on application exit.
     */
    fun shutdown() {
        for ((_, client) in clients.toList()) {
            ioScope.launch { runCatching { client.disconnect() } }
        }
        clients.clear()
        streamProxy.stop()
    }

    private suspend fun downloadSubtitle(client: VfsClient, file: FileNode): String? {
        return try {
            val localFile = File(cacheDir, file.name)
            if (!localFile.exists()) {
                client.downloadFile(file.path, localFile.absolutePath)
            }
            localFile.absolutePath
        } catch (e: Exception) {
            println("[VfsManager] subtitle download failed: ${e.message}")
            null
        }
    }

    suspend fun prepareLocalPlayback(videoNode: FileNode): PlaybackParams {
        val dir = videoNode.path.substringBeforeLast(File.separator).ifBlank { videoNode.path.substringBeforeLast('/') }
        val siblings = localClient.listDirectory(dir)
        val matched = matchExternalTracks(videoNode, siblings)

        val subtitleFiles = matched
            .filter { it.type == MatchedTrackType.SUBTITLE }
            .map { it.file.path }
        val externalAudioUrls = matched
            .filter { it.type == MatchedTrackType.AUDIO }
            .map { it.file.path }

        return PlaybackParams(
            streamUrl = videoNode.path,
            subtitleFiles = subtitleFiles,
            externalAudioUrls = externalAudioUrls,
            dirPath = dir,
            isLocal = true,
            filePath = videoNode.path
        )
    }

    private fun createClient(protocol: VfsProtocol): VfsClient = when (protocol) {
        VfsProtocol.SFTP -> SftpClient()
        VfsProtocol.WEBDAV -> WebdavClient()
        VfsProtocol.FTP -> FtpClient()
        VfsProtocol.LOCAL -> LocalClient()
    }

    private fun saveConfig() {
        try {
            val props = Properties()
            props.setProperty("server.count", _servers.size.toString())
            _servers.forEachIndexed { index, server ->
                props.setProperty("server.$index.id", server.id)
                props.setProperty("server.$index.name", server.name)
                props.setProperty("server.$index.protocol", server.protocol.name)
                props.setProperty("server.$index.host", server.host)
                props.setProperty("server.$index.port", server.port.toString())
                props.setProperty("server.$index.username", server.username)
                props.setProperty("server.$index.password", server.password)
                props.setProperty("server.$index.basePath", server.basePath)
            }
            FileOutputStream(configFile).use { props.store(it, "WindPlayer Server Configurations") }
        } catch (e: Exception) {
            println("[VfsManager] saveConfig failed: ${e.message}")
        }
    }

    private fun loadConfig() {
        try {
            if (!configFile.exists()) return
            val props = Properties()
            FileInputStream(configFile).use { props.load(it) }
            val count = props.getProperty("server.count", "0").toIntOrNull() ?: 0
            _servers.clear()
            for (i in 0 until count) {
                val id = props.getProperty("server.$i.id") ?: continue
                val name = props.getProperty("server.$i.name") ?: continue
                val protocolName = props.getProperty("server.$i.protocol") ?: continue
                val protocol = try { VfsProtocol.valueOf(protocolName) } catch (_: Exception) { continue }
                val host = props.getProperty("server.$i.host", "")
                val port = props.getProperty("server.$i.port", "0").toIntOrNull() ?: 0
                val username = props.getProperty("server.$i.username", "")
                val password = props.getProperty("server.$i.password", "")
                val basePath = props.getProperty("server.$i.basePath", "/")
                _servers.add(ServerConfig(id, name, protocol, host, port, username, password, basePath))
            }
            println("[VfsManager] loaded ${_servers.size} server(s)")
        } catch (e: Exception) {
            println("[VfsManager] loadConfig failed: ${e.message}")
        }
    }
}
