package dev.windplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.formatFileSize
import dev.windplayer.vfs.isSubtitle
import dev.windplayer.vfs.isVideo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerBrowseScreen(
    server: ServerConfig,
    files: List<FileNode>,
    currentPath: String,
    isLoading: Boolean,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onFilePlay: (FileNode) -> Unit,
    onDeleteFile: (FileNode) -> Unit = {},
    onRenameFile: (FileNode, String) -> Unit = { _, _ -> },
    onMoveFile: (FileNode, String) -> Unit = { _, _ -> }
) {
    var showAllFiles by remember { mutableStateOf(false) }
    var contextMenuFile by remember { mutableStateOf<FileNode?>(null) }
    var renameTarget by remember { mutableStateOf<FileNode?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Navigate up one directory if we're deeper than basePath; otherwise exit.
    fun handleBack() {
        val trimmedCurrent = currentPath.trimEnd('/').removePrefix("/")
        val trimmedBase = server.basePath.trimEnd('/').removePrefix("/")
        if (trimmedCurrent == trimmedBase || trimmedCurrent.isEmpty()) {
            onBack()
        } else {
            val parent = "/" + trimmedCurrent.substringBeforeLast('/', "").trimEnd('/')
            onNavigate(parent.ifBlank { "/" })
        }
    }

    BackHandler { handleBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(currentPath, color = Color(0xFF888888), fontSize = 11.sp, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { handleBack() }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showAllFiles = !showAllFiles }) {
                        Icon(
                            Icons.Outlined.ViewModule,
                            "Mode",
                            tint = if (showAllFiles) Color(0xFF0F84E4) else Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
            )
        },
        containerColor = Color(0xFF0F0F1A)
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF0F84E4))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    val displayFiles = if (showAllFiles) files else files.filter { it.isDirectory || it.isVideo() }
                    items(displayFiles, key = { it.path }) { file ->
                        MobileFileRow(
                            file = file,
                            onClick = {
                                if (file.isDirectory) {
                                    onNavigate(file.path)
                                }
                            },
                            onPlay = { onFilePlay(file) },
                            onLongClick = { contextMenuFile = file }
                        )
                    }
                    if (files.isEmpty()) {
                        item { Text("(empty)", color = Color(0xFF666666), fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                    }
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

                Surface(Modifier.fillMaxWidth().clickable {
                    renameTarget = file
                    renameText = file.name
                    contextMenuFile = null
                }, color = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, "Rename", tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Rename", color = Color.White, fontSize = 14.sp)
                    }
                }

                Surface(Modifier.fillMaxWidth().clickable {
                    onMoveFile(file, currentPath)
                    contextMenuFile = null
                }, color = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, "Move", tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Move to current dir", color = Color.White, fontSize = 14.sp)
                    }
                }

                Surface(Modifier.fillMaxWidth().clickable {
                    onDeleteFile(file)
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
    renameTarget?.let {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color(0xFF0F84E4), cursorColor = Color(0xFF0F84E4)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRenameFile(it, renameText)
                    renameTarget = null
                }) { Text("OK", color = Color(0xFF0F84E4)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = Color(0xFF888888)) }
            },
            containerColor = Color(0xFF1A1A2E)
        )
    }
}
