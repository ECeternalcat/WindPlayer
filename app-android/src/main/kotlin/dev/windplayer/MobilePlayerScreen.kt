package dev.windplayer

import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.windplayer.mpv.MpvEvent
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.ui.I18n
import dev.windplayer.ui.WindMotion
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.MatchedTrackType
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.StreamProxy
import dev.windplayer.vfs.VfsProtocol
import dev.windplayer.vfs.formatDuration
import dev.windplayer.vfs.isVideo
import dev.windplayer.vfs.matchExternalTracks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobilePlayerScreen(
    player: MpvPlayer,
    file: FileNode,
    onBack: () -> Unit,
    serverConfig: ServerConfig? = null,
    directoryVideos: List<FileNode> = emptyList(),
    localSiblings: List<FileNode> = emptyList(),
    currentIndex: Int = 0,
    autoPlayNext: Boolean = false,
    onFilePlayed: (FileNode) -> Unit = {},
    resumePosition: Double = 0.0,
    resumeSid: String? = null,
    resumeAid: String? = null,
    resumeSpeed: Double = 0.0,
    onPositionUpdate: (String, Double, Double) -> Unit = { _, _, _ -> },
    onPlaybackStateUpdate: (String, String?, String?, Double) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // CON-2: standalone scope for native/SSH teardown on dispose. Compose
    // cancels `scope` (from rememberCoroutineScope) BEFORE onDispose runs,
    // so any cleanup launched there would be cancelled mid-flight. SSH
    // disconnect via streamProxy.closeSession / streamProxy.stop can block
    // on unresponsive hosts (sshj default socket timeout is long), which
    // would ANR if done synchronously on the Main (Applier) thread.
    val teardownScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var volume by remember { mutableStateOf(100) }
    var speed by remember { mutableStateOf(1.0) }
    var fileLoaded by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var osdText by remember { mutableStateOf("") }
    var showTracks by remember { mutableStateOf(false) }
    var panelExpanded by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var longPressSpeeding by remember { mutableStateOf(false) }
    var savedSpeedValue by remember { mutableStateOf(1.0) }
    var isDragging by remember { mutableStateOf(false) }
    // H14: separate flag for slider scrubbing. `isDragging` tracks gesture
    // drags; without this, the 200ms polling loop overwrites `position` while
    // the user is actively scrubbing the slider, causing it to jump back.
    var isScrubbing by remember { mutableStateOf(false) }
    // H22: one-time prompt for WRITE_SETTINGS so the brightness gesture can
    // control the system brightness, not just the window.
    var brightnessPromptShown by remember { mutableStateOf(false) }
    var interactionCount by remember { mutableIntStateOf(0) }
    var currentIdx by remember { mutableIntStateOf(currentIndex) }
    var currentPfd by remember { mutableStateOf<android.os.ParcelFileDescriptor?>(null) }
    val streamProxy = remember { StreamProxy() }
    var streamSessionIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingSubtitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var subtitlesAdded by remember { mutableStateOf(false) }
    var eofHandled by remember { mutableStateOf(false) }
    var pendingResume by remember { mutableStateOf(resumePosition) }
    var pendingResumeSid by remember { mutableStateOf(resumeSid) }
    var pendingResumeAid by remember { mutableStateOf(resumeAid) }
    var pendingResumeSpeed by remember { mutableStateOf(resumeSpeed) }
    // Set in ON_RESUME for network streams; consumed in onSurfaceReattached so
    // the reload runs AFTER the surface is re-bound (avoids loadfile racing
    // attachSurface → black video with audio only).
    val pendingNetworkResume = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    var hasWriteSettings by remember { mutableStateOf(Settings.System.canWrite(context)) }
    var originalSystemBrightness by remember { mutableStateOf(-1) }

    /**
     * Add any pending external subtitle files once the main file is loaded.
     * Called from both the FileLoaded event and the subtitle-download completion
     * to handle whichever arrives first.
     */
    fun tryAddSubtitles() {
        if (!fileLoaded || subtitlesAdded || pendingSubtitles.isEmpty()) return
        subtitlesAdded = true
        android.util.Log.i("MpvPlayer", "tryAddSubtitles: adding ${pendingSubtitles.size} subtitles (fileLoaded=$fileLoaded)")
        scope.launch(Dispatchers.IO) {
            for ((i, subPath) in pendingSubtitles.withIndex()) {
                val flag = if (i == 0) "select" else "auto"
                try {
                    player.command("sub-add", subPath, flag)
                    android.util.Log.i("MpvPlayer", "sub-add ok: $subPath ($flag)")
                } catch (e: Exception) {
                    android.util.Log.w("MpvPlayer", "sub-add failed: $subPath - ${e.message}")
                }
            }
        }
    }

    suspend fun resolveAndLoad(path: String) {
        // H13: openFileDescriptor and resolveUrl perform disk + network I/O
        // (SSH connect for SFTP). Must run on Dispatchers.IO, never Main,
        // otherwise StrictMode flags it and we risk ANR.
        withContext(Dispatchers.IO) {
            // Capture the resume position BEFORE the resets below clear it, so we
            // can hand it to mpv's `start` option. Seeking via `time-pos` after
            // FileLoaded races the demuxer on HTTP streams and is silently lost
            // → network videos resumed from 0. The `start` option makes mpv seek
            // as part of loading (Range request during init), which is reliable.
            val startAt = pendingResume
            try { currentPfd?.close() } catch (_: Exception) {}
            currentPfd = null
            streamSessionIds.forEach { streamProxy.closeSession(it) }
            streamSessionIds = emptyList()
            pendingSubtitles = emptyList()
            subtitlesAdded = false
            eofHandled = false
            // M7: reset fileLoaded so a failed next-episode load doesn't
            // inherit the previous file's eof-reached state and spuriously
            // trigger auto-advance.
            fileLoaded = false
            // M23: clear any stale error so the UI doesn't show "Playback
            // failed" forever after a single transient failure.
            errorMsg = ""
            pendingResume = 0.0
            pendingResumeSid = null
            pendingResumeAid = null
            pendingResumeSpeed = 0.0

            val isNetwork = serverConfig != null
            val loadPath = if (path.startsWith("content://")) {
                try {
                    val uri = android.net.Uri.parse(path)
                    currentPfd = context.contentResolver.openFileDescriptor(uri, "r")
                    "fd://${currentPfd?.fd}"
                } catch (_: Exception) { path }
            } else if (serverConfig != null && serverConfig.protocol == VfsProtocol.SFTP) {
                try {
                    val url = streamProxy.createStreamUrl(serverConfig, path)
                    streamSessionIds = listOf(url.substringAfterLast('/'))
                    url
                } catch (_: Exception) { path }
            } else if (serverConfig != null) {
                try { MobileVfsManager.resolveUrl(serverConfig, path) } catch (_: Exception) { path }
            } else { path }

            // Larger demuxer cache for network streams so seeks stay within
            // the buffered region instead of re-requesting from SFTP.
            try {
                if (isNetwork) {
                    player.setProperty("cache", "yes")
                    player.setProperty("demuxer-max-bytes", "500M")
                    player.setProperty("demuxer-max-back-bytes", "150M")
                } else {
                    player.setProperty("cache", "auto")
                    player.setProperty("demuxer-max-bytes", "150M")
                    player.setProperty("demuxer-max-back-bytes", "75M")
                }
            } catch (_: Exception) {}

            // Tell mpv where to begin as part of loading. `start` is consumed on
            // loadfile, so it must be set before the command below. For non-resume
            // loads startAt is 0 (no-op).
            try {
                player.setProperty("start", if (startAt > 1.0) "%.3f".format(startAt) else "0")
            } catch (_: Exception) {}

            // player.command is fine to call from IO; MpvPlayer serializes via its own lock.
            player.command("loadfile", loadPath)
            // keep-open=yes leaves the player paused at EOF; explicitly resume
            // so the next file in auto-play starts immediately.
            try { player.setProperty("pause", "no") } catch (_: Exception) {}
            // Re-arm pendingResume so the FileLoaded handler also seeks as a
            // fallback in case the `start` option above is ignored by the build.
            // FileLoaded resets it to 0 after seeking, so auto-play-next is unaffected.
            pendingResume = startAt

            // Background: match & download external subtitle files so they can
            // be sub-added after the main file loads. Runs after loadfile so it
            // never delays playback start.
            if (serverConfig != null) {
                scope.launch(Dispatchers.IO) {
                    val videoNode = directoryVideos.getOrNull(currentIdx) ?: FileNode(
                        name = path.substringAfterLast('/'),
                        path = path,
                        isDirectory = false,
                        protocol = serverConfig.protocol
                    )
                    try {
                        val dirPath = path.substringBeforeLast('/').ifBlank { "/" }
                        val siblings = MobileVfsManager.listDirectory(serverConfig, dirPath)
                        val matched = matchExternalTracks(videoNode, siblings)
                        val subTracks = matched.filter { it.type == MatchedTrackType.SUBTITLE }
                        android.util.Log.i("MpvPlayer", "subtitle match: ${subTracks.size} found for '${videoNode.name}' among ${siblings.size} siblings")
                        val subFiles = mutableListOf<String>()
                        for (track in subTracks) {
                            val local = MobileVfsManager.downloadAuxFile(
                                serverConfig, track.file, context.cacheDir
                            )
                            if (local != null) {
                                subFiles.add(local.absolutePath)
                                android.util.Log.i("MpvPlayer", "subtitle downloaded: ${track.file.name} -> ${local.absolutePath}")
                            } else {
                                android.util.Log.w("MpvPlayer", "subtitle download failed: ${track.file.name}")
                            }
                        }
                        if (subFiles.isNotEmpty()) {
                            pendingSubtitles = subFiles
                            tryAddSubtitles()
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MpvPlayer", "subtitle matching failed: ${e.message}")
                    }
                }
            } else if (localSiblings.isNotEmpty()) {
                // Local files: match subtitles from sibling list (SAF directory).
                // Copy matching subtitle files to cache since mpv can't read
                // content:// URIs via sub-add.
                scope.launch(Dispatchers.IO) {
                    val videoNode = directoryVideos.getOrNull(currentIdx) ?: FileNode(
                        name = path.substringAfterLast('/'),
                        path = path,
                        isDirectory = false,
                        protocol = VfsProtocol.LOCAL
                    )
                    try {
                        val matched = matchExternalTracks(videoNode, localSiblings)
                        val subFiles = mutableListOf<String>()
                        for (track in matched.filter { it.type == MatchedTrackType.SUBTITLE }) {
                            val safeName = track.file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            val cached = java.io.File(context.cacheDir, safeName)
                            if (!cached.exists()) {
                                context.contentResolver.openInputStream(
                                    android.net.Uri.parse(track.file.path)
                                )?.use { input -> cached.outputStream().use { input.copyTo(it) } }
                            }
                            if (cached.exists()) subFiles.add(cached.absolutePath)
                        }
                        if (subFiles.isNotEmpty()) {
                            pendingSubtitles = subFiles
                            tryAddSubtitles()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Capture the current mpv frame as a thumbnail for [path] and persist it to
     * history. Called on exit and on every episode transition (auto-play-next /
     * manual skip) so that episodes left via "next" still get a cover instead of
     * the default icon. Must run while mpv still holds [path]'s frame.
     *
     * H15: caller MUST be on Dispatchers.IO — screenshot-to-file is a
     * synchronous JNI call (JPEG encode + disk write, ~100ms–1s).
     */
    fun captureThumbnailForPath(path: String) {
        try {
            // M8: use SHA-256 instead of String.hashCode() (32-bit, collisions
            // are easy across many history entries) to avoid thumbnail files
            // overwriting each other.
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(path.toByteArray(Charsets.UTF_8))
            val safeName = "thumb_${sha.joinToString("") { "%02x".format(it) }.take(32)}.jpg"
            val thumbFile = java.io.File(context.cacheDir, safeName)
            player.command("screenshot-to-file", thumbFile.absolutePath, "video")
            if (thumbFile.exists() && thumbFile.length() > 0) {
                HistoryStore.updateThumbnail(context, path, thumbFile.absolutePath)
            }
        } catch (_: Exception) {}
    }

    /**
     * Capture current frame as thumbnail (for history display), then exit.
     * Screenshot is taken BEFORE stop/dispose so the frame is still valid.
     * H15: the JNI screenshot call runs on IO to avoid ANR; onBack is posted
     * back to Main after the screenshot completes.
     */
    fun captureThumbAndExit() {
        val curPath = directoryVideos.getOrNull(currentIdx)?.path ?: file.path
        // Read properties on Main BEFORE the screenshot — they may change
        // once we yield to IO.
        val pos = position
        val dur = duration
        val spd = speed
        val sidVal = try { player.getPropertyString("sid") } catch (_: Exception) { null }
        val aidVal = try { player.getPropertyString("aid") } catch (_: Exception) { null }
        // Flush the latest position/tracks so exiting between 5s polling ticks
        // (or before the first tick) still records progress.
        if (pos > 0) {
            try {
                onPositionUpdate(curPath, pos, dur)
                onPlaybackStateUpdate(curPath, sidVal, aidVal, spd)
            } catch (_: Exception) {}
        }
        scope.launch(Dispatchers.IO) {
            captureThumbnailForPath(curPath)
            withContext(Dispatchers.Main) { onBack() }
        }
    }

    var backPressedOnce by remember { mutableStateOf(false) }
    BackHandler {
        if (backPressedOnce) {
            captureThumbAndExit()
        } else {
            backPressedOnce = true
            Toast.makeText(context, I18n.get("press_back_again"), Toast.LENGTH_SHORT).show()
            scope.launch {
                delay(2000)
                backPressedOnce = false
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Save original system brightness so we can restore it on exit.
        if (originalSystemBrightness < 0) {
            originalSystemBrightness = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) { 128 }
        }

        // Pause playback when the screen goes background; resume on foreground.
        val lifecycleOwner = lifecycleOwner
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    try { player.setProperty("pause", "yes") } catch (_: Exception) {}
                }
                Lifecycle.Event.ON_RESUME -> {
                    hasWriteSettings = Settings.System.canWrite(context)
                    if (fileLoaded && serverConfig != null) {
                        // Network streams need a reload (StreamProxy session dies
                        // in background), but loadfile must run AFTER the surface
                        // is re-attached or video stays black. Defer via a flag
                        // consumed in onSurfaceReattached (surfaceCreated).
                        // Rotation doesn't reach ON_RESUME, so it won't reload.
                        pendingNetworkResume.set(true)
                    } else {
                        try { player.setProperty("pause", "no") } catch (_: Exception) {}
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            // Restore window brightness to follow system.
            // Kotlin smart-casts `window` to non-null here because the LHS
            // `window?.attributes =` is a no-op when window is null, so the
            // RHS evaluation can safely assume non-null receiver.
            window?.attributes = window.attributes.apply { this.screenBrightness = -1f }
            // Restore system brightness if we changed it.
            if (hasWriteSettings && originalSystemBrightness >= 0) {
                try {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        originalSystemBrightness
                    )
                } catch (_: Exception) {}
            }
            // CON-2: PFD close + SSH teardown (streamProxy.closeSession +
            // streamProxy.stop) involve network I/O and can block for the
            // socket timeout on unresponsive hosts. Dispatch to teardownScope
            // (Dispatchers.IO) to avoid ANR / StrictMode network-on-main-thread
            // violations. The captured references are cleared after launch to
            // prevent re-entrant use; the launched block uses its own snapshot.
            val pfdToClose = currentPfd
            val sessionsToClose = streamSessionIds
            val proxyToStop = streamProxy
            currentPfd = null
            streamSessionIds = emptyList()
            teardownScope.launch {
                try { pfdToClose?.close() } catch (_: Exception) {}
                sessionsToClose.forEach {
                    try { proxyToStop.closeSession(it) } catch (_: Exception) {}
                }
                try { proxyToStop.stop() } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(player) {
        player.initAndroid(context)
        player.events.collect { event ->
            when (event) {
                is MpvEvent.FileLoaded -> {
                    fileLoaded = true
                    isPlaying = true
                    try {
                        duration = player.getPropertyDouble("duration")
                        speed = player.getPropertyDouble("speed")
                    } catch (_: Exception) {}
                    // Sync volume display from system media volume.
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    volume = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol * 100).toInt()
                    // Record to history (covers auto-play-next episodes too).
                    directoryVideos.getOrNull(currentIdx)?.let { onFilePlayed(it) }
                    // Resume from saved position (first load only; auto-play-next resets to 0).
                    if (pendingResume > 1.0) {
                        try {
                            player.setProperty("time-pos", "%.3f".format(pendingResume))
                            position = pendingResume
                        } catch (_: Exception) {}
                        pendingResume = 0.0
                    }
                    // Restore saved audio track.
                    pendingResumeAid?.let { aid ->
                        if (aid.isNotEmpty() && aid != "no") {
                            try { player.setProperty("aid", aid) } catch (_: Exception) {}
                        }
                        pendingResumeAid = null
                    }
                    // Restore saved subtitle track (overrides auto-select if user had one).
                    pendingResumeSid?.let { sidVal ->
                        try { player.setProperty("sid", sidVal) } catch (_: Exception) {}
                        pendingResumeSid = null
                    }
                    // Restore saved speed.
                    if (pendingResumeSpeed > 0 && pendingResumeSpeed != 1.0) {
                        try {
                            player.setProperty("speed", "%.2f".format(pendingResumeSpeed))
                            speed = pendingResumeSpeed
                        } catch (_: Exception) {}
                        pendingResumeSpeed = 0.0
                    }
                    // Auto-select first internal subtitle track if none is active.
                    scope.launch(Dispatchers.IO) {
                        try {
                            val sid = player.getPropertyString("sid")
                            if (sid == null || sid == "no" || sid.isEmpty()) {
                                val count = player.getPropertyLong("track-list/count").toInt()
                                for (i in 0 until count) {
                                    val type = player.getPropertyString("track-list/$i/type") ?: ""
                                    if (type == "sub") {
                                        val subId = player.getPropertyString("track-list/$i/id") ?: ""
                                        if (subId.isNotEmpty()) {
                                            player.setProperty("sid", subId)
                                            android.util.Log.i("MpvPlayer", "auto-selected internal subtitle: sid=$subId")
                                            break
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    tryAddSubtitles()
                }
                is MpvEvent.EndFile -> {
                    if (event.reason == 4) errorMsg = "Playback failed"
                    else if (!eofHandled && autoPlayNext && event.reason == 0) {
                        eofHandled = true
                        if (currentIdx + 1 < directoryVideos.size) {
                            val leavingPath = directoryVideos.getOrNull(currentIdx)?.path
                            currentIdx++
                            val nextFile = directoryVideos[currentIdx]
                            osdText = ">> ${nextFile.name}"
                            scope.launch(Dispatchers.IO) {
                                if (leavingPath != null) captureThumbnailForPath(leavingPath)
                                resolveAndLoad(nextFile.path)
                            }
                        } else {
                            onBack()
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // §7 Hot Reload: observe the subtitle-mount bus. When the translation
    // pipeline completes (foreground service), the SRT path is published here.
    // We mount it into mpv via `sub-add` so the user sees the subtitle
    // immediately without any manual action.
    // BUG-4: key on fileLoaded so the collector restarts when playback becomes
    // ready — if the translation completes while the player is reloading, the
    // StateFlow value persists and is picked up on the next composition.
    LaunchedEffect(fileLoaded) {
        val path = dev.windplayer.translate.TranslateService.pendingSubtitleMount.value
        if (path != null) {
            try {
                player.command("sub-add", path, "select")
                osdText = dev.windplayer.ui.I18n.get("gen_sub_ready")
                Log.i("MobilePlayerScreen", "Auto-mounted subtitle: $path")
            } catch (e: Exception) {
                Log.w("MobilePlayerScreen", "sub-add failed: ${e.message}")
            }
            dev.windplayer.translate.TranslateService.pendingSubtitleMount.value = null
        }
    }

    LaunchedEffect(fileLoaded) {
        var posCounter = 0
        while (fileLoaded) {
            delay(200)
            try {
                isPlaying = player.getPropertyString("pause") != "yes"
                if (!isDragging && !isScrubbing) position = player.getPropertyDouble("time-pos")

                val curPath = directoryVideos.getOrNull(currentIdx)?.path

                // Save playback position + tracks + speed every ~5 seconds
                if (++posCounter >= 25) {
                    posCounter = 0
                    if (curPath != null && position > 0) {
                        onPositionUpdate(curPath, position, duration)
                        try {
                            val sid = player.getPropertyString("sid")
                            val aid = player.getPropertyString("aid")
                            val spd = player.getPropertyDouble("speed")
                            onPlaybackStateUpdate(curPath, sid, aid, spd)
                        } catch (_: Exception) {}
                    }
                }

                // keep-open=yes prevents EndFile from firing on natural EOF,
                // so we poll eof-reached to detect playback completion.
                if (!eofHandled && player.getPropertyString("eof-reached") == "yes") {
                    eofHandled = true
                    controlsVisible = false
                    if (autoPlayNext && currentIdx + 1 < directoryVideos.size) {
                        val leavingPath = directoryVideos.getOrNull(currentIdx)?.path
                        currentIdx++
                        val nextFile = directoryVideos[currentIdx]
                        osdText = ">> ${nextFile.name}"
                        scope.launch(Dispatchers.IO) {
                            if (leavingPath != null) captureThumbnailForPath(leavingPath)
                            resolveAndLoad(nextFile.path)
                        }
                    } else {
                        // No next file or auto-play disabled ↁEreturn to file list.
                        captureThumbAndExit()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(osdText) {
        if (osdText.isNotEmpty()) { delay(2000); osdText = "" }
    }

    LaunchedEffect(controlsVisible, interactionCount) {
        if (controlsVisible && !isDragging) { delay(3000); if (!isDragging) controlsVisible = false }
    }

    fun seek(delta: Double) {
        try {
            val pos = player.getPropertyDouble("time-pos")
            val dur = player.getPropertyDouble("duration")
            val target = (pos + delta).coerceIn(0.0, dur)
            player.setProperty("time-pos", "%.3f".format(target))
            position = target
            osdText = "${fmt(delta)} ↁE${formatDuration(target)} / ${formatDuration(dur)}"
        } catch (_: Exception) {}
    }

    fun playNext() {
        if (currentIdx + 1 < directoryVideos.size) {
            val leavingPath = directoryVideos.getOrNull(currentIdx)?.path
            currentIdx++
            val nextFile = directoryVideos[currentIdx]
            osdText = ">> ${nextFile.name}"
            scope.launch(Dispatchers.IO) {
                if (leavingPath != null) captureThumbnailForPath(leavingPath)
                resolveAndLoad(nextFile.path)
            }
        }
    }

    fun playPrev() {
        if (currentIdx > 0) {
            val leavingPath = directoryVideos.getOrNull(currentIdx)?.path
            currentIdx--
            val prevFile = directoryVideos[currentIdx]
            osdText = "<< ${prevFile.name}"
            scope.launch(Dispatchers.IO) {
                if (leavingPath != null) captureThumbnailForPath(leavingPath)
                resolveAndLoad(prevFile.path)
            }
        }
    }

    fun setVol(v: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = v.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        volume = (clamped.toFloat() / max * 100).toInt()
        osdText = "${I18n.get("osd_vol")}: $clamped/$max"
    }

    fun setBrightness(v: Int) {
        val clamped = v.coerceIn(0, 255)
        if (hasWriteSettings) {
            try {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS, clamped
                )
            } catch (_: Exception) {}
        } else if (!brightnessPromptShown) {
            // H22: prompt the user ONCE to grant WRITE_SETTINGS so system
            // brightness can be controlled. Without this, the gesture only
            // adjusts window brightness and "restore on exit" is a no-op.
            brightnessPromptShown = true
            Toast.makeText(context, I18n.get("brightness_perm_hint"), Toast.LENGTH_LONG).show()
            try {
                val intent = android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
        val window = (context as? android.app.Activity)?.window
        // Kotlin smart-cast: same as in DisposableEffect.onDispose above.
        window?.attributes = window.attributes.apply { this.screenBrightness = clamped / 255f }
        osdText = "${I18n.get("osd_brightness")}: ${clamped * 100 / 255}%"
    }

    fun toggleSpeed() {
        val speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
        val idx = speeds.indexOf(speed).let { if (it < 0) 2 else it }
        speed = speeds[(idx + 1) % speeds.size]
        try { player.setProperty("speed", "%.2f".format(speed)) } catch (_: Exception) {}
        osdText = "${I18n.get("speed")}: %.2fx".format(speed)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                var startX = 0f; var startY = 0f; var mode = -1
                var startPos = 0.0; var startVol = 0; var startBright = 0
                var directionLocked = false
                // M16: throttle brightness/volume gestures to ~30Hz to avoid
                // per-pixel window.attributes relayout + setStreamVolume IPC.
                var lastGestureApply = 0L
                detectDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x; startY = offset.y
                        isDragging = true
                        mode = -1
                        directionLocked = false
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, _ ->
                        val dx = change.position.x - startX
                        val dy = startY - change.position.y

                        // Determine gesture direction on first significant movement.
                        if (!directionLocked && (kotlin.math.abs(dx) > 24 || kotlin.math.abs(dy) > 24)) {
                            directionLocked = true
                            if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                mode = 0 // horizontal = seek
                                startPos = try { player.getPropertyDouble("time-pos") } catch (_: Exception) { 0.0 }
                            } else {
                                mode = if (startX < size.width / 2) 1 else 2
                                if (mode == 1) {
                                    startBright = if (hasWriteSettings) {
                                        try { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
                                    } else {
                                        val wb = (context as? android.app.Activity)?.window?.attributes?.screenBrightness ?: -1f
                                        ((if (wb < 0) 0.5f else wb) * 255).toInt()
                                    }
                                } else {
                                    startVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                }
                            }
                        }

                        if (directionLocked) when (mode) {
                            0 -> { // horizontal seek: full screen width = ±30s
                                val deltaSec = (dx / size.width * 60.0).coerceIn(-30.0, 30.0)
                                try {
                                    val dur = player.getPropertyDouble("duration")
                                    val target = (startPos + deltaSec).coerceIn(0.0, dur)
                                    position = target
                                    player.setProperty("time-pos", "%.3f".format(target))
                                    osdText = "${fmt(deltaSec)} ↁE${formatDuration(target)} / ${formatDuration(dur)}"
                                } catch (_: Exception) {}
                            }
                            1 -> { // vertical left = brightness
                                val now = System.currentTimeMillis()
                                if (now - lastGestureApply > 33) {
                                    lastGestureApply = now
                                    setBrightness(startBright + (dy / size.height * 255).toInt())
                                }
                            }
                            2 -> { // vertical right = volume
                                val now = System.currentTimeMillis()
                                if (now - lastGestureApply > 33) {
                                    lastGestureApply = now
                                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    setVol(startVol + (dy / size.height * max).toInt())
                                }
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (showPlaylist || panelExpanded) {
                            // Dismiss any open panels first, then hide controls.
                            showPlaylist = false
                            panelExpanded = false
                            controlsVisible = false
                        } else {
                            controlsVisible = !controlsVisible
                        }
                        interactionCount++
                    },
                    onDoubleTap = { offset ->
                        val w = size.width
                        if (offset.x < w / 2) seek(-10.0) else seek(10.0)
                        interactionCount++
                    }
                )
            }
            // Long-press to 2x speed, release to restore
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    val downTime = System.currentTimeMillis()
                    val downX = first.position.x
                    val downY = first.position.y
                    var lastX = downX
                    var lastY = downY
                    var isSpeedMode = false

                    while (true) {
                        val event = withTimeoutOrNull(100L) {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            // Timeout  Echeck for long press activation
                            if (!isSpeedMode && !isDragging && fileLoaded) {
                                val elapsed = System.currentTimeMillis() - downTime
                                val moved = kotlin.math.hypot(
                                    (lastX - downX).toDouble(), (lastY - downY).toDouble()
                                )
                                if (elapsed > 400 && moved < 30) {
                                    isSpeedMode = true
                                    longPressSpeeding = true
                                    savedSpeedValue = speed
                                    speed = 2.0
                                    try { player.setProperty("speed", "2.0") } catch (_: Exception) {}
                                    osdText = "2.0x >>>"
                                }
                            }
                            continue
                        }
                        val change = event.changes.firstOrNull() ?: continue
                        lastX = change.position.x
                        lastY = change.position.y
                        if (!change.pressed) {
                            if (isSpeedMode) {
                                speed = savedSpeedValue
                                try { player.setProperty("speed", "%.2f".format(savedSpeedValue)) } catch (_: Exception) {}
                                longPressSpeeding = false
                                osdText = ""
                            }
                            break
                        }
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                // H12: PFD ownership lives in this screen (resolveAndLoad is
                // the single source of pfd open/close). MpvRenderView just
                // notifies us when the surface is ready, then we resolve +
                // loadfile via resolveAndLoad. This eliminates the previous
                // double-open where MpvRenderView kept its own pfd alive for
                // the whole session and resolveAndLoad opened another per
                // auto-play-next ↁEFD exhaustion on long playlists.
                MpvRenderView(
                    context = ctx,
                    player = player,
                    onSurfaceReady = {
                        // Triggered from the IO coroutine inside MpvRenderView
                        // once mpv is initialized and the surface is attached.
                        // resolveAndLoad opens (and closes prior) pfd on IO.
                        scope.launch { resolveAndLoad(file.path) }
                    },
                    onSurfaceReattached = {
                        // Surface is bound again after background→foreground.
                        // If ON_RESUME requested a network reload (proxy session
                        // died), do it now — loadfile runs against a live surface
                        // so video renders, not just audio.
                        if (pendingNetworkResume.compareAndSet(true, false) && serverConfig != null) {
                            val resumeAt = position
                            val resumeSpd = speed
                            val resumeSidVal = try { player.getPropertyString("sid") } catch (_: Exception) { null }
                            val resumeAidVal = try { player.getPropertyString("aid") } catch (_: Exception) { null }
                            scope.launch {
                                pendingResume = resumeAt
                                try {
                                    resolveAndLoad(directoryVideos.getOrNull(currentIdx)?.path ?: file.path)
                                } catch (_: Exception) {}
                                pendingResumeSid = resumeSidVal
                                pendingResumeAid = resumeAidVal
                                pendingResumeSpeed = resumeSpd
                            }
                        }
                    }
                )
            },
            // H11: release the SurfaceHolder.Callback + IO scope when the
            // AndroidView leaves composition (BackHandler / onBack). Without this,
            // each player entry leaks a SupervisorJob + SurfaceHolder.Callback
            // capturing player/context/filePath for the life of the process.
            onRelease = { it.release() },
            modifier = Modifier.fillMaxSize()
        )

        // OSD — WindMotion: fade + scale so feedback feels alive (was hard cut).
        AnimatedVisibility(
            visible = osdText.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard)) +
                scaleIn(initialScale = 0.92f, animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard)),
            exit = fadeOut(animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingExit)) +
                scaleOut(targetScale = 0.96f, animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingExit)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color(0x99000000),
                shape = WindRadius.Consent
            ) {
                Text(osdText, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }

        // Top bar
        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column {
                // Title row
                Surface(Modifier.fillMaxWidth(), color = Color(0x99000000)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { PhosphorIcon(Phosphor.ARROW_LEFT, "Back", tint = Color.White, size = 24.dp) }
                        Text(directoryVideos.getOrNull(currentIdx)?.name ?: file.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                // Collapsible panel row  Eright-aligned, slides in from right
                Surface(Modifier.fillMaxWidth(), color = Color(0x99000000)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Expandable buttons  Eslide in from the right
                        androidx.compose.animation.AnimatedVisibility(
                            visible = panelExpanded,
                            enter = expandHorizontally(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard), expandFrom = Alignment.End) + fadeIn(),
                            exit = shrinkHorizontally(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingExit), shrinkTowards = Alignment.End) + fadeOut()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = ::toggleSpeed) {
                                    PhosphorIcon(Phosphor.GAUGE, "Speed", tint = Color.White, size = 22.dp)
                                }
                                IconButton(onClick = { showTracks = true }) {
                                    PhosphorIcon(Phosphor.SUBTITLES, "Tracks", tint = Color.White, size = 22.dp)
                                }
                                IconButton(onClick = {
                                    player.command("screenshot", "subtitles")
                                    osdText = I18n.get("screenshot")
                                }) {
                                    PhosphorIcon(Phosphor.CAMERA, "Screenshot", tint = Color.White, size = 22.dp)
                                }
                                // AI Subtitle generation trigger.
                                IconButton(onClick = {
                                    if (dev.windplayer.translate.TranslateService.isRunning) {
                                        osdText = "Task already running"
                                    } else {
                                        val dur = try { player.getPropertyDouble("duration") } catch (_: Exception) { 0.0 }
                                        dev.windplayer.translate.TranslationStarter.showChoice(
                                            context = context,
                                            videoTitle = file.name,
                                            sourceUrl = file.path,
                                            duration = dur
                                        )
                                    }
                                }) {
                                    PhosphorIcon(Phosphor.GLOBE, I18n.get("generate_subtitles"), tint = Color.White, size = 22.dp)
                                }
                            }
                        }
                        // Toggle button  Ealways visible on the right edge
                        IconButton(onClick = { panelExpanded = !panelExpanded; interactionCount++ }) {
                            PhosphorIcon(
                                if (panelExpanded) Phosphor.CARET_UP else Phosphor.CARET_DOWN,
                                "Panel", tint = WindColors.LightSignalOrange, size = 28.dp
                            )
                        }
                        // Playlist toggle  Esame size, opens right-side video list
                        IconButton(onClick = { showPlaylist = !showPlaylist; interactionCount++ }) {
                            PhosphorIcon(Phosphor.QUEUE, "Playlist", tint = if (showPlaylist) WindColors.LightSignalOrange else Color.White, size = 28.dp)
                        }
                    }
                }
            }
        }

        // Bottom controls
        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(Modifier.fillMaxWidth(), color = Color(0x99000000)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (duration > 0) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDuration(position), color = Color.White, fontSize = 11.sp)
                            Slider(
                                value = position.toFloat().coerceIn(0f, duration.toFloat()),
                                onValueChange = { isScrubbing = true; position = it.toDouble() },
                                onValueChangeFinished = {
                                    player.setProperty("time-pos", "%.3f".format(position))
                                    isScrubbing = false
                                },
                                valueRange = 0f..duration.toFloat(),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = WindColors.LightSignalOrange,
                                    activeTrackColor = WindColors.LightSignalOrange,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                                )
                            )
                            Text(formatDuration(duration), color = Color.White, fontSize = 11.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { playPrev() }, enabled = currentIdx > 0) {
                            PhosphorIcon(Phosphor.SKIP_BACK, "Previous", tint = Color.White, size = 28.dp)
                        }
                        IconButton(onClick = { seek(-10.0) }) {
                            PhosphorIcon(Phosphor.REWIND, "Rewind", tint = Color.White, size = 28.dp)
                        }
                        IconButton(onClick = {
                            player.command("cycle", "pause")
                            isPlaying = !isPlaying
                        }, modifier = Modifier.size(48.dp)) {
                            PhosphorIcon(if (isPlaying) Phosphor.PAUSE else Phosphor.PLAY, "Play", tint = Color.White, size = 36.dp)
                        }
                        IconButton(onClick = { seek(10.0) }) {
                            PhosphorIcon(Phosphor.FAST_FORWARD, "Forward", tint = Color.White, size = 28.dp)
                        }
                        IconButton(onClick = { playNext() }, enabled = currentIdx + 1 < directoryVideos.size) {
                            PhosphorIcon(Phosphor.SKIP_FORWARD, "Next", tint = Color.White, size = 28.dp)
                        }
                    }
                }
            }
        }

        // WindMotion: fade in/out the loading + error overlays so they don't
        // snap on top of the player surface.
        AnimatedVisibility(
            visible = !fileLoaded && errorMsg.isEmpty(),
            enter = fadeIn(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard)),
            exit = fadeOut(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingExit)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            CircularProgressIndicator(color = WindColors.LightSignalOrange)
        }

        AnimatedVisibility(
            visible = errorMsg.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard)) +
                scaleIn(initialScale = 0.96f, animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard)),
            exit = fadeOut(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingExit)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(I18n.get("playback_error"), color = WindColors.SignalOrange, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(errorMsg, color = WindColors.MediaMuted, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text(I18n.get("back")) }
            }
        }
        // Right-side playlist panel  Eslides in from right
        AnimatedVisibility(
            visible = showPlaylist,
            enter = slideInHorizontally(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard), initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingExit), targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                Modifier.width(280.dp).fillMaxHeight(),
                color = Color(0xE6141413)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${I18n.get("playlist")} (${directoryVideos.size})", color = WindColors.MediaCream, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showPlaylist = false }) {
                            PhosphorIcon(Phosphor.X, "Close", tint = WindColors.MediaMuted, size = 20.dp)
                        }
                    }
                    HorizontalDivider(color = WindColors.MediaCream.copy(alpha = 0.12f))
                    LazyColumn(Modifier.weight(1f)) {
                        items(directoryVideos.size, key = { directoryVideos[it].path }) { i ->
                            val video = directoryVideos[i]
                            val isCurrent = i == currentIdx
                            Surface(
                                Modifier.fillMaxWidth().clickable {
                                    if (i != currentIdx) {
                                        val leavingPath = directoryVideos.getOrNull(currentIdx)?.path
                                        currentIdx = i
                                        showPlaylist = false
                                        scope.launch(Dispatchers.IO) {
                                            if (leavingPath != null) captureThumbnailForPath(leavingPath)
                                            resolveAndLoad(video.path)
                                        }
                                    }
                                },
                                color = if (isCurrent) WindColors.LightSignalOrange.copy(alpha = 0.15f) else Color.Transparent
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}", color = WindColors.MediaMuted, fontSize = 12.sp, modifier = Modifier.width(32.dp))
                                    Text(video.name, color = if (isCurrent) WindColors.MediaCream else WindColors.MediaMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (isCurrent) {
                                        PhosphorIcon(Phosphor.PLAY, "Playing", tint = WindColors.LightSignalOrange, size = 20.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTracks) {
        ModalBottomSheet(onDismissRequest = { showTracks = false }, containerColor = WindColors.MediaInk) {
            TrackSelectionContent(
                player = player,
                onDismiss = { showTracks = false }
            )
        }
    }
}

/**
 * Track selection bottom sheet.
 *
 * All mpv property reads/writes run on [Dispatchers.IO] so the UI never
 * blocks. Selecting a track dismisses the sheet immediately and applies
 * the change in the background.
 */
@Composable
private fun TrackSelectionContent(
    player: MpvPlayer,
    onDismiss: () -> Unit
) {
    var tabIndex by remember { mutableStateOf(0) }
    val tabs = listOf(I18n.get("video_track"), I18n.get("audio_track"), I18n.get("subtitle_track"))
    val scope = rememberCoroutineScope()
    val prop = when (tabIndex) { 0 -> "vid"; 1 -> "aid"; else -> "sid" }
    val trackType = when (tabIndex) { 0 -> "video"; 1 -> "audio"; else -> "sub" }

    var currentId by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }

    // Read track list + current selection off the main thread.
    LaunchedEffect(tabIndex) {
        withContext(Dispatchers.IO) {
            currentId = try { player.getPropertyString(prop) ?: "" } catch (_: Exception) { "" }
            val count = try { player.getPropertyLong("track-list/count").toInt() } catch (_: Exception) { 0 }
            val list = mutableListOf<Triple<String, String, String>>()
            for (i in 0 until count) {
                val type = try { player.getPropertyString("track-list/$i/type") ?: "" } catch (_: Exception) { "" }
                if (type != trackType) continue
                val tid = try { player.getPropertyString("track-list/$i/id") ?: "" } catch (_: Exception) { "" }
                val lang = try { player.getPropertyString("track-list/$i/lang") ?: "" } catch (_: Exception) { "" }
                val title = try { player.getPropertyString("track-list/$i/title") ?: "" } catch (_: Exception) { "" }
                list.add(Triple(tid, lang, title))
            }
            tracks = list
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        PrimaryTabRow(selectedTabIndex = tabIndex, containerColor = WindColors.MediaInk, contentColor = WindColors.LightSignalOrange) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(title, color = if (i == tabIndex) WindColors.LightSignalOrange else WindColors.MediaMuted) })
            }
        }
        Spacer(Modifier.height(8.dp))

        // Off button
        Surface(
            modifier = Modifier.fillMaxWidth().clickable {
                onDismiss()
                currentId = "no"
                scope.launch(Dispatchers.IO) {
                    try { player.setProperty(prop, "no") } catch (_: Exception) {}
                }
            },
            color = if (currentId == "no") WindColors.LightSignalOrange.copy(alpha = 0.15f) else Color.Transparent
        ) {
            Text(I18n.get("none"), color = if (currentId == "no") WindColors.LightSignalOrange else WindColors.MediaMuted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }

        for ((tid, lang, title) in tracks) {
            val selected = currentId == tid
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    onDismiss()
                    currentId = tid
                    scope.launch(Dispatchers.IO) {
                        try { player.setProperty(prop, tid) } catch (_: Exception) {}
                    }
                },
                color = if (selected) WindColors.LightSignalOrange.copy(alpha = 0.15f) else Color.Transparent
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#$tid", color = WindColors.MediaMuted, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title.ifBlank { lang.ifBlank { String.format(I18n.get("track_n"), tid) } }, color = WindColors.MediaCream, fontSize = 14.sp)
                        if (lang.isNotEmpty() && title.isNotEmpty()) Text(lang, color = WindColors.MediaMuted, fontSize = 11.sp)
                    }
                    if (selected) PhosphorIcon(Phosphor.CHECK, null, tint = WindColors.LightSignalOrange, size = 18.dp)
                }
            }
        }
    }
}

private fun fmt(d: Double): String = if (d >= 0) "+%ds".format(d.toInt()) else "%ds".format(d.toInt())
