package dev.windplayer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.VfsProtocol
import dev.windplayer.vfs.formatFileSize
import dev.windplayer.vfs.isSubtitle
import dev.windplayer.vfs.isVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onFilePlay: (FileNode, List<FileNode>) -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    onServerClick: (ServerConfig) -> Unit,
    servers: List<ServerConfig>,
    onServerDelete: (String) -> Unit,
    onServerEdit: (ServerConfig) -> Unit,
    history: List<HistoryEntry> = emptyList(),
    onPlayHistory: (HistoryEntry) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showAllFiles by remember { mutableStateOf(false) }
    var contextMenuFile by remember { mutableStateOf<FileNode?>(null) }
    var renameFile by remember { mutableStateOf<FileNode?>(null) }
    var renameText by remember { mutableStateOf("") }
    var clipboardFile by remember { mutableStateOf<FileNode?>(null) }
    var clipboardIsCut by remember { mutableStateOf(false) }

    var rootTreeUri by remember { mutableStateOf(SafHelper.loadTreeUri(context)) }
    var dirStack by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileNode>>(emptyList()) }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            SafHelper.takePermission(context, uri)
            SafHelper.saveTreeUri(context, uri)
            rootTreeUri = uri
            SafHelper.rootFromUri(context, uri)?.let { dirStack = listOf(it) }
        }
    }

    LaunchedEffect(rootTreeUri) {
        if (rootTreeUri != null && dirStack.isEmpty()) {
            SafHelper.rootFromUri(context, rootTreeUri!!)?.let { dirStack = listOf(it) }
        }
    }

    LaunchedEffect(dirStack.lastOrNull()) {
        val dir = dirStack.lastOrNull()
        files = if (dir != null) {
            withContext(Dispatchers.IO) { SafHelper.listFiles(context, dir) }
        } else emptyList()
    }

    // Navigate up one directory on system back when inside a subfolder;
    // at root level let the system handle back (exit screen).
    BackHandler(enabled = dirStack.size > 1) {
        dirStack = dirStack.dropLast(1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (dirStack.isNotEmpty()) dirStack.last().name ?: "WindPlayer" else "WindPlayer",
                        color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (dirStack.size > 1) {
                        IconButton(onClick = { dirStack = dirStack.dropLast(1) }) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                        }
                    }
                },
                actions = {
                    // Paste button  Evisible when a file is on the clipboard
                    if (clipboardFile != null) {
                        IconButton(onClick = {
                            val clip = clipboardFile ?: return@IconButton
                            val destDir = dirStack.lastOrNull()
                            if (destDir != null) {
                                scope.launch {
                                    val rootDoc = SafHelper.rootFromUri(context, rootTreeUri ?: return@launch) ?: return@launch
                                    val srcFile = rootDoc.listFiles().firstOrNull { it.name == clip.name }
                                    if (srcFile != null) {
                                        if (clipboardIsCut) {
                                            srcFile.renameTo(clip.name)
                                        } else {
                                            // Copy: create a new file and stream-copy contents.
                                            val destFile = destDir.createFile(srcFile.type ?: "application/octet-stream", clip.name)
                                            if (destFile != null) {
                                                context.contentResolver.openInputStream(srcFile.uri)?.use { input ->
                                                    context.contentResolver.openOutputStream(destFile.uri)?.use { output ->
                                                        input.copyTo(output)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    files = withContext(Dispatchers.IO) { SafHelper.listFiles(context, destDir) }
                                    clipboardFile = null
                                }
                            }
                        }) {
                            Icon(Icons.Default.FolderOpen, "Paste", tint = Color(0xFF0F84E4))
                        }
                    }
                    IconButton(onClick = { showAllFiles = !showAllFiles }) {
                        Icon(Icons.Outlined.ViewModule, "Mode", tint = if (showAllFiles) Color(0xFF0F84E4) else Color.White)
                    }
                    IconButton(onClick = { folderLauncher.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, "Open", tint = Color.White)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
            )
        },
        containerColor = Color(0xFF0F0F1A)
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            // Playback History section  Ehorizontal thumbnail carousel
            if (history.isNotEmpty()) {
                item { SectionHeader("Recent", Icons.Default.History) }
                item {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(history, key = { it.path }) { entry ->
                            HistoryCard(entry) { onPlayHistory(entry) }
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp), color = Color(0xFF333366)) }
            }

            // Network Storage section
            item { SectionHeader("Network Storage", Icons.Default.Cloud) }
            items(servers) { server ->
                Surface(Modifier.fillMaxWidth().clickable { onServerClick(server) }, color = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, null, tint = Color(0xFF0F84E4), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(server.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${server.protocol} · ${server.host}", color = Color(0xFF888888), fontSize = 11.sp)
                        }
                        IconButton(onClick = { onServerEdit(server) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, "Edit", tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onServerDelete(server.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, "Delete", tint = Color(0xFF666666), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            item {
                Surface(Modifier.fillMaxWidth().clickable { onAddServer() }, color = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = Color(0xFF0F84E4), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Add Server", color = Color(0xFF0F84E4), fontSize = 14.sp)
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp), color = Color(0xFF333366)) }

            // Local Storage section
            item { SectionHeader("Local Storage", Icons.Default.Folder) }

            if (dirStack.isEmpty()) {
                item {
                    Surface(Modifier.fillMaxWidth().clickable { folderLauncher.launch(null) }, color = Color.Transparent) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderOpen, null, tint = Color(0xFFFFA726), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Open Folder", color = Color(0xFFCCCCCC), fontSize = 14.sp)
                        }
                    }
                }
            } else {
                val displayFiles = if (showAllFiles) files else files.filter { it.isDirectory || it.isVideo() }
                items(displayFiles, key = { it.path }) { file ->
                    MobileFileRow(
                        file = file,
                        onClick = {
                            if (file.isDirectory) {
                                val parent = dirStack.last()
                                parent.listFiles().firstOrNull { it.name == file.name && it.isDirectory }?.let { sub ->
                                    dirStack = dirStack + sub
                                }
                            }
                        },
                        onPlay = { onFilePlay(file, files) },
                        onLongClick = { contextMenuFile = file }
                    )
                }
                if (files.isEmpty()) {
                    item { Text("(empty)", color = Color(0xFF666666), fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }

    // Long-press context menu
    contextMenuFile?.let { file ->
        ModalBottomSheet(onDismissRequest = { contextMenuFile = null }, containerColor = Color(0xFF1A1A2E)) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(file.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)

                // Rename
                Surface(Modifier.fillMaxWidth().clickable {
                    renameFile = file
                    renameText = file.name
                    contextMenuFile = null
                }, color = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, "Rename", tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Rename", color = Color.White, fontSize = 14.sp)
                    }
                }

                // Copy
                Surface(Modifier.fillMaxWidth().clickable {
                    clipboardFile = file
                    clipboardIsCut = false
                    contextMenuFile = null
                }, color = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, "Copy", tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Copy", color = Color.White, fontSize = 14.sp)
                    }
                }

                // Cut / Move
                Surface(Modifier.fillMaxWidth().clickable {
                    clipboardFile = file
                    clipboardIsCut = true
                    contextMenuFile = null
                }, color = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ArrowBack, "Cut", tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Cut", color = Color.White, fontSize = 14.sp)
                    }
                }

                // Delete
                Surface(Modifier.fillMaxWidth().clickable {
                    val docFile = dirStack.lastOrNull()?.listFiles()?.firstOrNull { it.name == file.name }
                    if (docFile?.delete() == true) {
                        files = files.filterNot { it.path == file.path }
                    }
                    contextMenuFile = null
                }, color = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF4444), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Delete", color = Color(0xFFFF4444), fontSize = 14.sp)
                    }
                }
            }
        }
    }

    // Rename dialog
    renameFile?.let {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameFile = null },
            title = { Text("Rename", color = Color.White) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color(0xFF0F84E4), cursorColor = Color(0xFF0F84E4)
                    )
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val docFile = dirStack.lastOrNull()?.listFiles()?.firstOrNull { d -> d.name == it.name }
                    if (docFile != null && renameText.isNotBlank()) {
                        docFile.renameTo(renameText)
                        scope.launch {
                            files = withContext(Dispatchers.IO) { SafHelper.listFiles(context, dirStack.last()) }
                        }
                    }
                    renameFile = null
                }) { Text("OK", color = Color(0xFF0F84E4)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renameFile = null }) {
                    Text("Cancel", color = Color(0xFF888888))
                }
            },
            containerColor = Color(0xFF1A1A2E)
        )
    }

    // Paste button in title bar when clipboard is active is handled in the actions section above
    // via clipboardFile state. The paste action appears when clipboardFile != null.
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color(0xFF888888), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileFileRow(
    file: FileNode,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val isVideo = file.isVideo()
    val iconTint = if (file.isDirectory) Color(0xFFFFA726) else if (isVideo) Color(0xFF0F84E4) else Color(0xFF888888)

    Surface(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick), color = Color.Transparent) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (file.isDirectory) Icons.Default.Folder else Icons.Default.PlayArrow,
                null, tint = iconTint, modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (file.size > 0) Text(formatFileSize(file.size), color = Color(0xFF888888), fontSize = 11.sp)
            }
            if (isVideo) {
                IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry, onClick: () -> Unit) {
    // Load thumbnail asynchronously
    var thumbBitmap by remember(entry.thumbnailPath) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(entry.thumbnailPath) {
        val path = entry.thumbnailPath ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            thumbBitmap = try { android.graphics.BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
        }
    }

    Column(
        Modifier.width(128.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF1A1A2E)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val bmp = thumbBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF0F84E4), modifier = Modifier.size(32.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            entry.name,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val mins = diff / 60000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}
