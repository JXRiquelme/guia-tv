plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.jxr.aeriolite"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.jxr.aeriolite"
        minSdk = 21
        targetSdk = 35
        versionCode = 14
        versionName = "1.10"
        // ARM only (phones and Android TV boxes): keeps the FFmpeg part small
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        create("lite") {
            storeFile = rootProject.file("lite.keystore")
            storePassword = "aeriolite"
            keyAlias = "lite"
            keyPassword = "aeriolite"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("lite")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging { resources { excludes += "/META-INF/**" } }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    // FFmpeg audio decoders (AC-3, E-AC-3, MP2, DTS…) for channels without sound
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    // Google Cast (only used on phones/tablets; never started on Android TV)
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")
    implementation("androidx.mediarouter:mediarouter:1.6.0")
}
