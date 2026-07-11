package dev.windplayer.translate

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Lists, deletes, and reports the size of generated SRT subtitle files.
 *
 * Subtitles are stored in `cacheDir/subtitles/wp_<sha8>.srt` by
 * [TranslationManager.writeSrt]. This object provides the management API
 * consumed by the AI Translation settings page.
 */
object SubtitleManager {

    private const val TAG = "SubtitleManager"
    private const val SUBDIR = "subtitles"

    data class Entry(
        val file: File,
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long
    )

    private fun srtDir(context: Context): File =
        File(context.cacheDir, SUBDIR).also { it.mkdirs() }

    /**
     * List all `.srt` files in the subtitle cache, newest first.
     */
    fun list(context: Context): List<Entry> {
        return try {
            srtDir(context).listFiles { f -> f.isFile && f.name.endsWith(".srt") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { Entry(it, it.name, it.length(), it.lastModified()) }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "list failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Total bytes consumed by generated subtitles.
     */
    fun totalSize(context: Context): Long =
        list(context).sumOf { it.sizeBytes }

    /**
     * Delete a single subtitle file by name.
     */
    fun delete(context: Context, fileName: String): Boolean {
        return try {
            File(srtDir(context), fileName).delete()
        } catch (e: Exception) {
            Log.w(TAG, "delete failed for $fileName: ${e.message}")
            false
        }
    }

    /**
     * Delete all generated subtitles. Returns the number deleted.
     */
    fun deleteAll(context: Context): Int {
        val entries = list(context)
        var count = 0
        for (e in entries) {
            if (e.file.delete()) count++
        }
        return count
    }
}
