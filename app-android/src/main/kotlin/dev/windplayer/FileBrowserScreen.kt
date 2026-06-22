package dev.windplayer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.formatFileSize
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
    onServerEdit: (ServerConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                items(files, key = { it.path }) { file ->
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
                        onPlay = { onFilePlay(file, files.filter { it.isVideo() }) }
                    )
                }
                if (files.isEmpty()) {
                    item { Text("(empty)", color = Color(0xFF666666), fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color(0xFF888888), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MobileFileRow(
    file: FileNode,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {
    val isVideo = file.isVideo()
    val iconTint = if (file.isDirectory) Color(0xFFFFA726) else if (isVideo) Color(0xFF0F84E4) else Color(0xFF888888)

    Surface(Modifier.fillMaxWidth().clickable { onClick() }, color = Color.Transparent) {
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
