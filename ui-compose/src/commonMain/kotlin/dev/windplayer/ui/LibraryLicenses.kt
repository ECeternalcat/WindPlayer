package dev.windplayer.ui

/**
 * Third-party library license metadata, shared between desktop and Android
 * settings screens. Versions are synced with [gradle/libs.versions.toml].
 */
data class LibraryLicense(
    val name: String,
    val version: String,
    val license: String,
    val url: String
)

val THIRD_PARTY_LIBRARIES: List<LibraryLicense> = listOf(
    LibraryLicense("mpv", "—", "GPL v2+", "https://github.com/mpv-player/mpv"),
    LibraryLicense("libmpv", "—", "GPL v2+", "https://github.com/mpv-player/mpv/blob/master/Copyright"),
    LibraryLicense("mpv-android (libplayer)", "—", "GPL v2+", "https://github.com/mpv-android/mpv-android"),
    LibraryLicense("Compose Multiplatform", "1.9.0", "Apache 2.0", "https://github.com/JetBrains/compose-multiplatform"),
    LibraryLicense("Kotlin Coroutines", "1.10.2", "Apache 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    LibraryLicense("Java Native Access (JNA)", "5.17.0", "Apache 2.0", "https://github.com/java-native-access/jna"),
    LibraryLicense("SSHJ", "0.39.0", "Apache 2.0", "https://github.com/hierynomus/sshj"),
    LibraryLicense("Ktor", "3.0.3", "Apache 2.0", "https://github.com/ktorio/ktor"),
    LibraryLicense("Apache Commons Net", "3.11.1", "Apache 2.0", "https://commons.apache.org/proper/commons-net/"),
    LibraryLicense("SLF4J", "2.0.16", "MIT", "https://www.slf4j.org/"),
    LibraryLicense("AndroidX Activity Compose", "1.9.3", "Apache 2.0", "https://developer.android.com/jetpack/androidx/releases/activity"),
    LibraryLicense("AndroidX Lifecycle", "2.8.7", "Apache 2.0", "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
    LibraryLicense("AndroidX Core KTX", "1.15.0", "Apache 2.0", "https://developer.android.com/jetpack/androidx/releases/core"),
    LibraryLicense("AndroidX DocumentFile", "1.0.1", "Apache 2.0", "https://developer.android.com/reference/androidx/documentfile/provider/package-summary"),
    LibraryLicense("AndroidX Security Crypto", "1.1.0-alpha06", "Apache 2.0", "https://developer.android.com/jetpack/androidx/releases/security"),
    LibraryLicense("Phosphor Icons", "2.1.1", "MIT", "https://phosphoricons.com/"),
    LibraryLicense("Sofia Sans", "—", "SIL OFL 1.1", "https://fonts.google.com/specimen/Sofia+Sans")
)
