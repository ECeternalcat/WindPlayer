package dev.windplayer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
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
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobilePlayerScreen(
    player: MpvPlayer,
    file: FileNode,
    onBack: () -> Unit,
    serverConfig: ServerConfig? = null,
    directoryVideos: List<FileNode> = emptyList(),
    currentIndex: Int = 0,
    autoPlayNext: Boolean = false
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
    var isDragging by remember { mutableStateOf(false) }
    var interactionCount by remember { mutableIntStateOf(0) }
    var currentIdx by remember { mutableIntStateOf(currentIndex) }
    var currentPfd by remember { mutableStateOf<android.os.ParcelFileDescriptor?>(null) }
    var brightnessLevel by remember { mutableStateOf(0) }  // -100..100, persisted across drags

    suspend fun resolveAndLoad(path: String) {
        try { currentPfd?.close() } catch (_: Exception) {}
        currentPfd = null
        val loadPath = if (path.startsWith("content://")) {
            try {
                val uri = android.net.Uri.parse(path)
                currentPfd = context.contentResolver.openFileDescriptor(uri, "r")
                "fd://${currentPfd?.fd}"
            } catch (_: Exception) { path }
        } else if (serverConfig != null) {
            try { MobileVfsManager.resolveUrl(serverConfig, path) } catch (_: Exception) { path }
        } else { path }
        player.command("loadfile", loadPath)
    }

    BackHandler { onBack() }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Pause playback when the screen goes background; resume on foreground.
        val lifecycleOwner = lifecycleOwner
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    try { player.setProperty("pause", "yes") } catch (_: Exception) {}
                }
                Lifecycle.Event.ON_RESUME -> {
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
            // Restore system default brightness
            window?.attributes = window?.attributes?.apply { this.screenBrightness = -1f }
            // Release any open ParcelFileDescriptor
            try { currentPfd?.close() } catch (_: Exception) {}
            currentPfd = null
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
                        volume = player.getPropertyLong("volume").toInt()
                        speed = player.getPropertyDouble("speed")
                    } catch (_: Exception) {}
                }
                is MpvEvent.EndFile -> {
                    if (event.reason == 4) errorMsg = "Playback failed"
                    else if (autoPlayNext && event.reason == 0 && currentIdx + 1 < directoryVideos.size) {
                        currentIdx++
                        val nextFile = directoryVideos[currentIdx]
                        osdText = ">> ${nextFile.name}"
                        scope.launch { resolveAndLoad(nextFile.path) }
                    }
                }
                else -> {}
            }
        }
    }

    LaunchedEffect(fileLoaded) {
        while (fileLoaded) {
            delay(200)
            try {
                isPlaying = player.getPropertyString("pause") != "yes"
                if (!isDragging) position = player.getPropertyDouble("time-pos")
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

    fun setVol(v: Int) {
        val clamped = v.coerceIn(0, 100)
        try { player.setProperty("volume", clamped.toString()) } catch (_: Exception) {}
        volume = clamped
        osdText = "Vol: $clamped%"
    }

    fun setBrightness(v: Int) {
        // Map -100..100 to 0.05..1.0 brightness (avoid 0 = fully black).
        // 0 maps to ~0.525 (slightly above mid). Negative = dimmer, positive = brighter.
        val clamped = v.coerceIn(-100, 100)
        brightnessLevel = clamped
        val brightness = (clamped + 100) / 200f * 0.95f + 0.05f
        val activity = context as? android.app.Activity
        val window = activity?.window
        window?.attributes = window?.attributes?.apply { this.screenBrightness = brightness }
        osdText = "Brightness: $clamped%"
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
                var startX = 0f; var startY = 0f; var mode = 0
                var startPos = 0.0; var startVol = 0; var startBright = 0
                detectDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x; startY = offset.y
                        isDragging = true
                        val w = size.width
                        mode = if (offset.x < w / 3) 1 else if (offset.x > w * 2 / 3) 2 else 0
                        try {
                            startPos = player.getPropertyDouble("time-pos")
                            startVol = player.getPropertyLong("volume").toInt()
                        } catch (_: Exception) {}
                        startBright = brightnessLevel
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        val dx = change.position.x - startX
                        val dy = startY - change.position.y
                        when (mode) {
                            0 -> { // center = seek
                                try {
                                    val dur = player.getPropertyDouble("duration")
                                    val target = (startPos + dx * 0.5).coerceIn(0.0, dur)
                                    position = target
                                    player.setProperty("time-pos", "%.3f".format(target))
                                    osdText = "${formatDuration(target)} / ${formatDuration(dur)}"
                                } catch (_: Exception) {}
                            }
                            1 -> setBrightness(startBright + (dy * 0.5).toInt())
                            2 -> setVol(startVol + (dy * 0.5).toInt())
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible; interactionCount++ },
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
                MpvRenderView(ctx, player, file.path, serverConfig) {
                    fileLoaded = true; isPlaying = true
                    try { duration = player.getPropertyDouble("duration") } catch (_: Exception) {}
                }
            },
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
            Surface(Modifier.fillMaxWidth(), color = Color(0x99000000)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                    Text(file.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                        IconButton(onClick = { seek(-10.0) }) {
                            Text("-10s", color = Color.White, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            player.command("cycle", "pause")
                            isPlaying = !isPlaying
                        }, modifier = Modifier.size(48.dp)) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                        IconButton(onClick = { seek(10.0) }) {
                            Text("+10s", color = Color.White, fontSize = 12.sp)
                        }
                        IconButton(onClick = ::toggleSpeed) {
                            Icon(Icons.Default.Speed, "Speed", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        IconButton(onClick = { showTracks = true }) {
                            Icon(Icons.Default.Subtitles, "Tracks", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        IconButton(onClick = {
                            player.command("screenshot")
                            osdText = "Screenshot"
                        }) {
                            Text("📷", fontSize = 20.sp)
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
    }

    // Track selection
    if (showTracks) {
        ModalBottomSheet(onDismissRequest = { showTracks = false }, containerColor = Color(0xFF1A1A2E)) {
            TrackSelectionContent(player)
        }
    }
}

@Composable
private fun TrackSelectionContent(player: MpvPlayer) {
    var tabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Video", "Audio", "Subtitle")

    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        TabRow(selectedTabIndex = tabIndex, containerColor = Color(0xFF1A1A2E), contentColor = Color(0xFF0F84E4)) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(title, color = if (i == tabIndex) Color(0xFF0F84E4) else Color(0xFF888888)) })
            }
        }
        Spacer(Modifier.height(8.dp))
        val prop = when (tabIndex) { 0 -> "vid"; 1 -> "aid"; else -> "sid" }
        val trackType = when (tabIndex) { 0 -> "video"; 1 -> "audio"; else -> "sub" }
        var currentId by remember(tabIndex) { mutableStateOf(player.getPropertyString(prop) ?: "1") }

        // Off button
        Surface(
            modifier = Modifier.fillMaxWidth().clickable {
                try { player.setProperty(prop, "no"); currentId = "no" } catch (_: Exception) {}
            },
            color = if (currentId == "no") Color(0xFF1A2A4E) else Color.Transparent
        ) {
            Text("Off", color = if (currentId == "no") Color(0xFF0F84E4) else Color(0xFFCCCCCC), fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }

        val count = try { player.getPropertyLong("track-list/count").toInt() } catch (_: Exception) { 0 }
        for (i in 0 until count) {
            val type = try { player.getPropertyString("track-list/$i/type") ?: "" } catch (_: Exception) { "" }
            if (type != trackType) continue
            val tid = try { player.getPropertyString("track-list/$i/id") ?: "" } catch (_: Exception) { "" }
            val lang = try { player.getPropertyString("track-list/$i/lang") ?: "" } catch (_: Exception) { "" }
            val title = try { player.getPropertyString("track-list/$i/title") ?: "" } catch (_: Exception) { "" }
            val selected = currentId == tid

            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    try { player.setProperty(prop, tid); currentId = tid } catch (_: Exception) {}
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
