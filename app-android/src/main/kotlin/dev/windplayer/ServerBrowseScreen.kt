package dev.windplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
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
    onFilePlay: (FileNode) -> Unit
) {
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
                    items(files, key = { it.path }) { file ->
                        MobileFileRow(
                            file = file,
                            onClick = {
                                if (file.isDirectory) {
                                    onNavigate(file.path)
                                }
                            },
                            onPlay = { onFilePlay(file) }
                        )
                    }
                    if (files.isEmpty()) {
                        item { Text("(empty)", color = Color(0xFF666666), fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                    }
                }
            }
        }
    }
}
