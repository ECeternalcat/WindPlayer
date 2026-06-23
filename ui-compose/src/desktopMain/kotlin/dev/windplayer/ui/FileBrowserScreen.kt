package dev.windplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.vfs.*
import kotlinx.coroutines.launch

private data class Breadcrumb(val name: String, val path: String)

@Composable
fun FileBrowserScreen(
    vfsManager: VfsManager,
    onPlayFile: (PlaybackParams) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    recentFiles: List<RecentFile> = emptyList(),
    onPlayRecentFile: ((RecentFile) -> Unit)? = null,
    bookmarks: List<String> = emptyList(),
    onBookmarkAdded: ((path: String) -> Unit)? = null,
    onBookmarkRemoved: ((path: String) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var serverList by remember { mutableStateOf(vfsManager.servers) }
    var activeServerId by remember { mutableStateOf<String?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var breadcrumbs by remember { mutableStateOf<List<Breadcrumb>>(emptyList()) }
    var isLocal by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("name") }
    var sortAsc by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FileNode?>(null) }
    var renameTarget by remember { mutableStateOf<FileNode?>(null) }
    var renameText by remember { mutableStateOf("") }

    if (showAddDialog) {
        AddServerDialog(
            initialConfig = editingServer,
            onDismiss = { showAddDialog = false; editingServer = null },
            onSave = { config ->
                vfsManager.addServer(config)
                serverList = vfsManager.servers
                showAddDialog = false
                editingServer = null
            }
        )
    }

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .background(Color(0xFF1A1A2E))
                .padding(12.dp)
        ) {
            Text(
                text = I18n.get("servers"),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    isLocal = true
                    activeServerId = null
                    currentPath = vfsManager.homeDirectory()
                    breadcrumbs = listOf(Breadcrumb("Home", currentPath))
                    scope.launch {
                        files = vfsManager.listLocalDirectory(currentPath)
                    }
                },
                color = if (isLocal) Color(0xFF2A2A4E) else Color.Transparent,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.MONITOR),
                        contentDescription = "Local",
                        tint = if (isLocal) Color(0xFF0F84E4) else Color(0xFFAAAAAA),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = I18n.get("local_files"), color = Color.White, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (isLocal) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        isLocal = true
                        activeServerId = null
                        currentPath = ""
                        breadcrumbs = listOf(Breadcrumb("Drives", ""))
                        files = vfsManager.listLocalRoots()
                    },
                    color = Color.Transparent,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = iconPainter(PhosphorIcons.LIST),
                            contentDescription = "Drives",
                            tint = Color(0xFFAAAAAA),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = I18n.get("drives"), color = Color(0xFFCCCCCC), fontSize = 13.sp)
                    }
                }
            }

            if (bookmarks.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = Color(0xFF333366)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.STAR),
                        contentDescription = null,
                        tint = Color(0xFFAAAAAA),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = I18n.get("bookmarks"),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                bookmarks.take(8).forEach { bmPath ->
                    val bmName = bmPath.substringAfterLast('\\').substringAfterLast('/').ifBlank { bmPath }
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                isLocal = true
                                activeServerId = null
                                currentPath = bmPath
                                breadcrumbs = listOf(Breadcrumb(bmName, bmPath))
                                searchQuery = ""
                                isLoading = true
                                files = try { vfsManager.listLocalDirectory(bmPath) } catch (_: Exception) { emptyList() }
                                isLoading = false
                            }
                        },
                        color = Color.Transparent,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 5.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = iconPainter(PhosphorIcons.FOLDER),
                                contentDescription = null,
                                tint = Color(0xFFFFA726),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = bmName,
                                color = Color(0xFFCCCCCC),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (onBookmarkRemoved != null) {
                                IconButton(
                                    onClick = { onBookmarkRemoved.invoke(bmPath) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        painter = iconPainter(PhosphorIcons.X),
                                        contentDescription = "Remove",
                                        tint = Color(0xFF666666),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (recentFiles.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = Color(0xFF333366)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.CLOCK),
                        contentDescription = null,
                        tint = Color(0xFFAAAAAA),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = I18n.get("recent"),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                recentFiles.take(8).forEach { recent ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onPlayRecentFile?.invoke(recent)
                        },
                        color = Color.Transparent,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 5.dp, horizontal = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = iconPainter(PhosphorIcons.VIDEO),
                                    contentDescription = null,
                                    tint = Color(0xFF0F84E4),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = recent.name,
                                    color = Color(0xFFCCCCCC),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (recent.position > 1.0 && recent.duration > 0) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${formatDuration(recent.position)} / ${formatDuration(recent.duration)}",
                                    color = Color(0xFF666666),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(start = 22.dp)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color(0xFF333366)
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(serverList, key = { it.id }) { server ->
                    val isActive = activeServerId == server.id
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                if (!vfsManager.isServerConnected(server.id)) {
                                    isLoading = true
                                    errorText = ""
                                    val result = vfsManager.connectServer(server.id)
                                    if (result.isFailure) {
                                        errorText = "Connection failed: ${result.exceptionOrNull()?.message}"
                                        isLoading = false
                                        return@launch
                                    }
                                }
                                activeServerId = server.id
                                isLocal = false
                                currentPath = server.basePath
                                breadcrumbs = listOf(Breadcrumb(server.name, server.basePath))
                                isLoading = true
                                val listResult = vfsManager.listServerDirectory(server.id, currentPath)
                                isLoading = false
                                if (listResult.isSuccess) {
                                    files = listResult.getOrDefault(emptyList())
                                } else {
                                    errorText = listResult.exceptionOrNull()?.message ?: "Unknown error"
                                }
                            }
                        },
                        color = if (isActive) Color(0xFF2A2A4E) else Color.Transparent,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (server.protocol) {
                                    VfsProtocol.SFTP -> "S"
                                    VfsProtocol.WEBDAV -> "W"
                                    VfsProtocol.FTP -> "F"
                                    else -> "?"
                                },
                                color = if (isActive) Color(0xFF0F84E4) else Color(0xFFAAAAAA),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = server.name,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${server.protocol.name} - ${server.host}",
                                    color = Color(0xFF888888),
                                    fontSize = 10.sp
                                )
                            }
                            if (isActive) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            vfsManager.disconnectServer(server.id)
                                            activeServerId = null
                                            files = emptyList()
                                            currentPath = ""
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        painter = iconPainter(PhosphorIcons.X),
                                        contentDescription = "Disconnect",
                                        tint = Color(0xFF888888),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }


            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F84E4))
            ) {
                Icon(
                    painter = iconPainter(PhosphorIcons.PLUS),
                    contentDescription = "Add",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = I18n.get("add_server"), fontSize = 12.sp)
            }

            if (onOpenSettings != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onOpenSettings.invoke() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2A2A3E),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.GEAR),
                        contentDescription = "Settings",
                        tint = Color(0xFFAAAAAA),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = I18n.get("settings"), fontSize = 12.sp)
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF0F0F1A))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (breadcrumbs.size > 1) {
                    IconButton(onClick = {
                        scope.launch {
                            val parentCrumb = breadcrumbs.dropLast(1).last()
                            breadcrumbs = breadcrumbs.dropLast(1)
                            currentPath = parentCrumb.path
                            isLoading = true
                            files = if (isLocal) {
                                if (currentPath.isEmpty()) vfsManager.listLocalRoots()
                                else vfsManager.listLocalDirectory(currentPath)
                            } else {
                                val sid = activeServerId ?: return@launch
                                vfsManager.listServerDirectory(sid, currentPath).getOrDefault(emptyList())
                            }
                            isLoading = false
                        }
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            painter = iconPainter(PhosphorIcons.ARROW_LEFT),
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    breadcrumbs.forEachIndexed { index, crumb ->
                        if (index > 0) {
                            Text(text = " / ", color = Color(0xFF666666), fontSize = 13.sp)
                        }
                        Text(
                            text = crumb.name,
                            color = if (index == breadcrumbs.lastIndex) Color.White else Color(0xFF0F84E4),
                            fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                if (index < breadcrumbs.lastIndex) {
                                    scope.launch {
                                        breadcrumbs = breadcrumbs.subList(0, index + 1)
                                        currentPath = crumb.path
                                        isLoading = true
                                        files = if (isLocal) {
                                            vfsManager.listLocalDirectory(currentPath)
                                        } else {
                                            val sid = activeServerId ?: return@launch
                                            vfsManager.listServerDirectory(sid, currentPath).getOrDefault(emptyList())
                                        }
                                        isLoading = false
                                    }
                                }
                            }
                        )
                    }
                }
                if (isLocal && currentPath.isNotBlank() && onBookmarkAdded != null) {
                    val isBookmarked = bookmarks.contains(currentPath)
                    IconButton(
                        onClick = {
                            if (isBookmarked) onBookmarkRemoved?.invoke(currentPath)
                            else onBookmarkAdded.invoke(currentPath)
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            painter = iconPainter(PhosphorIcons.STAR),
                            contentDescription = "Bookmark",
                            tint = if (isBookmarked) Color(0xFFFFA726) else Color(0xFF888888),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(I18n.get("search"), color = Color(0xFF666666), fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(
                            painter = iconPainter(PhosphorIcons.MAGNIFYING_GLASS),
                            contentDescription = null,
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                Icon(
                                    painter = iconPainter(PhosphorIcons.X),
                                    contentDescription = "Clear",
                                    tint = Color(0xFF666666),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 12.sp
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1A1A2E),
                        unfocusedContainerColor = Color(0xFF1A1A2E),
                        cursorColor = Color(0xFF0F84E4),
                        focusedIndicatorColor = Color(0xFF0F84E4),
                        unfocusedIndicatorColor = Color(0xFF333366)
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box {
                    TextButton(
                        onClick = { showSortMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = when (sortBy) {
                                "size" -> I18n.get("sort_size")
                                "date" -> I18n.get("sort_date")
                                "type" -> I18n.get("sort_type")
                                else -> I18n.get("sort_name")
                            },
                            color = Color(0xFFCCCCCC),
                            fontSize = 12.sp
                        )
                        Text(
                            text = if (sortAsc) " \u2191" else " \u2193",
                            color = Color(0xFF0F84E4),
                            fontSize = 12.sp
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(I18n.get("sort_name")) },
                            onClick = { sortBy = "name"; showSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(I18n.get("sort_size")) },
                            onClick = { sortBy = "size"; showSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(I18n.get("sort_date")) },
                            onClick = { sortBy = "date"; showSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(I18n.get("sort_type")) },
                            onClick = { sortBy = "type"; showSortMenu = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (sortAsc) I18n.get("sort_desc") else I18n.get("sort_asc")) },
                            onClick = { sortAsc = !sortAsc; showSortMenu = false }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (errorText.isNotBlank()) {
                Text(text = errorText, color = Color(0xFFFF4444), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF0F84E4))
                }
            } else if (currentPath.isBlank() && activeServerId == null && !isLocal) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = I18n.get("no_selection"),
                            color = Color(0xFF333366),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = I18n.get("select_prompt"),
                            color = Color(0xFF666666),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                val displayFiles = remember(files, sortBy, sortAsc, searchQuery) {
                    val filtered = if (searchQuery.isBlank()) files
                        else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    val dirs = filtered.filter { it.isDirectory }
                    val fileItems = filtered.filter { !it.isDirectory }
                    val comparator: Comparator<FileNode> = when (sortBy) {
                        "size" -> compareBy { it.size }
                        "date" -> compareBy { it.lastModified }
                        "type" -> compareBy { it.name.substringAfterLast('.').lowercase() }
                        else -> compareBy { it.name.lowercase() }
                    }
                    val sortedDirs = if (sortAsc) dirs.sortedWith(comparator) else dirs.sortedWith(comparator.reversed())
                    val sortedFiles = if (sortAsc) fileItems.sortedWith(comparator) else fileItems.sortedWith(comparator.reversed())
                    sortedDirs + sortedFiles
                }
                if (displayFiles.isEmpty() && searchQuery.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "${I18n.get("no_results")} \"$searchQuery\"",
                            color = Color(0xFF666666),
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(displayFiles, key = { it.path }) { file ->
                        FileRow(
                            file = file,
                            onClick = {
                                if (file.isDirectory) {
                                    scope.launch {
                                        currentPath = file.path
                                        breadcrumbs = breadcrumbs + Breadcrumb(file.name, file.path)
                                        isLoading = true
                                        errorText = ""
                                        searchQuery = ""
                                        files = if (isLocal) {
                                            vfsManager.listLocalDirectory(file.path)
                                        } else {
                                            val sid = activeServerId ?: return@launch
                                            vfsManager.listServerDirectory(sid, file.path).getOrDefault(emptyList())
                                        }
                                        isLoading = false
                                    }
                                }
                            },
                            onPlay = {
                                scope.launch {
                                    isLoading = true
                                    errorText = ""
                                    val dirDir = file.path.substringBeforeLast('/')
                                    val dirDirLocal = file.path.substringBeforeLast('\\').ifBlank { file.path.substringBeforeLast('/') }
                                    val videoPaths = files
                                        .filter { it.isVideo() }
                                        .sortedBy { it.name.lowercase() }
                                        .map { it.path }
                                    val fileIndex = videoPaths.indexOf(file.path)
                                    val result = if (isLocal) {
                                        Result.success(vfsManager.prepareLocalPlayback(file).copy(
                                            dirPath = dirDirLocal,
                                            isLocal = true,
                                            directoryVideoPaths = videoPaths,
                                            currentFileIndex = fileIndex
                                        ))
                                    } else {
                                        val sid = activeServerId ?: return@launch
                                        vfsManager.preparePlayback(sid, file).map { it.copy(
                                            serverId = sid,
                                            dirPath = dirDir,
                                            directoryVideoPaths = videoPaths,
                                            currentFileIndex = fileIndex
                                        ) }
                                    }
                                    isLoading = false
                                    if (result.isSuccess) {
                                        onPlayFile(result.getOrNull()!!)
                                    } else {
                                        errorText = result.exceptionOrNull()?.message ?: "Unknown error"
                                    }
                                }
                            },
                            showActions = isLocal,
                            onDelete = { deleteTarget = file },
                            onRename = {
                                renameTarget = file
                                renameText = file.name
                            }
                        )
                    }
                }
                }
            }
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(I18n.get("delete_file")) },
            text = { Text("${I18n.get("delete")} \"${deleteTarget!!.name}\"? ${I18n.get("delete_confirm")}") },
            confirmButton = {
                TextButton(onClick = {
                    val target = deleteTarget!!
                    deleteTarget = null
                    scope.launch {
                        if (vfsManager.deleteLocalFile(target.path)) {
                            files = files.filterNot { it.path == target.path }
                        } else {
                            errorText = "Failed to delete file"
                        }
                    }
                }) { Text(I18n.get("delete"), color = Color(0xFFFF4444)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(I18n.get("cancel")) }
            },
            containerColor = Color(0xFF1A1A2E),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCCCCCC)
        )
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(I18n.get("rename_file")) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 14.sp
                    ),
                    colors = TextFieldDefaults.colors(
                        cursorColor = Color(0xFF0F84E4),
                        focusedIndicatorColor = Color(0xFF0F84E4),
                        unfocusedIndicatorColor = Color(0xFF333366)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = renameTarget!!
                    renameTarget = null
                    if (renameText.isNotBlank() && renameText != target.name) {
                        scope.launch {
                            if (vfsManager.renameLocalFile(target.path, renameText)) {
                                files = if (currentPath.isEmpty()) vfsManager.listLocalRoots()
                                    else vfsManager.listLocalDirectory(currentPath)
                            } else {
                                errorText = "Failed to rename file"
                            }
                        }
                    }
                }) { Text(I18n.get("rename")) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(I18n.get("cancel")) }
            },
            containerColor = Color(0xFF1A1A2E),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCCCCCC)
        )
    }
}

@Composable
private fun FileRow(
    file: FileNode,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    showActions: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null
) {
    val isVideo = file.isVideo()
    val iconName = when {
        file.isDirectory -> PhosphorIcons.FOLDER
        isVideo -> PhosphorIcons.VIDEO
        file.isSubtitle() -> PhosphorIcons.SUBTITLES
        else -> PhosphorIcons.FILE
    }
    val iconTint = when {
        file.isDirectory -> Color(0xFFFFA726)
        isVideo -> Color(0xFF0F84E4)
        file.isSubtitle() -> Color(0xFF66BB6A)
        else -> Color(0xFF888888)
    }
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = iconPainter(iconName),
                contentDescription = iconName,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (file.size > 0) {
                Text(
                    text = formatFileSize(file.size),
                    color = Color(0xFF888888),
                    fontSize = 11.sp
                )
            }
            if (isVideo) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.PLAY),
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (showActions) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            painter = iconPainter(PhosphorIcons.DOTS_THREE),
                            contentDescription = "More",
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text(I18n.get("rename")) },
                                onClick = { showMenu = false; onRename() }
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text(I18n.get("delete"), color = Color(0xFFFF4444)) },
                                onClick = { showMenu = false; onDelete() }
                            )
                        }
                    }
                }
            }
        }
    }
}
