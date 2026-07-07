package dev.windplayer.translate

/**
 * Finite-state machine for the ASR + LLM translation pipeline.
 *
 * Per AITranslate.md §5.1: every result handler uses sealed-class pattern
 * matching, never bare `try/catch(Exception)`. This guarantees the UI always
 * knows exactly which phase failed and can render an appropriate message.
 *
 * State transitions:
 * ```
 * Queued → Transcribing → TranslatingChunk → Completed
 *                                     ↓
 *                               Failed (network / format / id mismatch)
 * ```
 */
sealed interface TaskState {
    /** Task is queued, waiting for a worker slot. */
    data object Queued : TaskState

    /** Whisper ASR is running on the audio data. [progress] is 0..1. */
    data class Transcribing(val progress: Float) : TaskState

    /** LLM is translating chunk [currentChunk] of [totalChunks]. */
    data class TranslatingChunk(
        val currentChunk: Int,
        val totalChunks: Int
    ) : TaskState

    /** Pipeline finished; [srtFilePath] points to the generated .srt file. */
    data class Completed(val srtFilePath: String) : TaskState

    /** Terminal failure states. Each carries diagnostic info for the UI. */
    sealed class Failed : TaskState {
        data class NetworkError(val retryCount: Int, val message: String) : Failed()
        data class FormatCorrupted(val chunkId: Int, val rawJson: String) : Failed()
        data class IdMismatch(val expectedCount: Int, val actualCount: Int) : Failed()
        data class AsrError(val message: String) : Failed()
        data class ModelDownloadError(val message: String) : Failed()
        data class Cancelled(val partialSrtPath: String? = null) : Failed()
    }
}

/**
 * Whether the current [TaskState] is terminal (no further transitions).
 */
val TaskState.isTerminal: Boolean
    get() = this is TaskState.Completed || this is TaskState.Failed

/**
 * Whether the current [TaskState] is an active in-progress state.
 */
val TaskState.isActive: Boolean
    get() = this is TaskState.Transcribing || this is TaskState.TranslatingChunk
