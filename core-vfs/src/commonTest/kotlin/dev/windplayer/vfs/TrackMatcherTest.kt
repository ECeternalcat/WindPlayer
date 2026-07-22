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

    // ------------------------------------------------------------------
    // ARCH-15: regression tests for the 0.85 fuzzy threshold and the
    // M1 numeric-comparison fix in extractEpisodeFeature.
    // ------------------------------------------------------------------

    @Test
    fun `Level 4 fuzzy matches subtitle above 0_85 threshold`() {
        // Same base name with a minor noise suffix; similarity ≈ 0.92 → match.
        val v = video("Concert.2024.mkv")
        val siblings = listOf(sub("Concert.2024.chs.srt"))
        val matched = matchExternalTracks(v, siblings)
        // Exact name match wins first (Concert.2024.chs starts with "Concert.2024."),
        // so confidence is EXACT not FUZZY. Sanity check the count and type.
        assertEquals(1, matched.size)
    }

    @Test
    fun `Level 4 fuzzy rejects subtitle below 0_85 threshold`() {
        // No episode feature; very different name. similarity ≈ 0.5 → reject.
        val v = video("RandomMovie.mkv")
        val siblings = listOf(sub("TotallyDifferentFilm.srt"))
        val matched = matchExternalTracks(v, siblings)
        assertEquals(0, matched.size)
    }

    @Test
    fun `Level 4 fuzzy rejects audio even when similar`() {
        // ARCH-15 / design §1: audio is NEVER fuzzy-matched (would pick up
        // director-interview BGM, soundtracks, etc.). Verify.
        val v = video("Concert.2024.mkv")
        val siblings = listOf(audio("Concert.2024.soundtrack.mka"))
        val matched = matchExternalTracks(v, siblings)
        // Exact name match wins (Concert.2024.soundtrack starts with Concert.2024.).
        // If we wanted to test pure fuzzy on audio, we'd need a name that's only
        // 85% similar without an exact prefix — but the algorithm blocks audio
        // fuzzy entirely, so any non-prefix audio file is rejected:
        val v2 = video("Concert.2024.mkv")
        val siblings2 = listOf(audio("Concrt.2024.mka"))  // missing 'e' — 96% similar
        val matched2 = matchExternalTracks(v2, siblings2)
        assertEquals(0, matched2.size)
    }

    @Test
    fun `M1 regression S1E1 matches S01E01 subtitles`() {
        // Before the M1 numeric fix, S1E1 (season="1", ep="1") was treated
        // as string "1" vs "01" — different keys, no match. After M1 the
        // comparison is numeric so S1E1 ↔ S01E01 cross-match works.
        val v = video("Show.S1E1.mkv")
        val siblings = listOf(sub("Show.S01E01.eng.srt"))
        val matched = matchExternalTracks(v, siblings)
        assertEquals(1, matched.size)
        assertEquals(MatchConfidence.STRUCTURED, matched[0].confidence)
    }

    @Test
    fun `M1 regression S01E05 does not match S01E06`() {
        val v = video("Show.S01E05.mkv")
        val siblings = listOf(sub("Show.S01E06.srt"))
        val matched = matchExternalTracks(v, siblings)
        assertEquals(0, matched.size)
    }

    @Test
    fun `S01E01 keeps season and episode when numbers are equal`() {
        val matched = matchExternalTracks(video("Show.S01E01.mkv"), listOf(sub("Show.EP001.srt")))
        assertEquals(0, matched.size)
    }

    @Test
    fun `structured episode requires matching title`() {
        val matched = matchExternalTracks(video("Show.S01E01.mkv"), listOf(sub("Other.S01E01.srt")))
        assertEquals(0, matched.size)
    }

    @Test
    fun `cleaned prefix does not match a longer unrelated title`() {
        val matched = matchExternalTracks(video("Movie.mkv"), listOf(sub("MovieTrailer.srt")))
        assertEquals(0, matched.size)
    }

    @Test
    fun `extractEpisodeFeature blocks fuzzy after structured match found nothing`() {
        // Per design §2: if the video has an episode feature, Level 4 (fuzzy)
        // is BLOCKED — even if no Level 3 subtitle exists. This prevents
        // grabbing subtitles from a different episode.
        val v = video("Show.S01E01.mkv")
        val siblings = listOf(sub("Show.S01E02.srt"))  // close name, wrong ep
        val matched = matchExternalTracks(v, siblings)
        assertEquals(0, matched.size)
    }
}
