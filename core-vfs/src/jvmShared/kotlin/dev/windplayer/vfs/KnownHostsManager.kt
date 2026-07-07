package dev.windplayer.vfs

import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.io.File
import java.security.PublicKey
import java.util.logging.Logger

private val LOG = Logger.getLogger("dev.windplayer.vfs.KnownHostsManager")

/**
 * Trust-on-first-use (TOFU) host key verifier, persisted to an OpenSSH-format
 * `known_hosts` file at [knownHostsFile].
 *
 * Behavior:
 *  - **First time seeing a host**: persist the host's key and accept.
 *  - **Host already known, key matches**: accept.
 *  - **Host already known, key MISMATCH**: reject (the default `hostKeyChangedAction`
 *    in [OpenSSHKnownHosts] returns `false`). This is the MITM protection.
 *
 * Compare with [PromiscuousVerifier] which accepted *every* key on *every*
 * connection, allowing trivial MITM.
 */
class TofuHostKeyVerifier(
    hostsFile: File
) : OpenSSHKnownHosts(hostsFile.ensureExists()) {

    private val knownHostsFile: File = hostsFile

    @Synchronized
    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
        return try {
            val entry = HostEntry(null, hostname, KeyType.fromKey(key), key)
            entries().add(entry)
            write()
            restrictFilePermissions(knownHostsFile)
            LOG.info("TOFU: recorded new host key for $hostname (type=${KeyType.fromKey(key)}, fp=${SecurityUtils.getFingerprint(key)})")
            true
        } catch (e: Exception) {
            LOG.warning("TOFU: failed to record host key for $hostname: ${e.message}")
            // Reject on persistence failure so we don't silently accept every key.
            false
        }
    }
}

/** Ensure the file exists (create empty if missing) so [OpenSSHKnownHosts] can parse it. */
private fun File.ensureExists(): File {
    if (!exists()) {
        try {
            parentFile?.mkdirs()
            createNewFile()
            restrictFilePermissions(this)
        } catch (e: Exception) {
            LOG.warning("Could not create known_hosts at $absolutePath: ${e.message}")
        }
    }
    return this
}

/**
 * HostKeyVerifier that always rejects. Used as the fail-closed fallback when
 * the TOFU store cannot be opened — never [PromiscuousVerifier].
 *
 * Connections will fail with an SSH handshake error until the user fixes the
 * known_hosts file permissions / location. This is the correct security
 * posture: we refuse to send credentials over an unauthenticated channel.
 */
private object RejectAllHostKeyVerifier : HostKeyVerifier {
    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
    override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
        LOG.warning("Rejecting host key for $hostname:$port — known_hosts unavailable (fail-closed)")
        return false
    }
}

/**
 * Singleton-ish accessor for the app's [HostKeyVerifier]. Resolves to a
 * [TofuHostKeyVerifier] backed by `~/.windplayer/known_hosts` on success, or
 * fails closed via [RejectAllHostKeyVerifier] on any error.
 *
 * Kept as a `val` so every caller shares the same in-memory state — the
 * underlying [OpenSSHKnownHosts] caches parsed entries.
 */
object KnownHostsManager {
    /**
     * Optional base directory for the TOFU `known_hosts` file.
     *
     * Desktop clients should leave this unset; the file is stored at
     * `~/.windplayer/known_hosts`. Android must call [initialize] before any
     * SSH connection (e.g. from [android.app.Application.onCreate] or
     * [android.app.Activity.onCreate]) so the file is written to the app's
     * private files directory instead of `/` (Android's `user.home`).
     */
    private var customBaseDir: File? = null

    /**
     * Configure where `known_hosts` is persisted. Safe to call multiple times;
     * must be called before [verifier] is first accessed to take effect.
     */
    @Synchronized
    fun initialize(baseDir: File) {
        customBaseDir = baseDir
    }

    @Synchronized
    private fun knownHostsFile(): File {
        val base = customBaseDir ?: File(System.getProperty("user.home"), ".windplayer")
        return File(base, "known_hosts")
    }

    val verifier: HostKeyVerifier by lazy {
        val file = knownHostsFile()
        try {
            TofuHostKeyVerifier(file)
        } catch (e: Exception) {
            LOG.warning(
                "known_hosts at ${file.absolutePath} unreadable ($e); " +
                    "fail-closed: all SSH connections will be rejected until permissions are fixed"
            )
            RejectAllHostKeyVerifier
        }
    }
}
