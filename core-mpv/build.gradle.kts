plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm("desktop")
    android {
        namespace = "dev.windplayer.core.mpv"
        compileSdk = 36
        minSdk = 24
    }

    // expect/actual classes (used for MpvPlayer) graduated to Beta in Kotlin
    // 2.x; the warning is informational, not a problem. Pass the flag to
    // suppress the noise.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jna)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
