import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
                // compose.ui / material3 / foundation accessors are deprecated
                // in CMP 1.11 (the string forms don't carry the per-artifact
                // version matrix — material3 is on 1.11.0-alpha07 while ui is
                // on 1.11.2). The accessors remain the only safe way to depend
                // on these without tracking each artifact's version manually.
                @Suppress("DEPRECATION")
                implementation(compose.ui)
                @Suppress("DEPRECATION")
                implementation(compose.material3)
                @Suppress("DEPRECATION")
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

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "WindPlayer"
            // Release pipeline passes -PpackageVersion from the git tag.
            packageVersion = (project.findProperty("packageVersion") as? String) ?: "0.1.0"
            windows {
                iconFile = file("${rootProject.projectDir.absolutePath}/icons/Launcher Icons/Windows/ic_launcher_round-multi-size-256x256.ico")
            }
        }
    }
}
