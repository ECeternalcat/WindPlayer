package dev.windplayer.vfs

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.logging.Logger
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient

private val LOG = Logger.getLogger("dev.windplayer.vfs.StreamProxy")

/**
 * Android counterpart to the desktop StreamProxy.
 *
 * mpv's Android build does not include SFTP/SSH support, so we run a tiny
 * local HTTP server on 127.0.0.1 and translate range requests into SFTP reads.
 *
 * This implementation uses raw sockets instead of `com.sun.net.httpserver`
 * because the latter is not available on Android.
 */
class StreamProxy {

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    private val sessions = mutableMapOf<String, StreamSession>()
    // CON-1: daemon threads so an Activity destroy that races `onDispose`
    // doesn't leave zombie workers holding the process alive. Mirrors the
    // desktop StreamProxy thread factory.
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "StreamProxy-worker").apply { isDaemon = true }
    }
    @Volatile
    private var running = true

    init {
        // CON-1: accept thread must be daemon too.
        Thread({ acceptLoop() }, "StreamProxy-accept").apply { isDaemon = true }.start()
        LOG.info("HTTP proxy started on 127.0.0.1:$port")
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = server.accept()
                executor.execute { handleSocket(socket) }
            } catch (e: SocketException) {
                if (running) LOG.warning("accept error: ${e.message}")
            } catch (e: Exception) {
                if (running) LOG.warning("accept error: ${e.message}")
            }
        }
    }

    @Synchronized
    fun createStreamUrl(config: ServerConfig, filePath: String): String {
        val id = UUID.randomUUID().toString()
        val normalized = filePath.replace(Regex("/++"), "/")
        sessions[id] = StreamSession(config, normalized)
        return "http://127.0.0.1:$port/stream/$id"
    }

    @Synchronized
    fun closeSession(id: String) {
        sessions.remove(id)?.close()
    }

    fun stop() {
        running = false
        executor.shutdownNow()
        synchronized(this) {
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
        try { server.close() } catch (_: Exception) {}
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = s.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    sendError(out, 400, "Bad Request")
                    return
                }
                val method = parts[0]
                val path = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(":")
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }

                // SEC-6: reject cross-origin requests. The session UUID alone
                // authenticates the request, but a DNS-rebinding attack or an
                // embedded WebView could still issue a same-host request.
                // Reject anything whose Host header isn't localhost / 127.0.0.1
                // (or missing, which is what mpv sends).
                val host = headers["host"]
                if (host != null && host != "127.0.0.1:$port" && host != "localhost:$port" &&
                    host != "127.0.0.1" && host != "localhost") {
                    sendError(out, 403, "Forbidden")
                    return
                }

                val id = path.removePrefix("/stream/")
                val session: StreamSession?
                synchronized(this) { session = sessions[id] }

                if (session == null) {
                    sendError(out, 404, "Not Found")
                    return
                }

                val fileSize = session.open()
                if (fileSize <= 0) {
                    sendError(out, 500, "Internal Server Error")
                    return
                }

                if (method == "HEAD") {
                    sendHeaders(out, 200, fileSize, null, null)
                    return
                }

                if (method != "GET") {
                    sendError(out, 405, "Method Not Allowed")
                    return
                }

                val rangeHeader = headers["range"]
                var start = 0L
                var end = fileSize - 1
                var status = 200

                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val rangeSpec = rangeHeader.removePrefix("bytes=")
                    // M2: reject multi-range; handle suffix range bytes=-N.
                    if (rangeSpec.contains(",")) {
                        sendRangeNotSatisfiable(out, fileSize)
                        return
                    }
                    val rangeParts = rangeSpec.split("-")
                    if (rangeParts[0].isEmpty()) {
                        // Suffix range: bytes=-N → last N bytes
                        val suffixLen = rangeParts.getOrNull(1)?.toLongOrNull() ?: fileSize
                        start = (fileSize - suffixLen).coerceAtLeast(0)
                        end = fileSize - 1
                    } else {
                        start = rangeParts[0].toLongOrNull() ?: 0
                        if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                            end = rangeParts[1].toLongOrNull() ?: (fileSize - 1)
                        }
                    }
                    if (start > end || start >= fileSize) {
                        sendRangeNotSatisfiable(out, fileSize)
                        return
                    }
                    end = minOf(end, fileSize - 1)
                    status = 206
                }

                val contentLength = end - start + 1
                sendHeaders(out, status, contentLength, fileSize, start to end)
                streamResponse(session, out, start, end)
            }
        } catch (_: SocketException) {
            // Client closed connection; ignore.
        } catch (e: Exception) {
            LOG.warning("stream error: ${e.message}")
        }
    }

    private fun sendHeaders(
        out: OutputStream,
        status: Int,
        contentLength: Long,
        totalSize: Long?,
        range: Pair<Long, Long>?
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status ${statusText(status)}\r\n")
        sb.append("Content-Type: application/octet-stream\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: $contentLength\r\n")
        sb.append("Connection: close\r\n")
        if (range != null && totalSize != null) {
            sb.append("Content-Range: bytes ${range.first}-${range.second}/$totalSize\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun sendError(out: OutputStream, status: Int, message: String) {
        val body = "$message\r\n".toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status ${statusText(status)}\r\n")
        sb.append("Content-Type: text/plain\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun sendRangeNotSatisfiable(out: OutputStream, totalSize: Long) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 416 Range Not Satisfiable\r\n")
        sb.append("Content-Range: bytes */$totalSize\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        else -> "Internal Server Error"
    }

    private fun streamResponse(session: StreamSession, out: OutputStream, start: Long, end: Long) {
            val buf = ByteArray(1024 * 1024)
            var offset = start
            var totalSent = 0L
        while (offset <= end) {
            val toRead = minOf(buf.size.toLong(), end - offset + 1).toInt()
            val n = session.read(offset, buf, toRead)
            if (n <= 0) {
                LOG.info("read returned $n at offset $offset")
                break
            }
            out.write(buf, 0, n)
            out.flush()
            offset += n
            totalSent += n
        }
        LOG.info("sent $totalSent bytes")
    }

    private class StreamSession(
        private val config: ServerConfig,
        private val filePath: String
    ) {
        private var ssh: SSHClient? = null
        private var sftp: SFTPClient? = null
        private var remoteFile: RemoteFile? = null
        private var fileSize: Long = 0
        // H2: once closed, refuse to re-open (prevents handler-thread re-open
        // after closeSession leaking a fresh SSH connection).
        @Volatile
        private var closed = false

        @Synchronized
        fun open(): Long {
            if (closed) return -1
            if (remoteFile != null) return fileSize
            val client = SSHClient(createSshjConfig())
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

        @Synchronized
        fun close() {
            closed = true
            try { remoteFile?.close() } catch (_: Exception) {}
            try { sftp?.close() } catch (_: Exception) {}
            try { ssh?.disconnect() } catch (_: Exception) {}
            remoteFile = null
            sftp = null
            ssh = null
        }
    }
}
