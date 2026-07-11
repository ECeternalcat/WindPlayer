package dev.windplayer.translate

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates the full ASR + LLM translation pipeline.
 *
 * Per AITranslate.md §2: this is the domain-layer coordinator that controls
 * the serial queue and drives the state machine ([TaskState]).
 *
 * Flow:
 * ```
 * ensureModel → extractAudio → whisper.transcribe → chunk → LLM translate each chunk → merge → write SRT
 * ```
 *
 * Designed to run inside a ForegroundService (§6.1) — [state] is a [StateFlow]
 * the service observes to update its notification + call stopSelf() on terminal.
 */
class TranslationManager(
    private val context: Context,
    private val audioSourceUrl: () -> String,
    private val audioDuration: () -> Double,
    private val doTranslate: Boolean = true,
    private val audioTrackIndex: Int = -1
) {
    private val _state = MutableStateFlow<TaskState>(TaskState.Queued)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pipelineJob: Job? = null

    private var whisperEngine: WhisperEngine? = null
    private var modelFetcher: ModelFetcher = ModelFetcher(context)

    /**
     * Persisted config. Set by the UI; defaults are in [TranslationConfig].
     */
    var config: TranslationConfig = TranslationConfigHelper.load(context)
        private set

    fun updateConfig(newConfig: TranslationConfig) {
        config = newConfig
        TranslationConfigHelper.save(context, newConfig)
    }

    /**
     * Start the full pipeline for the currently loaded video.
     *
     * Idempotent: if a pipeline is already running, does nothing.
     */
    fun start() {
        if (pipelineJob?.isActive == true) {
            Log.w(TAG, "Pipeline already running — ignoring start()")
            return
        }
        pipelineJob = scope.launch { runPipeline() }
    }

    /**
     * Cancel the pipeline.
     */
    fun cancel() {
        pipelineJob?.cancel()
        pipelineJob = null
        _state.value = TaskState.Failed.Cancelled()
        whisperEngine?.release()
        whisperEngine = null
    }

    /**
     * Release all native resources (Whisper context). Safe to call multiple times.
     */
    fun release() {
        pipelineJob?.cancel()
        scope.cancel()
        whisperEngine?.release()
        whisperEngine = null
    }

    // ------------------------------------------------------------------
    // Pipeline
    // ------------------------------------------------------------------

    private suspend fun runPipeline() {
        try {
            val sourceUrl = audioSourceUrl()
            val duration = audioDuration()
            Log.i(TAG, "Pipeline start: $sourceUrl (${duration}s)")

            // 1. Ensure Whisper model is downloaded.
            val modelExists = modelFetcher.isModelPresent(config.whisperModel)
            _state.value = TaskState.Transcribing(if (modelExists) 0.05f else 0f)

            val modelFile = modelFetcher.ensureModel(config.whisperModel) { downloaded, total ->
                if (total > 0) {
                    _state.value = TaskState.Transcribing(downloaded.toFloat() / total.toFloat() * 0.1f)
                }
            } ?: run {
                _state.value = TaskState.Failed.ModelDownloadError(
                    "Failed to download ${config.whisperModel}"
                )
                return
            }

            // 2. Load the model.
            _state.value = TaskState.Transcribing(0.1f)
            // BUG-3: release old engine before creating a new one to avoid
            // leaking the native WhisperContext (75-574MB of GGML data).
            if (whisperEngine != null && !whisperEngine!!.isLoaded) {
                whisperEngine!!.release()
                whisperEngine = null
            }
            if (whisperEngine == null) {
                whisperEngine = WhisperEngine().apply { loadModel(modelFile.absolutePath) }
            }

            // 3. Extract audio from the video (MediaExtractor + MediaCodec —
            //    no mpv dependency, avoids the singleton conflict).
            _state.value = TaskState.Transcribing(0.15f)
            val extractor = AudioExtractor(context)
            val audioResult = extractor.extractPcmAudio(sourceUrl, duration, audioTrackIndex) { progress ->
                _state.value = TaskState.Transcribing(0.1f + progress * 0.3f)
            }
            val audioData = audioResult.getOrElse { e ->
                _state.value = TaskState.Failed.AsrError("Audio extraction failed: ${e.message}")
                return
            }

            // 4. Run Whisper ASR.
            _state.value = TaskState.Transcribing(0.4f)
            val whisperResult = whisperEngine!!.transcribe(audioData)
            val segments = whisperResult.getOrElse { e ->
                _state.value = TaskState.Failed.AsrError("ASR failed: ${e.message}")
                return
            }

            if (segments.isEmpty()) {
                _state.value = TaskState.Failed.AsrError("No speech detected")
                return
            }
            Log.i(TAG, "ASR complete: ${segments.size} segments")

            // 5. Chunk segments for LLM.
            val chunks = ChunkingStrategy.chunk(
                segments,
                maxLines = config.maxLinesPerChunk,
                maxChars = config.maxCharsPerChunk
            )
            Log.i(TAG, "Chunked into ${chunks.size} batches")

            // 6. Translate each chunk via LLM — only if user chose translate
            // AND has an API key configured.
            if (!doTranslate || config.llmApiKey.isBlank()) {
                // Source-only mode (user chose "source language" or no API key).
                Log.w(TAG, "Skipping LLM translation — writing original-language subtitles only")
                val srtFile = writeSrt(segments, sourceUrl)
                TranslateService.pendingSubtitleMount.value = TranslateService.SubtitleMountRequest(
                    primaryPath = srtFile.absolutePath
                )
                _state.value = TaskState.Completed(srtFile.absolutePath)
                return
            }

            val llm = LLMRemoteSource(config)
            val allTranslations = mutableMapOf<Int, String>()
            var detectedLanguage: String? = null

            for ((i, chunk) in chunks.withIndex()) {
                _state.value = TaskState.TranslatingChunk(i + 1, chunks.size)
                Log.i(TAG, "Translating chunk ${i + 1}/${chunks.size} (${chunk.size} segments)")

                val contextRef = if (i > 0) chunks[i - 1].lastOrNull()?.let { ref ->
                    ref.copy(translatedText = allTranslations[ref.id])
                } else null

                // §5.2 格式损坏隔离: max 2 retries (3 total attempts) before
                // degrading to original text. Exponential backoff between
                // retries to handle transient rate limits / network blips.
                val maxRetries = 2
                var response: LLMRemoteSource.TranslationResponse? = null
                for (attempt in 0..maxRetries) {
                    val result = llm.translateChunk(chunk, contextRef)
                    if (result.isSuccess) {
                        response = result.getOrThrow()
                        break
                    }
                    val err = result.exceptionOrNull()
                    if (attempt < maxRetries) {
                        val backoffMs = 1000L * (attempt + 1) // 1s, 2s
                        Log.w(TAG, "Chunk ${i + 1} attempt ${attempt + 1}/${maxRetries + 1} failed: ${err?.message} — retrying in ${backoffMs}ms")
                        delay(backoffMs)
                    } else {
                        Log.w(TAG, "Chunk ${i + 1} failed after ${maxRetries + 1} attempts — degrading to original text")
                    }
                }

                if (response != null) {
                    allTranslations.putAll(response.translations)
                    if (detectedLanguage == null) detectedLanguage = response.detectedLanguage
                }
                // response == null → this chunk's segments keep originalText
                // (translatedText stays null → displayText falls back to original).
            }

            // 7. Merge translations back into timeline.
            val mergeResult = SubtitleMergeEngine.merge(segments, allTranslations, detectedLanguage)
            Log.i(TAG, "Merge complete: ${mergeResult.segments.size} segments, ${mergeResult.untranslatedCount} untranslated")

            // 8. Write SRT files. Translation mode produces three files so
            // the player can choose how to display them (Dual-Subtitle-Plan §4):
            //   - wp_xx.srt        translated text only
            //   - wp_xx_source.srt  original text only
            //   - wp_xx_dual.srt    translated + original stacked per cue
            val srtDir = File(context.cacheDir, "subtitles").apply { mkdirs() }
            val hashHex = hashSourceUrl(sourceUrl)

            val translatedFile = File(srtDir, "wp_$hashHex.srt").also {
                it.writeText(SubtitleMergeEngine.toSrtContent(mergeResult.segments), Charsets.UTF_8)
            }
            val sourceFile = File(srtDir, "wp_${hashHex}_source.srt").also {
                it.writeText(SubtitleMergeEngine.toSourceSrtContent(mergeResult.segments), Charsets.UTF_8)
            }
            val dualFile = File(srtDir, "wp_${hashHex}_dual.srt").also {
                it.writeText(SubtitleMergeEngine.toDualSrtContent(mergeResult.segments), Charsets.UTF_8)
            }
            Log.i(TAG, "Written: ${translatedFile.name}, ${sourceFile.name}, ${dualFile.name}")

            // Publish all three paths so MobilePlayerScreen can mount the
            // appropriate one(s) based on the user's display-mode setting.
            TranslateService.pendingSubtitleMount.value = TranslateService.SubtitleMountRequest(
                primaryPath = translatedFile.absolutePath,
                secondaryPath = sourceFile.absolutePath,
                dualPath = dualFile.absolutePath
            )

            _state.value = TaskState.Completed(translatedFile.absolutePath)
            Log.i(TAG, "Pipeline complete: ${translatedFile.absolutePath}")

        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = TaskState.Failed.Cancelled()
            throw e
        } catch (e: java.io.IOException) {
            // BUG-8: classify network/IO errors separately from generic crashes.
            Log.e(TAG, "Pipeline network/IO error", e)
            _state.value = TaskState.Failed.NetworkError(0, e.message ?: "Network error")
        } catch (e: Exception) {
            // BUG-8: don't mask NPE/codec errors as "NetworkError".
            Log.e(TAG, "Pipeline failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            _state.value = TaskState.Failed.AsrError(msg)
        }
    }

    /**
     * Write a single SRT file to the app cache directory.
     * Used for source-only mode (no translation).
     * BUG-22: SHA-256 of the source URL for collision-free dedup.
     */
    private fun writeSrt(
        segments: List<SubtitleSegment>,
        sourceUrl: String
    ): File {
        val srtDir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val srtFile = File(srtDir, "wp_${hashSourceUrl(sourceUrl)}.srt")
        srtFile.writeText(SubtitleMergeEngine.toSrtContent(segments), Charsets.UTF_8)
        return srtFile
    }

    /** 8-hex-char SHA-256 prefix of [sourceUrl], used as the subtitle file key. */
    private fun hashSourceUrl(sourceUrl: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(sourceUrl.toByteArray(Charsets.UTF_8))
        return hashBytes.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "TranslationManager"
    }
}
