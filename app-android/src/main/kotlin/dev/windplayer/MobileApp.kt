package dev.windplayer

import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.ui.I18n
import dev.windplayer.ui.PlayerSettings
import dev.windplayer.ui.ThemeMode
import dev.windplayer.ui.AccentColor
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.VfsProtocol
import dev.windplayer.vfs.isVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MobileApp(
    externalVideoUri: Uri? = null,
    onExternalVideoConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("browser") }
    var settings by remember {
        mutableStateOf(SettingsHelper.load(context)).also {
            // L6: set I18n synchronously during initial composition so the first
            // frame renders in the correct language (previously a LaunchedEffect
            // set it AFTER composition → one frame of English on non-en cold start).
            I18n.current = it.value.language
        }
    }
    var pendingFile by remember { mutableStateOf<FileNode?>(null) }
    val player = remember { MpvPlayer() }
    // H7: standalone scope for native teardown. rememberCoroutineScope()
    // gets CANCELLED by Compose before onDispose runs, which would skip
    // player.dispose() → native handle leak across Activity recreation.
    // This remembered scope survives composition disposal and only dies
    // when the launched coroutine completes + the scope is GC'd.
    val teardownScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // Defense-in-depth: ensure mpv native handle + observer are released when
    // MobileApp leaves composition (e.g. Activity destroyed by the system).
    // Normal teardown still happens via MobilePlayerScreen.onBack.
    DisposableEffect(player) {
        onDispose {
            // H14: dispose() synchronously calls MPVLib.destroy() (JNI), which
            // can take 100ms–1s+. Run on teardownScope (NOT scope) to avoid
            // the coroutine being cancelled mid-teardown by Compose.
            teardownScope.launch {
                try { player.dispose() } catch (_: Exception) {}
            }
        }
    }

    var servers by remember { mutableStateOf(ServerStore.load(context)) }
    var localFolders by remember { mutableStateOf(LocalFolderStore.load(context)) }
    var history by remember { mutableStateOf(HistoryStore.load(context)) }
    var activeServer by remember { mutableStateOf<ServerConfig?>(null) }
    var playServerConfig by remember { mutableStateOf<ServerConfig?>(null) }
    var directoryVideos by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var localSiblings by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var playIndex by remember { mutableStateOf(0) }
    var resumePosition by remember { mutableStateOf(0.0) }
    var resumeSid by remember { mutableStateOf<String?>(null) }
    var resumeAid by remember { mutableStateOf<String?>(null) }
    var resumeSpeed by remember { mutableStateOf(0.0) }
    var serverFiles by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var serverPath by remember { mutableStateOf("") }
    var serverLoading by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var pendingHistoryPlay by remember { mutableStateOf<String?>(null) }

    // When a server directory listing finishes and we have a pending history
    // play, auto-start playback with the full sibling video list so auto-play-next
    // works and the player exits to the correct directory.
    LaunchedEffect(serverFiles) {
        val targetPath = pendingHistoryPlay ?: return@LaunchedEffect
        if (serverFiles.isEmpty()) return@LaunchedEffect
        val srv = activeServer ?: run { pendingHistoryPlay = null; return@LaunchedEffect }
        pendingHistoryPlay = null
        val vids = serverFiles.filter { it.isVideo() }
        val idx = vids.indexOfFirst { it.path == targetPath }
        if (idx >= 0) {
            pendingFile = vids[idx]
            playServerConfig = srv
            directoryVideos = vids
            playIndex = idx
        } else {
            pendingFile = FileNode(
                name = targetPath.substringAfterLast('/'),
                path = targetPath, isDirectory = false, protocol = srv.protocol
            )
            playServerConfig = srv
            directoryVideos = listOf(pendingFile!!)
            playIndex = 0
        }
    }

    LaunchedEffect(activeServer, serverPath) {
        val srv = activeServer ?: return@LaunchedEffect
        serverLoading = true
        serverError = null
        serverFiles = try {
            withContext(Dispatchers.IO) { MobileVfsManager.listDirectory(srv, serverPath) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // H21: re-throw — if this LaunchedEffect was cancelled because the
            // user navigated to another directory, we must NOT overwrite the
            // newer coroutine's result with emptyList().
            throw e
        } catch (e: Exception) {
            serverError = e.message ?: "Connection failed"
            activeServer = null
            serverPath = ""
            emptyList()
        }
        serverLoading = false
    }

    LaunchedEffect(serverError) {
        serverError?.let {
            Toast.makeText(context, String.format(I18n.get("connect_failed"), it), Toast.LENGTH_LONG).show()
            serverError = null
        }
    }

    LaunchedEffect(Unit) {
        // Force-initialize ServerStore so we know whether encryption is in use.
        ServerStore.load(context)
        if (!ServerStore.encryptionActive) {
            Toast.makeText(
                context,
                I18n.get("encryption_warning"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(settings.language) { I18n.current = settings.language }

    // AI Translation choice sheet — observable by any screen via TranslationStarter.
    val translateRequest by dev.windplayer.translate.TranslationStarter.pendingRequest.collectAsState()
    translateRequest?.let { params ->
        dev.windplayer.translate.TranslateChoiceSheet(
            params = params,
            onDismiss = { dev.windplayer.translate.TranslationStarter.consume() }
        )
    }

    // Request POST_NOTIFICATIONS permission on Android 13+ so the translation
    // ForegroundService notification is visible.
    // BUG-34: show Toast if denied so the user knows notifications won't appear.
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val perm = android.content.pm.PackageManager.PERMISSION_GRANTED
            val current = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            if (current != perm) {
                (context as? android.app.Activity)?.requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                )
                // After request, check result (requestPermissions is async but
                // the permission state updates synchronously on most devices
                // when the dialog is dismissed). If still denied, inform user.
                kotlinx.coroutines.delay(500) // wait for dialog
                val after = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                if (after != perm) {
                    Toast.makeText(context,
                        "Notifications disabled — translation progress won't show in status bar",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Handle external video URI (from ACTION_VIEW / ACTION_SEND intents).
    LaunchedEffect(externalVideoUri) {
        val uri = externalVideoUri ?: return@LaunchedEffect
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "video"
        val fileNode = FileNode(
            name = java.net.URLDecoder.decode(fileName, "UTF-8"),
            path = uri.toString(),
            isDirectory = false,
            protocol = VfsProtocol.LOCAL
        )
        pendingFile = fileNode
        playServerConfig = null
        directoryVideos = listOf(fileNode)
        localSiblings = emptyList()
        playIndex = 0
        resumePosition = 0.0
        resumeSid = null
        resumeAid = null
        resumeSpeed = 0.0
        // H19: clear the consumed URI so re-sharing the SAME file triggers
        // this LaunchedEffect again (its key — the URI — would otherwise be
        // unchanged and the share silently ignored).
        onExternalVideoConsumed()
    }

    val configuration = LocalConfiguration.current
    val isDark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM ->
            (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    SideEffect {
        WindColors.applyDark(isDark)
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isDark
        }
    }

    // Accent color: WindPlayer orange or Auto (Material You dynamic on Android 12+)
    val accent = settings.accentColor
    // isDynamicColorAvailable() is not available in CMP 1.9.0's Material3 fork;
    // SDK >= S (Android 12) is the equivalent check it performs internally.
    val dynamicSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val cs = when (accent) {
        AccentColor.WINDPLAYER -> androidColorScheme(isDark)
        AccentColor.AUTO -> {
            if (dynamicSupported) {
                if (isDark) androidx.compose.material3.dynamicDarkColorScheme(context)
                else androidx.compose.material3.dynamicLightColorScheme(context)
            } else {
                androidColorScheme(isDark)
            }
        }
    }
    LaunchedEffect(accent, isDark) {
        WindColors.applyAccent(
            if (accent == AccentColor.AUTO && dynamicSupported) cs.primary else null
        )
    }

    MaterialTheme(
        colorScheme = cs,
        typography = WindTypography
    ) {
        // Push the body style (carrying Sofia Sans) into LocalTextStyle so raw
        // Text() calls inherit the family — M3 MaterialTheme doesn't do this.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides WindTypography.bodyLarge
        ) {
        Surface(modifier = Modifier.fillMaxSize(), color = WindColors.CanvasCream) {
            // WindMotion: derive a single screen key from the various state
            // conditions so Crossfade has a comparable target. Order matters —
            // `pendingFile` (player) wins over everything (incl. settings),
            // matching the previous `when` precedence.
            val screenKey = when {
                pendingFile != null -> "player"
                screen == "addServer" || screen == "editServer" -> "addServer"
                screen == "settings" -> "settings"
                activeServer != null -> "serverBrowse"
                else -> "browser"
            }
            androidx.compose.animation.Crossfade(
                targetState = screenKey,
                animationSpec = androidx.compose.animation.core.tween(
                    dev.windplayer.ui.WindMotion.DurMedium,
                    easing = dev.windplayer.ui.WindMotion.EasingStandard
                ),
                label = "screen"
            ) { key ->
                when (key) {
                    "player" -> {
                        if (pendingFile != null) {
            MobilePlayerScreen(
                player = player,
                file = pendingFile!!,
                serverConfig = playServerConfig,
                directoryVideos = directoryVideos,
                localSiblings = localSiblings,
                currentIndex = playIndex,
                autoPlayNext = settings.autoPlayNext,
                resumePosition = resumePosition,
                resumeSid = resumeSid,
                resumeAid = resumeAid,
                resumeSpeed = resumeSpeed,
                onFilePlayed = { playedFile ->
                    val proto = playServerConfig?.protocol ?: VfsProtocol.LOCAL
                    val sid = playServerConfig?.id
                    val treeUri = if (proto == VfsProtocol.LOCAL) SafHelper.loadTreeUri(context)?.toString() else null
                    val parentDocId = if (proto == VfsProtocol.LOCAL) SafPlaylistBuilder.extractParentDocId(playedFile.path) else null
                    // H16: HistoryStore.add does SharedPreferences load + commit on
                    // the calling thread. Dispatch to IO to avoid main-thread jank
                    // during FileLoaded, then update Compose state on Main.
                    scope.launch(Dispatchers.IO) {
                        val updated = HistoryStore.add(context, HistoryEntry(
                            name = playedFile.name, path = playedFile.path,
                            protocol = proto, serverId = sid,
                            timestamp = System.currentTimeMillis(),
                            parentDocId = parentDocId, treeUriString = treeUri
                        ))
                        withContext(Dispatchers.Main) { history = updated }
                    }
                },
                onPositionUpdate = { path, pos, dur ->
                    // H16: periodic (every 5s) SharedPreferences write — must not
                    // block the main thread during playback.
                    scope.launch(Dispatchers.IO) { HistoryStore.updatePosition(context, path, pos, dur) }
                },
                onPlaybackStateUpdate = { path, sTrack, aTrack, spd ->
                    scope.launch(Dispatchers.IO) { HistoryStore.updatePlaybackState(context, path, sTrack, aTrack, spd) }
                },
                 onBack = {
                     try { player.command("stop") } catch (_: Exception) {}
                     try { player.detachSurface() } catch (_: Exception) {}
                     // H14: dispose off the main thread to avoid ANR during teardown.
                     teardownScope.launch {
                         try { player.dispose() } catch (_: Exception) {}
                     }
                    pendingFile = null
                    directoryVideos = emptyList()
                    // Reload history to pick up thumbnails generated during playback.
                    history = HistoryStore.load(context)
                }
            )
                    }
                }
                "addServer" -> {
                    AddServerScreen(
                onBack = { screen = "browser"; editingServer = null },
                onSave = { config ->
                    if (editingServer != null) {
                        servers = ServerStore.remove(context, editingServer!!.id)
                        servers = ServerStore.add(context, config)
                    } else {
                        servers = ServerStore.add(context, config)
                    }
                    editingServer = null
                    screen = "browser"
                },
                initialConfig = if (screen == "editServer") editingServer else null
            )
                }
                "settings" -> {
                    MobileSettingsScreen(
                        settings = settings,
                        onSettingsChanged = { newSettings ->
                            settings = newSettings
                            SettingsHelper.save(context, newSettings)
                        },
                        onBack = { screen = "browser" }
                    )
                }
                "serverBrowse" -> {
                    if (activeServer != null) {
                    ServerBrowseScreen(
                server = activeServer!!,
                files = serverFiles,
                currentPath = serverPath,
                isLoading = serverLoading,
                onBack = { activeServer = null; serverPath = "" },
                onNavigate = { dir -> serverPath = dir },
                onFilePlay = { file ->
                    val vids = serverFiles.filter { it.isVideo() }
                    val idx = vids.indexOfFirst { it.path == file.path }
                    pendingFile = file
                    playServerConfig = activeServer
                    directoryVideos = vids
                    playIndex = if (idx >= 0) idx else 0
                    resumePosition = 0.0
                    resumeSid = null
                    resumeAid = null
                    resumeSpeed = 0.0
                },
                onDeleteFile = { file ->
                    scope.launch {
                        val srv = activeServer ?: return@launch
                        val ok = withContext(Dispatchers.IO) { MobileVfsManager.deleteRemoteFile(srv, file.path) }
                        if (ok) {
                            serverFiles = serverFiles.filterNot { it.path == file.path }
                            Toast.makeText(context, I18n.get("toast_deleted"), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, I18n.get("toast_delete_failed"), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onRenameFile = { file, newName ->
                    scope.launch {
                        val srv = activeServer ?: return@launch
                        val ok = withContext(Dispatchers.IO) { MobileVfsManager.renameRemoteFile(srv, file.path, newName) }
                        if (ok) {
                            serverFiles = serverFiles.map { if (it.path == file.path) it.copy(name = newName) else it }
                            Toast.makeText(context, I18n.get("toast_renamed"), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, I18n.get("toast_rename_failed"), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onMoveFile = { file, destDir ->
                    scope.launch {
                        val srv = activeServer ?: return@launch
                        val ok = withContext(Dispatchers.IO) { MobileVfsManager.moveRemoteFile(srv, file.path, destDir) }
                        if (ok) {
                            serverFiles = serverFiles.filterNot { it.path == file.path }
                            Toast.makeText(context, I18n.get("toast_moved"), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, I18n.get("toast_move_failed"), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
                    }
                }
                "browser" -> FileBrowserScreen(
            onFilePlay = { file, allFiles ->
                val allVideos = allFiles.filter { it.isVideo() }
                val idx = allVideos.indexOfFirst { it.path == file.path }
                pendingFile = file
                directoryVideos = allVideos
                localSiblings = allFiles
                playServerConfig = null
                playIndex = if (idx >= 0) idx else 0
                resumePosition = 0.0
                resumeSid = null
                resumeAid = null
                resumeSpeed = 0.0
            },
            onOpenSettings = { screen = "settings" },
            onAddServer = { screen = "addServer" },
            onServerClick = { srv -> activeServer = srv; serverPath = srv.basePath },
            servers = servers,
            onServerDelete = { id -> servers = ServerStore.remove(context, id) },
            onServerEdit = { srv -> editingServer = srv; screen = "editServer" },
            localFolders = localFolders,
            onAddLocalFolder = { name, treeUriStr ->
                val uri = android.net.Uri.parse(treeUriStr)
                localFolders = LocalFolderStore.add(context, name, uri)
            },
            onLocalFolderDelete = { id ->
                localFolders = LocalFolderStore.remove(context, id)
            },
            history = history,
            onPlayHistory = { entry ->
                if (entry.protocol == VfsProtocol.LOCAL) {
                    scope.launch {
                        val fileNode = FileNode(
                            name = entry.name, path = entry.path,
                            isDirectory = false, protocol = VfsProtocol.LOCAL
                        )
                        var siblings: List<FileNode> = listOf(fileNode)
                        var idx = 0
                        if (entry.treeUriString != null && entry.parentDocId != null) {
                            val vids = SafPlaylistBuilder.buildSiblingPlaylist(
                                context, entry.treeUriString, entry.parentDocId
                            )
                            if (vids.isNotEmpty()) {
                                val found = vids.indexOfFirst { it.path == entry.path }
                                if (found >= 0) {
                                    siblings = vids
                                    idx = found
                                }
                            }
                        }
                        pendingFile = fileNode
                        playServerConfig = null
                        directoryVideos = siblings
                        localSiblings = siblings
                        playIndex = idx
                        resumePosition = entry.position
                        resumeSid = entry.selectedSid
                        resumeAid = entry.selectedAid
                        resumeSpeed = entry.speed
                    }
                } else {
                    val srv = servers.firstOrNull { it.id == entry.serverId }
                    if (srv != null) {
                        pendingHistoryPlay = entry.path
                        resumePosition = entry.position
                        resumeSid = entry.selectedSid
                        resumeAid = entry.selectedAid
                        resumeSpeed = entry.speed
                        activeServer = srv
                        serverPath = entry.path.substringBeforeLast('/').ifBlank { "/" }
                    } else {
                        Toast.makeText(context, I18n.get("server_not_found"), Toast.LENGTH_SHORT).show()
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
