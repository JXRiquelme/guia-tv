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
        versionCode = 17
        versionName = "1.13"
        // ARM only (phones and Android TV boxes): keeps the FFmpeg part small
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildFeatures { buildConfig = true }

    // Two apps from the same code: "tv" stays light (no Cast, no PiP, starts on Canal 13);
    // "movil" is for phones/tablets (Cast, PiP, remembers the last channel, richer design).
    // Same package name, so each one updates the copy already installed on its device.
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            buildConfigField("boolean", "TV", "true")
        }
        create("movil") {
            dimension = "device"
            buildConfigField("boolean", "TV", "false")
        }
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
    // Google Cast: phone/tablet app only (the TV app doesn't include it)
    "movilImplementation"("com.google.android.gms:play-services-cast-framework:21.4.0")
    "movilImplementation"("androidx.mediarouter:mediarouter:1.6.0")
}
