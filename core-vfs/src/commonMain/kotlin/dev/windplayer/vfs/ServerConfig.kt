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
     * The bare hostname with any `user:pass@` userinfo, `http://` / `https://`
     * scheme prefix, and trailing `:port` stripped. Users may paste a full URL
     * into the host field by mistake; we extract just the host.
     *
     * SEC-8: also strip `user:pass@` so credentials pasted into the host field
     * don't end up persisted unencrypted under the `host` key.
     */
    val bareHost: String
        get() {
            // Strip scheme first so the userinfo regex doesn't match inside it.
            val noScheme = host.removePrefix("https://").removePrefix("http://")
            // Strip `user:pass@` or `user@` if present.
            val noUserInfo = noScheme.substringAfterLast('@')
            return noUserInfo.substringBefore(':').trimEnd('/')
        }

    /**
     * URL scheme for HTTP-based protocols (WebDAV). Detected from the host prefix;
     * falls back to checking if the port is explicitly 443 / 80.
     *
     * SEC-2: when no explicit scheme is provided and the port is neither 443
     * nor 80, **default to `https`**. Previously defaulted to `http`, which
     * silently leaked `Authorization: Basic` headers over cleartext. Users who
     * really want cleartext must now opt in by typing `http://` in the host
     * field (the AddServer UI documents this).
     *
     * Note: deliberately does NOT call [defaultPort] — that would create a
     * circular dependency (`defaultPort` → `httpScheme` → `defaultPort`).
     */
    fun httpScheme(): String = when {
        host.startsWith("https://", ignoreCase = true) -> "https"
        host.startsWith("http://", ignoreCase = true) -> "http"
        port == 443 -> "https"
        port == 80 -> "http"
        else -> "https"
    }

    fun defaultPort(): Int = when (protocol) {
        VfsProtocol.SFTP -> if (port > 0) port else 22
        VfsProtocol.WEBDAV -> if (port > 0) port else if (httpScheme() == "https") 443 else 80
        VfsProtocol.FTP -> if (port > 0) port else 21
        VfsProtocol.LOCAL -> 0
    }
}

