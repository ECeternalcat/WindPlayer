package dev.windplayer.translate

import java.net.URI

internal fun normalizeSourceIdentity(sourceUrl: String, namespace: String? = null): String {
    val trimmed = sourceUrl.trim()
    val normalized = try {
        val normalized = URI(trimmed).normalize()
        val withoutFragment = normalized.toASCIIString().substringBeforeLast('#')
        val schemeEnd = withoutFragment.indexOf(':')
        if (normalized.scheme == null || schemeEnd < 0) withoutFragment
        else normalized.scheme.lowercase() + withoutFragment.substring(schemeEnd)
    } catch (_: Exception) {
        trimmed.substringBefore('#')
    }
    return if (namespace.isNullOrBlank()) normalized else "$namespace|$normalized"
}
