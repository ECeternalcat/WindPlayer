package dev.windplayer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

object ThumbnailGenerator {
    /**
     * Generate a video thumbnail and save it as JPEG to [cacheDir].
     * Returns the absolute path of the thumbnail file, or null on failure.
     *
     * For local files: pass the content:// URI string.
     * For HTTP streams (StreamProxy): pass the http:// URL.
     */
    fun generate(context: Context, videoPath: String): String? {
        val retriever = MediaMetadataRetriever()
        try {
            when {
                videoPath.startsWith("content://") -> {
                    retriever.setDataSource(context, Uri.parse(videoPath))
                }
                videoPath.startsWith("http://") -> {
                    retriever.setDataSource(videoPath, java.util.HashMap())
                }
                else -> return null
            }
            val bitmap = retriever.getFrameAtTime(0)
                ?: retriever.getFrameAtTime(1_000_000)
                ?: return null

            // Scale down to 256px wide (16:9 → 144px tall)
            val targetW = 256
            val ratio = targetW.toFloat() / bitmap.width
            val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            val thumb = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            if (thumb !== bitmap) bitmap.recycle()

            val safeName = "thumb_${videoPath.hashCode().toString(16)}.jpg"
            val file = File(context.cacheDir, safeName)
            file.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            thumb.recycle()
            return file.absolutePath
        } catch (_: Exception) {
            return null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
