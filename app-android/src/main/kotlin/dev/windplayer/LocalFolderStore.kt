package dev.windplayer

import android.content.Context
import android.net.Uri

data class LocalFolder(
    val id: String,
    val name: String,
    val treeUriString: String
)

object LocalFolderStore {
    private const val PREFS = "windplayer_local_folders"

    fun load(context: Context): List<LocalFolder> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = p.getInt("count", 0)
        return (0 until count).mapNotNull { i ->
            val id = p.getString("f${i}_id", null) ?: return@mapNotNull null
            LocalFolder(
                id = id,
                name = p.getString("f${i}_name", "Folder") ?: "Folder",
                treeUriString = p.getString("f${i}_uri", "") ?: ""
            )
        }
    }

    fun add(context: Context, name: String, treeUri: Uri): List<LocalFolder> {
        val current = load(context)
        val entry = LocalFolder(
            id = "local_${System.currentTimeMillis()}",
            name = name,
            treeUriString = treeUri.toString()
        )
        val updated = current + entry
        save(context, updated)
        return updated
    }

    fun remove(context: Context, id: String): List<LocalFolder> {
        val updated = load(context).filterNot { it.id == id }
        save(context, updated)
        return updated
    }

    private fun save(context: Context, folders: List<LocalFolder>) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // M15: capture oldCount BEFORE staging the new count. Previously this
        // read p.getInt("count",0) AFTER e.putInt("count",…), relying on the
        // fact that apply() doesn't update the in-memory cache mid-edit —
        // fragile and inconsistent with ServerStore's safer pattern.
        val oldCount = p.getInt("count", 0)
        val e = p.edit()
        e.putInt("count", folders.size)
        folders.forEachIndexed { i, f ->
            e.putString("f${i}_id", f.id)
            e.putString("f${i}_name", f.name)
            e.putString("f${i}_uri", f.treeUriString)
        }
        for (i in folders.size until oldCount) {
            listOf("id", "name", "uri").forEach { field -> e.remove("f${i}_$field") }
        }
        e.apply()
    }
}
