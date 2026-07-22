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
    // H4: keep a handle so stop() can shut the pool down. HttpServer.stop()
    // does NOT shut down the associated Executor; the cached-pool threads are
    // non-daemon and would keep the JVM alive on exit.
    private val executor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "StreamProxy-worker").apply { isDaemon = true }
    }

    init {
        server.createContext("/stream", this::handleRequest)
        server.executor = executor
        server.start()
        LOG.info("HTTP proxy started on 127.0.0.1:$port")
    }

    @Synchronized
    fun createStreamUrl(config: ServerConfig, filePath: String): String {
        // H17: full UUID = 122 bits of entropy. The session id is the ONLY
        // authentication for the local HTTP endpoint, so the previously-used
        // `take(8)` (32 bits) was brute-forceable by any local process.
        val id = UUID.randomUUID().toString()
        val normalized = filePath.replace(Regex("/+"), "/")
        sessions[id] = StreamSession(config, normalized)
        return "http://127.0.0.1:$port/stream/$id"
    }

    @Synchronized
    fun closeSession(id: String) {
        sessions.remove(id)?.close()
    }

    private fun handleRequest(exchange: HttpExchange) {
        // SEC-6: reject cross-origin requests. The session UUID authenticates
        // the request, but a DNS-rebinding attack or embedded browser could
        // still issue a same-host request. mpv omits the Host header entirely
        // (or sends 127.0.0.1:$port), so we only reject clearly foreign hosts.
        val hostHeader = exchange.requestHeaders.getFirst("Host")
        if (hostHeader != null && hostHeader != "127.0.0.1:$port" && hostHeader != "localhost:$port" &&
            hostHeader != "127.0.0.1" && hostHeader != "localhost") {
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
            return
        }

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
            if (method != "GET" && method != "HEAD") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return
            }
            val fileSize = session.open()
            if (fileSize < 0) {
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

            val range = if (rangeHeader == null) 0L..(fileSize - 1) else parseByteRange(rangeHeader, fileSize)
            if (range == null) {
                    exchange.responseHeaders.set("Content-Range", "bytes */$fileSize")
                    exchange.sendResponseHeaders(416, -1)
                    exchange.close()
                    return
            }
            val start = range.first
            val end = range.last

            val contentLength = end - start + 1
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("Content-Length", contentLength.toString())
            exchange.responseHeaders.set("Connection", "close")

            if (rangeHeader != null) {
                exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$fileSize")
                exchange.sendResponseHeaders(206, contentLength)
                LOG.info("206 bytes $start-$end/$fileSize ($contentLength bytes)")
            } else {
                exchange.sendResponseHeaders(200, if (contentLength == 0L) -1 else contentLength)
                LOG.info("200 $contentLength bytes")
            }

            val buf = ByteArray(1024 * 1024)
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
        executor.shutdownNow()
    }

    private class StreamSession(
        private val config: ServerConfig,
        private val filePath: String
    ) {
        private var ssh: SSHClient? = null
        private var sftp: SFTPClient? = null
        private var remoteFile: RemoteFile? = null
        private var fileSize: Long = 0
        // H2: once closed, refuse to re-open. Without this, a handler thread
        // that looked up the session before closeSession() can re-enter open()
        // after close() nulled remoteFile, opening a fresh SSH connection that
        // nobody will ever close — a connection leak per seek/switch race.
        @Volatile
        private var closed = false

        @Synchronized
        fun open(): Long {
            if (closed) return -1
            if (remoteFile != null) return fileSize
            val client = SSHClient(createSshjConfig())
            var sftpClient: SFTPClient? = null
            var file: RemoteFile? = null
            try {
                client.addHostKeyVerifier(KnownHostsManager.verifier)
                client.connect(config.bareHost, config.defaultPort())
                client.authPassword(config.username, config.password)
                sftpClient = client.newSFTPClient()
                file = sftpClient.open(filePath)
                fileSize = file.fetchAttributes().size
                ssh = client
                sftp = sftpClient
                remoteFile = file
            } catch (e: Exception) {
                try { file?.close() } catch (_: Exception) {}
                try { sftpClient?.close() } catch (_: Exception) {}
                try { client.disconnect() } catch (_: Exception) {}
                throw e
            }
            LOG.info("opened $filePath ($fileSize bytes)")
            return fileSize
        }

        @Synchronized
        fun read(offset: Long, buf: ByteArray, len: Int): Int {
            val file = remoteFile ?: return -1
            return file.read(offset, buf, 0, len)
        }

        // Must be @Synchronized to race against in-flight open()/read() calls.
        // Without it, close() can null out remoteFile/sftp/ssh while a handler
        // thread is inside read() -> use-after-free / IllegalStateException.
        @Synchronized
        fun close() {
            closed = true
            try { remoteFile?.close() } catch (_: Exception) {}
            try { sftp?.close() } catch (_: Exception) {}
            try { ssh?.disconnect() } catch (_: Exception) {}
            ssh = null; sftp = null; remoteFile = null
        }
    }
}
