package dev.windplayer.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperChunkingTest {
    @Test
    fun shortAudioUsesOneWindow() {
        assertEquals(listOf(WhisperWindow(0, 30 * 16_000L)), planWhisperWindows(30 * 16_000L))
    }

    @Test
    fun longAudioUsesOverlappingWindows() {
        assertEquals(
            listOf(
                WhisperWindow(0, 120 * 16_000L),
                WhisperWindow(110 * 16_000L, 230 * 16_000L),
                WhisperWindow(220 * 16_000L, 250 * 16_000L)
            ),
            planWhisperWindows(250 * 16_000L)
        )
    }

    @Test
    fun offsetAndOverlapDuplicateAreMergedAndIdsAreContinuous() {
        val result = mergeWhisperSegments(
            listOf(
                0L to listOf(SubtitleSegment(99, 110_000, 118_000, "hello")),
                110_000L to listOf(
                    SubtitleSegment(0, 0, 8_000, "hello"),
                    SubtitleSegment(1, 12_000, 16_000, "world")
                )
            )
        )
        assertEquals(listOf(0, 1), result.map { it.id })
        assertEquals(listOf("hello", "world"), result.map { it.originalText })
        assertEquals(122_000L, result[1].startMs)
    }
}
