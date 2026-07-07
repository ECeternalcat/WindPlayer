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
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(project(":core-mpv"))
                implementation(project(":core-vfs"))
                // compose.desktop.currentOs lives ONLY in app-desktop — putting
                // it in a library pins Skia/LWJGL natives to the build-host OS,
                // making the library non-portable across CI runners.
                // compose.{runtime,foundation,material3,ui} accessors are
                // deprecated in CMP 1.11 (string forms don't carry the
                // per-artifact version matrix). Suppressed until CMP ships a
                // non-deprecated replacement.
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
