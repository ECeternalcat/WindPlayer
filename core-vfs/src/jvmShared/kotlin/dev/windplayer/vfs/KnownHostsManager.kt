package dev.windplayer.vfs

import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
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
 * Compare with the previous [PromiscuousVerifier] which accepted *every* key on
 * *every* connection, allowing trivial MITM.
 *
 * If the `known_hosts` file cannot be opened or parsed, we fall back to a
 * [PromiscuousVerifier] with a log warning — so a broken file doesn't lock
 * the user out, but they should fix permissions and reconnect.
 */
class TofuHostKeyVerifier(
    knownHostsFile: File
) : OpenSSHKnownHosts(knownHostsFile.ensureExists()) {

    @Synchronized
    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
        return try {
            val entry = HostEntry(null, hostname, KeyType.fromKey(key), key)
            entries().add(entry)
            write()
            LOG.info("TOFU: recorded new host key for $hostname (type=${KeyType.fromKey(key)})")
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
        } catch (e: Exception) {
            LOG.warning("Could not create known_hosts at $absolutePath: ${e.message}")
        }
    }
    return this
}

/**
 * Singleton-ish accessor for the app's [HostKeyVerifier]. Resolves to a
 * [TofuHostKeyVerifier] backed by `~/.windplayer/known_hosts` on success, or
 * falls back to [PromiscuousVerifier] with a logged warning on failure.
 *
 * Kept as a `val` so every caller shares the same in-memory state — the
 * underlying [OpenSSHKnownHosts] caches parsed entries.
 */
object KnownHostsManager {
    private val knownHostsFile: File by lazy {
        File(System.getProperty("user.home"), ".windplayer/known_hosts")
    }

    val verifier: HostKeyVerifier by lazy {
        try {
            TofuHostKeyVerifier(knownHostsFile)
        } catch (e: Exception) {
            LOG.warning(
                "known_hosts at ${knownHostsFile.absolutePath} unreadable ($e); " +
                    "falling back to PromiscuousVerifier — server connections will be MITM-vulnerable"
            )
            PromiscuousVerifier()
        }
    }
}
