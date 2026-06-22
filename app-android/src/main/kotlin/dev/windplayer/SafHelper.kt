package dev.windplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.windplayer.vfs.FileNode
import dev.windplayer.vfs.FileNodeComparator
import dev.windplayer.vfs.VfsProtocol

object SafHelper {
    private const val PREFS = "windplayer"
    private const val KEY_TREE_URI = "tree_uri"

    fun loadTreeUri(context: Context): Uri? {
        val str = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        return try { Uri.parse(str) } catch (_: Exception) { null }
    }

    fun saveTreeUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun takePermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
    }

    fun listFiles(context: Context, dir: DocumentFile): List<FileNode> {
        return dir.listFiles()
            .filter { it.name != null }
            .map { doc ->
                FileNode(
                    name = doc.name!!,
                    path = doc.uri.toString(),
                    isDirectory = doc.isDirectory,
                    size = if (doc.isDirectory) 0L else doc.length(),
                    lastModified = doc.lastModified(),
                    protocol = VfsProtocol.LOCAL
                )
            }
            .sortedWith(FileNodeComparator)
    }

    fun rootFromUri(context: Context, uri: Uri): DocumentFile? {
        return DocumentFile.fromTreeUri(context, uri)
    }
}
