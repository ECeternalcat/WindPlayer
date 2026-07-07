package dev.windplayer.translate

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts audio from a video file using Android's built-in MediaExtractor +
 * MediaCodec (no FFmpeg, no mpv dependency).
 *
 * Critical fix: detects actual PCM encoding (16-bit vs 32-bit float) from
 * the codec output format. Previously hardcoded 16-bit, which produced
 * garbage when the decoder output float (common on Samsung Exynos).
 *
 * Also provides [listAudioTracks] for the track-selection UI.
 */
class AudioExtractor(private val context: Context) {

    /**
     * List all audio tracks in a media file for user selection.
     * Returns (trackIndex, language, codec, channels, sampleRate) for each.
     */
    suspend fun listAudioTracks(sourceUrl: String): List<AudioTrackInfo> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            setDataSource(extractor, sourceUrl)
        } catch (e: Exception) {
            Log.e(TAG, "listAudioTracks: cannot open source", e)
            return@withContext emptyList()
        }

        val tracks = mutableListOf<AudioTrackInfo>()
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            val lang = fmt.getString(MediaFormat.KEY_LANGUAGE) ?: "und"
            val sr = try { fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { 0 }
            val ch = try { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 0 }
            tracks.add(AudioTrackInfo(i, lang, mime.replace("audio/", ""), ch, sr))
        }
        extractor.release()
        tracks
    }

    data class AudioTrackInfo(
        val index: Int,
        val language: String,
        val codec: String,
        val channels: Int,
        val sampleRate: Int
    ) {
        val displayName: String get() {
            val langName = LANGUAGE_NAMES[language] ?: language
            return "$langName · ${channels}ch · ${codec}"
        }
    }

    suspend fun extractPcmAudio(
        sourceUrl: String,
        durationSec: Double,
        trackIndex: Int = -1,
        onProgress: ((Float) -> Unit)? = null
    ): Result<FloatArray> = withContext(Dispatchers.IO) {
        if (sourceUrl.startsWith("sftp://") || sourceUrl.startsWith("webdav://") ||
            sourceUrl.startsWith("ftp://") || sourceUrl.startsWith("ftps://")) {
            return@withContext Result.failure(
                RuntimeException("Remote protocol not supported for audio extraction")
            )
        }

        try {
            Log.i(TAG, "Extracting audio from: $sourceUrl (track=$trackIndex)")

            val extractor = MediaExtractor()
            try {
                setDataSource(extractor, sourceUrl)
            } catch (e: Exception) {
                Log.e(TAG, "setDataSource failed", e)
                return@withContext Result.failure(
                    RuntimeException("Cannot open audio source: ${e.message}")
                )
            }

            // Find the audio track (user-specified or first audio).
            var audioTrackIdx = trackIndex
            if (audioTrackIdx < 0) {
                audioTrackIdx = -1
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) { audioTrackIdx = i; break }
                }
            }
            if (audioTrackIdx < 0) {
                extractor.release()
                return@withContext Result.failure(RuntimeException("No audio track found"))
            }

            val inputFormat = extractor.getTrackFormat(audioTrackIdx)
            extractor.selectTrack(audioTrackIdx)
            val srcSampleRate = try { inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { 48000 }
            val srcChannels = try { inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 2 }
            val srcDurationUs = try { inputFormat.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            Log.i(TAG, "Audio track $audioTrackIdx: ${srcSampleRate}Hz ${srcChannels}ch $mime")

            val maxBytes = (MAX_DURATION_SEC * srcSampleRate * srcChannels * 4).toLong() // worst case 32-bit

            var tempPcm: File? = null
            var codec: MediaCodec? = null
            var totalOutputBytes = 0L
            var actualSampleRate = srcSampleRate
            var actualChannels = srcChannels
            var bytesPerSample = 2 // default 16-bit; updated after format change
            var isFloatPcm = false

            try {
                tempPcm = File(context.cacheDir, "whisper_pcm_${System.currentTimeMillis()}.raw")
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                FileOutputStream(tempPcm).use { out ->
                    while (!sawOutputEOS) {
                        if (!sawInputEOS) {
                            val inIdx = codec.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val inBuf = codec.getInputBuffer(inIdx)!!
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEOS = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val outFmt = codec.outputFormat
                                actualSampleRate = try { outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { srcSampleRate }
                                actualChannels = try { outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { srcChannels }
                                // CRITICAL: detect PCM encoding to avoid reading float as int16.
                                val pcmEncoding = try {
                                    outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } catch (_: Exception) {
                                    AudioFormat.ENCODING_PCM_16BIT
                                }
                                isFloatPcm = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
                                bytesPerSample = if (isFloatPcm) 4 else 2
                                Log.i(TAG, "Decoded format: ${actualSampleRate}Hz ${actualChannels}ch " +
                                    "${if (isFloatPcm) "float32" else "int16"} (${bytesPerSample}B/sample)")
                            }
                            outIdx >= 0 -> {
                                val effectiveMax = (MAX_DURATION_SEC * actualSampleRate * actualChannels * bytesPerSample).toLong()
                                if (totalOutputBytes < effectiveMax) {
                                    val outBuf = codec.getOutputBuffer(outIdx)
                                    if (outBuf != null && info.size > 0) {
                                        outBuf.position(info.offset)
                                        outBuf.limit(info.offset + info.size)
                                        val chunk = ByteArray(outBuf.remaining())
                                        outBuf.get(chunk)
                                        out.write(chunk)
                                        totalOutputBytes += chunk.size
                                    }
                                }
                                codec.releaseOutputBuffer(outIdx, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                                if (srcDurationUs > 0 && onProgress != null) {
                                    val p = (info.presentationTimeUs.toFloat() / srcDurationUs.toFloat()).coerceIn(0f, 1f)
                                    onProgress(p)
                                }
                            }
                        }
                    }
                }
                codec.stop()
            } finally {
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }

            Log.i(TAG, "Decoded $totalOutputBytes bytes (${totalOutputBytes / (actualChannels * bytesPerSample)} frames)")

            // Convert to Whisper format (16kHz mono float32).
            // Kotlin smart-casts tempPcm to non-null here: if File creation had
            // failed above, the assignment would have thrown and we'd never
            // reach this line (the outer try/catch returns early).
            val result = convertToWhisperFormat(tempPcm, actualSampleRate, actualChannels, bytesPerSample)
            tempPcm.delete()

            // CRITICAL: validate audio is not silent/garbage.
            var maxAmp = 0f
            var sumSq = 0.0
            for (s in result) {
                val abs = Math.abs(s)
                if (abs > maxAmp) maxAmp = abs
                sumSq += (s.toDouble() * s.toDouble())
            }
            val rms = Math.sqrt(sumSq / result.size)
            Log.i(TAG, "Audio validation: ${result.size} samples, maxAmp=$maxAmp, rms=$rms")

            if (maxAmp < 0.001f) {
                Log.e(TAG, "Audio appears silent (maxAmp=$maxAmp) — extraction likely failed")
                return@withContext Result.failure(
                    RuntimeException("Extracted audio is silent (max amplitude $maxAmp). The codec may output an unsupported format.")
                )
            }

            onProgress?.invoke(1f)
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction failed", e)
            Result.failure(e)
        }
    }

    private fun setDataSource(extractor: MediaExtractor, sourceUrl: String) {
        if (sourceUrl.startsWith("content://")) {
            val uri = Uri.parse(sourceUrl)
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                // Fallback: open via ContentResolver FileDescriptor.
                Log.w(TAG, "setDataSource(context, uri) failed, trying FileDescriptor fallback")
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                } ?: throw e
            }
        } else {
            extractor.setDataSource(sourceUrl)
        }
    }

    /**
     * Convert raw PCM file to 16kHz mono FloatArray [-1.0, 1.0].
     * Handles both 16-bit signed and 32-bit float input.
     */
    private fun convertToWhisperFormat(
        pcmFile: File,
        srcRate: Int,
        srcChannels: Int,
        bytesPerSample: Int
    ): FloatArray {
        val bytesPerFrame = bytesPerSample * srcChannels
        val srcFrameCount = (pcmFile.length() / bytesPerFrame).toInt()
        val targetRate = 16000
        val dstFrameCount = (srcFrameCount.toLong() * targetRate / srcRate).toInt()
        val result = FloatArray(dstFrameCount)

        val ratio = srcRate.toFloat() / targetRate.toFloat()
        val needResample = srcRate != targetRate

        BufferedInputStream(FileInputStream(pcmFile)).use { input ->
            val chunkBuf = ByteArray(65536)
            var chunkPos = 0
            var chunkLen = 0

            for (srcFrame in 0 until srcFrameCount) {
                // Ensure enough bytes for this frame.
                while (chunkPos + bytesPerFrame > chunkLen && chunkLen >= 0) {
                    val remaining = chunkLen - chunkPos
                    if (remaining > 0) System.arraycopy(chunkBuf, chunkPos, chunkBuf, 0, remaining)
                    chunkPos = 0
                    val read = input.read(chunkBuf, remaining, chunkBuf.size - remaining)
                    chunkLen = if (read <= 0) -1 else remaining + read
                    if (chunkLen < 0) break
                }
                if (chunkLen < 0 || chunkPos + bytesPerFrame > chunkLen) break // EOF

                // Read one sample per channel, downmix to mono.
                var monoFloat = if (bytesPerSample == 4) {
                    // 32-bit float: read 4 bytes per channel.
                    if (srcChannels == 1) {
                        readFloatLE(chunkBuf, chunkPos)
                    } else {
                        var sum = 0f
                        for (ch in 0 until srcChannels) {
                            sum += readFloatLE(chunkBuf, chunkPos + ch * 4)
                        }
                        sum / srcChannels
                    }
                } else {
                    // 16-bit signed: read 2 bytes per channel.
                    if (srcChannels == 1) {
                        readInt16LE(chunkBuf, chunkPos).toFloat() / 32768.0f
                    } else {
                        var sum = 0
                        for (ch in 0 until srcChannels) {
                            sum += readInt16LE(chunkBuf, chunkPos + ch * 2)
                        }
                        (sum / srcChannels).toFloat() / 32768.0f
                    }
                }
                chunkPos += bytesPerFrame

                // Write to result (with or without resampling).
                if (!needResample) {
                    if (srcFrame < result.size) result[srcFrame] = monoFloat
                } else {
                    val dstStart = (srcFrame / ratio).toInt()
                    val dstEnd = ((srcFrame + 1) / ratio).toInt()
                    for (d in dstStart until minOf(dstEnd + 1, result.size)) {
                        result[d] = monoFloat
                    }
                }
            }
        }
        return result
    }

    private fun readInt16LE(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort().toInt()
    }

    private fun readFloatLE(buf: ByteArray, offset: Int): Float {
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
    }

    companion object {
        private const val TAG = "AudioExtractor"
        private const val MAX_DURATION_SEC = 1800

        /** ISO 639-2 → display name for common languages. */
        val LANGUAGE_NAMES = mapOf(
            "und" to "Unknown", "eng" to "English", "zho" to "Chinese",
            "chi" to "Chinese", "jpn" to "Japanese", "kor" to "Korean",
            "spa" to "Spanish", "fra" to "French", "fre" to "French",
            "deu" to "German", "ger" to "German", "ita" to "Italian",
            "por" to "Portuguese", "rus" to "Russian", "ara" to "Arabic",
            "hin" to "Hindi", "tha" to "Thai", "vie" to "Vietnamese",
            "tur" to "Turkish", "pol" to "Polish", "nld" to "Dutch"
        )
    }
}
