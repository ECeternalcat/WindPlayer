package dev.windplayer.vfs

import net.schmizz.sshj.common.SecurityUtils

private var sshjInitialized = false

/**
 * One-time Android compatibility setup for SSHJ.
 *
 * Android's built-in BouncyCastle provider is stripped down and lacks algorithms
 * that SSHJ expects (SHA-256, X25519, etc.). Disable SSHJ's BouncyCastle
 * registration so it falls back to Android's Conscrypt provider, which supports
 * those algorithms.
 *
 * Safe to call on desktop (no-op). Must be called before any SSHJ class triggers
 * [SecurityUtils] initialization.
 */
@Synchronized
fun initializeSshj() {
    if (sshjInitialized) return
    sshjInitialized = true
    if (isAndroidRuntime()) {
        SecurityUtils.setRegisterBouncyCastle(false)
    }
}

/**
 * Detect whether we are running on Android.
 *
 * Uses a class availability check so the same code can live in the shared
 * `jvmShared` source set without an Android compile dependency.
 */
internal fun isAndroidRuntime(): Boolean {
    return try {
        Class.forName("android.os.Build")
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
