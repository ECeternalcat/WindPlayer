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
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.sshj)
                implementation(libs.commons.net)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation("org.slf4j:slf4j-nop:2.0.16")
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
    namespace = "dev.windplayer.core.vfs"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
}
