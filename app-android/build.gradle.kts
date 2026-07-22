plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.windplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.windplayer"
        minSdk = 24
        targetSdk = 36
        // Release pipeline passes -PversionCode / -PversionName from the git tag.
        // Defaults are for local debug builds only.
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as? String) ?: "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    val releaseStoreFile = System.getenv("WINDPLAYER_KEYSTORE_FILE")
    val releaseStorePassword = System.getenv("WINDPLAYER_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("WINDPLAYER_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("WINDPLAYER_KEY_PASSWORD")
    if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
        jniLibs {
            // libplayer.so needed for Surface attachment
        }
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.14904198 rc1"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ui-compose"))
    implementation(project(":core-mpv"))
    implementation(project(":core-vfs"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.core.ktx)

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Whisper ASR engine (pure native C++ + JNI bridge, arm64-v8a only).
    implementation(files("libs/whisper-android.aar"))

    // Compose Multiplatform is 1.11.1; this Android app uses the AndroidX BOM
    // for its direct, versionless AndroidX Compose dependencies.
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
}
