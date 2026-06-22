plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":core-mpv"))
                implementation(project(":core-vfs"))
                implementation(project(":ui-compose"))
                implementation(compose.desktop.currentOs)
                implementation(compose.ui)
                implementation(compose.material3)
                implementation(compose.foundation)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.windplayer.MainKt"

        jvmArgs += "-Dmpv.lib.path=${projectDir.absolutePath}\\..\\lib\\mpv-dev"

        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "WindPlayer"
            packageVersion = "0.1.0"
        }
    }
}
