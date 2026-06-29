package dev.windplayer.vfs

import java.util.logging.Logger

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private val LOG = Logger.getLogger("dev.windplayer.vfs.WebdavClient")

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
                // H18: don't follow redirects. A malicious / compromised WebDAV
                // server can return `302 Location: http://attacker/` to capture
                // our Basic `Authorization` header (Ktor would otherwise re-send
                // it to the redirect target). WebDAV PROPFIND/GET responses are
                // not expected to redirect in normal operation.
                followRedirects = false
            }
            // M4: probe the server with PROPFIND Depth:0 to verify host
            // reachability + credentials. Without this, connect() always
            // returns true and auth/host errors only surface as an empty
            // listing indistinguishable from a genuinely empty directory.
            val probeResponse = httpClient!!.request("$baseUrl${normalizePath(config.basePath)}") {
                method = HttpMethod.parse("PROPFIND")
                header("Depth", "0")
                header("Authorization", buildBasicAuth(config.username, config.password))
                contentType(ContentType.Application.Xml)
                setBody("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/></d:prop></d:propfind>")
            }
            LOG.info("connect probe: ${probeResponse.status}")
            if (probeResponse.status.value == 401) {
                LOG.warning("WebDAV authentication failed (401)")
                disconnect()
                return@withContext false
            }
            LOG.info("configured for $baseUrl")
            true
        } catch (e: Exception) {
            LOG.warning("connect failed: ${e.message}")
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
            LOG.warning("listDirectory failed: ${e.message}")
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
        val basePathNoSlash = normalizePath(requestPath).trimEnd('/')
        return try {
            // C6: XXE hardening. The WebDAV response is untrusted (server may be
            // malicious or compromised). Without these features disabled, a
            // payload like <!DOCTYPE foo [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            // could exfiltrate local files, perform SSRF, or trigger DoS via
            // billion-laughs / quadratic blowup.
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val results = mutableListOf<FileNode>()
            for (i in 0 until responses.length) {
                parseResponse(responses.item(i))?.let { node ->
                    // Skip the entry that echoes the requested directory itself.
                    if (!node.path.equals(basePathNoSlash, ignoreCase = true)) {
                        results.add(node)
                    }
                }
            }
            results.sortedWith(FileNodeComparator)
        } catch (e: Exception) {
            LOG.warning("XML parse failed: ${e.message}")
            emptyList()
        }
    }

    /** Parse a single `<D:response>` element into a [FileNode], or null if malformed. */
    private fun parseResponse(responseNode: Node): FileNode? {
        val propstat = findChildElement(responseNode, "propstat") ?: return null
        val prop = findChildElement(propstat, "prop") ?: return null

        val href = findChildElement(responseNode, "href")?.textContent?.trim().orEmpty()
        val displayName = findChildElement(prop, "displayname")?.textContent?.trim().orEmpty()
        val contentLength = findChildElement(prop, "getcontentlength")
            ?.textContent?.trim()?.toLongOrNull() ?: 0L
        val lastModified = parseHttpDate(findChildElement(prop, "getlastmodified")?.textContent?.trim())
        val isDir = findChildElement(prop, "resourcetype")
            ?.let { hasChildElement(it, "collection") } ?: false

        val normalizedHref = normalizePath(href).trimEnd('/')
        val name = if (displayName.isNotBlank()) displayName else normalizedHref.substringAfterLast('/')

        return FileNode(
            name = name,
            path = normalizedHref,
            isDirectory = isDir,
            size = contentLength,
            lastModified = lastModified,
            protocol = VfsProtocol.WEBDAV
        )
    }

    /** First direct child element of [parent] whose `localName` matches, or null. */
    private fun findChildElement(parent: Node, localName: String): Node? {
        var child = parent.firstChild
        while (child != null) {
            if (child.localName == localName) return child
            child = child.nextSibling
        }
        return null
    }

    private fun hasChildElement(parent: Node, localName: String): Boolean =
        findChildElement(parent, localName) != null

    private fun parseHttpDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0
        return try {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH)
                .parse(dateStr)?.time ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
