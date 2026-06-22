package dev.windplayer.vfs

interface VfsClient {
    val protocol: VfsProtocol
    suspend fun connect(config: ServerConfig): Boolean
    suspend fun disconnect()
    suspend fun listDirectory(path: String): List<FileNode>
    suspend fun resolveUrl(path: String): String
    suspend fun downloadFile(remotePath: String, localPath: String)
    fun isConnected(): Boolean
}

data class PlaybackParams(
    val streamUrl: String,
    val subtitleFiles: List<String>,
    val externalAudioUrls: List<String> = emptyList(),
    val mpvOptions: Map<String, String> = emptyMap(),
    val serverId: String? = null,
    val dirPath: String? = null,
    val isLocal: Boolean = false,
    val directoryVideoPaths: List<String> = emptyList(),
    val currentFileIndex: Int = -1,
    val resumePosition: Double = 0.0,
    val filePath: String = "",
    val streamSessionIds: List<String> = emptyList()
)
