plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm("desktop")
    androidTarget()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.ui)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(project(":core-mpv"))
                implementation(project(":core-vfs"))
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
            }
        }

        val mobileMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":core-mpv"))
                implementation(project(":core-vfs"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val androidMain by getting {
            dependsOn(mobileMain)
        }
    }
}

android {
    namespace = "dev.windplayer.ui"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
}
