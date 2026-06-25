package dev.windplayer

import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlaylistPlay
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
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.MatchedTrackType
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.StreamProxy
import dev.windplayer.vfs.VfsProtocol
import dev.windplayer.vfs.formatDuration
import dev.windplayer.vfs.isVideo
import dev.windplayer.vfs.matchExternalTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    onPositionUpdate: (String, Double, Double) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var isDragging by remember { mutableStateOf(false) }
    var interactionCount by remember { mutableIntStateOf(0) }
    var currentIdx by remember { mutableIntStateOf(currentIndex) }
    var currentPfd by remember { mutableStateOf<android.os.ParcelFileDescriptor?>(null) }
    val streamProxy = remember { StreamProxy() }
    var streamSessionIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingSubtitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var subtitlesAdded by remember { mutableStateOf(false) }
    var eofHandled by remember { mutableStateOf(false) }
    var pendingResume by remember { mutableStateOf(resumePosition) }

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
            try { currentPfd?.close() } catch (_: Exception) {}
            currentPfd = null
            streamSessionIds.forEach { streamProxy.closeSession(it) }
            streamSessionIds = emptyList()
            pendingSubtitles = emptyList()
            subtitlesAdded = false
            eofHandled = false
            pendingResume = 0.0

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

            // player.command is fine to call from IO; MpvPlayer serializes via its own lock.
            player.command("loadfile", loadPath)
            // keep-open=yes leaves the player paused at EOF; explicitly resume
            // so the next file in auto-play starts immediately.
            try { player.setProperty("pause", "no") } catch (_: Exception) {}

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
     * Capture current frame as thumbnail (for history display), then exit.
     * Screenshot is taken BEFORE stop/dispose so the frame is still valid.
     * The ~100ms encode stall is acceptable at exit time.
     */
    fun captureThumbAndExit() {
        val curPath = directoryVideos.getOrNull(currentIdx)?.path ?: file.path
        try {
            val safeName = "thumb_${curPath.hashCode().toString(16)}.jpg"
            val thumbFile = java.io.File(context.cacheDir, safeName)
            player.command("screenshot-to-file", thumbFile.absolutePath, "video")
            if (thumbFile.exists() && thumbFile.length() > 0) {
                HistoryStore.updateThumbnail(context, curPath, thumbFile.absolutePath)
            }
        } catch (_: Exception) {}
        onBack()
    }

    var backPressedOnce by remember { mutableStateOf(false) }
    BackHandler {
        if (backPressedOnce) {
            captureThumbAndExit()
        } else {
            backPressedOnce = true
            Toast.makeText(context, "Press back again to exit playback", Toast.LENGTH_SHORT).show()
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
                    try { player.setProperty("pause", "no") } catch (_: Exception) {}
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
            window?.attributes = window?.attributes?.apply { this.screenBrightness = -1f }
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
            // Release any open ParcelFileDescriptor
            try { currentPfd?.close() } catch (_: Exception) {}
            currentPfd = null
            // Tear down the local SFTP HTTP proxy and its sessions.
            streamSessionIds.forEach { streamProxy.closeSession(it) }
            streamSessionIds = emptyList()
            try { streamProxy.stop() } catch (_: Exception) {}
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
                            currentIdx++
                            val nextFile = directoryVideos[currentIdx]
                            osdText = ">> ${nextFile.name}"
                            scope.launch { resolveAndLoad(nextFile.path) }
                        } else {
                            onBack()
                        }
                    }
                }
                else -> {}
            }
        }
    }

    LaunchedEffect(fileLoaded) {
        var posCounter = 0
        while (fileLoaded) {
            delay(200)
            try {
                isPlaying = player.getPropertyString("pause") != "yes"
                if (!isDragging) position = player.getPropertyDouble("time-pos")

                val curPath = directoryVideos.getOrNull(currentIdx)?.path

                // Save playback position every ~5 seconds (25 × 200ms)
                if (++posCounter >= 25) {
                    posCounter = 0
                    if (curPath != null && position > 0) {
                        onPositionUpdate(curPath, position, duration)
                    }
                }

                // keep-open=yes prevents EndFile from firing on natural EOF,
                // so we poll eof-reached to detect playback completion.
                if (!eofHandled && player.getPropertyString("eof-reached") == "yes") {
                    eofHandled = true
                    controlsVisible = false
                    if (autoPlayNext && currentIdx + 1 < directoryVideos.size) {
                        currentIdx++
                        val nextFile = directoryVideos[currentIdx]
                        osdText = ">> ${nextFile.name}"
                        scope.launch { resolveAndLoad(nextFile.path) }
                    } else {
                        // No next file or auto-play disabled → return to file list.
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
            osdText = "${fmt(delta)} → ${formatDuration(target)} / ${formatDuration(dur)}"
        } catch (_: Exception) {}
    }

    fun playNext() {
        if (currentIdx + 1 < directoryVideos.size) {
            currentIdx++
            val nextFile = directoryVideos[currentIdx]
            osdText = ">> ${nextFile.name}"
            scope.launch { resolveAndLoad(nextFile.path) }
        }
    }

    fun playPrev() {
        if (currentIdx > 0) {
            currentIdx--
            val prevFile = directoryVideos[currentIdx]
            osdText = "<< ${prevFile.name}"
            scope.launch { resolveAndLoad(prevFile.path) }
        }
    }

    fun setVol(v: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = v.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        volume = (clamped.toFloat() / max * 100).toInt()
        osdText = "Vol: $clamped/$max"
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
        }
        val window = (context as? android.app.Activity)?.window
        window?.attributes = window?.attributes?.apply { this.screenBrightness = clamped / 255f }
        osdText = "Brightness: ${clamped * 100 / 255}%"
    }

    fun toggleSpeed() {
        val speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
        val idx = speeds.indexOf(speed).let { if (it < 0) 2 else it }
        speed = speeds[(idx + 1) % speeds.size]
        try { player.setProperty("speed", "%.2f".format(speed)) } catch (_: Exception) {}
        osdText = "Speed: %.2fx".format(speed)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                var startX = 0f; var startY = 0f; var mode = -1
                var startPos = 0.0; var startVol = 0; var startBright = 0
                var directionLocked = false
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
                                    osdText = "${fmt(deltaSec)} → ${formatDuration(target)} / ${formatDuration(dur)}"
                                } catch (_: Exception) {}
                            }
                            1 -> { // vertical left = brightness
                                setBrightness(startBright + (dy / size.height * 255).toInt())
                            }
                            2 -> { // vertical right = volume
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                setVol(startVol + (dy / size.height * max).toInt())
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
                    },
                    onLongPress = { showTracks = true }
                )
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
                // auto-play-next → FD exhaustion on long playlists.
                MpvRenderView(
                    context = ctx,
                    player = player,
                    onSurfaceReady = {
                        // Triggered from the IO coroutine inside MpvRenderView
                        // once mpv is initialized and the surface is attached.
                        // resolveAndLoad opens (and closes prior) pfd on IO.
                        scope.launch { resolveAndLoad(file.path) }
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

        // OSD
        if (osdText.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0x99000000),
                shape = RoundedCornerShape(8.dp)
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
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                        Text(directoryVideos.getOrNull(currentIdx)?.name ?: file.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                // Collapsible panel row — right-aligned, slides in from right
                Surface(Modifier.fillMaxWidth(), color = Color(0x99000000)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Expandable buttons — slide in from the right
                        androidx.compose.animation.AnimatedVisibility(
                            visible = panelExpanded,
                            enter = expandHorizontally(animationSpec = tween(250), expandFrom = Alignment.End) + fadeIn(),
                            exit = shrinkHorizontally(animationSpec = tween(250), shrinkTowards = Alignment.End) + fadeOut()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = ::toggleSpeed) {
                                    Icon(Icons.Default.Speed, "Speed", tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                                IconButton(onClick = { showTracks = true }) {
                                    Icon(Icons.Default.Subtitles, "Tracks", tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                                IconButton(onClick = {
                                    player.command("screenshot", "subtitles")
                                    osdText = "Screenshot"
                                }) {
                                    Icon(Icons.Outlined.PhotoCamera, "Screenshot", tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                        // Toggle button — always visible on the right edge
                        IconButton(onClick = { panelExpanded = !panelExpanded; interactionCount++ }) {
                            Icon(
                                if (panelExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                "Panel", tint = Color(0xFF0F84E4), modifier = Modifier.size(28.dp)
                            )
                        }
                        // Playlist toggle — same size, opens right-side video list
                        IconButton(onClick = { showPlaylist = !showPlaylist; interactionCount++ }) {
                            Icon(Icons.Outlined.PlaylistPlay, "Playlist", tint = if (showPlaylist) Color(0xFF0F84E4) else Color.White, modifier = Modifier.size(28.dp))
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
                                onValueChange = { position = it.toDouble() },
                                onValueChangeFinished = { player.setProperty("time-pos", "%.3f".format(position)) },
                                valueRange = 0f..duration.toFloat(),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF0F84E4), activeTrackColor = Color(0xFF0F84E4))
                            )
                            Text(formatDuration(duration), color = Color.White, fontSize = 11.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { playPrev() }, enabled = currentIdx > 0) {
                            Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = { seek(-10.0) }) {
                            Icon(Icons.Default.FastRewind, "Rewind", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = {
                            player.command("cycle", "pause")
                            isPlaying = !isPlaying
                        }, modifier = Modifier.size(48.dp)) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                        IconButton(onClick = { seek(10.0) }) {
                            Icon(Icons.Default.FastForward, "Forward", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = { playNext() }, enabled = currentIdx + 1 < directoryVideos.size) {
                            Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }

        if (!fileLoaded && errorMsg.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF0F84E4))
        }

        if (errorMsg.isNotEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Playback Error", color = Color(0xFFFF4444), fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(errorMsg, color = Color(0xFFCCCCCC), fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Back") }
            }
        }

        // Right-side playlist panel — slides in from right
        AnimatedVisibility(
            visible = showPlaylist,
            enter = slideInHorizontally(animationSpec = tween(250), initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(animationSpec = tween(250), targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                Modifier.width(280.dp).fillMaxHeight(),
                color = Color(0xE61A1A2E)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Playlist (${directoryVideos.size})", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showPlaylist = false }) {
                            Icon(Icons.Default.ArrowBack, "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    HorizontalDivider(color = Color(0xFF333366))
                    LazyColumn(Modifier.weight(1f)) {
                        items(directoryVideos.size, key = { directoryVideos[it].path }) { i ->
                            val video = directoryVideos[i]
                            val isCurrent = i == currentIdx
                            Surface(
                                Modifier.fillMaxWidth().clickable {
                                    if (i != currentIdx) {
                                        currentIdx = i
                                        showPlaylist = false
                                        scope.launch { resolveAndLoad(video.path) }
                                    }
                                },
                                color = if (isCurrent) Color(0xFF1A2A4E) else Color.Transparent
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}", color = Color(0xFF888888), fontSize = 12.sp, modifier = Modifier.width(32.dp))
                                    Text(video.name, color = if (isCurrent) Color(0xFF0F84E4) else Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (isCurrent) {
                                        Icon(Icons.Default.PlayArrow, "Playing", tint = Color(0xFF0F84E4), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Track selection
    if (showTracks) {
        ModalBottomSheet(onDismissRequest = { showTracks = false }, containerColor = Color(0xFF1A1A2E)) {
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
    val tabs = listOf("Video", "Audio", "Subtitle")
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
        TabRow(selectedTabIndex = tabIndex, containerColor = Color(0xFF1A1A2E), contentColor = Color(0xFF0F84E4)) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(title, color = if (i == tabIndex) Color(0xFF0F84E4) else Color(0xFF888888)) })
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
            color = if (currentId == "no") Color(0xFF1A2A4E) else Color.Transparent
        ) {
            Text("Off", color = if (currentId == "no") Color(0xFF0F84E4) else Color(0xFFCCCCCC), fontSize = 14.sp, modifier = Modifier.padding(16.dp))
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
                color = if (selected) Color(0xFF1A2A4E) else Color.Transparent
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#$tid", color = Color(0xFF888888), fontSize = 12.sp, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title.ifBlank { lang.ifBlank { "Track $tid" } }, color = Color.White, fontSize = 14.sp)
                        if (lang.isNotEmpty() && title.isNotEmpty()) Text(lang, color = Color(0xFF888888), fontSize = 11.sp)
                    }
                    if (selected) Text("●", color = Color(0xFF0F84E4), fontSize = 16.sp)
                }
            }
        }
    }
}

private fun fmt(d: Double): String = if (d >= 0) "+%ds".format(d.toInt()) else "%ds".format(d.toInt())
