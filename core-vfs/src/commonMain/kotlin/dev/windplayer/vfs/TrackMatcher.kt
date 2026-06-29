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
    Regex("""[Ss](\d{1,2})\s*[Ee](\d{1,4})"""),
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
    val videoEpisodeFeature = extractEpisodeFeature(videoNode.name)

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
            val fileEpisode = extractEpisodeFeature(file.name)
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
    if (fLower.startsWith("$vLower.") || fLower.startsWith("$vLower ")) return true
    val vClean = vLower.replace(Regex("""[\s._-]"""), "")
    val fClean = fLower.replace(Regex("""[\s._-]"""), "")
    return fClean.startsWith(vClean)
}

private fun extractEpisodeFeature(filename: String): String? {
    for (pattern in EPISODE_PATTERNS) {
        val match = pattern.find(filename)
        if (match != null) {
            val season = match.groupValues.getOrNull(1)
            val episode = match.groupValues.getOrNull(2)
            // M1: compare numerically, not as strings. "1" != "01" as strings,
            // so S1E1 (season=1, ep=1) would bypass the S##E## branch and fall
            // through to EP001 — breaking structured matching against S01E01 subs.
            if (episode != null && season != null && season.toIntOrNull() != episode.toIntOrNull()) {
                return "S${season.padStart(2, '0')}E${episode.padStart(2, '0')}"
            }
            val ep = match.groupValues[1]
            return "EP${ep.padStart(3, '0')}"
        }
    }
    return null
}

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
