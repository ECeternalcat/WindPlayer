package dev.windplayer.translate

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads Whisper GGML model files directly from HuggingFace with Range-based
 * resume support (AITranslate.md §3.1).
 *
 * - Never stores models on a private server — always HF direct.
 * - If a partial download exists, resumes from the byte offset.
 * - If the full file exists and [force] is false, skips download.
 *
 * Models are stored in the app's internal files dir:
 * `filesDir/whisper_models/ggml-tiny.bin`
 */
class ModelFetcher(private val context: Context) {

    /**
     * Directory where model files are persisted.
     */
    val modelDir: File = File(context.filesDir, "whisper_models").also { it.mkdirs() }

    /**
     * Get the local path for a model file, downloading if necessary.
     *
     * @param modelFileName e.g. `"ggml-tiny.bin"` (must be in [WhisperModelWhiteList])
     * @param onProgress optional callback receiving (bytesDownloaded, totalBytes)
     * @return local [File] path, or null on failure
     */
    /**
     * Get the local path for a model file, downloading if necessary.
     *
     * @param modelFileName e.g. `"ggml-tiny.bin"` (must be in [WhisperModelWhiteList])
     * @param onProgress optional callback receiving (bytesDownloaded, totalBytes)
     * @return local [File] path, or null on failure (error logged with details)
     */
    suspend fun ensureModel(
        modelFileName: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val target = File(modelDir, modelFileName)

        // Fast path: file already exists and is non-trivially sized.
        if (target.exists() && target.length() > 1_000_000) {
            Log.i(TAG, "Model $modelFileName already present (${target.length()} bytes)")
            return@withContext target
        }

        // Download with resume.
        val url = WhisperModelWhiteList.hfUrl(modelFileName)
        try {
            downloadWithResume(url, target, onProgress)
            // BUG-13: verify downloaded file is non-trivially sized. A corrupt
            // resume or wrong-content response could leave a tiny/garbage file.
            if (!target.exists() || target.length() < 1_000_000) {
                Log.e(TAG, "Downloaded model too small (${target.length()} bytes) — likely corrupt")
                target.delete()
                return@withContext null
            }
            target
        } catch (e: javax.net.ssl.SSLException) {
            // SSL/TLS handshake failures are common in regions where HF is
            // blocked or behind a proxy. Give the user an actionable message.
            Log.e(TAG, "SSL error downloading $modelFileName — HF may be blocked; try VPN", e)
            // Delete partial file so next attempt starts fresh.
            target.delete()
            null
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Timeout downloading $modelFileName — network too slow or HF unreachable", e)
            target.delete()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed for $modelFileName: ${e.message}", e)
            // Delete partial file so next attempt starts fresh (not a corrupt resume).
            target.delete()
            null
        }
    }

    /**
     * Check whether a model file is already fully downloaded locally.
     */
    fun isModelPresent(modelFileName: String): Boolean {
        val f = File(modelDir, modelFileName)
        return f.exists() && f.length() > 1_000_000
    }

    /**
     * Delete a model file to reclaim storage.
     */
    fun deleteModel(modelFileName: String): Boolean {
        return File(modelDir, modelFileName).delete()
    }

    // ------------------------------------------------------------------
    // HTTP download with Range resume (§3.1 断点续传)
    // ------------------------------------------------------------------

    private fun downloadWithResume(
        urlStr: String,
        target: File,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val url = URL(urlStr)
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        var output: FileOutputStream? = null

        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true

                // Resume: if partial file exists, request from that offset.
                val existingBytes = if (target.exists()) target.length() else 0L
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                    Log.i(TAG, "Resuming from $existingBytes bytes")
                }
            }

            val responseCode = connection.responseCode

            // 200 = full download (server ignored Range or no partial existed).
            // 206 = partial content (resume successful).
            if (responseCode != 200 && responseCode != 206) {
                throw RuntimeException("HTTP $responseCode downloading $urlStr")
            }

            val totalSize = connection.contentLengthLong.let { total ->
                if (responseCode == 206) {
                    // For 206, contentLength is the remaining bytes; add what
                    // we already have to compute the true total.
                    val existing = if (target.exists()) target.length() else 0L
                    total + existing
                } else {
                    total
                }
            }

            val append = responseCode == 206 && target.exists()
            input = connection.inputStream
            output = FileOutputStream(target, append)

            val buffer = ByteArray(64 * 1024)
            var downloaded = if (append) target.length() else 0L

            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                downloaded += read
                onProgress?.invoke(downloaded, if (totalSize > 0) totalSize else -1L)
            }

            output.flush()
            Log.i(TAG, "Download complete: ${target.name} (${target.length()} bytes)")
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    companion object {
        private const val TAG = "ModelFetcher"
    }
}
