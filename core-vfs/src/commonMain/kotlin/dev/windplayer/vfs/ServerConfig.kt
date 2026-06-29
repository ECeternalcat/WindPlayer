package dev.windplayer.vfs

data class ServerConfig(
    val id: String,
    val name: String,
    val protocol: VfsProtocol,
    val host: String,
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val basePath: String = "/",
    /**
     * H18: For FTP, enables FTPS (FTP over TLS). Has no effect for SFTP
     * (already encrypted) or WebDAV (TLS inferred from the `https://` host
     * prefix — see [httpScheme]).
     *
     * Default is `false` for backward compatibility with existing config files;
     * the AddServer UI sets it to `true` for new FTP servers (prefer security).
     */
    val useTls: Boolean = false
) {
    /**
     * The bare hostname with any `http://` / `https://` scheme prefix and
     * trailing `:port` stripped. Users may include the scheme to indicate
     * TLS for WebDAV; some paste `host:port` into the host field by mistake.
     */
    val bareHost: String
        get() = host.removePrefix("https://").removePrefix("http://").substringBefore(':').trimEnd('/')

    /**
     * URL scheme for HTTP-based protocols (WebDAV). Detected from the host prefix;
     * falls back to checking if the port is explicitly 443.
     *
     * Note: deliberately does NOT call [defaultPort] — that would create a
     * circular dependency (`defaultPort` → `httpScheme` → `defaultPort`).
     */
    fun httpScheme(): String = when {
        host.startsWith("https://", ignoreCase = true) -> "https"
        host.startsWith("http://", ignoreCase = true) -> "http"
        port == 443 -> "https"
        else -> "http"
    }

    fun defaultPort(): Int = when (protocol) {
        VfsProtocol.SFTP -> if (port > 0) port else 22
        VfsProtocol.WEBDAV -> if (port > 0) port else if (httpScheme() == "https") 443 else 80
        VfsProtocol.FTP -> if (port > 0) port else 21
        VfsProtocol.LOCAL -> 0
    }
}

