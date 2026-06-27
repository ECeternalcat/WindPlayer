package dev.windplayer

import androidx.activity.compose.BackHandler
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
import dev.windplayer.vfs.FileNode
import dev.windplayer.ui.I18n
import dev.windplayer.vfs.ServerConfig
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
                        Text(
                            server.name,
                            color = WindColors.Ink,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(currentPath, color = WindColors.Slate, fontSize = 11.sp, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { handleBack() }) {
                        PhosphorIcon(Phosphor.ARROW_LEFT, "Back", tint = WindColors.Ink, size = 22.dp)
                    }
                },
                actions = {
                    IconButton(onClick = { showAllFiles = !showAllFiles }) {
                        PhosphorIcon(
                            if (showAllFiles) Phosphor.LIST_BULLETS else Phosphor.GRID_FOUR,
                            "Mode",
                            tint = if (showAllFiles) WindColors.SignalOrange else WindColors.Ink,
                            size = 22.dp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WindColors.LiftedCream)
            )
        },
        containerColor = WindColors.CanvasCream
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = WindColors.Ink)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    val displayFiles = if (showAllFiles) files else files.filter { it.isDirectory || it.isVideo() }
                    items(displayFiles, key = { it.path }) { file ->
                        MobileFileRow(
                            file = file,
                            onClick = {
                                if (file.isDirectory) {
                                    onNavigate(file.path)
                                } else if (file.isVideo()) {
                                    onFilePlay(file)
                                }
                            },
                            onPlay = { onFilePlay(file) },
                            onLongClick = { contextMenuFile = file }
                        )
                    }
                    if (files.isEmpty()) {
                        item {
                            Text(
                                I18n.get("empty_dir"),
                                color = WindColors.DustTaupe,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Long-press context menu
    contextMenuFile?.let { file ->
        ModalBottomSheet(onDismissRequest = { contextMenuFile = null }, containerColor = WindColors.White) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    file.name,
                    color = WindColors.Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                SheetItem(Phosphor.PENCIL_SIMPLE, I18n.get("rename"), WindColors.Ink) {
                    renameTarget = file
                    renameText = file.name
                    contextMenuFile = null
                }
                SheetItem(Phosphor.FOLDER_SIMPLE, I18n.get("move_here"), WindColors.Ink) {
                    onMoveFile(file, currentPath)
                    contextMenuFile = null
                }
                SheetItem(Phosphor.TRASH, I18n.get("delete"), WindColors.SignalOrange) {
                    onDeleteFile(file)
                    contextMenuFile = null
                }
            }
        }
    }

    // Rename dialog
    renameTarget?.let {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            shape = WindRadius.Stadium,
            containerColor = WindColors.White,
            titleContentColor = WindColors.Ink,
            textContentColor = WindColors.Slate,
            title = { Text(I18n.get("rename_file")) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = WindRadius.Pill,
                    textStyle = androidx.compose.ui.text.TextStyle(color = WindColors.Ink, fontSize = 14.sp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = WindColors.CanvasCream,
                        unfocusedContainerColor = WindColors.CanvasCream,
                        focusedTextColor = WindColors.Ink,
                        unfocusedTextColor = WindColors.Ink,
                        focusedIndicatorColor = WindColors.Ink,
                        unfocusedIndicatorColor = WindColors.Hairline,
                        cursorColor = WindColors.Ink
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRenameFile(it, renameText)
                    renameTarget = null
                }) { Text(I18n.get("ok"), color = WindColors.Ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(I18n.get("cancel"), color = WindColors.Slate) }
            }
        )
    }
}

@Composable
private fun SheetItem(glyph: Char, label: String, tint: Color, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), color = Color.Transparent) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(glyph, label, tint = tint, size = 22.dp)
            Spacer(Modifier.width(16.dp))
            Text(label, color = tint, fontSize = 14.sp)
        }
    }
}
