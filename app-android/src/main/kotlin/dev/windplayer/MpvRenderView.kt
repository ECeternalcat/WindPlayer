package dev.windplayer

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.windplayer.mpv.MpvPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SurfaceView that owns the mpv ↔ Surface binding lifecycle.
 *
 * **PFD ownership**: this view deliberately does NOT open `ParcelFileDescriptor`
 * or resolve network URLs. The caller (`MobilePlayerScreen`) is the single
 * owner of any pfd and the resolver of any URL — see A-2026-H12 in
 * `Documents/Audit-2026-06-23.md`. Splitting ownership here used to leak one
 * pfd per auto-play-next transition until FD exhaustion crashed the app.
 *
 * Flow:
 *  1. surfaceCreated → init mpv (if first time) → attachSurface → initialize
 *     → invoke [onSurfaceReady]. The screen then runs `resolveAndLoad` which
 *     opens the pfd and calls `loadfile`.
 *  2. surfaceDestroyed → detachSurface (no pfd cleanup here; the screen owns it).
 */
class MpvRenderView(
    context: Context,
    private val player: MpvPlayer,
    private val onSurfaceReady: () -> Unit,
    private val onSurfaceReattached: () -> Unit = {}
) : SurfaceView(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // H6: signals whether the current Surface is still valid. Set false in
    // surfaceDestroyed; checked by the IO coroutine before attachSurface to
    // avoid binding mpv to a destroyed surface during rapid rotation.
    private val surfaceValid = AtomicBoolean(false)
    // Track whether onSurfaceReady has been called at least once. On subsequent
    // surface recreations (e.g. returning from background), we skip it — mpv
    // still has the file loaded and will resume from the current position once
    // the surface is reattached. Calling loadfile again would restart from 0.
    private val firstInitDone = AtomicBoolean(false)
    // H10: keep a reference so release() can unregister the callback.
    // Without this, the anonymous callback (which captures player/context/
    // onSurfaceReady) leaks for the process lifetime if the holder outlives
    // the view (e.g. window reuse).
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceCreated")
            surfaceValid.set(true)
            scope.launch {
                try {
                    // mpv calls are serialized by MpvPlayer.lock internally.
                    // No outer synchronized(player) needed (would be harmful:
                    // blocks all player callers; see A-2026-H7).
                    val firstInit = !player.isCreated()
                    if (firstInit) {
                        player.createWithContext(context)
                        val s = SettingsHelper.load(context)
                        player.setOption("vo", "gpu")
                        player.setOption("hwdec", if (s.hwdecAuto) "auto-safe" else "no")
                        player.setOption("keep-open", "yes")
                        player.setOption("idle", "yes")
                        player.setOption("sub-font-size", s.subFontSize.toString())
                        player.setOption("sub-border-size", s.subBorderSize.toString())
                        player.setOption("volume", s.defaultVolume.toString())
                        player.setOption("screenshot-directory", context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath)
                        Log.i(TAG, "Attaching surface...")
                    }
                    // CON-3: do attach + initialize on the Main thread.
                    // surfaceCreated and surfaceDestroyed both fire on Main,
                    // so running attach here serializes against any pending
                    // destroy. The previous code ran attach on Dispatchers.IO
                    // which created a TOCTOU window: surfaceValid.get() could
                    // return true, then surfaceDestroyed fires on Main and
                    // detaches, then the IO coroutine resumes and calls
                    // attachSurface against an already-destroyed Surface.
                    // attachSurface is a fast JNI call; initialize() is the
                    // slow step but only on first init.
                    withContext(Dispatchers.Main) {
                        if (!surfaceValid.get()) {
                            Log.i(TAG, "surface invalidated before attach, aborting")
                            return@withContext
                        }
                        check(player.attachSurface(holder.surface)) { "Unable to attach mpv Surface" }
                        player.initialize()
                        if (firstInit && !firstInitDone.getAndSet(true)) {
                            Log.i(TAG, "mpv ready, handing off to screen for loadfile")
                            // Screen takes it from here: opens pfd (if needed) and
                            // issues loadfile. Centralized pfd ownership = no leaks.
                            onSurfaceReady()
                        } else {
                            Log.i(TAG, "surface reattached (resuming playback)")
                            // Force mpv to re-init the video chain so it binds to
                            // the freshly-attached surface. Without this the vo
                            // (torn down when the surface was destroyed in the
                            // background) never recovers → audio-only black screen.
                            // (Network also reloads via onSurfaceReattached below,
                            // which re-inits video too; this covers local files.)
                            try {
                                player.setProperty("vid", "no")
                                player.setProperty("vid", "1")
                            } catch (_: Exception) {}
                            // Notify the screen AFTER the surface is bound — it
                            // uses this to reload network streams (whose proxy
                            // session died in the background) so loadfile runs
                            // against a live surface instead of racing this
                            // attach (which left video black with audio only).
                            onSurfaceReattached()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Player init error", e)
                }
            }
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            Log.i(TAG, "surfaceChanged: ${w}x${h}")
            // H23: dispatch vid toggle to IO scope — the two setProperty calls
            // each acquire the mpv lock and can block on a busy decoder.
            if (player.isCreated()) {
                scope.launch {
                    try {
                        // Toggle vid to force mpv to re-read ANativeWindow size
                        // after rotation (kept from the legacy workaround).
                        player.setProperty("vid", "no")
                        player.setProperty("vid", "1")
                    } catch (_: Exception) {}
                }
            }
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceValid.set(false)
            try { player.detachSurface() } catch (_: Exception) {}
        }
    }

    init {
        holder.addCallback(surfaceCallback)
    }

    /**
     * Cancel any in-flight surface-created coroutine, mark the surface
     * invalid, and unregister the SurfaceHolder callback. Called from
     * `AndroidView.onRelease` when the player screen leaves composition.
     * Does NOT touch pfd — that's the screen's job.
     */
    fun release() {
        holder.removeCallback(surfaceCallback)
        scope.cancel()
        surfaceValid.set(false)
    }

    companion object {
        private const val TAG = "MpvRenderView"
    }
}
