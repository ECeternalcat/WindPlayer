package dev.windplayer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    Row(modifier = modifier.fillMaxSize().background(WindColors.CanvasCream)) {
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(WindColors.LiftedCream)
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            SidebarLabel(text = I18n.get("servers"))
            Spacer(modifier = Modifier.height(10.dp))

            NavItem(
                label = I18n.get("local_files"),
                icon = PhosphorIcons.MONITOR,
                active = isLocal,
                onClick = {
                    isLocal = true
                    activeServerId = null
                    currentPath = vfsManager.homeDirectory()
                    breadcrumbs = listOf(Breadcrumb("Home", currentPath))
                    scope.launch {
                        files = vfsManager.listLocalDirectory(currentPath)
                    }
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            if (isLocal) {
                NavItem(
                    label = I18n.get("drives"),
                    icon = PhosphorIcons.LIST,
                    active = false,
                    subtle = true,
                    onClick = {
                        isLocal = true
                        activeServerId = null
                        currentPath = ""
                        breadcrumbs = listOf(Breadcrumb("Drives", ""))
                        files = vfsManager.listLocalRoots()
                    }
                )
            }

            if (bookmarks.isNotEmpty()) {
                SidebarDivider()
                SidebarLabel(
                    text = I18n.get("bookmarks"),
                    leadingIcon = PhosphorIcons.STAR
                )
                Spacer(modifier = Modifier.height(6.dp))
                bookmarks.take(8).forEach { bmPath ->
                    val bmName = bmPath.substringAfterLast('\\').substringAfterLast('/').ifBlank { bmPath }
                    BookmarkRow(
                        name = bmName,
                        onClick = {
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
                        onRemove = if (onBookmarkRemoved != null) {
                            { onBookmarkRemoved.invoke(bmPath) }
                        } else null
                    )
                }
            }

            if (recentFiles.isNotEmpty()) {
                SidebarDivider()
                SidebarLabel(
                    text = I18n.get("recent"),
                    leadingIcon = PhosphorIcons.CLOCK
                )
                Spacer(modifier = Modifier.height(6.dp))
                recentFiles.take(8).forEach { recent ->
                    RecentRow(
                        recent = recent,
                        onClick = { onPlayRecentFile?.invoke(recent) }
                    )
                }
            }

            SidebarDivider()

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(serverList, key = { it.id }) { server ->
                    val isActive = activeServerId == server.id
                    ServerRow(
                        server = server,
                        active = isActive,
                        modifier = Modifier.animateItem(),
                        onClick = {
                            scope.launch {
                                if (!vfsManager.isServerConnected(server.id)) {
                                    isLoading = true
                                    errorText = ""
                                    val result = vfsManager.connectServer(server.id)
                                    if (result.isFailure) {
                                        errorText = "${I18n.get("connection_failed_prefix")}: ${result.exceptionOrNull()?.message}"
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
                                    errorText = listResult.exceptionOrNull()?.message ?: I18n.get("unknown_error")
                                }
                            }
                        },
                        onDisconnect = if (isActive) {
                            {
                                scope.launch {
                                    vfsManager.disconnectServer(server.id)
                                    activeServerId = null
                                    files = emptyList()
                                    currentPath = ""
                                }
                            }
                        } else null
                    )
                }
            }


            PrimaryPillButton(
                text = I18n.get("add_server"),
                icon = PhosphorIcons.PLUS,
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth()
            )

            if (onOpenSettings != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedPillButton(
                    text = I18n.get("settings"),
                    icon = PhosphorIcons.GEAR,
                    onClick = { onOpenSettings.invoke() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(WindColors.CanvasCream)
                .padding(horizontal = 24.dp, vertical = 20.dp)
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
                    }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painter = iconPainter(PhosphorIcons.ARROW_LEFT),
                            contentDescription = I18n.get("back"),
                            tint = WindColors.Ink,
                            modifier = Modifier.size(20.dp)
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
                            Text(
                                text = " / ",
                                color = WindColors.DustTaupe,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                        val isLast = index == breadcrumbs.lastIndex
                        Text(
                            text = crumb.name,
                            color = if (isLast) WindColors.Ink else WindColors.LinkBlue,
                            fontSize = 14.sp,
                            fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.clickable {
                                if (!isLast) {
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
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = iconPainter(PhosphorIcons.STAR),
                            contentDescription = I18n.get("bookmark"),
                            tint = if (isBookmarked) WindColors.LightSignalOrange else WindColors.Slate,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(I18n.get("search"), color = WindColors.Slate, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(
                            painter = iconPainter(PhosphorIcons.MAGNIFYING_GLASS),
                            contentDescription = null,
                            tint = WindColors.Slate,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    painter = iconPainter(PhosphorIcons.X),
                                    contentDescription = I18n.get("clear"),
                                    tint = WindColors.Slate,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = WindRadius.Pill,
                    modifier = Modifier.weight(1f).height(48.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = WindColors.Ink,
                        fontSize = 13.sp
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = WindColors.White,
                        unfocusedContainerColor = WindColors.White,
                        cursorColor = WindColors.Ink,
                        focusedIndicatorColor = WindColors.Ink,
                        unfocusedIndicatorColor = WindColors.Hairline
                    )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box {
                    TextButton(
                        onClick = { showSortMenu = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = WindRadius.Pill,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = WindColors.White,
                            contentColor = WindColors.Ink
                        )
                    ) {
                        Text(
                            text = when (sortBy) {
                                "size" -> I18n.get("sort_size")
                                "date" -> I18n.get("sort_date")
                                "type" -> I18n.get("sort_type")
                                else -> I18n.get("sort_name")
                            },
                            color = WindColors.Ink,
                            fontSize = 13.sp
                        )
                        Icon(
                            painter = iconPainter(PhosphorIcons.CARET_DOWN),
                            contentDescription = null,
                            tint = WindColors.Slate,
                            modifier = Modifier.size(14.dp).padding(start = 4.dp)
                        )
                        Text(
                            text = if (sortAsc) " \u2191" else " \u2193",
                            color = WindColors.LightSignalOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
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

            Spacer(modifier = Modifier.height(12.dp))

            if (errorText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.WARNING),
                        contentDescription = null,
                        tint = WindColors.SignalOrange,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = errorText, color = WindColors.SignalOrange, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = WindColors.Ink)
                }
            } else if (currentPath.isBlank() && activeServerId == null && !isLocal) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Ghost watermark — cream-on-cream wordmark (DESIGN.md §4),
                        // sets the section mood without competing with the prompt.
                        Text(
                            text = "WindPlayer",
                            color = WindColors.GhostWatermark,
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-1.44).sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = I18n.get("select_prompt"),
                            color = WindColors.Slate,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                val displayFiles = remember(files, sortBy, sortAsc, searchQuery) {
                    val mediaOnly = files.filter { it.isDirectory || it.isVideo() || it.isSubtitle() }
                    val filtered = if (searchQuery.isBlank()) mediaOnly
                        else mediaOnly.filter { it.name.contains(searchQuery, ignoreCase = true) }
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
                            color = WindColors.Slate,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(displayFiles, key = { it.path }) { file ->
                        FileRow(
                            file = file,
                            modifier = Modifier.animateItem(),
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
                                        errorText = result.exceptionOrNull()?.message ?: I18n.get("unknown_error")
                                    }
                                }
                            },
                            showActions = true,
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
                        val ok = if (isLocal) {
                            vfsManager.deleteLocalFile(target.path)
                        } else {
                            val sid = activeServerId ?: return@launch
                            vfsManager.deleteServerFile(sid, target.path)
                        }
                        if (ok) {
                            files = files.filterNot { it.path == target.path }
                        } else {
                            errorText = I18n.get("delete_failed")
                        }
                    }
                }) { Text(I18n.get("delete"), color = WindColors.SignalOrange) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(I18n.get("cancel")) }
            },
            shape = WindRadius.Stadium,
            containerColor = WindColors.White,
            titleContentColor = WindColors.Ink,
            textContentColor = WindColors.Slate
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
                    shape = WindRadius.Pill,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = WindColors.Ink,
                        fontSize = 14.sp
                    ),
                    colors = TextFieldDefaults.colors(
                        cursorColor = WindColors.Ink,
                        focusedContainerColor = WindColors.CanvasCream,
                        unfocusedContainerColor = WindColors.CanvasCream,
                        focusedIndicatorColor = WindColors.Ink,
                        unfocusedIndicatorColor = WindColors.Hairline
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = renameTarget!!
                    // M13: reject Windows-invalid filename characters and path
                    // traversal attempts before hitting the filesystem.
                    val invalid = Regex("""[\\/:*?"<>|]""")
                    if (renameText.isBlank()) {
                        renameTarget = null
                    } else if (renameText.contains(invalid) || renameText.contains("..")) {
                        errorText = I18n.get("invalid_filename")
                        renameTarget = null
                    } else {
                        renameTarget = null
                        if (renameText != target.name) {
                        scope.launch {
                            val ok = if (isLocal) {
                                vfsManager.renameLocalFile(target.path, renameText)
                            } else {
                                val sid = activeServerId ?: return@launch
                                vfsManager.renameServerFile(sid, target.path, renameText)
                            }
                            if (ok) {
                                files = if (isLocal) {
                                    if (currentPath.isEmpty()) vfsManager.listLocalRoots()
                                    else vfsManager.listLocalDirectory(currentPath)
                                } else {
                                    val sid2 = activeServerId ?: return@launch
                                    vfsManager.listServerDirectory(sid2, currentPath).getOrDefault(emptyList())
                                }
                            } else {
                                errorText = I18n.get("rename_failed")
                            }
                        }
                        }
                    }
                }) { Text(I18n.get("rename")) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(I18n.get("cancel")) }
            },
            shape = WindRadius.Stadium,
            containerColor = WindColors.White,
            titleContentColor = WindColors.Ink,
            textContentColor = WindColors.Slate
        )
    }
}

@Composable
private fun SidebarLabel(text: String, leadingIcon: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(WindColors.SignalOrange)
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (leadingIcon != null) {
            Icon(
                painter = iconPainter(leadingIcon),
                contentDescription = null,
                tint = WindColors.Slate,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text.uppercase(),
            color = WindColors.Slate,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.48.sp
        )
    }
}

@Composable
private fun SidebarDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        thickness = 1.dp,
        color = WindColors.Hairline
    )
}

@Composable
private fun NavItem(
    label: String,
    icon: String,
    active: Boolean,
    subtle: Boolean = false,
    onClick: () -> Unit
) {
    // WindMotion: animate nav item selection (bg + tint).
    val bg by animateColorAsState(
        targetValue = if (active) WindColors.Ink else Color.Transparent,
        animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
        label = "navBg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (active) WindColors.CanvasCream else WindColors.Slate,
        animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
        label = "navIconTint"
    )
    val textTint by animateColorAsState(
        targetValue = if (active) WindColors.CanvasCream else if (subtle) WindColors.Slate else WindColors.Ink,
        animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
        label = "navTextTint"
    )
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = bg,
        shape = WindRadius.Pill
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = iconPainter(icon),
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = textTint,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun BookmarkRow(
    name: String,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color.Transparent,
        shape = WindRadius.Pill
    ) {
        Row(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = iconPainter(PhosphorIcons.FOLDER),
                contentDescription = null,
                tint = WindColors.LightSignalOrange,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = name,
                color = WindColors.Charcoal,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.X),
                        contentDescription = I18n.get("remove"),
                        tint = WindColors.DustTaupe,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentRow(recent: RecentFile, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color.Transparent,
        shape = WindRadius.Pill
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = iconPainter(PhosphorIcons.VIDEO),
                    contentDescription = null,
                    tint = WindColors.Ink,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = recent.name,
                    color = WindColors.Charcoal,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (recent.position > 1.0 && recent.duration > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatDuration(recent.position)} / ${formatDuration(recent.duration)}",
                    color = WindColors.Slate,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 22.dp)
                )
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerConfig,
    active: Boolean,
    onClick: () -> Unit,
    onDisconnect: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (active) WindColors.Ink else Color.Transparent,
        shape = WindRadius.Pill
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (active) WindColors.CanvasCream.copy(alpha = 0.15f) else WindColors.CanvasCream,
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = when (server.protocol) {
                            VfsProtocol.SFTP -> "S"
                            VfsProtocol.WEBDAV -> "W"
                            VfsProtocol.FTP -> "F"
                            else -> "?"
                        },
                        color = if (active) WindColors.CanvasCream else WindColors.Slate,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    color = if (active) WindColors.CanvasCream else WindColors.Ink,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${server.protocol.name} - ${server.host}",
                    color = if (active) WindColors.CanvasCream.copy(alpha = 0.6f) else WindColors.Slate,
                    fontSize = 10.sp
                )
            }
            if (onDisconnect != null) {
                IconButton(
                    onClick = onDisconnect,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.X),
                        contentDescription = I18n.get("disconnect"),
                        tint = WindColors.CanvasCream.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryPillButton(
    text: String,
    icon: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = WindRadius.Button,
        colors = ButtonDefaults.buttonColors(
            containerColor = WindColors.Ink,
            contentColor = WindColors.CanvasCream
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(
                painter = iconPainter(icon),
                contentDescription = null,
                tint = WindColors.CanvasCream,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OutlinedPillButton(
    text: String,
    icon: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = WindRadius.Button,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = WindColors.White,
            contentColor = WindColors.Ink
        ),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, WindColors.Ink),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(
                painter = iconPainter(icon),
                contentDescription = null,
                tint = WindColors.Ink,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FileRow(
    file: FileNode,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    showActions: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isVideo = file.isVideo()
    val iconName = when {
        file.isDirectory -> PhosphorIcons.FOLDER
        isVideo -> PhosphorIcons.VIDEO
        file.isSubtitle() -> PhosphorIcons.SUBTITLES
        else -> PhosphorIcons.FILE
    }
    val iconTint = when {
        file.isDirectory -> WindColors.LightSignalOrange
        isVideo -> WindColors.Ink
        file.isSubtitle() -> WindColors.ClayBrown
        else -> WindColors.DustTaupe
    }
    var showMenu by remember { mutableStateOf(false) }
    Surface(

        modifier = modifier.fillMaxWidth().clickable { onClick() },
        color = Color.Transparent,
        shape = WindRadius.Chip
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    color = WindColors.Ink,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (file.size > 0) {
                Text(
                    text = formatFileSize(file.size),
                    color = WindColors.Slate,
                    fontSize = 11.sp
                )
            }
            if (isVideo) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(WindColors.Ink)
                ) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.PLAY),
                        contentDescription = I18n.get("play"),
                        tint = WindColors.CanvasCream,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (showActions) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            painter = iconPainter(PhosphorIcons.DOTS_THREE),
                            contentDescription = I18n.get("more"),
                            tint = WindColors.Slate,
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
                                text = { Text(I18n.get("delete"), color = WindColors.SignalOrange) },
                                onClick = { showMenu = false; onDelete() }
                            )
                        }
                    }
                }
            }
        }
    }
}
