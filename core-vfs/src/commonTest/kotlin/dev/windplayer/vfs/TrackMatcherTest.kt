package dev.windplayer.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Smoke tests for [TrackMatcher]. Goal: give CI something to fail on if the
 * matching algorithm regresses, and to exercise the test infrastructure
 * (commonTest source set + `kotlin("test")` dependency).
 *
 * Not exhaustive — covers the documented Level 2/3/4 cases from
 * `Documents/external-media-track-matching-and-scheduling.md.md`.
 */
class TrackMatcherTest {

    private fun video(name: String) = FileNode(
        name = name,
        path = "/videos/$name",
        isDirectory = false,
        size = 0,
        protocol = VfsProtocol.LOCAL
    )

    private fun sub(name: String) = FileNode(
        name = name,
        path = "/videos/$name",
        isDirectory = false,
        size = 0,
        protocol = VfsProtocol.LOCAL
    )

    private fun audio(name: String) = FileNode(
        name = name,
        path = "/videos/$name",
        isDirectory = false,
        size = 0,
        protocol = VfsProtocol.LOCAL
    )

    @Test
    fun `video extension set contains common formats`() {
        assertTrue("mkv" in VIDEO_EXTENSIONS)
        assertTrue("mp4" in VIDEO_EXTENSIONS)
        assertTrue("avi" in VIDEO_EXTENSIONS)
    }

    @Test
    fun `subtitle extension set contains ass and srt`() {
        assertTrue("ass" in SUBTITLE_EXTENSIONS)
        assertTrue("srt" in SUBTITLE_EXTENSIONS)
        assertTrue("vtt" in SUBTITLE_EXTENSIONS)
    }

    @Test
    fun `isVideo true for mkv false for srt`() {
        assertTrue(video("Movie.mkv").isVideo())
        assertFalse(sub("Movie.srt").isVideo())
    }

    @Test
    fun `isSubtitle true for srt false for mkv`() {
        assertTrue(sub("Movie.srt").isSubtitle())
        assertFalse(video("Movie.mkv").isSubtitle())
    }

    @Test
    fun `FileNodeComparator puts directories first`() {
        val files = listOf(
            FileNode("b.mkv", "/b.mkv", isDirectory = false, protocol = VfsProtocol.LOCAL),
            FileNode("A_dir", "/A_dir", isDirectory = true, protocol = VfsProtocol.LOCAL),
            FileNode("a.mkv", "/a.mkv", isDirectory = false, protocol = VfsProtocol.LOCAL),
            FileNode("Z_dir", "/Z_dir", isDirectory = true, protocol = VfsProtocol.LOCAL)
        )
        val sorted = files.sortedWith(FileNodeComparator)
        // Directories first (alphabetical), then files (alphabetical)
        assertEquals(listOf("A_dir", "Z_dir", "a.mkv", "b.mkv"), sorted.map { it.name })
    }

    @Test
    fun `Level 2 exact-name match hits subtitle`() {
        val v = video("Concert.2024.1080p.mkv")
        val siblings = listOf(
            sub("Concert.2024.1080p.cht.ass"),
            sub("unrelated.srt")
        )
        val matched = matchExternalTracks(v, siblings)
        assertEquals(1, matched.size)
        assertEquals(MatchedTrackType.SUBTITLE, matched[0].type)
        assertEquals(MatchConfidence.EXACT, matched[0].confidence)
    }

    @Test
    fun `Level 2 exact-name match hits audio track`() {
        val v = video("Concert.2024.1080p.mkv")
        val siblings = listOf(audio("Concert.2024.1080p.FLAC.mka"))
        val matched = matchExternalTracks(v, siblings)
        assertEquals(1, matched.size)
        assertEquals(MatchedTrackType.AUDIO, matched[0].type)
    }

    @Test
    fun `Level 3 episode feature match hits subtitles`() {
        val v = video("Show.S01E01.mkv")
        val siblings = listOf(
            sub("Show.S01E01.eng.srt"),
            sub("Show.S01E01.sc.srt"),
            sub("Show.S01E02.sc.srt")  // different episode — must NOT match
        )
        val matched = matchExternalTracks(v, siblings)
        assertEquals(2, matched.size)
        matched.forEach {
            assertEquals(MatchedTrackType.SUBTITLE, it.type)
        }
    }

    @Test
    fun `unrelated files are not matched`() {
        val v = video("Movie.mkv")
        val siblings = listOf(
            sub("OtherMovie.srt"),
            audio("BGM.mp3")
        )
        val matched = matchExternalTracks(v, siblings)
        // BGM.mp3 should not be picked up as audio for "Movie.mkv" — different base name
        // (Fuzzy matching is sub-only and mp3 is not in our audio ext set anyway.)
        assertEquals(0, matched.size)
    }
}
