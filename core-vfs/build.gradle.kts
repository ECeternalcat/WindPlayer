plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.android.kotlin.multiplatform.library")
}
kotlin {
    jvm("desktop")
    android {
        namespace = "dev.windplayer.core.vfs"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        // Common test source set: target-agnostic tests for [commonMain] code.
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // jvmShared: code that requires the JVM stdlib or JVM-only libs
        // (sshj / ktor-cio / commons-net / java.xml / String.format / URLEncoder).
        // Both desktopMain and androidMain inherit from this so the protocol
        // implementations can stay in one place instead of being duplicated.
        val jvmShared by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.sshj)
                implementation(libs.commons.net)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation(libs.jna.platform)
                // slf4j-nop only on desktop (silence SSHJ's chatty INFO logs).
                // Android gets no SLF4J binding → SSHJ warnings are visible in
                // logcat, which is strictly better than total silence.
                implementation(libs.slf4j.nop)
            }
        }

        val androidMain by getting {
            dependsOn(jvmShared)
        }
    }
}
