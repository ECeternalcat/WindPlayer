package dev.windplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.mpv.MpvEvent
import dev.windplayer.mpv.MpvFormat
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.vfs.PlaybackParams
import dev.windplayer.vfs.VfsManager
import dev.windplayer.vfs.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Desktop player control bar + side panels (track selection / playlist / cheatsheet).
 *
 * Parameter surface intentionally small (8 params): the bulk of the previous
 * 25 params is now bundled in [PlaybackParams] (the same data class App.kt
 * already owns), [PlayerCallbacks], and [PlayerFlows].
 *
 * The body deconstructs `params` into local vals for clarity and so the
 * downstream logic (event handlers, polling loops, click handlers) doesn't
 * have to repeat `params?.xxx ?: default` everywhere.
 */
@Composable
fun PlayerScreen(
    player: MpvPlayer,
    params: PlaybackParams? = null,
    callbacks: PlayerCallbacks = PlayerCallbacks.NoOp,
    flows: PlayerFlows = PlayerFlows(),
    vfsManager: VfsManager? = null,
    isFullscreen: Boolean = false,
    autoPlayNext: Boolean = false,
    initialHwdec: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Deconstruct PlaybackParams into the local names the body uses.
    val initialFilePath = params?.streamUrl ?: ""
    val initialSubtitleFiles = params?.subtitleFiles ?: emptyList()
    val initialExternalAudioUrls = params?.externalAudioUrls ?: emptyList()
    val initialMpvOptions = params?.mpvOptions ?: emptyMap()
    val playbackServerId = params?.serverId
    val playbackDirPath = params?.dirPath
    val playbackIsLocal = params?.isLocal ?: false
    val directoryVideoPaths = params?.directoryVideoPaths ?: emptyList()
    val currentFileIndex = params?.currentFileIndex ?: -1
    val resumePosition = params?.resumePosition ?: 0.0
    val filePath = params?.filePath ?: ""

    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var statusText by remember { mutableStateOf(I18n.get("ready")) }
    var fileLoaded by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekTarget by remember { mutableStateOf(0.0) }
    var subtitlesAdded by remember { mutableStateOf(false) }
    var audioAdded by remember { mutableStateOf(false) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(100L) }
    var isMuted by remember { mutableStateOf(false) }
    var isVolumeDragging by remember { mutableStateOf(false) }
    // H24: seed from the host's setting rather than hardcoding true. Without
    // this, the lightning-bulb icon always shows "on" even when the user
    // disabled HW decode in Settings (the mpv option was correctly set from
    // settingsState.hwdecAuto in Main.kt, but the UI disagreed).
    var hwdecAuto by remember(initialHwdec) { mutableStateOf(initialHwdec) }
    var speed by remember { mutableStateOf(1.0) }
    var osdText by remember { mutableStateOf("") }
    var eofAutoPlayed by remember { mutableStateOf(false) }
    var resumeApplied by remember { mutableStateOf(false) }
    var showCheatsheet by remember { mutableStateOf(false) }
    val manualTrackResources = remember { mutableStateListOf<PlaybackParams>() }

    DisposableEffect(vfsManager) {
        onDispose {
            val manager = vfsManager
            if (manager != null) manualTrackResources.forEach(manager::releasePlayback)
            manualTrackResources.clear()
        }
    }

    // The MpvPlayer instance lives for the application, while playback params
    // change for every episode. Restart the collector for each loaded path so
    // EOF, resume and external-track handling cannot retain the first episode.
    LaunchedEffect(player, initialFilePath) {
        // Register observers for low-frequency properties (L12 finally lets these
        // actually fire). The high-frequency `time-pos` stays on a 200ms polling
        // loop below because emitting it as an event ~60 times/sec would flood
        // the SharedFlow and starve other events.
        player.observeProperty("pause", MpvFormat.FLAG)
        player.observeProperty("volume", MpvFormat.INT64)
        player.observeProperty("mute", MpvFormat.FLAG)
        player.observeProperty("speed", MpvFormat.DOUBLE)
        player.observeProperty("duration", MpvFormat.DOUBLE)
        player.observeProperty("eof-reached", MpvFormat.FLAG)
        launch {
            // Only `time-pos` needs polling (high-frequency, ~frame rate).
            // Plus a periodic position-report back to the host (every 25 ticks = 5s).
            var posUpdateCounter = 0
            while (true) {
                delay(200)
                if (!fileLoaded) continue
                try {
                    if (!isSeeking) {
                        val pos = player.getPropertyDouble("time-pos") ?: 0.0
                        if (pos >= 0) position = pos
                    }
                } catch (_: Exception) {}

                if (++posUpdateCounter >= 25) {
                    posUpdateCounter = 0
                    val fp = filePath.ifBlank { directoryVideoPaths.getOrNull(currentFileIndex) ?: initialFilePath }
                    callbacks.onPositionUpdate(fp, position, duration)
                }
            }
        }
        launch {
            player.events.collect { event ->
                when (event) {
                    is MpvEvent.FileLoaded -> {
                        fileLoaded = true
                        isPlaying = true
                        // One-shot sync read of the properties observers cover, in
                        // case mpv's first observer emission missed (it shouldn't,
                        // but a defensive read here avoids UI appearing stale).
                        try {
                            val dur = player.getPropertyDouble("duration") ?: 0.0
                            if (dur > 0) duration = dur
                            volume = player.getPropertyLong("volume") ?: 100L
                            isMuted = player.getPropertyString("mute") == "yes"
                            speed = player.getPropertyDouble("speed") ?: 1.0
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

                        // audio-add requires an active file, so it must run
                        // AFTER loadfile resolves (i.e. here in FileLoaded),
                        // not before. Sending it pre-loadfile silently fails
                        // and the external audio track is lost forever.
                        if (!audioAdded && initialExternalAudioUrls.isNotEmpty()) {
                            initialExternalAudioUrls.forEach { audioUrl ->
                                try {
                                    player.command("audio-add", audioUrl)
                                } catch (_: Exception) {}
                            }
                            audioAdded = true
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
                            statusText = I18n.get("err_open_failed")
                        } else if (fileLoaded) {
                            isPlaying = false
                            val shouldAutoPlay = (event.reason == 0 || event.reason == 2)
                                && autoPlayNext
                                && currentFileIndex >= 0
                                && directoryVideoPaths.isNotEmpty()
                            if (shouldAutoPlay) {
                                val nextIndex = currentFileIndex + 1
                                if (nextIndex < directoryVideoPaths.size) {
                                    val nextPath = directoryVideoPaths[nextIndex]
                                    val nextName = nextPath.substringAfterLast('/').substringAfterLast('\\')
                                    statusText = String.format(I18n.get("next_msg"), nextName)
                                    fileLoaded = false
                                    callbacks.onOsdEvent(">> Next: $nextName")
                                    callbacks.onJumpToFile(nextPath)
                                } else {
                                    statusText = I18n.get("playlist_complete")
                                    callbacks.onOsdEvent("Playlist complete")
                                }
                            } else {
                                statusText = if (event.reason == 0) I18n.get("status_ended") else I18n.get("status_stopped")
                            }
                        }
                    }
                    is MpvEvent.Error -> {
                        statusText = String.format(I18n.get("error_prefix"), event.message)
                    }
                    is MpvEvent.PropertyChange -> handlePropertyChange(
                        event = event,
                        // The block below captures these vars by reference;
                        // pass them via helper params to keep dispatch readable.
                        setIsPlaying = { isPlaying = it },
                        setIsMuted = { isMuted = it },
                        setVolume = { volume = it },
                        setSpeed = { speed = it },
                        setDuration = { duration = it },
                        onEofReached = {
                            // Triggered once when `eof-reached` flips to true.
                            if (!eofAutoPlayed && autoPlayNext
                                && currentFileIndex >= 0
                                && directoryVideoPaths.isNotEmpty()
                            ) {
                                eofAutoPlayed = true
                                val nextIndex = currentFileIndex + 1
                                if (nextIndex < directoryVideoPaths.size) {
                                    val nextPath = directoryVideoPaths[nextIndex]
                                    val nextName = nextPath.substringAfterLast('/').substringAfterLast('\\')
                                    statusText = String.format(I18n.get("next_msg"), nextName)
                                    fileLoaded = false
                                    callbacks.onOsdEvent(">> Next: $nextName")
                                    callbacks.onJumpToFile(nextPath)
                                } else {
                                    statusText = I18n.get("playlist_complete")
                                    callbacks.onOsdEvent("Playlist complete")
                                }
                            }
                        },
                        isVolumeDragging = { isVolumeDragging }
                    )
                    else -> {}
                }
            }
        }
    }

    // H8: unregister the 6 property observers registered in LaunchedEffect above.
    // LaunchedEffect cancels its coroutine on dispose but does NOT call back into
    // mpv to unobserve — without this, each Browser→Player→Browser→Player cycle
    // adds another batch and every property change fires N callbacks.
    DisposableEffect(player) {
        onDispose { player.clearPropertyObservers() }
    }

    LaunchedEffect(flows.osdEvents) {
        flows.osdEvents?.collectLatest { text ->
            osdText = text
            delay(2000)
            osdText = ""
        }
    }

    LaunchedEffect(flows.playlistToggle) {
        flows.playlistToggle?.collect {
            if (directoryVideoPaths.isNotEmpty()) {
                showPlaylist = !showPlaylist
                showTrackSheet = false
                callbacks.onTracksToggle(showPlaylist)
            }
        }
    }

    LaunchedEffect(flows.cheatsheetToggle) {
        flows.cheatsheetToggle?.collect {
            showCheatsheet = !showCheatsheet
            callbacks.onTracksToggle(showCheatsheet || showTrackSheet || showPlaylist)
        }
    }

    // M6: include resumePosition in the key so replaying the same file with a
    // different saved position (e.g. from Recent) re-triggers load + resume-seek.
    LaunchedEffect(initialFilePath, resumePosition) {
        if (initialFilePath.isNotBlank()) {
            fileLoaded = false
            isPlaying = false
            position = 0.0
            duration = 0.0
            subtitlesAdded = false
            audioAdded = false
            speed = 1.0
            isSeeking = false
            eofAutoPlayed = false
            resumeApplied = false
            statusText = I18n.get("loading")
            for ((key, value) in initialMpvOptions) {
                player.setProperty(key, value)
            }
            // External audio tracks are attached in the FileLoaded handler —
            // mpv rejects audio-add before a file is loaded.
            player.command("loadfile", initialFilePath)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WindColors.MediaInk)
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
                Text(text = formatDuration(position), color = WindColors.MediaCream, fontSize = 12.sp)
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
                        thumbColor = WindColors.LightSignalOrange,
                        activeTrackColor = WindColors.LightSignalOrange,
                        inactiveTrackColor = WindColors.MediaCream.copy(alpha = 0.15f)
                    )
                )
                Text(text = formatDuration(duration), color = WindColors.MediaCream, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val fp = filePath.ifBlank { directoryVideoPaths.getOrNull(currentFileIndex) ?: initialFilePath }
                    callbacks.onPositionUpdate(fp, position, duration)
                    player.command("stop")
                    fileLoaded = false
                    isPlaying = false
                    position = 0.0
                    duration = 0.0
                    statusText = I18n.get("ready")
                    subtitlesAdded = false
                    callbacks.onBack()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = iconPainter(PhosphorIcons.ARROW_LEFT),
                    contentDescription = I18n.get("back"),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            // WindMotion: subtle press-scale on primary controls.
            val playPauseSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            IconButton(
                onClick = {
                    if (fileLoaded) {
                        player.command("cycle", "pause")
                    }
                },
                interactionSource = playPauseSource,
                modifier = Modifier.size(32.dp).pressScale(playPauseSource)
            ) {
                Icon(
                    painter = if (isPlaying) pauseIcon else playIcon,
                    contentDescription = if (isPlaying) I18n.get("pause") else I18n.get("play"),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (fileLoaded) {
                IconButton(
                    onClick = {
                        showTrackSheet = !showTrackSheet
                        if (showTrackSheet) showPlaylist = false
                        callbacks.onTracksToggle(showTrackSheet || showPlaylist)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.LIST),
                        contentDescription = I18n.get("tracks"),
                        tint = if (showTrackSheet) WindColors.LightSignalOrange else WindColors.MediaCream,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (fileLoaded && directoryVideoPaths.isNotEmpty()) {
                IconButton(
                    onClick = {
                        showPlaylist = !showPlaylist
                        if (showPlaylist) showTrackSheet = false
                        callbacks.onTracksToggle(showTrackSheet || showPlaylist)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.QUEUE),
                        contentDescription = "Playlist",
                        tint = if (showPlaylist) WindColors.LightSignalOrange else WindColors.MediaCream,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // WindMotion: OSD fade + scale so volume/seek feedback feels alive.
            // Use animateFloatAsState (not AnimatedVisibility) to keep the row's
            // weight allocation stable — the surrounding buttons shouldn't
            // shift when OSD appears/disappears.
            val osdAlpha by animateFloatAsState(
                targetValue = if (osdText.isNotBlank()) 1f else 0f,
                animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
                label = "osdAlpha"
            )
            val osdScale by animateFloatAsState(
                targetValue = if (osdText.isNotBlank()) 1f else 0.92f,
                animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
                label = "osdScale"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .graphicsLayer {
                        alpha = osdAlpha
                        scaleX = osdScale
                        scaleY = osdScale
                    }
            ) {
                Text(
                    text = osdText,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (fileLoaded) {
                IconButton(
                    onClick = { player.command("cycle", "mute") },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = if (isMuted) speakerSlashIcon else speakerHighIcon,
                        contentDescription = if (isMuted) I18n.get("unmute") else I18n.get("mute"),
                        tint = WindColors.MediaCream,
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
                        thumbColor = WindColors.LightSignalOrange,
                        activeTrackColor = WindColors.LightSignalOrange,
                        inactiveTrackColor = WindColors.MediaCream.copy(alpha = 0.15f)
                    )
                )
            }

            IconButton(
                onClick = { callbacks.onToggleFullscreen() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = if (isFullscreen) cornersInIcon else cornersOutIcon,
                    contentDescription = if (isFullscreen) I18n.get("windowed") else I18n.get("fullscreen"),
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
                        contentDescription = if (hwdecAuto) I18n.get("hw_decode") else I18n.get("sw_decode"),
                        tint = if (hwdecAuto) WindColors.LightSignalOrange else WindColors.MediaMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Button(
                    onClick = {
                        player.setProperty("speed", "1.00")
                        speed = 1.0
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = WindRadius.Button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (speed != 1.0) WindColors.LightSignalOrange else WindColors.MediaSurface
                    )
                ) {
                    Text(
                        text = "%.1fx".format(speed),
                        fontSize = 10.sp,
                        color = if (speed != 1.0) WindColors.MediaCream else WindColors.MediaMuted
                    )
                }
            }

            Text(
                text = statusText,
                color = WindColors.MediaMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
        }

        // WindMotion: panels slide up + fade to match the LayoutManager's
        // physical panel-expand on the Swing side. Previously hard cuts.
        AnimatedVisibility(
            visible = showTrackSheet,
            enter = slideInVertically(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard)) { it / 2 } +
                fadeIn(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard)),
            exit = slideOutVertically(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingExit)) { it / 2 } +
                fadeOut(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingExit))
        ) {
            TrackSelectionSheet(
                player = player,
                onDismiss = {
                    showTrackSheet = false
                    callbacks.onTracksToggle(showTrackSheet || showPlaylist)
                },
                vfsManager = vfsManager,
                serverId = playbackServerId,
                dirPath = playbackDirPath,
                isLocal = playbackIsLocal,
                onPlaybackResourceCreated = { manualTrackResources.add(it) }
            )
        }

        AnimatedVisibility(
            visible = showPlaylist,
            enter = slideInVertically(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard)) { it / 2 } +
                fadeIn(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard)),
            exit = slideOutVertically(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingExit)) { it / 2 } +
                fadeOut(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingExit))
        ) {
            PlaylistPanel(
                videoPaths = directoryVideoPaths,
                currentIndex = currentFileIndex,
                onJumpToFile = { path ->
                    showPlaylist = false
                    callbacks.onTracksToggle(false)
                    callbacks.onJumpToFile(path)
                },
                onDismiss = {
                    showPlaylist = false
                    callbacks.onTracksToggle(false)
                }
            )
        }

        // Cheatsheet: full-screen overlay, fade + scale (no slide).
        AnimatedVisibility(
            visible = showCheatsheet,
            enter = fadeIn(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard)),
            exit = fadeOut(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingExit))
        ) {
            CheatsheetOverlay(onDismiss = {
                showCheatsheet = false
                callbacks.onTracksToggle(showTrackSheet || showPlaylist)
            })
        }
    }
}

/**
 * Dispatch a [MpvEvent.PropertyChange] to the appropriate state setter.
 *
 * Pulled out of the events-collector block so the dispatch table is readable.
 * `value` types match what [MpvPlayer.observeProperty] promises for each format:
 *  - FLAG ↁEBoolean    (true / false)
 *  - INT64 ↁELong
 *  - DOUBLE ↁEDouble
 */
private fun handlePropertyChange(
    event: MpvEvent.PropertyChange,
    setIsPlaying: (Boolean) -> Unit,
    setIsMuted: (Boolean) -> Unit,
    setVolume: (Long) -> Unit,
    setSpeed: (Double) -> Unit,
    setDuration: (Double) -> Unit,
    onEofReached: () -> Unit,
    isVolumeDragging: () -> Boolean
) {
    when (event.name) {
        // pause is FLAG: value=true means "paused", so isPlaying = !paused.
        "pause" -> setIsPlaying(event.value != true)
        "mute" -> setIsMuted(event.value == true)
        "volume" -> {
            val v = event.value as? Long ?: return
            // Skip while the user is dragging the volume slider  Ethey own the value.
            if (!isVolumeDragging()) setVolume(v)
        }
        "speed" -> (event.value as? Double)?.let(setSpeed)
        "duration" -> (event.value as? Double)?.let { if (it > 0) setDuration(it) }
        "eof-reached" -> if (event.value == true) onEofReached()
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
            .background(WindColors.MediaInk)
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
                tint = WindColors.MediaCream,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${I18n.get("playlist")} (${videoPaths.size})",
                color = WindColors.MediaCream,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = iconPainter(PhosphorIcons.X),
                    contentDescription = I18n.get("close"),
                    tint = WindColors.MediaMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        HorizontalDivider(color = WindColors.MediaCream.copy(alpha = 0.12f))
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(videoPaths.size, key = { it }) { index ->
                val path = videoPaths[index]
                val name = path.substringAfterLast('/').substringAfterLast('\\')
                val isCurrent = index == currentIndex
                // WindMotion: animate current-track highlight.
                val itemBg by animateColorAsState(
                    targetValue = if (isCurrent) WindColors.LightSignalOrange.copy(alpha = 0.15f) else Color.Transparent,
                    animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
                    label = "playlistBg"
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().animateItem().clickable { onJumpToFile(path) },
                    color = itemBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCurrent) {
                            Icon(
                                painter = iconPainter(PhosphorIcons.PLAY),
                                contentDescription = null,
                                tint = WindColors.LightSignalOrange,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                color = WindColors.MediaMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.width(14.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = name,
                            color = if (isCurrent) WindColors.MediaCream else WindColors.MediaMuted,
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
        // ARCH-6: use the named MediaScrim token instead of an inline hex.
        color = WindColors.MediaScrim
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
                    color = WindColors.MediaCream,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.X),
                        contentDescription = I18n.get("close"),
                        tint = WindColors.MediaMuted,
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
                        color = WindColors.LightSignalOrange,
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
                                color = WindColors.MediaCream,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = desc,
                                color = WindColors.MediaMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
