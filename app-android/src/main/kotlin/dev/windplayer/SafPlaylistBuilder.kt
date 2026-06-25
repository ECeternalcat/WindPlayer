package dev.windplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.FileNodeComparator
import dev.windplayer.vfs.VfsProtocol
import dev.windplayer.vfs.isVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SafPlaylistBuilder {

    /**
     * Build a list of sibling video [FileNode]s from a SAF tree, using raw
     * ContentResolver queries (not DocumentFile.listFiles) for performance.
     *
     * Returns an empty list if permission was lost, the folder was deleted,
     * or any I/O error occurs. The caller should fall back to single-file
     * playback in that case.
     */
    suspend fun buildSiblingPlaylist(
        context: Context,
        treeUriStr: String,
        parentDocId: String
    ): List<FileNode> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FileNode>()
        try {
            val treeUri = Uri.parse(treeUriStr)

            // Verify we still have persistable read permission for this tree.
            val hasPermission = context.contentResolver.persistedUriPermissions
                .any { it.uri == treeUri && it.isReadPermission }
            if (!hasPermission) return@withContext emptyList()

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )

            context.contentResolver.query(
                childrenUri, projection, null, null,
                "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val mimeType = cursor.getString(mimeCol) ?: continue
                    // Accept any non-directory file whose extension is a known video format.
                    // Using isVideo() on the name is more inclusive than mimeType.startsWith("video/")
                    // because some providers report "application/octet-stream" for mkv/ts.
                    val name = cursor.getString(nameCol) ?: continue
                    val fakeNode = FileNode(name = name, path = "", isDirectory = false, protocol = VfsProtocol.LOCAL)
                    if (!fakeNode.isVideo()) continue

                    val childDocId = cursor.getString(idCol)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    result.add(FileNode(
                        name = name,
                        path = docUri.toString(),
                        isDirectory = false,
                        size = size,
                        protocol = VfsProtocol.LOCAL
                    ))
                }
            }
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        result.sortedWith(FileNodeComparator)
    }

    /**
     * Extract the parent document ID from a SAF document URI.
     *
     * SAF document URIs look like:
     *   content://com.android.externalstorage.documents/tree/primary%3AVideos/document/primary%3AVideos%2Fmovie.mkv
     *
     * The document ID is the last path segment after "/document/". The parent
     * is everything before the last "/" in the decoded document ID.
     */
    fun extractParentDocId(fileUriStr: String): String? {
        return try {
            val uri = Uri.parse(fileUriStr)
            val docId = DocumentsContract.getDocumentId(uri)
            val parent = docId.substringBeforeLast('/', "")
            parent.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
