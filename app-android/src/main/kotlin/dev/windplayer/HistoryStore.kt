package dev.windplayer

import android.content.Context
import dev.windplayer.vfs.VfsProtocol

data class HistoryEntry(
    val name: String,
    val path: String,
    val protocol: VfsProtocol,
    val serverId: String?,
    val timestamp: Long,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val parentDocId: String? = null,
    val treeUriString: String? = null,
    val thumbnailPath: String? = null
)

object HistoryStore {
    private const val PREFS = "windplayer_history"
    private const val MAX = 10

    fun load(context: Context): List<HistoryEntry> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = p.getInt("count", 0)
        return (0 until count).mapNotNull { i ->
            val name = p.getString("h${i}_name", null) ?: return@mapNotNull null
            HistoryEntry(
                name = name,
                path = p.getString("h${i}_path", "") ?: "",
                protocol = try { VfsProtocol.valueOf(p.getString("h${i}_proto", "LOCAL") ?: "LOCAL") } catch (_: Exception) { VfsProtocol.LOCAL },
                serverId = p.getString("h${i}_sid", null),
                timestamp = p.getLong("h${i}_ts", 0L),
                position = p.getString("h${i}_pos", "0")?.toDoubleOrNull() ?: 0.0,
                duration = p.getString("h${i}_dur", "0")?.toDoubleOrNull() ?: 0.0,
                parentDocId = p.getString("h${i}_pdid", null),
                treeUriString = p.getString("h${i}_turi", null),
                thumbnailPath = p.getString("h${i}_thumb", null)
            )
        }
    }

    fun add(context: Context, entry: HistoryEntry): List<HistoryEntry> {
        val current = load(context).filterNot { it.path == entry.path }
        val updated = (listOf(entry) + current).take(MAX)
        save(context, updated)
        return updated
    }

    fun updatePosition(context: Context, path: String, position: Double, duration: Double) {
        val current = load(context)
        val updated = current.map {
            if (it.path == path) it.copy(
                position = if (position > 0) position else it.position,
                duration = if (duration > 0) duration else it.duration
            ) else it
        }
        save(context, updated)
    }

    fun updateThumbnail(context: Context, path: String, thumbPath: String?) {
        val current = load(context)
        val updated = current.map {
            if (it.path == path) it.copy(thumbnailPath = thumbPath) else it
        }
        save(context, updated)
    }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldCount = p.getInt("count", 0)
        val e = p.edit()
        e.putInt("count", entries.size)
        entries.forEachIndexed { i, h ->
            e.putString("h${i}_name", h.name)
            e.putString("h${i}_path", h.path)
            e.putString("h${i}_proto", h.protocol.name)
            e.putString("h${i}_sid", h.serverId)
            e.putLong("h${i}_ts", h.timestamp)
            e.putString("h${i}_pos", h.position.toString())
            e.putString("h${i}_dur", h.duration.toString())
            e.putString("h${i}_pdid", h.parentDocId)
            e.putString("h${i}_turi", h.treeUriString)
            e.putString("h${i}_thumb", h.thumbnailPath)
        }
        for (i in entries.size until oldCount) {
            listOf("name", "path", "proto", "sid", "ts", "pos", "dur", "pdid", "turi", "thumb").forEach { f -> e.remove("h${i}_$f") }
        }
        e.apply()
    }
}
