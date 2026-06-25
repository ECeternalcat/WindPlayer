Local File History Playback Architecture (Android SAF)

This document outlines the robust architectural plan for enabling multi-file playback (auto-play next) when launching local video files from the application's history. It addresses the critical limitations of Android's Storage Access Framework (SAF), specifically regarding permission persistence, UI thread performance, and file system mutations.

1. Data Model Evolution

The history entry must be expanded to include the contextual pointers required to reconstruct the playback playlist.

Component: HistoryStore.kt

data class HistoryEntry(
    val name: String,
    val path: String,       // The specific document URI string
    val protocol: VfsProtocol,
    val serverId: String?,
    val timestamp: Long,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    // NEW FIELDS
    val parentDocId: String? = null, // The Document ID of the folder containing the file
    val treeUriString: String? = null // The root Tree URI granted by the user
)


Rationale: We store parentDocId instead of a vague parentPath to construct correct SAF queries later. treeUriString is required as the base authorization token.

2. Permission Persistence (Critical)

A history entry is useless if the app loses read permissions after a restart. Permissions must be solidified at the moment of selection.

Component: FileBrowserScreen.kt (or the component handling the ActivityResultLauncher)

When the user selects a folder via ACTION_OPEN_DOCUMENT_TREE, the resulting Uri must be persisted immediately:

fun onTreeSelected(context: Context, treeUri: Uri) {
    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
    // Proceed to open the folder...
}


3. High-Performance SAF Querying

Do not use DocumentFile.listFiles(). We must use raw ContentResolver queries targeting only video files to avoid massive memory allocations and I/O bottlenecks.

Component: SafPlaylistBuilder.kt (New Helper)

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SafPlaylistBuilder {

    /**
     * Builds a list of sibling video files asynchronously.
     */
    suspend fun buildSiblingPlaylist(
        context: Context,
        treeUriStr: String,
        parentDocId: String
    ): List<PlaylistItem> = withContext(Dispatchers.IO) {
        val playlist = mutableListOf<PlaylistItem>()
        val treeUri = Uri.parse(treeUriStr)
        
        try {
            // 1. Verify we still have permission for this tree
            val persistedUris = context.contentResolver.persistedUriPermissions
            if (persistedUris.none { it.uri == treeUri }) {
                return@withContext emptyList() // Permission lost/revoked
            }

            // 2. Construct the query URI for the parent folder's children
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, 
                parentDocId
            )

            // 3. Define projections to load only necessary metadata
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            // 4. Query the Content Provider
            context.contentResolver.query(
                childrenUri,
                projection,
                null,
                null,
                "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} ASC" // Sort alphabetically
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val mimeType = cursor.getString(mimeCol)
                    // 5. Filter for video types only
                    if (mimeType?.startsWith("video/") == true) {
                        val childDocId = cursor.getString(idCol)
                        val name = cursor.getString(nameCol)
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        
                        playlist.add(PlaylistItem(name = name, uri = docUri))
                    }
                }
            }
        } catch (e: Exception) {
            // Log error: e.g., folder deleted, SD card removed
        }
        
        return@withContext playlist
    }
}

data class PlaylistItem(val name: String, val uri: Uri)


4. UI Thread Decoupling & Fault Tolerance

When launching from history, the process must not block the UI. Furthermore, we must handle the edge case where the target file has been deleted externally.

Component: MobileApp.kt (or the ViewModel handling History playback)

// Example flow inside a ViewModel or CoroutineScope
fun onPlayHistoryEntry(context: Context, entry: HistoryEntry) {
    // 1. Show UI Loading State
    _uiState.value = UiState.Loading
    
    viewModelScope.launch {
        // Fallback: A playlist containing only the requested file
        val targetUri = Uri.parse(entry.path)
        var finalPlaylist = listOf(PlaylistItem(entry.name, targetUri))
        var startIndex = 0

        // 2. Attempt to reconstruct the full playlist in the background
        if (entry.treeUriString != null && entry.parentDocId != null) {
            val siblings = SafPlaylistBuilder.buildSiblingPlaylist(
                context, 
                entry.treeUriString, 
                entry.parentDocId
            )
            
            if (siblings.isNotEmpty()) {
                // 3. Fault Tolerance: Ensure the target file still exists in the sibling list
                val foundIndex = siblings.indexOfFirst { it.uri == targetUri }
                if (foundIndex != -1) {
                    finalPlaylist = siblings
                    startIndex = foundIndex
                } else {
                    // File was deleted externally or renamed.
                    // We must alert the user.
                    _uiState.value = UiState.Error("The original file cannot be found in its directory.")
                    return@launch
                }
            }
        }

        // 4. Launch Player with the verified playlist and index
        _uiState.value = UiState.ReadyToPlay
        startPlayer(finalPlaylist, startIndex, entry.position)
    }
}


5. Backward Compatibility Strategy

History entries created before this feature was implemented will have null for parentDocId and treeUriString.

The logic in Section 4 elegantly handles this: the if condition fails, and it defaults to playing the single file using the fallback finalPlaylist initialized at the top, perfectly preserving legacy behavior without crashes.