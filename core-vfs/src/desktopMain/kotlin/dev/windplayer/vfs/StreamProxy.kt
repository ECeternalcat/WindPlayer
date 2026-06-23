package dev.windplayer.vfs

import java.util.logging.Logger

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

private val LOG = Logger.getLogger("dev.windplayer.vfs.StreamProxy")

class StreamProxy {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int = server.address.port
    private val sessions = mutableMapOf<String, StreamSession>()

    init {
        server.createContext("/stream", this::handleRequest)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        LOG.info("HTTP proxy started on 127.0.0.1:$port")
    }

    @Synchronized
    fun createStreamUrl(config: ServerConfig, filePath: String): String {
        val id = UUID.randomUUID().toString().take(8)
        val normalized = filePath.replace(Regex("/+"), "/")
        sessions[id] = StreamSession(config, normalized)
        return "http://127.0.0.1:$port/stream/$id"
    }

    @Synchronized
    fun closeSession(id: String) {
        sessions.remove(id)?.close()
    }

    private fun handleRequest(exchange: HttpExchange) {
        val id = exchange.requestURI.path.removePrefix("/stream/")
        val session: StreamSession?
        synchronized(this) { session = sessions[id] }
        if (session == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }

        val method = exchange.requestMethod
        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        LOG.info("$method ${exchange.requestURI} Range=$rangeHeader")

        try {
            val fileSize = session.open()
            if (fileSize <= 0) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                return
            }

            if (method == "HEAD") {
                exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                exchange.responseHeaders.set("Content-Length", fileSize.toString())
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return
            }

            var start = 0L
            var end = fileSize - 1

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val parts = rangeHeader.removePrefix("bytes=").split("-")
                start = parts[0].toLongOrNull() ?: 0
                if (parts.size > 1 && parts[1].isNotEmpty()) {
                    end = parts[1].toLongOrNull() ?: (fileSize - 1)
                }
                if (start > end || start >= fileSize) {
                    exchange.responseHeaders.set("Content-Range", "bytes */$fileSize")
                    exchange.sendResponseHeaders(416, -1)
                    exchange.close()
                    return
                }
                end = minOf(end, fileSize - 1)
            }

            val contentLength = end - start + 1
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("Content-Length", contentLength.toString())
            exchange.responseHeaders.set("Connection", "close")

            if (start > 0 || end < fileSize - 1) {
                exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$fileSize")
                exchange.sendResponseHeaders(206, contentLength)
                LOG.info("206 bytes $start-$end/$fileSize ($contentLength bytes)")
            } else {
                exchange.sendResponseHeaders(200, contentLength)
                LOG.info("200 $contentLength bytes")
            }

            val buf = ByteArray(64 * 1024)
            var offset = start
            var totalSent = 0L
            val out = exchange.responseBody
            while (offset <= end) {
                val toRead = minOf(buf.size.toLong(), end - offset + 1).toInt()
                val n = session.read(offset, buf, toRead)
                if (n <= 0) {
                    LOG.info("read returned $n at offset $offset")
                    break
                }
                out.write(buf, 0, n)
                offset += n
                totalSent += n
            }
            out.flush()
            LOG.info("sent $totalSent bytes total")
        } catch (_: java.io.IOException) {
        } catch (e: Exception) {
            LOG.info("stream error: ${e.message}")
        } finally {
            exchange.close()
        }
    }

    fun stop() {
        synchronized(this) {
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
        server.stop(1)
    }

    private class StreamSession(
        private val config: ServerConfig,
        private val filePath: String
    ) {
        private var ssh: SSHClient? = null
        private var sftp: SFTPClient? = null
        private var remoteFile: RemoteFile? = null
        private var fileSize: Long = 0

        @Synchronized
        fun open(): Long {
            if (remoteFile != null) return fileSize
            val client = SSHClient()
            client.addHostKeyVerifier(KnownHostsManager.verifier)
            client.connect(config.bareHost, config.defaultPort())
            client.authPassword(config.username, config.password)
            val sftpClient = client.newSFTPClient()
            val file = sftpClient.open(filePath)
            fileSize = file.fetchAttributes().size
            ssh = client
            sftp = sftpClient
            remoteFile = file
            LOG.info("opened $filePath ($fileSize bytes)")
            return fileSize
        }

        @Synchronized
        fun read(offset: Long, buf: ByteArray, len: Int): Int {
            val file = remoteFile ?: return -1
            return file.read(offset, buf, 0, len)
        }

        fun close() {
            try { remoteFile?.close() } catch (_: Exception) {}
            try { sftp?.close() } catch (_: Exception) {}
            try { ssh?.disconnect() } catch (_: Exception) {}
            ssh = null; sftp = null; remoteFile = null
        }
    }
}
