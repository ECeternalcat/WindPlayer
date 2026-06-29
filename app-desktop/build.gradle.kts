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

        // Forward-slash path works on both Windows and Unix; Java's File/Path
        // accepts both. Avoid `\..` (Windows-only) so Linux CI / distZip
        // produces a runnable distribution.
        jvmArgs += "-Dmpv.lib.path=${rootProject.projectDir.absolutePath}/lib/mpv-dev"

        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "WindPlayer"
            // Release pipeline passes -PpackageVersion from the git tag.
            packageVersion = (project.findProperty("packageVersion") as? String) ?: "0.1.0"
            windows {
                iconFile = file("${rootProject.projectDir.absolutePath}/icons/Launcher Icons/Windows/ic_launcher_round-multi-size-256x256.ico")
            }
        }
    }
}
