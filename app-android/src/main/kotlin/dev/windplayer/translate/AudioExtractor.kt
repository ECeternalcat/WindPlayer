package dev.windplayer.translate

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class ExtractedAudio(
    private val file: File,
    val totalSamples: Long,
    val durationSec: Double
) : Closeable {
    private var channel: FileChannel? = RandomAccessFile(file, "r").channel

    @Synchronized
    fun readChunk(startSample: Long, maxSamples: Int): FloatArray {
        check(startSample >= 0 && maxSamples >= 0) { "Invalid audio chunk" }
        if (startSample >= totalSamples || maxSamples == 0) return FloatArray(0)
        val count = minOf(maxSamples.toLong(), totalSamples - startSample).toInt()
        val bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        var position = startSample * 4
        while (bytes.hasRemaining()) {
            val read = channel?.read(bytes, position) ?: throw IllegalStateException("Audio is closed")
            if (read < 0) break
            position += read
        }
        bytes.flip()
        return FloatArray(bytes.remaining() / 4) { bytes.float }
    }

    override fun close() {
        channel?.close()
        channel = null
        file.delete()
    }
}

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
    ): Result<ExtractedAudio> = withContext(Dispatchers.IO) {
        if (sourceUrl.startsWith("sftp://") || sourceUrl.startsWith("webdav://") ||
            sourceUrl.startsWith("ftp://") || sourceUrl.startsWith("ftps://")) {
            return@withContext Result.failure(
                RuntimeException("Remote protocol not supported for audio extraction")
            )
        }

        var tempPcm: File? = null
        var outputFile: File? = null
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

            var codec: MediaCodec? = null
            var totalOutputBytes = 0L
            var actualSampleRate = srcSampleRate
            var actualChannels = srcChannels
            var bytesPerSample = 2 // default 16-bit; updated after format change
            var isFloatPcm = false

            try {
                check(context.cacheDir.usableSpace >= MIN_FREE_SPACE_BYTES) { "Not enough disk space for audio extraction" }
                tempPcm = File.createTempFile("whisper_pcm_", ".raw", context.cacheDir)
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                FileOutputStream(tempPcm!!).use { out ->
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
                                check(totalOutputBytes + info.size <= MAX_RAW_PCM_BYTES) { "Decoded audio exceeds disk safety limit" }
                                if (info.size > 0) {
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
            outputFile = File.createTempFile("whisper_", ".f32", context.cacheDir)
            val totalSamples = convertToWhisperFormat(tempPcm!!, outputFile, actualSampleRate, actualChannels, bytesPerSample)
            tempPcm!!.delete()
            tempPcm = null
            onProgress?.invoke(1f)
            Result.success(ExtractedAudio(outputFile, totalSamples, totalSamples / 16000.0))
        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction failed", e)
            tempPcm?.delete()
            outputFile?.delete()
            if (e is CancellationException) throw e
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
     * Convert raw PCM file to 16kHz mono float32 little-endian, streaming.
     * Handles both 16-bit signed and 32-bit float input.
     */
    private fun convertToWhisperFormat(
        pcmFile: File,
        outputFile: File,
        srcRate: Int,
        srcChannels: Int,
        bytesPerSample: Int
    ): Long {
        val bytesPerFrame = bytesPerSample * srcChannels
        val srcFrameCount = pcmFile.length() / bytesPerFrame
        val targetRate = 16000
        val dstFrameCount = (srcFrameCount * targetRate + srcRate - 1) / srcRate
        check(dstFrameCount * 4 <= MAX_TARGET_PCM_BYTES) { "Whisper audio exceeds disk safety limit" }
        RandomAccessFile(pcmFile, "r").use { input ->
            BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                val frame = ByteArray(bytesPerFrame)
                var nextDst = 0L
                for (srcFrame in 0 until srcFrameCount) {
                    input.readFully(frame)
                var monoFloat = if (bytesPerSample == 4) {
                    // 32-bit float: read 4 bytes per channel.
                    if (srcChannels == 1) {
                            readFloatLE(frame, 0)
                    } else {
                        var sum = 0f
                        for (ch in 0 until srcChannels) {
                            sum += readFloatLE(frame, ch * 4)
                        }
                        sum / srcChannels
                    }
                } else {
                    // 16-bit signed: read 2 bytes per channel.
                    if (srcChannels == 1) {
                            readInt16LE(frame, 0).toFloat() / 32768.0f
                    } else {
                        var sum = 0
                        for (ch in 0 until srcChannels) {
                            sum += readInt16LE(frame, ch * 2)
                        }
                        (sum / srcChannels).toFloat() / 32768.0f
                    }
                }
                    val dstUntil = minOf(dstFrameCount, ((srcFrame + 1) * targetRate) / srcRate)
                    while (nextDst < dstUntil) {
                        val bits = java.lang.Float.floatToIntBits(monoFloat)
                        output.write(bits and 0xff); output.write((bits ushr 8) and 0xff)
                        output.write((bits ushr 16) and 0xff); output.write((bits ushr 24) and 0xff)
                        nextDst++
                    }
                }
            }
        }
        return dstFrameCount
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
        private const val MAX_RAW_PCM_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_TARGET_PCM_BYTES = 2L * 1024 * 1024 * 1024
        private const val MIN_FREE_SPACE_BYTES = 256L * 1024 * 1024

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
