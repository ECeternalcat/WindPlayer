package dev.windplayer

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.ui.I18n
import dev.windplayer.ui.PlayerSettings
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.VfsProtocol
import dev.windplayer.vfs.isVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MobileApp(externalVideoUri: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("browser") }
    var settings by remember { mutableStateOf(SettingsHelper.load(context)) }
    var pendingFile by remember { mutableStateOf<FileNode?>(null) }
    val player = remember { MpvPlayer() }

    // Defense-in-depth: ensure mpv native handle + observer are released when
    // MobileApp leaves composition (e.g. Activity destroyed by the system).
    // Normal teardown still happens via MobilePlayerScreen.onBack.
    DisposableEffect(player) {
        onDispose {
            // H14: dispose() synchronously calls MPVLib.destroy() (JNI), which
            // can take 100ms–1s+. Run on Dispatchers.IO to avoid blocking the
            // main thread during Activity teardown.
            scope.launch(Dispatchers.IO) {
                try { player.dispose() } catch (_: Exception) {}
            }
        }
    }

    var servers by remember { mutableStateOf(ServerStore.load(context)) }
    var history by remember { mutableStateOf(HistoryStore.load(context)) }
    var activeServer by remember { mutableStateOf<ServerConfig?>(null) }
    var playServerConfig by remember { mutableStateOf<ServerConfig?>(null) }
    var directoryVideos by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var localSiblings by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var playIndex by remember { mutableStateOf(0) }
    var resumePosition by remember { mutableStateOf(0.0) }
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
            Toast.makeText(context, "Connect failed: $it", Toast.LENGTH_LONG).show()
            serverError = null
        }
    }

    LaunchedEffect(Unit) {
        // Force-initialize ServerStore so we know whether encryption is in use.
        ServerStore.load(context)
        if (!ServerStore.encryptionActive) {
            Toast.makeText(
                context,
                "Warning: device doesn't support encrypted storage — server passwords stored in plaintext",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(settings.language) { I18n.current = settings.language }

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
    }

    when {
        pendingFile != null -> {
            MobilePlayerScreen(
                player = player,
                file = pendingFile!!,
                serverConfig = playServerConfig,
                directoryVideos = directoryVideos,
                localSiblings = localSiblings,
                currentIndex = playIndex,
                autoPlayNext = settings.autoPlayNext,
                resumePosition = resumePosition,
                onFilePlayed = { playedFile ->
                    val proto = playServerConfig?.protocol ?: VfsProtocol.LOCAL
                    val sid = playServerConfig?.id
                    val treeUri = if (proto == VfsProtocol.LOCAL) SafHelper.loadTreeUri(context)?.toString() else null
                    val parentDocId = if (proto == VfsProtocol.LOCAL) SafPlaylistBuilder.extractParentDocId(playedFile.path) else null
                    history = HistoryStore.add(context, HistoryEntry(
                        name = playedFile.name, path = playedFile.path,
                        protocol = proto, serverId = sid,
                        timestamp = System.currentTimeMillis(),
                        parentDocId = parentDocId, treeUriString = treeUri
                    ))
                },
                onPositionUpdate = { path, pos, dur ->
                    HistoryStore.updatePosition(context, path, pos, dur)
                },
                onBack = {
                    try { player.command("stop") } catch (_: Exception) {}
                    try { player.detachSurface() } catch (_: Exception) {}
                    // H14: dispose off the main thread to avoid ANR during teardown.
                    scope.launch(Dispatchers.IO) {
                        try { player.dispose() } catch (_: Exception) {}
                    }
                    pendingFile = null
                    directoryVideos = emptyList()
                    // Reload history to pick up thumbnails generated during playback.
                    history = HistoryStore.load(context)
                }
            )
        }
        screen == "addServer" || screen == "editServer" -> {
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
        screen == "settings" -> {
            MobileSettingsScreen(
                settings = settings,
                onSettingsChanged = { newSettings ->
                    settings = newSettings
                    SettingsHelper.save(context, newSettings)
                },
                onBack = { screen = "browser" }
            )
        }
        activeServer != null -> {
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
                },
                onDeleteFile = { file ->
                    scope.launch {
                        val srv = activeServer ?: return@launch
                        val ok = withContext(Dispatchers.IO) { MobileVfsManager.deleteRemoteFile(srv, file.path) }
                        if (ok) {
                            serverFiles = serverFiles.filterNot { it.path == file.path }
                            Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onRenameFile = { file, newName ->
                    scope.launch {
                        val srv = activeServer ?: return@launch
                        val ok = withContext(Dispatchers.IO) { MobileVfsManager.renameRemoteFile(srv, file.path, newName) }
                        if (ok) {
                            serverFiles = serverFiles.map { if (it.path == file.path) it.copy(name = newName) else it }
                            Toast.makeText(context, "Renamed", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Rename failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onMoveFile = { file, destDir ->
                    scope.launch {
                        val srv = activeServer ?: return@launch
                        val ok = withContext(Dispatchers.IO) { MobileVfsManager.moveRemoteFile(srv, file.path, destDir) }
                        if (ok) {
                            serverFiles = serverFiles.filterNot { it.path == file.path }
                            Toast.makeText(context, "Moved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Move failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
        else -> FileBrowserScreen(
            onFilePlay = { file, allFiles ->
                val allVideos = allFiles.filter { it.isVideo() }
                val idx = allVideos.indexOfFirst { it.path == file.path }
                pendingFile = file
                directoryVideos = allVideos
                localSiblings = allFiles
                playServerConfig = null
                playIndex = if (idx >= 0) idx else 0
                resumePosition = 0.0
            },
            onOpenSettings = { screen = "settings" },
            onAddServer = { screen = "addServer" },
            onServerClick = { srv -> activeServer = srv; serverPath = srv.basePath },
            servers = servers,
            onServerDelete = { id -> servers = ServerStore.remove(context, id) },
            onServerEdit = { srv -> editingServer = srv; screen = "editServer" },
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
                    }
                } else {
                    val srv = servers.firstOrNull { it.id == entry.serverId }
                    if (srv != null) {
                        pendingHistoryPlay = entry.path
                        resumePosition = entry.position
                        activeServer = srv
                        serverPath = entry.path.substringBeforeLast('/').ifBlank { "/" }
                    } else {
                        Toast.makeText(context, "Server not found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}
