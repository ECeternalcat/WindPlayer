package dev.windplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.mpv.MpvEvent
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.vfs.VfsManager
import dev.windplayer.vfs.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    player: MpvPlayer,
    initialFilePath: String = "",
    initialSubtitleFiles: List<String> = emptyList(),
    initialExternalAudioUrls: List<String> = emptyList(),
    initialMpvOptions: Map<String, String> = emptyMap(),
    onBack: (() -> Unit)? = null,
    onTracksToggle: ((Boolean) -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    osdEvents: SharedFlow<String>? = null,
    vfsManager: VfsManager? = null,
    playbackServerId: String? = null,
    playbackDirPath: String? = null,
    playbackIsLocal: Boolean = false,
    directoryVideoPaths: List<String> = emptyList(),
    currentFileIndex: Int = -1,
    onPlayNextFile: ((filePath: String) -> Unit)? = null,
    onJumpToFile: ((filePath: String) -> Unit)? = null,
    onOsdEvent: ((String) -> Unit)? = null,
    resumePosition: Double = 0.0,
    filePath: String = "",
    playlistToggle: SharedFlow<Unit>? = null,
    cheatsheetToggle: SharedFlow<Unit>? = null,
    onPositionUpdate: ((filePath: String, position: Double, duration: Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var statusText by remember { mutableStateOf("Ready") }
    var fileLoaded by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekTarget by remember { mutableStateOf(0.0) }
    var subtitlesAdded by remember { mutableStateOf(false) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(100L) }
    var isMuted by remember { mutableStateOf(false) }
    var isVolumeDragging by remember { mutableStateOf(false) }
    var hwdecAuto by remember { mutableStateOf(true) }
    var speed by remember { mutableStateOf(1.0) }
    var osdText by remember { mutableStateOf("") }
    var eofAutoPlayed by remember { mutableStateOf(false) }
    var resumeApplied by remember { mutableStateOf(false) }
    var showCheatsheet by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        launch {
            var posUpdateCounter = 0
            while (true) {
                delay(200)
                if (!fileLoaded) continue
                try {
                    isPlaying = player.getPropertyString("pause") != "yes"
                    if (!isSeeking) {
                        val pos = player.getPropertyDouble("time-pos")
                        if (pos >= 0) position = pos
                    }
                    val dur = player.getPropertyDouble("duration")
                    if (dur > 0) duration = dur
                } catch (_: Exception) {}

                if (!eofAutoPlayed && duration > 0 && position >= duration - 1.0
                    && currentFileIndex >= 0 && onPlayNextFile != null
                    && directoryVideoPaths.isNotEmpty()
                ) {
                    try {
                        if (player.getPropertyString("eof-reached") == "yes") {
                            eofAutoPlayed = true
                            val nextIndex = currentFileIndex + 1
                            if (nextIndex < directoryVideoPaths.size) {
                                val nextPath = directoryVideoPaths[nextIndex]
                                val nextName = nextPath.substringAfterLast('/').substringAfterLast('\\')
                                statusText = "Next: $nextName"
                                fileLoaded = false
                                onOsdEvent?.invoke(">> Next: $nextName")
                                onPlayNextFile.invoke(nextPath)
                            } else {
                                statusText = "Playlist complete"
                                onOsdEvent?.invoke("Playlist complete")
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (++posUpdateCounter >= 25) {
                    posUpdateCounter = 0
                    val fp = filePath.ifBlank { directoryVideoPaths.getOrNull(currentFileIndex) ?: initialFilePath }
                    onPositionUpdate?.invoke(fp, position, duration)
                }
            }
        }
        launch {
            while (true) {
                delay(1000)
                if (!fileLoaded) continue
                try {
                    if (!isVolumeDragging) {
                        volume = player.getPropertyLong("volume")
                        isMuted = player.getPropertyString("mute") == "yes"
                    }
                    speed = player.getPropertyDouble("speed")
                } catch (_: Exception) {}
            }
        }
        launch {
            player.events.collect { event ->
                when (event) {
                    is MpvEvent.FileLoaded -> {
                        fileLoaded = true
                        isPlaying = true
                        try {
                            val dur = player.getPropertyDouble("duration")
                            if (dur > 0) duration = dur
                        } catch (_: Exception) {}
                        val fileName = player.getPropertyString("filename") ?: "unknown"
                        statusText = fileName

                        if (!subtitlesAdded && initialSubtitleFiles.isNotEmpty()) {
                            initialSubtitleFiles.forEach { subPath ->
                                try {
                                    player.command("sub-add", subPath)
                                } catch (_: Exception) {}
                            }
                            subtitlesAdded = true
                        }

                        if (!resumeApplied && resumePosition > 1.0) {
                            resumeApplied = true
                            try {
                                player.setProperty("time-pos", "%.3f".format(resumePosition))
                                position = resumePosition
                            } catch (_: Exception) {}
                        }
                    }
                    is MpvEvent.EndFile -> {
                        if (event.reason == 4) {
                            isPlaying = false
                            fileLoaded = false
                            statusText = "Error: mpv failed to open file"
                        } else if (fileLoaded) {
                            isPlaying = false
                            val shouldAutoPlay = (event.reason == 0 || event.reason == 2)
                                && currentFileIndex >= 0
                                && onPlayNextFile != null
                                && directoryVideoPaths.isNotEmpty()
                            if (shouldAutoPlay) {
                                val nextIndex = currentFileIndex + 1
                                if (nextIndex < directoryVideoPaths.size) {
                                    val nextPath = directoryVideoPaths[nextIndex]
                                    val nextName = nextPath.substringAfterLast('/').substringAfterLast('\\')
                                    statusText = "Next: $nextName"
                                    fileLoaded = false
                                    onOsdEvent?.invoke(">> Next: $nextName")
                                    onPlayNextFile.invoke(nextPath)
                                } else {
                                    statusText = "Playlist complete"
                                    onOsdEvent?.invoke("Playlist complete")
                                }
                            } else {
                                statusText = if (event.reason == 0) "Ended" else "Stopped"
                            }
                        }
                    }
                    is MpvEvent.Error -> {
                        statusText = "Error: ${event.message}"
                    }
                    else -> {}
                }
            }
        }
    }

    LaunchedEffect(osdEvents) {
        if (osdEvents == null) return@LaunchedEffect
        osdEvents.collectLatest { text ->
            osdText = text
            delay(2000)
            osdText = ""
        }
    }

    LaunchedEffect(playlistToggle) {
        if (playlistToggle == null) return@LaunchedEffect
        playlistToggle.collect {
            if (directoryVideoPaths.isNotEmpty()) {
                showPlaylist = !showPlaylist
                showTrackSheet = false
                onTracksToggle?.invoke(showPlaylist)
            }
        }
    }

    LaunchedEffect(cheatsheetToggle) {
        if (cheatsheetToggle == null) return@LaunchedEffect
        cheatsheetToggle.collect {
            showCheatsheet = !showCheatsheet
            onTracksToggle?.invoke(showCheatsheet || showTrackSheet || showPlaylist)
        }
    }

    LaunchedEffect(initialFilePath) {
        if (initialFilePath.isNotBlank()) {
            fileLoaded = false
            isPlaying = false
            position = 0.0
            duration = 0.0
            subtitlesAdded = false
            speed = 1.0
            isSeeking = false
            eofAutoPlayed = false
            resumeApplied = false
            statusText = "Loading..."
            for ((key, value) in initialMpvOptions) {
                player.setProperty(key, value)
            }
            for (audioUrl in initialExternalAudioUrls) {
                player.command("audio-add", audioUrl)
            }
            player.command("loadfile", initialFilePath)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val playIcon = iconPainter(PhosphorIcons.PLAY)
        val pauseIcon = iconPainter(PhosphorIcons.PAUSE)
        val speakerHighIcon = iconPainter(PhosphorIcons.SPEAKER_HIGH)
        val speakerSlashIcon = iconPainter(PhosphorIcons.SPEAKER_SLASH)
        val cornersOutIcon = iconPainter(PhosphorIcons.CORNERS_OUT)
        val cornersInIcon = iconPainter(PhosphorIcons.CORNERS_IN)

        if (duration > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = formatDuration(position), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = position.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { newPos ->
                        isSeeking = true
                        seekTarget = newPos.toDouble()
                        position = newPos.toDouble()
                    },
                    onValueChangeFinished = {
                        player.setProperty("time-pos", "%.3f".format(seekTarget))
                        isSeeking = false
                    },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF0F84E4),
                        activeTrackColor = Color(0xFF0F84E4)
                    )
                )
                Text(text = formatDuration(duration), color = Color.White, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = {
                        val fp = filePath.ifBlank { directoryVideoPaths.getOrNull(currentFileIndex) ?: initialFilePath }
                        onPositionUpdate?.invoke(fp, position, duration)
                        player.command("stop")
                        fileLoaded = false
                        isPlaying = false
                        position = 0.0
                        duration = 0.0
                        statusText = "Ready"
                        subtitlesAdded = false
                        onBack()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.ARROW_LEFT),
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            IconButton(
                onClick = {
                    if (fileLoaded) {
                        player.command("cycle", "pause")
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = if (isPlaying) pauseIcon else playIcon,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (fileLoaded) {
                IconButton(
                    onClick = {
                        showTrackSheet = !showTrackSheet
                        if (showTrackSheet) showPlaylist = false
                        onTracksToggle?.invoke(showTrackSheet || showPlaylist)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.LIST),
                        contentDescription = "Tracks",
                        tint = if (showTrackSheet) Color(0xFF0F84E4) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (fileLoaded && directoryVideoPaths.isNotEmpty()) {
                IconButton(
                    onClick = {
                        showPlaylist = !showPlaylist
                        if (showPlaylist) showTrackSheet = false
                        onTracksToggle?.invoke(showTrackSheet || showPlaylist)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.QUEUE),
                        contentDescription = "Playlist",
                        tint = if (showPlaylist) Color(0xFF0F84E4) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (osdText.isNotBlank()) {
                Text(
                    text = osdText,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            if (fileLoaded) {
                IconButton(
                    onClick = { player.command("cycle", "mute") },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = if (isMuted) speakerSlashIcon else speakerHighIcon,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Slider(
                    value = volume.toFloat().coerceIn(0f, 100f),
                    onValueChange = { newVol ->
                        isVolumeDragging = true
                        volume = newVol.toLong()
                        player.setProperty("volume", newVol.toLong().toString())
                    },
                    onValueChangeFinished = {
                        isVolumeDragging = false
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.width(80.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF0F84E4),
                        activeTrackColor = Color(0xFF0F84E4)
                    )
                )
            }

            IconButton(
                onClick = { onToggleFullscreen?.invoke() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = if (isFullscreen) cornersInIcon else cornersOutIcon,
                    contentDescription = if (isFullscreen) "Windowed" else "Fullscreen",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (fileLoaded) {
                IconButton(
                    onClick = {
                        hwdecAuto = !hwdecAuto
                        player.setProperty("hwdec", if (hwdecAuto) "auto" else "no")
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.LIGHTNING),
                        contentDescription = if (hwdecAuto) "HW Decode" else "SW Decode",
                        tint = if (hwdecAuto) Color(0xFF0F84E4) else Color(0xFF888888),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Button(
                    onClick = {
                        player.setProperty("speed", "1.00")
                        speed = 1.0
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (speed != 1.0) Color(0xFF0F84E4) else Color(0xFF2A2A3E)
                    )
                ) {
                    Text(
                        text = "%.1fx".format(speed),
                        fontSize = 10.sp,
                        color = if (speed != 1.0) Color.White else Color(0xFF888888)
                    )
                }
            }

            Text(
                text = statusText,
                color = Color(0xFF666666),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
        }

        if (showTrackSheet) {
            TrackSelectionSheet(
                player = player,
                onDismiss = {
                    showTrackSheet = false
                    onTracksToggle?.invoke(showTrackSheet || showPlaylist)
                },
                vfsManager = vfsManager,
                serverId = playbackServerId,
                dirPath = playbackDirPath,
                isLocal = playbackIsLocal
            )
        }

        if (showPlaylist) {
            PlaylistPanel(
                videoPaths = directoryVideoPaths,
                currentIndex = currentFileIndex,
                onJumpToFile = { path ->
                    showPlaylist = false
                    onTracksToggle?.invoke(false)
                    onJumpToFile?.invoke(path)
                },
                onDismiss = {
                    showPlaylist = false
                    onTracksToggle?.invoke(false)
                }
            )
        }

        if (showCheatsheet) {
            CheatsheetOverlay(onDismiss = {
                showCheatsheet = false
                onTracksToggle?.invoke(showTrackSheet || showPlaylist)
            })
        }
    }
}

@Composable
private fun PlaylistPanel(
    videoPaths: List<String>,
    currentIndex: Int,
    onJumpToFile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF12121E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = iconPainter(PhosphorIcons.QUEUE),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${I18n.get("playlist")} (${videoPaths.size})",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = iconPainter(PhosphorIcons.X),
                    contentDescription = "Close",
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        HorizontalDivider(color = Color(0xFF333366))
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(videoPaths.size, key = { it }) { index ->
                val path = videoPaths[index]
                val name = path.substringAfterLast('/').substringAfterLast('\\')
                val isCurrent = index == currentIndex
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onJumpToFile(path) },
                    color = if (isCurrent) Color(0xFF1A2A4E) else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCurrent) {
                            Icon(
                                painter = iconPainter(PhosphorIcons.PLAY),
                                contentDescription = null,
                                tint = Color(0xFF0F84E4),
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                color = Color(0xFF666666),
                                fontSize = 11.sp,
                                modifier = Modifier.width(14.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = name,
                            color = if (isCurrent) Color.White else Color(0xFFCCCCCC),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheatsheetOverlay(onDismiss: () -> Unit) {
    val shortcuts = listOf(
        I18n.get("playback") to listOf(
            "Space" to I18n.get("play_hint"),
            "N" to I18n.get("play_next"),
            "P" to I18n.get("playlist"),
            "A" to I18n.get("ab_a"),
            "Shift+B" to I18n.get("ab_b"),
            "Shift+A" to I18n.get("ab_clear"),
            "." to I18n.get("frame_fwd"),
            "," to I18n.get("frame_back")
        ),
        I18n.get("seek") to listOf(
            "\u2190 / \u2192" to I18n.get("seek_5"),
            "Shift+\u2190 / Shift+\u2192" to I18n.get("seek_30")
        ),
        I18n.get("volume") to listOf(
            "\u2191 / \u2193" to I18n.get("vol_5"),
            "M" to I18n.get("mute_toggle"),
            "Wheel" to I18n.get("vol_scroll")
        ),
        I18n.get("speed") to listOf(
            "[ / ]" to I18n.get("speed_ctrl"),
            "\\" to I18n.get("speed_reset")
        ),
        I18n.get("subtitle") to listOf(
            "V" to I18n.get("cycle_sub"),
            "B" to I18n.get("cycle_audio"),
            "Z / X" to I18n.get("sub_delay"),
            "G / H" to I18n.get("audio_delay")
        ),
        I18n.get("video_eq") to listOf(
            "1 / 2" to I18n.get("brightness_ctrl"),
            "3 / 4" to I18n.get("contrast_ctrl"),
            "5 / 6" to I18n.get("saturation_ctrl"),
            "7 / 8" to I18n.get("gamma_ctrl"),
            "0" to I18n.get("eq_reset")
        ),
        I18n.get("other") to listOf(
            "Enter / F11" to I18n.get("fullscreen"),
            "I" to I18n.get("pip"),
            "Esc" to I18n.get("exit_fullscreen"),
            "S" to I18n.get("screenshot"),
            "F1" to I18n.get("cheatsheet")
        ),
        I18n.get("mouse") to listOf(
            I18n.get("left_click") to I18n.get("play_hint"),
            I18n.get("double_click") to I18n.get("fullscreen"),
            I18n.get("middle_click") to I18n.get("fullscreen"),
            I18n.get("ctx_menu") to I18n.get("ctx_menu"),
            "Wheel" to I18n.get("vol_5"),
            I18n.get("drag_left") to I18n.get("brightness_ctrl"),
            I18n.get("drag_center") to I18n.get("seek_5"),
            I18n.get("drag_right") to I18n.get("vol_5"),
            I18n.get("drag_pip") to I18n.get("pip")
        )
    )

    Surface(
        modifier = Modifier.fillMaxSize().clickable { onDismiss() },
        color = Color(0xE6000000)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = I18n.get("keyboard_shortcuts"),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.X),
                        contentDescription = "Close",
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shortcuts.size) { sectionIndex ->
                    val (title, items) = shortcuts[sectionIndex]
                    Text(
                        text = title,
                        color = Color(0xFF0F84E4),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    items.forEach { (key, desc) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = key,
                                color = Color(0xFFCCCCCC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = desc,
                                color = Color(0xFF888888),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
