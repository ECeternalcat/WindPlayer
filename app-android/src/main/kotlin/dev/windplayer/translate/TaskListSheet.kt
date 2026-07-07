package dev.windplayer.translate

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.Phosphor
import dev.windplayer.PhosphorIcon
import dev.windplayer.WindColors
import dev.windplayer.WindRadius
import dev.windplayer.ui.I18n
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom sheet showing all translation tasks (running + history).
 *
 * Features:
 * - Running tasks show live state (Transcribing / Translating)
 * - Completed source-only tasks have a "Translate" button to re-run with LLM
 * - Any terminal task can be deleted
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tasks by TaskStore.tasks.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = WindColors.LiftedCream,
        shape = WindRadius.Stadium
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            // Title row
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PhosphorIcon(Phosphor.MICROPHONE, null, tint = WindColors.SignalOrange, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Translation Tasks",
                    color = WindColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (tasks.any { !it.isActive }) {
                    TextButton(onClick = { TaskStore.clearTerminal() }) {
                        Text("Clear", color = WindColors.Slate, fontSize = 13.sp)
                    }
                }
            }

            if (tasks.isEmpty()) {
                Text(
                    "No tasks yet. Long-press a video to generate subtitles.",
                    color = WindColors.Slate, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp)
                )
            } else {
                LazyColumn {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onDelete = { TaskStore.remove(task.id) },
                            onTranslate = {
                                // Re-run: use the stored source URL, force translate.
                                if (!TranslateService.isRunning) {
                                    TranslationStarter.showChoice(
                                        context = context,
                                        videoTitle = task.videoTitle,
                                        sourceUrl = task.sourceUrl,
                                        duration = task.duration
                                    )
                                    onDismiss()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TranslateTask,
    onDelete: () -> Unit,
    onTranslate: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = WindColors.White,
        shape = WindRadius.Button
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // State icon
            val (icon, iconTint) = when {
                task.isActive -> Phosphor.MICROPHONE to WindColors.SignalOrange
                task.isCompleted -> Phosphor.CHECK to Color(0xFF4CAF50)
                task.isFailed -> Phosphor.WARNING to WindColors.SignalOrange
                else -> Phosphor.FILE to WindColors.Slate
            }
            if (task.isActive) {
                // Pulse alpha for running tasks.
                val transition = rememberInfiniteTransition(label = "pulse")
                val alpha by transition.animateFloat(
                    initialValue = 0.4f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "alpha"
                )
                PhosphorIcon(icon, null, tint = iconTint.copy(alpha = alpha), size = 24.dp)
            } else {
                PhosphorIcon(icon, null, tint = iconTint, size = 24.dp)
            }

            Spacer(Modifier.width(12.dp))

            // Title + state text
            Column(Modifier.weight(1f)) {
                Text(
                    task.videoTitle,
                    color = WindColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val stateText = when (val s = task.state) {
                    is TaskState.Queued -> "Queued…"
                    is TaskState.Transcribing -> {
                        val pct = (s.progress * 100).toInt().coerceIn(0, 100)
                        when {
                            pct < 10 -> "Downloading model…"
                            pct < 15 -> "Loading model…"
                            pct < 40 -> "Extracting audio… $pct%"
                            else -> "Transcribing… $pct%"
                        }
                    }
                    is TaskState.TranslatingChunk -> "Translating ${s.currentChunk}/${s.totalChunks}"
                    is TaskState.Completed -> if (task.sourceOnly) "Source-only ✓" else "Translated ✓"
                    is TaskState.Failed.NetworkError -> "Failed: ${s.message}"
                    is TaskState.Failed.AsrError -> "Failed: ${s.message}"
                    is TaskState.Failed.ModelDownloadError -> "Model download failed"
                    is TaskState.Failed.FormatCorrupted -> "Format error (chunk ${s.chunkId})"
                    is TaskState.Failed.IdMismatch -> "ID mismatch"
                    is TaskState.Failed.Cancelled -> "Cancelled"
                }
                Text(
                    "$stateText · ${dateFormat.format(Date(task.timestamp))}",
                    color = WindColors.Slate, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            // Action buttons
            // Re-translate button for completed source-only tasks.
            if (task.isCompleted && task.sourceOnly) {
                Surface(
                    onClick = onTranslate,
                    color = WindColors.SignalOrange,
                    shape = WindRadius.Pill
                ) {
                    Text(
                        "Translate",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            // Delete button (terminal tasks only).
            if (task.isTerminal) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    PhosphorIcon(Phosphor.X, "Delete", tint = WindColors.DustTaupe, size = 16.dp)
                }
            }
        }
    }
}
