package dev.windplayer.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.windplayer.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the ASR + LLM translation pipeline alive
 * against Android's background-process killing (AITranslate.md §6.1).
 *
 * Displays a non-dismissable notification with live progress:
 * ```
 * 标题: 视频《xxx》字幕处理中
 * 内容: 正在翻译 (45%) | 剩余约 2 分钟
 * ```
 *
 * Lifecycle: started when the user triggers "Generate Subtitles", runs until
 * [TaskState.isTerminal], then calls stopSelf().
 */
class TranslateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null
    private var translationManager: TranslationManager? = null

    companion object {
        const val CHANNEL_ID = "translate_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_VIDEO_TITLE = "video_title"
        const val EXTRA_SOURCE_URL = "source_url"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_TRANSLATE = "do_translate"
        const val EXTRA_TRACK_INDEX = "track_index"

        /**
         * §7 Hot Reload: when the pipeline completes, the generated SRT path
         * is published here. MobilePlayerScreen collects this flow and calls
         * `sub-add` to mount the subtitle into mpv — silent, no user action
         * needed.
         */
        val pendingSubtitleMount = MutableStateFlow<String?>(null)

        /**
         * Global observable task state — any UI (player, file browser) can
         * collect this to show an indicator or progress dialog.
         */
        val taskState = MutableStateFlow<TaskState>(TaskState.Queued)

        /** Title of the video currently being processed (for UI display). */
        var currentVideoTitle: String? = null
            private set

        /** ID of the current task in TaskStore. */
        var currentTaskId: String? = null
            private set

        /** True when a pipeline is actively running (including the brief Queued phase). */
        @Volatile
        private var taskActive: Boolean = false

        val isRunning: Boolean get() = taskActive

        /**
         * Start the translation pipeline. Returns false if a task is already
         * running (caller should show a "busy" Toast instead of starting).
         */
        fun start(
            context: Context,
            videoTitle: String,
            sourceUrl: String,
            duration: Double,
            doTranslate: Boolean = true,
            trackIndex: Int = -1
        ): Boolean {
            if (isRunning) return false
            taskActive = true
            currentVideoTitle = videoTitle
            taskState.value = TaskState.Queued

            currentTaskId = java.util.UUID.randomUUID().toString()
            TaskStore.add(TranslateTask(
                id = currentTaskId!!,
                videoTitle = videoTitle,
                sourceUrl = sourceUrl,
                duration = duration,
                state = TaskState.Queued,
                sourceOnly = !doTranslate
            ))

            val intent = Intent(context, TranslateService::class.java).apply {
                putExtra(EXTRA_VIDEO_TITLE, videoTitle)
                putExtra(EXTRA_SOURCE_URL, sourceUrl)
                putExtra(EXTRA_DURATION, duration)
                putExtra(EXTRA_TRANSLATE, doTranslate)
                putExtra(EXTRA_TRACK_INDEX, trackIndex)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TranslateService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // BUG-1: startForeground MUST be called before any early return.
        // On Android 8+, startForegroundService gives us 5s to call
        // startForeground or the system kills the app.
        createNotificationChannel()
        val notif = buildNotification("WindPlayer", "Starting…", -1f)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            Log.e("TranslateService", "startForeground failed: ${e.message}", e)
        }

        val title = intent?.getStringExtra(EXTRA_VIDEO_TITLE) ?: "video"
        val sourceUrl = intent?.getStringExtra(EXTRA_SOURCE_URL)
        if (sourceUrl == null) {
            Log.w("TranslateService", "No source URL in intent — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        val duration = intent.getDoubleExtra(EXTRA_DURATION, 0.0)
        val doTranslate = intent.getBooleanExtra(EXTRA_TRANSLATE, true)
        val trackIndex = intent.getIntExtra(EXTRA_TRACK_INDEX, -1)

        // Create and start the translation manager.
        translationManager = TranslationManager(
            context = this,
            audioSourceUrl = { sourceUrl },
            audioDuration = { duration },
            doTranslate = doTranslate,
            audioTrackIndex = trackIndex
        )
        translationManager!!.start()

        // Observe state and update the notification + global flow.
        stateJob = scope.launch {
            translationManager!!.state.collectLatest { state ->
                taskState.value = state // publish to UI
                currentTaskId?.let { TaskStore.update(it, state) }
                updateNotification(title, state)
                if (state.isTerminal) {
                    Log.i("TranslateService", "Terminal state: $state")
                    // Toast feedback so the user knows the result even if they're
                    // not looking at the player screen or notification.
                    val msg = when (state) {
                        is TaskState.Completed -> "✓ Subtitle ready!"
                        is TaskState.Failed.NetworkError -> "Failed: ${state.message}"
                        is TaskState.Failed.AsrError -> "Failed: ${state.message}"
                        is TaskState.Failed.ModelDownloadError -> "Failed: model download"
                        is TaskState.Failed.FormatCorrupted -> "Failed: format error (chunk ${state.chunkId})"
                        is TaskState.Failed.IdMismatch -> "Failed: ID mismatch"
                        is TaskState.Failed.Cancelled -> "Cancelled"
                        // Non-terminal states shouldn't reach here (guarded by
                        // isTerminal above), but the compiler needs exhaustiveness.
                        else -> ""
                    }
                    if (msg.isNotEmpty()) {
                        android.widget.Toast.makeText(this@TranslateService, msg, android.widget.Toast.LENGTH_LONG).show()
                    }

                    stopForeground(STOP_FOREGROUND_REMOVE)
                    delay(2000)
                    taskActive = false
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stateJob?.cancel()
        translationManager?.release()
        translationManager = null
        // BUG-5: ensure taskActive resets even if the service is killed
        // without going through the terminal-state collector.
        taskActive = false
        currentVideoTitle = null
        currentTaskId = null
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Subtitle Translation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress for AI subtitle generation"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String, progress: Float): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("《$title》")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress >= 0) {
            val percent = (progress * 100).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true) // indeterminate
        }

        return builder.build()
    }

    private fun updateNotification(title: String, state: TaskState) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val (text, progress) = when (state) {
            is TaskState.Queued -> "Starting…" to -1f
            is TaskState.Transcribing -> {
                when {
                    state.progress < 0.02f -> "Downloading model…" to state.progress
                    state.progress < 0.1f -> "Loading model…" to state.progress
                    state.progress < 0.4f -> "Extracting audio…" to state.progress
                    else -> "Transcribing (ASR)…" to state.progress
                }
            }
            is TaskState.TranslatingChunk -> {
                val pct = state.currentChunk.toFloat() / state.totalChunks.toFloat()
                "Translating (${state.currentChunk}/${state.totalChunks})…" to pct
            }
            is TaskState.Completed -> "Subtitle ready!" to 1f
            is TaskState.Failed.NetworkError -> "Network error: ${state.message}" to -1f
            is TaskState.Failed.FormatCorrupted -> "Format error in chunk ${state.chunkId}" to -1f
            is TaskState.Failed.IdMismatch -> "ID mismatch: ${state.expectedCount} vs ${state.actualCount}" to -1f
            is TaskState.Failed.AsrError -> "ASR error: ${state.message}" to -1f
            is TaskState.Failed.ModelDownloadError -> "Model download failed" to -1f
            is TaskState.Failed.Cancelled -> "Cancelled" to -1f
        }

        mgr.notify(NOTIFICATION_ID, buildNotification(title, text, progress))
    }
}
