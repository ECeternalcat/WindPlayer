package dev.windplayer.translate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * One translation task entry — tracked from start to terminal state.
 *
 * Lives in [TranslateService.taskList] so the FileBrowserScreen indicator
 * and the task list sheet can observe all tasks (running + history).
 */
data class TranslateTask(
    val id: String = UUID.randomUUID().toString(),
    val videoTitle: String,
    val sourceUrl: String,
    val duration: Double,
    val state: TaskState,
    val sourceOnly: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isActive: Boolean get() = state is TaskState.Transcribing || state is TaskState.TranslatingChunk
    val isTerminal: Boolean get() = state.isTerminal
    val isCompleted: Boolean get() = state is TaskState.Completed
    val isFailed: Boolean get() = state is TaskState.Failed
    val srtPath: String? get() = (state as? TaskState.Completed)?.srtFilePath
}

/**
 * Global task store — observed by UI across screens.
 */
object TaskStore {
    private val _tasks = MutableStateFlow<List<TranslateTask>>(emptyList())
    val tasks: StateFlow<List<TranslateTask>> = _tasks.asStateFlow()

    /** True if any task is currently running. */
    val hasActive: Boolean get() = _tasks.value.any { it.isActive }

    /** True if there are any tasks at all (for showing the indicator button). */
    val hasAny: Boolean get() = _tasks.value.isNotEmpty()

    fun add(task: TranslateTask) {
        _tasks.value = _tasks.value + task
    }

    fun update(taskId: String, state: TaskState) {
        _tasks.value = _tasks.value.map {
            if (it.id == taskId) it.copy(state = state) else it
        }
    }

    fun remove(taskId: String) {
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
    }

    fun clearTerminal() {
        _tasks.value = _tasks.value.filter { it.isActive }
    }
}
