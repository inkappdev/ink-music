import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// the release key, kept out of git: keystore.properties beside settings.gradle.kts (storeFile,
// storePassword, keyAlias, keyPassword; the key itself in ~/.config/ink-music, backed up apart).
// Without it release builds are signed as debug builds are (fine for a look, not for a release)
val release = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "app.ink.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.ink.music"
        minSdk = 30
        targetSdk = 37
        // each release: both up (the code by one), and a changelog in fastlane/.../changelogs/<code>.txt
        versionCode = 68
        versionName = "2.1.17"
        // ink weekly's weeks (tools/weekly.py writes them); -PweeklyUrl=... to try a week before it's pushed
        val weekly = (project.findProperty("weeklyUrl") as String?) ?: "https://inkhome.app/weekly.json"
        buildConfigField("String", "WEEKLY", "\"$weekly\"")
        // Last.fm's key and secret for ink music, kept out of git with the release key (a build without them has no Last.fm)
        buildConfigField("String", "LASTFM_KEY", "\"${release?.getProperty("lastfmKey").orEmpty()}\"")
        buildConfigField("String", "LASTFM_SECRET", "\"${release?.getProperty("lastfmSecret").orEmpty()}\"")
        // -Pfdroid: F-Droid's build, which F-Droid keeps up to date itself: no looking at GitHub for a new version
        buildConfigField("boolean", "UPDATER", "${!project.hasProperty("fdroid")}")
    }

    // the key only where it's kept (Fedora); Windows' keystore.properties holds Last.fm's alone, its builds signed as debug ones
    val signs = release?.getProperty("storeFile") != null
    signingConfigs {
        if (signs) create("release") {
            storeFile = file(release!!.getProperty("storeFile"))
            storePassword = release.getProperty("storePassword")
            keyAlias = release.getProperty("keyAlias")
            keyPassword = release.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // F-Droid's build left unsigned: F-Droid signs it with its own key
            signingConfig = when {
                signs -> signingConfigs.getByName("release")
                project.hasProperty("fdroid") -> null
                else -> signingConfigs.getByName("debug")
            }
            // -PbesideDebug: a release build that installs next to the debug one (its own id), to try R8's build
            if (project.hasProperty("besideDebug")) applicationIdSuffix = ".check"
        }
    }
    buildFeatures { compose = true; buildConfig = true }

    // ink music's own way to a USB DAC (app/src/main/cpp/usbaudio.c: plain C on Linux's usbfs)
    ndkVersion = "29.0.14206865"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }

    // full: every source, YouTube Music too (GitHub releases, IzzyOnDroid, Obtainium); play: no
    // YouTube, the only build that may ever go near the Play Console
    flavorDimensions += "sources"
    productFlavors {
        create("full") { dimension = "sources"; buildConfigField("boolean", "YOUTUBE", "true") }
        create("play") { dimension = "sources"; buildConfigField("boolean", "YOUTUBE", "false") }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // moving covers: Apple's are HLS (MovingCover)
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // what's behind a bar, softly blurred (Chris Banes' Haze, Apache-2.0; RenderEffect on Android 12+)
    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    // pairing a computer by its QR code (PairCamera): the camera by CameraX, the code read by ZXing's core (both
    // Apache-2.0, neither Play services)
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")
    // YouTube Music, the full build only (NewPipe's extractor, GPL-3.0): never in the play build
    "fullImplementation"("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    "fullImplementation"(project(":youtube"))
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // Android's own is a stub under unit tests
}
