plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm("desktop")
    android {
        namespace = "dev.windplayer.ui"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                @Suppress("DEPRECATION")
                implementation(compose.runtime)
                @Suppress("DEPRECATION")
                implementation(compose.ui)
                // Compose resources library — drives the generated Res accessors
                // used by desktopMain for SVG icons + fonts. Documented location
                // for this dependency is commonMain so the auto Res class
                // generation triggers.
                @Suppress("DEPRECATION")
                implementation(compose.components.resources)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(project(":core-mpv"))
                implementation(project(":core-vfs"))
                @Suppress("DEPRECATION")
                implementation(compose.runtime)
                @Suppress("DEPRECATION")
                implementation(compose.foundation)
                @Suppress("DEPRECATION")
                implementation(compose.material3)
                @Suppress("DEPRECATION")
                implementation(compose.ui)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(project(":core-mpv"))
                implementation(project(":core-vfs"))
                @Suppress("DEPRECATION")
                implementation(compose.runtime)
                @Suppress("DEPRECATION")
                implementation(compose.foundation)
                @Suppress("DEPRECATION")
                implementation(compose.material3)
                @Suppress("DEPRECATION")
                implementation(compose.ui)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// Generate the Res accessors class as `public` in a fixed package so callers
// (Icons.kt, WindTheme.kt) can import it. Default is `internal` under a
// generated package which is awkward to reference across modules.
compose.resources {
    publicResClass = true
    packageOfResClass = "dev.windplayer.ui.resources"
}
