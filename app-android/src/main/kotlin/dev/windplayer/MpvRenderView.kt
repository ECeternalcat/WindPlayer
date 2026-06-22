package dev.windplayer

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.vfs.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MpvRenderView(
    context: Context,
    private val player: MpvPlayer,
    private val filePath: String,
    private val serverConfig: ServerConfig? = null,
    private val onLoaded: () -> Unit
) : SurfaceView(context) {

    private var pfd: android.os.ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceCreated")
                scope.launch {
                    try {
                        // Resolve URL / open PFD OUTSIDE the player lock to avoid
                        // blocking other player calls during network I/O.
                        val loadPath = resolvePath(context, filePath, serverConfig)

                        synchronized(player) {
                            if (!player.isCreated()) {
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
                            player.attachSurface(holder.surface)
                            player.initialize()
                            Log.i(TAG, "mpv ready, loading file...")
                            player.command("loadfile", loadPath)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Player init error", e)
                    }
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                Log.i(TAG, "surfaceChanged: ${w}x${h}")
                synchronized(player) {
                    if (player.isCreated()) {
                        try {
                            player.setProperty("vid", "no")
                            player.setProperty("vid", "1")
                        } catch (_: Exception) {}
                    }
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(player) {
                    try { player.detachSurface() } catch (_: Exception) {}
                    try { pfd?.close() } catch (_: Exception) {}
                    pfd = null
                }
            }
        })
    }

    private suspend fun resolvePath(context: Context, path: String, server: ServerConfig? = null): String {
        if (path.startsWith("content://")) {
            return try {
                val uri = android.net.Uri.parse(path)
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                val fd = pfd?.fd ?: return path
                Log.i(TAG, "Opened as fd://$fd")
                "fd://$fd"
            } catch (e: Exception) {
                Log.e(TAG, "Open content URI failed: ${e.message}")
                path
            }
        }
        if (server != null) {
            return try {
                MobileVfsManager.resolveUrl(server, path)
            } catch (e: Exception) {
                Log.e(TAG, "resolveUrl failed: ${e.message}")
                path
            }
        }
        return path
    }

    fun release() {
        scope.cancel()
        synchronized(player) {
            try { pfd?.close() } catch (_: Exception) {}
            pfd = null
        }
    }

    companion object {
        private const val TAG = "MpvRenderView"
    }
}
