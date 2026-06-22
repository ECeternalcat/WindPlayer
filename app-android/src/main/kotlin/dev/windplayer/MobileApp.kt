package dev.windplayer

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.ui.I18n
import dev.windplayer.ui.PlayerSettings
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.isVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MobileApp() {
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
            try { player.dispose() } catch (_: Exception) {}
        }
    }

    var servers by remember { mutableStateOf(ServerStore.load(context)) }
    var activeServer by remember { mutableStateOf<ServerConfig?>(null) }
    var playServerConfig by remember { mutableStateOf<ServerConfig?>(null) }
    var directoryVideos by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var playIndex by remember { mutableStateOf(0) }
    var serverFiles by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var serverPath by remember { mutableStateOf("") }
    var serverLoading by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var serverError by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(settings.language) { I18n.current = settings.language }

    when {
        pendingFile != null -> {
            MobilePlayerScreen(
                player = player,
                file = pendingFile!!,
                serverConfig = playServerConfig,
                directoryVideos = directoryVideos,
                currentIndex = playIndex,
                autoPlayNext = settings.autoPlayNext,
                onBack = {
                    try { player.command("stop") } catch (_: Exception) {}
                    try { player.detachSurface() } catch (_: Exception) {}
                    player.dispose()
                    pendingFile = null
                    directoryVideos = emptyList()
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
                    activeServer = null
                }
            )
        }
        else -> FileBrowserScreen(
            onFilePlay = { file, allVideos ->
                val idx = allVideos.indexOfFirst { it.path == file.path }
                pendingFile = file
                directoryVideos = allVideos
                playIndex = if (idx >= 0) idx else 0
            },
            onOpenSettings = { screen = "settings" },
            onAddServer = { screen = "addServer" },
            onServerClick = { srv -> activeServer = srv; serverPath = srv.basePath },
            servers = servers,
            onServerDelete = { id -> servers = ServerStore.remove(context, id) },
            onServerEdit = { srv -> editingServer = srv; screen = "editServer" }
        )
    }
}
