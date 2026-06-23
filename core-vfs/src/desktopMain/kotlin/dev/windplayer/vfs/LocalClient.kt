package dev.windplayer.vfs

import java.util.logging.Logger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val LOG = Logger.getLogger("dev.windplayer.vfs.LocalClient")

class LocalClient : VfsClient {

    override val protocol = VfsProtocol.LOCAL

    override suspend fun connect(config: ServerConfig): Boolean = true

    override suspend fun disconnect() {}

    override suspend fun listDirectory(path: String): List<FileNode> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            LOG.info("path does not exist or is not a directory: $path")
            return@withContext emptyList()
        }
        dir.listFiles()?.filter { it.name != "." && it.name != ".." }?.map { file ->
            FileNode(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified(),
                protocol = VfsProtocol.LOCAL
            )
        }?.sortedWith(FileNodeComparator)
            ?: emptyList()
    }

    override suspend fun resolveUrl(path: String): String = path

    override suspend fun downloadFile(remotePath: String, localPath: String): Unit = withContext(Dispatchers.IO) {
        File(remotePath).copyTo(File(localPath), overwrite = true)
    }

    override fun isConnected(): Boolean = true

    companion object {
        fun listRoots(): List<FileNode> {
            return File.listRoots().map { root ->
                FileNode(
                    name = root.absolutePath,
                    path = root.absolutePath,
                    isDirectory = true,
                    protocol = VfsProtocol.LOCAL
                )
            }
        }

        fun homeDirectory(): String = System.getProperty("user.home")
    }
}
