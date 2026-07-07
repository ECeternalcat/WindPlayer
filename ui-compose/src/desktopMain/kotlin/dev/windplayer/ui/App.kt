package dev.windplayer.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.PlaybackParams
import dev.windplayer.vfs.VfsProtocol
import kotlinx.coroutines.launch

enum class AppScreen { BROWSER, PLAYER, SETTINGS }

/**
 * Top-level desktop composable. Routes between browser / settings / player
 * screens and orchestrates playback-preparation + playlist jumps.
 *
 * Parameter surface intentionally small (5 params): all hoisted state, callbacks
 * and flows are bundled in [DesktopAppState] / [DesktopAppCallbacks] /
 * [DesktopAppFlows] respectively.
 */
@Composable
fun App(
    state: DesktopAppState,
    callbacks: DesktopAppCallbacks = DesktopAppCallbacks.NoOp,
    flows: DesktopAppFlows = DesktopAppFlows(),
    initialFilePath: String = "",
    modifier: Modifier = Modifier
) {
    val player = state.player
    val vfsManager = state.vfsManager

    var currentScreen by remember { mutableStateOf(AppScreen.BROWSER) }
    var pendingPlayback by remember { mutableStateOf<PlaybackParams?>(null) }
    val scope = rememberCoroutineScope()

    fun switchScreen(screen: AppScreen) {
        currentScreen = screen
        callbacks.onScreenChange(screen)
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
        // Infer the protocol from the server config when available so the
        // FileNode's metadata is accurate (previously hardcoded SFTP for any
        // non-local path). preparePlayback below ignores this field — it
        // reads ServerConfig.protocol directly — but other call sites
        // (history, track matching) consult node.protocol.
        val proto = if (isLocal) {
            VfsProtocol.LOCAL
        } else {
            serverId?.let { vfsManager.getServer(it)?.protocol } ?: VfsProtocol.SFTP
        }
        val node = FileNode(
            name = fileName,
            path = filePath,
            isDirectory = false,
            size = 0,
            protocol = proto
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
            callbacks.onFilePlayed(fileName, filePath, isLocal, serverId)
        } else {
            // H25: surface preparePlayback failures so the user gets feedback
            // instead of the screen silently staying on BROWSER with no
            // indication of what went wrong.
            val msg = result.exceptionOrNull()?.message ?: "Failed to open file"
            callbacks.onOsdEmit(msg)
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
        val current = pendingPlayback
        if (current != null && current.currentFileIndex >= 0) {
            val nextIndex = current.currentFileIndex + 1
            val paths = current.directoryVideoPaths
            if (nextIndex < paths.size) {
                playNextFile(paths[nextIndex])
            }
        }
    }

    // H28: register the skip-next callback ONCE, not on every recomposition.
    // rememberUpdatedState keeps the lambda pointing at the latest skipNextAction
    // without changing identity, so LaunchedEffect(Unit) runs only a single time.
    val skipNextActionRef = rememberUpdatedState(skipNextAction)
    LaunchedEffect(Unit) {
        callbacks.onSkipNextRegistered { skipNextActionRef.value() }
    }

    LaunchedEffect(flows.dropFilePath) {
        flows.dropFilePath?.collect { path ->
            prepareAndPlay(path)
        }
    }

    val themeMode = state.settings.themeMode
    val isDark = remember(themeMode) {
        when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> DesktopSystemTheme.isSystemDark()
        }
    }
    // M12: LaunchedEffect(isDark) fires only when isDark actually changes, not
    // on every recomposition (~5x/sec during playback due to position polling).
    // applyDark does ~15 mutableStateOf writes each call.
    LaunchedEffect(isDark) { WindColors.applyDark(isDark) }

    // Accent color: WindPlayer (orange) or Follow System (Windows accent via JNA registry)
    val accent = state.settings.accentColor
    val cs = when (accent) {
        AccentColor.WINDPLAYER -> windColorScheme(isDark)
        AccentColor.AUTO -> windColorSchemeAuto(isDark)
    }
    LaunchedEffect(accent, isDark) {
        if (accent == AccentColor.AUTO) {
            val argb = try { readWindowsAccentColor() } catch (_: Exception) { null }
            val primary = if (argb != null) {
                // Registry returns 0xAARRGGBB; convert to Compose Color
                androidx.compose.ui.graphics.Color(argb)
            } else {
                cs.primary // M3 purple fallback
            }
            WindColors.applyAccent(primary)
        } else {
            WindColors.applyAccent(null)
        }
    }

    MaterialTheme(
        colorScheme = cs,
        typography = WindTypography,
        shapes = WindShapes
    ) {
        // M3 MaterialTheme doesn't push typography into LocalTextStyle, so raw
        // Text() calls wouldn't inherit the font. Provide bodyLarge (which now
        // carries Sofia Sans) so every Text inherits the family.
        androidx.compose.runtime.CompositionLocalProvider(
            LocalTextStyle provides WindTypography.bodyLarge
        ) {
        // WindMotion: crossfade screen transitions. PlayerScreen is heavy
        // (mpv-backed) but takes `player` as a parameter, so two simultaneous
        // compositions during the ~250ms fade are safe — mpv calls are
        // serialized by the player's internal lock.
        Crossfade(
            targetState = currentScreen,
            animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard),
            label = "screen"
        ) { screen ->
            when (screen) {
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
                        callbacks.onFilePlayed(replayName, replayPath, params.isLocal, params.serverId)
                    },
                    onOpenSettings = { switchScreen(AppScreen.SETTINGS) },
                    recentFiles = state.recentFiles,
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
                    bookmarks = state.bookmarks,
                    onBookmarkAdded = callbacks::onBookmarkAdded,
                    onBookmarkRemoved = callbacks::onBookmarkRemoved,
                    modifier = modifier
                )
            }
            AppScreen.SETTINGS -> {
                SettingsScreen(
                    settings = state.settings,
                    onSettingsChanged = { newSettings -> callbacks.onSettingsChanged(newSettings) },
                    onBack = { switchScreen(AppScreen.BROWSER) },
                    modifier = modifier
                )
            }
            AppScreen.PLAYER -> {
                PlayerScreen(
                    player = player,
                    params = pendingPlayback,
                    callbacks = object : PlayerCallbacks {
                        override fun onBack() {
                            player.command("stop")
                            pendingPlayback?.let { vfsManager.releasePlayback(it) }
                            pendingPlayback = null
                            switchScreen(AppScreen.BROWSER)
                        }
                        override fun onTracksToggle(expanded: Boolean) =
                            callbacks.onTracksToggle(expanded)
                        override fun onToggleFullscreen() = callbacks.onToggleFullscreen()
                        override fun onJumpToFile(filePath: String) = playNextFile(filePath)
                        override fun onOsdEvent(text: String) = callbacks.onOsdEmit(text)
                        override fun onPositionUpdate(filePath: String, position: Double, duration: Double) =
                            callbacks.onPositionUpdate(filePath, position, duration)
                    },
                    flows = PlayerFlows(
                        osdEvents = flows.osdEvents,
                        playlistToggle = flows.playlistToggle,
                        cheatsheetToggle = flows.cheatsheetToggle
                    ),
                    vfsManager = vfsManager,
                    isFullscreen = state.isFullscreen,
                    autoPlayNext = state.settings.autoPlayNext,
                    initialHwdec = state.settings.hwdecAuto,
                    modifier = modifier
                )
            }
        }
        }
        }
    }
}
