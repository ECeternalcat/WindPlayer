package dev.windplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.PlaybackParams
import dev.windplayer.vfs.VfsManager
import dev.windplayer.vfs.VfsProtocol
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

enum class AppScreen { BROWSER, PLAYER, SETTINGS }

@Composable
fun App(
    player: MpvPlayer,
    vfsManager: VfsManager,
    initialFilePath: String = "",
    onScreenChange: ((AppScreen) -> Unit)? = null,
    onTracksToggle: ((Boolean) -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    osdEvents: SharedFlow<String>? = null,
    onOsdEmit: ((String) -> Unit)? = null,
    dropFilePath: SharedFlow<String>? = null,
    playlistToggle: SharedFlow<Unit>? = null,
    cheatsheetToggle: SharedFlow<Unit>? = null,
    onSkipNextRegistered: ((() -> Unit) -> Unit)? = null,
    settings: PlayerSettings = PlayerSettings.DEFAULT,
    onSettingsChanged: ((PlayerSettings) -> Unit)? = null,
    recentFiles: List<RecentFile> = emptyList(),
    onFilePlayed: ((name: String, path: String, isLocal: Boolean, serverId: String?) -> Unit)? = null,
    onPositionUpdate: ((filePath: String, position: Double, duration: Double) -> Unit)? = null,
    bookmarks: List<String> = emptyList(),
    onBookmarkAdded: ((path: String) -> Unit)? = null,
    onBookmarkRemoved: ((path: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var currentScreen by remember { mutableStateOf(AppScreen.BROWSER) }
    var pendingPlayback by remember { mutableStateOf<PlaybackParams?>(null) }
    val scope = rememberCoroutineScope()

    fun switchScreen(screen: AppScreen) {
        currentScreen = screen
        onScreenChange?.invoke(screen)
    }

    suspend fun prepareAndPlay(
        filePath: String,
        serverId: String? = null,
        isLocal: Boolean = true,
        dirPath: String? = null,
        inheritedDirPaths: List<String> = emptyList(),
        inheritedIndex: Int = -1,
        resumePosition: Double = 0.0
    ) {
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        val node = FileNode(
            name = fileName,
            path = filePath,
            isDirectory = false,
            size = 0,
            protocol = if (isLocal) VfsProtocol.LOCAL else VfsProtocol.SFTP
        )
        val newIndex = if (inheritedDirPaths.isNotEmpty()) {
            inheritedDirPaths.indexOf(filePath).takeIf { it >= 0 } ?: inheritedIndex
        } else inheritedIndex
        val result = if (isLocal) {
            Result.success(vfsManager.prepareLocalPlayback(node).copy(
                dirPath = dirPath ?: filePath.substringBeforeLast('\\').ifBlank { filePath.substringBeforeLast('/') },
                isLocal = true,
                directoryVideoPaths = inheritedDirPaths,
                currentFileIndex = newIndex,
                resumePosition = resumePosition
            ))
        } else {
            val sid = serverId ?: return
            vfsManager.preparePlayback(sid, node).map { it.copy(
                serverId = sid,
                dirPath = dirPath ?: filePath.substringBeforeLast('/'),
                directoryVideoPaths = inheritedDirPaths,
                currentFileIndex = newIndex,
                resumePosition = resumePosition
            ) }
        }
        if (result.isSuccess) {
            pendingPlayback = result.getOrNull()!!
            if (currentScreen != AppScreen.PLAYER) {
                switchScreen(AppScreen.PLAYER)
            }
            onFilePlayed?.invoke(fileName, filePath, isLocal, serverId)
        }
    }

    fun playNextFile(filePath: String) {
        val current = pendingPlayback ?: return
        // Release previous playback's stream sessions before starting a new one.
        vfsManager.releasePlayback(current)
        scope.launch {
            val nextIndex = if (current.directoryVideoPaths.isNotEmpty()) {
                current.directoryVideoPaths.indexOf(filePath).takeIf { it >= 0 } ?: current.currentFileIndex + 1
            } else -1
            prepareAndPlay(
                filePath = filePath,
                serverId = current.serverId,
                isLocal = current.isLocal,
                dirPath = current.dirPath,
                inheritedDirPaths = current.directoryVideoPaths,
                inheritedIndex = nextIndex
            )
        }
    }

    val skipNextAction: () -> Unit = {
        if (pendingPlayback != null && pendingPlayback!!.currentFileIndex >= 0) {
            val nextIndex = pendingPlayback!!.currentFileIndex + 1
            val paths = pendingPlayback!!.directoryVideoPaths
            if (nextIndex < paths.size) {
                playNextFile(paths[nextIndex])
            }
        }
    }

    SideEffect {
        onSkipNextRegistered?.invoke(skipNextAction)
    }

    LaunchedEffect(dropFilePath) {
        if (dropFilePath == null) return@LaunchedEffect
        dropFilePath.collect { path ->
            prepareAndPlay(path)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF0F84E4),
            surface = Color(0xFF0F0F1A),
            background = Color(0xFF0F0F1A)
        )
    ) {
        when (currentScreen) {
            AppScreen.BROWSER -> {
                FileBrowserScreen(
                    vfsManager = vfsManager,
                    onPlayFile = { params ->
                        pendingPlayback = params
                        switchScreen(AppScreen.PLAYER)
                        val replayPath = params.filePath.ifBlank {
                            params.directoryVideoPaths.getOrNull(params.currentFileIndex) ?: params.streamUrl
                        }
                        val replayName = replayPath.substringAfterLast('/').substringAfterLast('\\')
                        onFilePlayed?.invoke(replayName, replayPath, params.isLocal, params.serverId)
                    },
                    onOpenSettings = { switchScreen(AppScreen.SETTINGS) },
                    recentFiles = recentFiles,
                    onPlayRecentFile = { recent ->
                        scope.launch {
                            prepareAndPlay(
                                filePath = recent.path,
                                serverId = recent.serverId,
                                isLocal = recent.isLocal,
                                resumePosition = recent.position
                            )
                        }
                    },
                    bookmarks = bookmarks,
                    onBookmarkAdded = onBookmarkAdded,
                    onBookmarkRemoved = onBookmarkRemoved,
                    modifier = modifier
                )
            }
            AppScreen.SETTINGS -> {
                SettingsScreen(
                    settings = settings,
                    onSettingsChanged = { newSettings -> onSettingsChanged?.invoke(newSettings) },
                    onBack = { switchScreen(AppScreen.BROWSER) },
                    modifier = modifier
                )
            }
            AppScreen.PLAYER -> {
                val effectiveDirPaths = pendingPlayback?.directoryVideoPaths ?: emptyList()
                val effectiveIndex = pendingPlayback?.currentFileIndex ?: -1
                PlayerScreen(
                    player = player,
                    initialFilePath = pendingPlayback?.streamUrl ?: initialFilePath,
                    initialSubtitleFiles = pendingPlayback?.subtitleFiles ?: emptyList(),
                    initialExternalAudioUrls = pendingPlayback?.externalAudioUrls ?: emptyList(),
                    initialMpvOptions = pendingPlayback?.mpvOptions ?: emptyMap(),
                    onBack = {
                        player.command("stop")
                        pendingPlayback?.let { vfsManager.releasePlayback(it) }
                        pendingPlayback = null
                        switchScreen(AppScreen.BROWSER)
                    },
                    onTracksToggle = onTracksToggle,
                    onToggleFullscreen = onToggleFullscreen,
                    isFullscreen = isFullscreen,
                    osdEvents = osdEvents,
                    vfsManager = vfsManager,
                    playbackServerId = pendingPlayback?.serverId,
                    playbackDirPath = pendingPlayback?.dirPath,
                    playbackIsLocal = pendingPlayback?.isLocal ?: false,
                    directoryVideoPaths = effectiveDirPaths,
                    currentFileIndex = effectiveIndex,
                    onPlayNextFile = if (settings.autoPlayNext) { filePath -> playNextFile(filePath) } else null,
                    onJumpToFile = { filePath -> playNextFile(filePath) },
                    onOsdEvent = onOsdEmit,
                    resumePosition = pendingPlayback?.resumePosition ?: 0.0,
                    filePath = pendingPlayback?.filePath ?: pendingPlayback?.streamUrl ?: "",
                    playlistToggle = playlistToggle,
                    cheatsheetToggle = cheatsheetToggle,
                    onPositionUpdate = onPositionUpdate,
                    modifier = modifier
                )
            }
        }
    }
}
