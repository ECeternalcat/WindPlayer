package dev.windplayer.vfs

data class MatchedTrack(
    val file: FileNode,
    val type: MatchedTrackType,
    val confidence: MatchConfidence
)

enum class MatchedTrackType { SUBTITLE, AUDIO }

enum class MatchConfidence { EXACT, STRUCTURED, FUZZY }

private val AUDIO_EXTENSIONS = setOf("mka", "flac", "dts", "ac3", "wav", "ogg", "opus", "aac", "mp3", "ape", "wma", "truehd", "thd")

private val EPISODE_PATTERNS = listOf(
    Regex("""[Ee][Pp](\d{1,4})"""),
    Regex("""\b(\d{1,4})\s*\["""),
    Regex("""\s[-‐–]\s*(\d{1,4})\s*(?:\[|$)"""),
    Regex("""\s[-‐–]\s*(\d{1,4})\s*""")
)

fun matchExternalTracks(
    videoNode: FileNode,
    siblings: List<FileNode>,
    subsDirFiles: List<FileNode> = emptyList()
): List<MatchedTrack> {
    val allCandidates = siblings + subsDirFiles
    val videoBase = videoNode.name.substringBeforeLast('.')
    val videoExt = videoNode.name.substringAfterLast('.').lowercase()
    val videoEpisodeFeature = extractEpisodeFeature(videoBase)

    val subs = mutableListOf<MatchedTrack>()
    val audios = mutableListOf<MatchedTrack>()

    for (file in allCandidates) {
        if (file.isDirectory || file.path == videoNode.path) continue
        if (file.name.equals(videoNode.name, ignoreCase = true)) continue

        val ext = file.name.substringAfterLast('.').lowercase()
        val isSub = ext in SUBTITLE_EXTENSIONS
        val isAudio = ext in AUDIO_EXTENSIONS
        if (!isSub && !isAudio) continue

        val fileBase = file.name.substringBeforeLast('.')

        if (exactBaseMatch(videoBase, fileBase)) {
            val type = if (isSub) MatchedTrackType.SUBTITLE else MatchedTrackType.AUDIO
            val list = if (isSub) subs else audios
            list.add(MatchedTrack(file, type, MatchConfidence.EXACT))
            continue
        }

        if (videoEpisodeFeature != null) {
            val fileEpisode = extractEpisodeFeature(fileBase)
            if (fileEpisode == videoEpisodeFeature) {
                val type = if (isSub) MatchedTrackType.SUBTITLE else MatchedTrackType.AUDIO
                val list = if (isSub) subs else audios
                list.add(MatchedTrack(file, type, MatchConfidence.STRUCTURED))
                continue
            }
            if (isAudio) continue
        }

        if (isSub && videoEpisodeFeature == null) {
            val sim = similarity(videoBase.lowercase(), fileBase.lowercase())
            if (sim >= 0.85) {
                subs.add(MatchedTrack(file, MatchedTrackType.SUBTITLE, MatchConfidence.FUZZY))
            }
        }
    }

    return subs + audios
}

private fun exactBaseMatch(videoBase: String, fileBase: String): Boolean {
    val vLower = videoBase.lowercase()
    val fLower = fileBase.lowercase()
    if (fLower == vLower) return true
    return listOf('.', ' ', '_', '-').any { fLower.startsWith("$vLower$it") }
}

private data class EpisodeFeature(val title: String, val season: Int?, val episode: Int)

private fun extractEpisodeFeature(filename: String): EpisodeFeature? {
    val seasonEpisode = Regex("""(?i)S(\d{1,2})\s*E(\d{1,4})""").find(filename)
    if (seasonEpisode != null) {
        return EpisodeFeature(
            normalizeTitle(filename.substring(0, seasonEpisode.range.first)),
            seasonEpisode.groupValues[1].toIntOrNull() ?: return null,
            seasonEpisode.groupValues[2].toIntOrNull() ?: return null
        )
    }
    for (pattern in EPISODE_PATTERNS) {
        val match = pattern.find(filename)
        if (match != null) {
            val episode = match.groupValues[1].toIntOrNull() ?: return null
            return EpisodeFeature(normalizeTitle(filename.substring(0, match.range.first)), null, episode)
        }
    }
    return null
}

private fun normalizeTitle(title: String): String =
    title.lowercase().trim(' ', '.', '_', '-').replace(Regex("""[\s._-]+"""), "")

private fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val maxLen = maxOf(a.length, b.length)
    if (maxLen == 0) return 1.0
    val dist = levenshtein(a, b)
    return 1.0 - dist.toDouble() / maxLen.toDouble()
}

private fun levenshtein(a: String, b: String): Int {
    val m = a.length
    val n = b.length
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 0..m) dp[i][0] = i
    for (j in 0..n) dp[0][j] = j
    for (i in 1..m) {
        for (j in 1..n) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
        }
    }
    return dp[m][n]
}
