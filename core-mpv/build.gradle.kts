plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm("desktop")
    androidTarget()

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

        val mobileMain by creating {
            dependsOn(commonMain)
        }

        val androidMain by getting {
            dependsOn(mobileMain)
        }
    }
}

android {
    namespace = "dev.windplayer.core.mpv"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
}
