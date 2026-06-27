package dev.windplayer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import dev.windplayer.ui.I18n
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
    onServerEdit: (ServerConfig) -> Unit,
    localFolders: List<LocalFolder> = emptyList(),
    onAddLocalFolder: (String, String) -> Unit = { _, _ -> },
    onLocalFolderDelete: (String) -> Unit = {},
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
    var showAddFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showAddChooser by remember { mutableStateOf(false) }
    var pendingFolderName by remember { mutableStateOf<String?>(null) }

    // Root of the currently-browsed local tree; set when a saved local folder
    // is opened (used by the clipboard paste source lookup). No longer
    // persisted/auto-opened — folders live in the LocalFolderStore list.
    var rootTreeUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var dirStack by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileNode>>(emptyList()) }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            SafHelper.takePermission(context, uri)
            val name = pendingFolderName
            pendingFolderName = null
            // Always the list flow now: the name dialog runs before the picker.
            if (name != null) {
                onAddLocalFolder(name, uri.toString())
            }
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
                        color = WindColors.Ink,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (dirStack.size > 1) {
                        IconButton(onClick = { dirStack = dirStack.dropLast(1) }) {
                            PhosphorIcon(Phosphor.ARROW_LEFT, "Back", tint = WindColors.Ink, size = 22.dp)
                        }
                    }
                },
                actions = {
                    // Paste button — visible when a file is on the clipboard
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
                            PhosphorIcon(Phosphor.FOLDER_OPEN, I18n.get("paste"), tint = WindColors.SignalOrange, size = 22.dp)
                        }
                    }
                    IconButton(onClick = { showAllFiles = !showAllFiles }) {
                        PhosphorIcon(
                            if (showAllFiles) Phosphor.LIST_BULLETS else Phosphor.GRID_FOUR,
                            "Mode",
                            tint = if (showAllFiles) WindColors.SignalOrange else WindColors.Ink,
                            size = 22.dp
                        )
                    }
                    IconButton(onClick = { showAddChooser = true }) {
                        PhosphorIcon(Phosphor.PLUS, "Add Storage", tint = WindColors.Ink, size = 22.dp)
                    }
                    IconButton(onClick = onOpenSettings) {
                        PhosphorIcon(Phosphor.GEAR, "Settings", tint = WindColors.Ink, size = 22.dp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WindColors.LiftedCream)
            )
        },
        containerColor = WindColors.CanvasCream
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            // Playback History section — horizontal thumbnail carousel
            if (history.isNotEmpty()) {
                item { SectionHeader(I18n.get("recent")) }
                item {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(history, key = { it.path }) { entry ->
                            HistoryCard(entry) { onPlayHistory(entry) }
                        }
                    }
                }
                item { HairlineDivider() }
            }

            // Network Storage section
            item { SectionHeader(I18n.get("network_storage")) }
            items(servers) { server ->
                Surface(Modifier.fillMaxWidth().clickable { onServerClick(server) }, color = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProtocolBadge(server)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(server.name, color = WindColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${server.protocol} · ${server.host}", color = WindColors.Slate, fontSize = 11.sp)
                        }
                        IconButton(onClick = { onServerEdit(server) }, modifier = Modifier.size(32.dp)) {
                            PhosphorIcon(Phosphor.PENCIL_SIMPLE, "Edit", tint = WindColors.Slate, size = 18.dp)
                        }
                        IconButton(onClick = { onServerDelete(server.id) }, modifier = Modifier.size(32.dp)) {
                            PhosphorIcon(Phosphor.TRASH, "Delete", tint = WindColors.DustTaupe, size = 18.dp)
                        }
                    }
                }
            }
            item {
                Surface(Modifier.fillMaxWidth().clickable { onAddServer() }, color = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(WindColors.Ink),
                            contentAlignment = Alignment.Center
                        ) {
                            PhosphorIcon(Phosphor.PLUS, "Add", tint = WindColors.CanvasCream, size = 16.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(I18n.get("add_server"), color = WindColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            item { HairlineDivider() }

            // Local Storage section
            item { SectionHeader(I18n.get("local_storage")) }

            if (dirStack.isEmpty()) {
                // Show saved local folders as list (like servers)
                items(localFolders, key = { it.id }) { folder ->
                    Surface(Modifier.fillMaxWidth().clickable {
                        val treeUri = android.net.Uri.parse(folder.treeUriString)
                        rootTreeUri = treeUri
                        SafHelper.rootFromUri(context, treeUri)?.let { dirStack = listOf(it) }
                    }, color = Color.Transparent) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PhosphorIcon(Phosphor.FOLDER, null, tint = WindColors.LightSignalOrange, size = 24.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(folder.name, color = WindColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(I18n.get("on_this_device"), color = WindColors.Slate, fontSize = 11.sp)
                            }
                            IconButton(onClick = { onLocalFolderDelete(folder.id) }, modifier = Modifier.size(32.dp)) {
                                PhosphorIcon(Phosphor.TRASH, "Delete", tint = WindColors.DustTaupe, size = 18.dp)
                            }
                        }
                    }
                }
                item {
                    Surface(Modifier.fillMaxWidth().clickable { showAddFolderDialog = true; newFolderName = "" }, color = Color.Transparent) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(WindColors.White)
                                    .borderRing(),
                                contentAlignment = Alignment.Center
                            ) {
                                PhosphorIcon(Phosphor.PLUS, "Add", tint = WindColors.LightSignalOrange, size = 16.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(I18n.get("add_folder"), color = WindColors.LightSignalOrange, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
                            } else if (file.isVideo()) {
                                onFilePlay(file, files)
                            }
                        },
                        onPlay = { onFilePlay(file, files) },
                        onLongClick = { contextMenuFile = file }
                    )
                }
                if (files.isEmpty()) {
                    item { Text(I18n.get("empty_dir"), color = WindColors.DustTaupe, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }

    // Long-press context menu
    contextMenuFile?.let { file ->
        ModalBottomSheet(onDismissRequest = { contextMenuFile = null }, containerColor = WindColors.White) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(file.name, color = WindColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)

                SheetItem(Phosphor.PENCIL_SIMPLE, I18n.get("rename"), WindColors.Ink) {
                    renameFile = file
                    renameText = file.name
                    contextMenuFile = null
                }
                SheetItem(Phosphor.FOLDER_OPEN, I18n.get("copy"), WindColors.Ink) {
                    clipboardFile = file
                    clipboardIsCut = false
                    contextMenuFile = null
                }
                SheetItem(Phosphor.SCISSORS, I18n.get("cut"), WindColors.Ink) {
                    clipboardFile = file
                    clipboardIsCut = true
                    contextMenuFile = null
                }
                SheetItem(Phosphor.TRASH, I18n.get("delete"), WindColors.SignalOrange) {
                    val docFile = dirStack.lastOrNull()?.listFiles()?.firstOrNull { it.name == file.name }
                    if (docFile?.delete() == true) {
                        files = files.filterNot { it.path == file.path }
                    }
                    contextMenuFile = null
                }
            }
        }
    }

    // Rename dialog
    renameFile?.let {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameFile = null },
            shape = WindRadius.Stadium,
            containerColor = WindColors.White,
            titleContentColor = WindColors.Ink,
            textContentColor = WindColors.Slate,
            title = { Text(I18n.get("rename_file")) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = WindRadius.Pill,
                    textStyle = androidx.compose.ui.text.TextStyle(color = WindColors.Ink, fontSize = 14.sp),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedTextColor = WindColors.Ink, unfocusedTextColor = WindColors.Ink,
                        focusedContainerColor = WindColors.CanvasCream,
                        unfocusedContainerColor = WindColors.CanvasCream,
                        focusedIndicatorColor = WindColors.Ink,
                        unfocusedIndicatorColor = WindColors.Hairline,
                        cursorColor = WindColors.Ink
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
                }) { Text(I18n.get("ok"), color = WindColors.Ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renameFile = null }) {
                    Text(I18n.get("cancel"), color = WindColors.Slate)
                }
            }
        )
    }

    // Add-storage chooser — unified entry for Network vs Local storage.
    if (showAddChooser) {
        ModalBottomSheet(onDismissRequest = { showAddChooser = false }, containerColor = WindColors.White) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    I18n.get("add_storage"),
                    color = WindColors.Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                AddChooserRow(
                    glyph = Phosphor.CLOUD,
                    title = I18n.get("network_storage"),
                    subtitle = "SFTP · WebDAV · FTP",
                    tint = WindColors.Ink
                ) {
                    showAddChooser = false
                    onAddServer()
                }
                AddChooserRow(
                    glyph = Phosphor.MONITOR,
                    title = I18n.get("local_storage"),
                    subtitle = I18n.get("on_this_device"),
                    tint = WindColors.Ink
                ) {
                    showAddChooser = false
                    newFolderName = ""
                    showAddFolderDialog = true
                }
            }
        }
    }

    // Add local folder name dialog
    if (showAddFolderDialog) {
        AlertDialog(
            onDismissRequest = { showAddFolderDialog = false },
            shape = WindRadius.Stadium,
            containerColor = WindColors.White,
            titleContentColor = WindColors.Ink,
            textContentColor = WindColors.Slate,
            title = { Text(I18n.get("add_folder")) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    shape = WindRadius.Pill,
                    placeholder = { Text(I18n.get("folder_name"), color = WindColors.DustTaupe) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = WindColors.Ink, fontSize = 14.sp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = WindColors.Ink, unfocusedTextColor = WindColors.Ink,
                        focusedContainerColor = WindColors.CanvasCream,
                        unfocusedContainerColor = WindColors.CanvasCream,
                        focusedIndicatorColor = WindColors.Ink,
                        unfocusedIndicatorColor = WindColors.Hairline,
                        cursorColor = WindColors.Ink
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFolderName.isNotBlank()) {
                        showAddFolderDialog = false
                        pendingFolderName = newFolderName
                        folderLauncher.launch(null)
                    }
                }) { Text(I18n.get("select_folder"), color = WindColors.Ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddFolderDialog = false }) { Text(I18n.get("cancel"), color = WindColors.Slate) }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(6.dp).clip(CircleShape).background(WindColors.SignalOrange)
        )
        Spacer(Modifier.width(8.dp))
        Text(title.uppercase(), color = WindColors.Slate, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.48.sp)
    }
}

@Composable
private fun HairlineDivider() {
    HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp), thickness = 1.dp, color = WindColors.Hairline)
}

@Composable
private fun ProtocolBadge(server: ServerConfig) {
    Surface(shape = CircleShape, color = WindColors.CanvasCream, modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = when (server.protocol) {
                    dev.windplayer.vfs.VfsProtocol.SFTP -> "S"
                    dev.windplayer.vfs.VfsProtocol.WEBDAV -> "W"
                    dev.windplayer.vfs.VfsProtocol.FTP -> "F"
                    else -> "?"
                },
                color = WindColors.Ink,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
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

@Composable
private fun AddChooserRow(
    glyph: Char,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), color = Color.Transparent) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(glyph, title, tint = tint, size = 24.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, color = WindColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = WindColors.Slate, fontSize = 11.sp)
            }
        }
    }
}

private fun Modifier.borderRing(): Modifier = this.then(
    Modifier.border(width = 1.5.dp, color = WindColors.LightSignalOrange, shape = CircleShape)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileFileRow(
    file: FileNode,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val isVideo = file.isVideo()
    val glyph = when {
        file.isDirectory -> Phosphor.FOLDER
        isVideo -> Phosphor.VIDEO
        else -> Phosphor.FILE
    }
    val iconTint = when {
        file.isDirectory -> WindColors.LightSignalOrange
        isVideo -> WindColors.Ink
        else -> WindColors.DustTaupe
    }

    Surface(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick), color = Color.Transparent) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(glyph, null, tint = iconTint, size = 24.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, color = WindColors.Ink, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (file.size > 0) Text(formatFileSize(file.size), color = WindColors.Slate, fontSize = 11.sp)
            }
            if (isVideo) {
                IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                    PhosphorIcon(Phosphor.PLAY, "Play", tint = WindColors.Ink, size = 26.dp)
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
        Modifier.width(132.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            Modifier.fillMaxWidth().height(76.dp),
            shape = RoundedCornerShape(20.dp),
            color = WindColors.LiftedCream
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
                    PhosphorIcon(Phosphor.PLAY, null, tint = WindColors.LightSignalOrange, size = 28.dp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name,
            color = WindColors.Ink,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
