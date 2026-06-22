package dev.windplayer.vfs

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WebdavClient : VfsClient {

    override val protocol = VfsProtocol.WEBDAV

    private var httpClient: HttpClient? = null
    private var config: ServerConfig? = null
    private var baseUrl: String = ""

    override suspend fun connect(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            this@WebdavClient.config = config
            val scheme = config.httpScheme()
            baseUrl = "$scheme://${config.bareHost}:${config.defaultPort()}"
            httpClient = HttpClient(CIO) {
                expectSuccess = false
            }
            println("[WebdavClient] configured for $baseUrl")
            true
        } catch (e: Exception) {
            println("[WebdavClient] connect failed: ${e.message}")
            false
        }
    }

    override suspend fun disconnect() {
        try {
            httpClient?.close()
        } catch (_: Exception) {}
        httpClient = null
    }

    override suspend fun listDirectory(path: String): List<FileNode> = withContext(Dispatchers.IO) {
        val client = httpClient ?: throw IllegalStateException("Not connected")
        val cfg = config ?: throw IllegalStateException("No config")
        try {
            val url = "$baseUrl${normalizePath(path)}"
            val response: HttpResponse = client.request(url) {
                method = HttpMethod.parse("PROPFIND")
                header("Depth", "1")
                header("Authorization", buildBasicAuth(cfg.username, cfg.password))
                contentType(ContentType.Application.Xml)
                setBody("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<d:propfind xmlns:d=\"DAV:\">" +
                    "<d:prop>" +
                    "<d:displayname/>" +
                    "<d:getcontentlength/>" +
                    "<d:getlastmodified/>" +
                    "<d:resourcetype/>" +
                    "</d:prop>" +
                    "</d:propfind>")
            }

            val body = response.bodyAsText()
            parsePropfindResponse(body, path)
        } catch (e: Exception) {
            println("[WebdavClient] listDirectory failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun resolveUrl(path: String): String {
        val cfg = config ?: throw IllegalStateException("No config")
        val port = cfg.defaultPort()
        val scheme = cfg.httpScheme()
        val defaultPort = if (scheme == "https") 443 else 80
        return buildUrlWithCredentials(
            scheme = scheme,
            username = cfg.username,
            password = cfg.password,
            host = cfg.bareHost,
            port = port,
            defaultPort = defaultPort,
            path = path
        )
    }

    override suspend fun downloadFile(remotePath: String, localPath: String) = withContext(Dispatchers.IO) {
        val client = httpClient ?: throw IllegalStateException("Not connected")
        val cfg = config ?: throw IllegalStateException("No config")
        val url = "$baseUrl${normalizePath(remotePath)}"
        val response = client.get(url) {
            header("Authorization", buildBasicAuth(cfg.username, cfg.password))
        }
        val bytes: ByteArray = response.body()
        File(localPath).writeBytes(bytes)
        println("[WebdavClient] downloaded $remotePath -> $localPath")
    }

    override fun isConnected(): Boolean = httpClient != null

    private fun normalizePath(path: String): String {
        return if (path.startsWith("/")) path else "/$path"
    }

    private fun buildBasicAuth(username: String, password: String): String {
        if (username.isBlank()) return ""
        val credentials = "$username:$password"
        return "Basic ${java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())}"
    }

    private fun parsePropfindResponse(xml: String, requestPath: String): List<FileNode> {
        val results = mutableListOf<FileNode>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc: Document = builder.parse(ByteArrayInputStream(xml.toByteArray()))

            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val basePath = normalizePath(requestPath)
            val basePathNoSlash = basePath.trimEnd('/')

            for (i in 0 until responses.length) {
                val responseNode = responses.item(i)
                var href = ""
                var displayName = ""
                var contentLength = 0L
                var lastModified = 0L
                var isDir = false

                var child: Node? = responseNode.firstChild
                while (child != null) {
                    when {
                        child.localName == "href" -> href = child.textContent.trim()
                        child.localName == "propstat" -> {
                            var propChild: Node? = child.firstChild
                            while (propChild != null) {
                                if (propChild.localName == "prop") {
                                    var p: Node? = propChild.firstChild
                                    while (p != null) {
                                        when {
                                            p.localName == "displayname" -> displayName = p.textContent.trim()
                                            p.localName == "getcontentlength" -> contentLength = p.textContent.trim().toLongOrNull() ?: 0
                                            p.localName == "getlastmodified" -> {
                                                val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH)
                                                lastModified = try { sdf.parse(p.textContent.trim())?.time ?: 0 } catch (_: Exception) { 0 }
                                            }
                                            p.localName == "resourcetype" -> {
                                                var rt: Node? = p.firstChild
                                                while (rt != null) {
                                                    if (rt.localName == "collection") isDir = true
                                                    rt = rt.nextSibling
                                                }
                                            }
                                        }
                                        p = p.nextSibling
                                    }
                                }
                                propChild = propChild.nextSibling
                            }
                        }
                    }
                    child = child.nextSibling
                }

                val normalizedHref = normalizePath(href).trimEnd('/')
                if (normalizedHref.equals(basePathNoSlash, ignoreCase = true)) continue

                val name = if (displayName.isNotBlank()) displayName else normalizedHref.substringAfterLast('/')
                val nodePath = normalizedHref

                results.add(FileNode(
                    name = name,
                    path = nodePath,
                    isDirectory = isDir,
                    size = contentLength,
                    lastModified = lastModified,
                    protocol = VfsProtocol.WEBDAV
                ))
            }
        } catch (e: Exception) {
            println("[WebdavClient] XML parse failed: ${e.message}")
        }

        return results.sortedWith(FileNodeComparator)
    }
}
