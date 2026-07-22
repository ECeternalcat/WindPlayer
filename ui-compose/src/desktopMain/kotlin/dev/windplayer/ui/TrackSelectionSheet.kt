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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.mpv.TrackInfo
import dev.windplayer.vfs.*
import kotlinx.coroutines.launch

enum class TrackType(val label: String, val mpvProp: String) {
    VIDEO("Video", "vid"),
    AUDIO("Audio", "aid"),
    SUBTITLE("Subtitle", "sid")
}

fun MpvPlayer.queryTracks(): List<TrackInfo> {
    val count = try {
        getPropertyLong("track-list/count")?.toInt() ?: 0
    } catch (_: Exception) {
        0
    }
    return (0 until count).mapNotNull { i ->
        try {
            val type = getPropertyString("track-list/$i/type") ?: return@mapNotNull null
            val id = getPropertyString("track-list/$i/id")?.toIntOrNull() ?: return@mapNotNull null
            TrackInfo(
                id = id,
                type = type,
                codec = getPropertyString("track-list/$i/codec"),
                lang = getPropertyString("track-list/$i/lang"),
                title = getPropertyString("track-list/$i/title"),
                isDefault = getPropertyString("track-list/$i/default")?.let { it == "true" || it == "yes" } == true,
                isForced = getPropertyString("track-list/$i/forced")?.let { it == "true" || it == "yes" } == true,
                isSelected = getPropertyString("track-list/$i/selected")?.let { it == "true" || it == "yes" } == true,
                isExternal = getPropertyString("track-list/$i/external")?.let { it == "true" || it == "yes" } == true
            )
        } catch (_: Exception) {
            null
        }
    }
}

private fun TrackType.extensions(): Set<String> = when (this) {
    TrackType.SUBTITLE -> SUBTITLE_EXTENSIONS
    TrackType.AUDIO -> setOf("mp3", "aac", "flac", "wav", "mka", "ogg", "opus", "ac3", "dts", "wma", "ape")
    TrackType.VIDEO -> VIDEO_EXTENSIONS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSelectionSheet(
    player: MpvPlayer,
    onDismiss: () -> Unit,
    vfsManager: VfsManager? = null,
    serverId: String? = null,
    dirPath: String? = null,
    isLocal: Boolean = false,
    onPlaybackResourceCreated: (PlaybackParams) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var tracks by remember { mutableStateOf(player.queryTracks()) }
    var selectedTab by remember { mutableStateOf(0) }
    val tabTypes = TrackType.entries.toTypedArray()
    var browseMode by remember { mutableStateOf(false) }
    var browseFiles by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var browseLoading by remember { mutableStateOf(false) }
    var browsePath by remember { mutableStateOf(dirPath ?: "") }
    val scope = rememberCoroutineScope()

    fun refreshTracks() {
        tracks = player.queryTracks()
    }

    fun loadDirectory(path: String) {
        if (vfsManager == null) return
        scope.launch {
            browseLoading = true
            browseFiles = if (isLocal) {
                vfsManager.listLocalDirectory(path)
            } else {
                val sid = serverId ?: return@launch
                vfsManager.listServerDirectory(sid, path).getOrDefault(emptyList())
            }
            browsePath = path
            browseLoading = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!browseMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = I18n.get("track_selection"),
                    color = WindColors.MediaCream,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.X),
                        contentDescription = I18n.get("close"),
                        tint = WindColors.LightSignalOrange,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = WindColors.MediaSurface,
                contentColor = WindColors.MediaCream
            ) {
                tabTypes.forEachIndexed { index, type ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = iconPainter(when (type) {
                                        TrackType.VIDEO -> PhosphorIcons.VIDEO
                                        TrackType.AUDIO -> PhosphorIcons.SPEAKER_HIGH
                                        TrackType.SUBTITLE -> PhosphorIcons.SUBTITLES
                                    }),
                                    contentDescription = type.label,
                                    tint = if (selectedTab == index) WindColors.LightSignalOrange else WindColors.MediaMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(type.localizedLabel(), fontSize = 13.sp)
                            }
                        }
                    )
                }
            }

            val currentType = tabTypes[selectedTab]
            val filteredTracks = tracks.filter { it.type == currentType.mpvPropLabel() }
            val currentTrackId = try {
                player.getPropertyString(currentType.mpvProp)?.toIntOrNull()
            } catch (_: Exception) { null }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (currentType != TrackType.VIDEO) {
                    item {
                        TrackItem(
                            label = I18n.get("none"),
                            isSelected = currentTrackId == null || currentTrackId <= 0,
                            onClick = {
                                player.setProperty(currentType.mpvProp, "no")
                                refreshTracks()
                            }
                        )
                    }
                }
                items(filteredTracks) { track ->
                    TrackItem(
                        label = trackDisplayName(track),
                        isSelected = track.id == currentTrackId,
                        badges = buildList {
                            if (track.isDefault) add("DEF")
                            if (track.isForced) add("FORCED")
                            if (track.isExternal) add("EXT")
                        },
                        onClick = {
                            player.setProperty(currentType.mpvProp, track.id.toString())
                            refreshTracks()
                        }
                    )
                }
                if (vfsManager != null && dirPath != null) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                browseMode = true
                                loadDirectory(dirPath)
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = iconPainter(PhosphorIcons.PLUS),
                                contentDescription = "Add",
                                tint = WindColors.LightSignalOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(String.format(I18n.get("add_external"), currentType.localizedLabel()), color = WindColors.LightSignalOrange, fontSize = 13.sp)
                        }
                    }
                }
            }
        } else {
            val browseType = tabTypes[selectedTab]
            val exts = browseType.extensions()

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val parent = browsePath.substringBeforeLast('/').ifBlank { dirPath }
                    if (parent != null && parent != browsePath) {
                        loadDirectory(parent)
                    }
                }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.ARROW_LEFT),
                        contentDescription = I18n.get("back"),
                        tint = WindColors.LightSignalOrange,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = String.format(I18n.get("add_title"), browseType.localizedLabel()),
                    color = WindColors.MediaCream,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { browseMode = false }) {
                    Text(I18n.get("cancel"), color = WindColors.MediaMuted, fontSize = 13.sp)
                }
            }

            if (browsePath.isNotBlank()) {
                Text(
                    text = browsePath,
                    color = WindColors.MediaMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            if (browseLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = WindColors.LightSignalOrange, modifier = Modifier.size(24.dp))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    val filtered = browseFiles.filter { f ->
                        f.isDirectory || f.name.substringAfterLast('.', "").lowercase() in exts
                    }
                    items(filtered) { file ->
                        TrackItem(
                            label = "${if (file.isDirectory) "[DIR] " else ""}${file.name}" +
                                    if (!file.isDirectory && file.size > 0) " (${formatFileSize(file.size)})" else "",
                            isSelected = false,
                            onClick = {
                                if (file.isDirectory) {
                                    loadDirectory(file.path)
                                } else {
                                    scope.launch {
                                        val cmd = when (browseType) {
                                            TrackType.SUBTITLE -> "sub-add"
                                            TrackType.AUDIO -> "audio-add"
                                            TrackType.VIDEO -> "video-add"
                                        }
                                        val url = if (isLocal) {
                                            file.path
                                        } else {
                                            val sid = serverId ?: return@launch
                                            val mgr = vfsManager ?: return@launch
                                            val prepared = mgr.preparePlayback(sid, file).getOrNull()
                                                ?: return@launch
                                            onPlaybackResourceCreated(prepared)
                                            prepared.streamUrl
                                        }
                                        player.command(cmd, url)
                                        browseMode = false
                                        refreshTracks()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun TrackType.mpvPropLabel(): String = when (this) {
    TrackType.VIDEO -> "video"
    TrackType.AUDIO -> "audio"
    TrackType.SUBTITLE -> "sub"
}

private fun TrackType.localizedLabel(): String = when (this) {
    TrackType.VIDEO -> I18n.get("video_track")
    TrackType.AUDIO -> I18n.get("audio_track")
    TrackType.SUBTITLE -> I18n.get("subtitle_track")
}

private fun trackDisplayName(track: TrackInfo): String {
    val parts = mutableListOf<String>()
    track.lang?.let { parts.add(it) }
    track.codec?.let { parts.add(it.uppercase()) }
    track.title?.let { parts.add(it) }
    return if (parts.isEmpty()) "Track ${track.id}" else parts.joinToString(" · ")
}

@Composable
private fun TrackItem(
    label: String,
    isSelected: Boolean,
    badges: List<String> = emptyList(),
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) WindColors.LightSignalOrange.copy(alpha = 0.15f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // L8: both branches were identical (CHECK vs CHECK); the transparent
        // tint already hides the icon when not selected. Simplified.
        Icon(
            painter = iconPainter(PhosphorIcons.CHECK),
            contentDescription = if (isSelected) "Selected" else "",
            tint = if (isSelected) WindColors.LightSignalOrange else Color.Transparent,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = WindColors.MediaCream,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        badges.forEach { badge ->
            Surface(
                color = WindColors.MediaCream.copy(alpha = 0.1f),
                shape = WindRadius.Chip
            ) {
                Text(
                    text = badge,
                    color = WindColors.MediaMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}
