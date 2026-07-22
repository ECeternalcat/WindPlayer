package dev.windplayer.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceIdentityTest {
    @Test
    fun normalizesUriWithoutChangingSourceSpecificData() {
        assertEquals(
            "content://provider/tree/root/document/movie.mkv?token=A%2FB",
            normalizeSourceIdentity(
                "CONTENT://provider/tree/root/dir/../document/movie.mkv?token=A%2FB#preview"
            )
        )
    }

    @Test
    fun keepsDifferentSourcesDistinct() {
        assertNotEquals(
            normalizeSourceIdentity("content://provider/document/a.mkv"),
            normalizeSourceIdentity("content://provider/document/b.mkv")
        )
    }

    @Test
    fun namespacesRemotePathsByServer() {
        assertNotEquals(
            normalizeSourceIdentity("/shows/episode.mkv", "server-a"),
            normalizeSourceIdentity("/shows/episode.mkv", "server-b")
        )
    }
}
