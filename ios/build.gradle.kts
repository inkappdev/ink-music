// ink music on an iPhone (2026-11-07, Bronson's own, sideloaded): Compose Multiplatform for the look, Apple's own
// AVFoundation for sound, the screens' Kotlin in a static framework that the small Xcode app (ios/app) wraps. Built only
// on a Mac (tools/build-ios.sh), and only with -Pios: the phone's and the computer's builds never see it.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { t ->
        t.binaries.framework { baseName = "InkMusic"; isStatic = false }
    }
    sourceSets {
        commonMain.dependencies {
            // what every ink music shares (core, made multiplatform): sync, picks, taste, scrobbling, podcasts...
            implementation(project(":core"))
            // YouTube Music's own API, by hand, and its streams (shared with the phone's full build and the computer)
            implementation(project(":youtube"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
}
