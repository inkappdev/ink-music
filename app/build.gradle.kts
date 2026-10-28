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
        minSdk = 31
        targetSdk = 37
        // each release: both up (the code by one), and a changelog in fastlane/.../changelogs/<code>.txt
        versionCode = 41
        versionName = "1.4.6"
        // ink weekly's weeks (tools/weekly.py writes them); -PweeklyUrl=... to try a week before it's pushed
        val weekly = (project.findProperty("weeklyUrl") as String?) ?: "https://raw.githubusercontent.com/inkappdev/ink-music/main/weekly.json"
        buildConfigField("String", "WEEKLY", "\"$weekly\"")
        // Last.fm's key and secret for ink music, kept out of git with the release key (a build without them has no Last.fm)
        buildConfigField("String", "LASTFM_KEY", "\"${release?.getProperty("lastfmKey").orEmpty()}\"")
        buildConfigField("String", "LASTFM_SECRET", "\"${release?.getProperty("lastfmSecret").orEmpty()}\"")
    }

    signingConfigs {
        if (release != null) create("release") {
            storeFile = file(release.getProperty("storeFile"))
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
            signingConfig = signingConfigs.getByName(if (release != null) "release" else "debug")
            // -PbesideDebug: a release build that installs next to the debug one (its own id), to try R8's build
            if (project.hasProperty("besideDebug")) applicationIdSuffix = ".check"
        }
    }
    buildFeatures { compose = true; buildConfig = true }

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
    implementation("androidx.media3:media3-session:1.11.1")
    // what's behind a bar, softly blurred (Chris Banes' Haze, Apache-2.0; RenderEffect on Android 12+)
    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    // YouTube Music, the full build only (NewPipe's extractor, GPL-3.0): never in the play build
    "fullImplementation"("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // Android's own is a stub under unit tests
}
