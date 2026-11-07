// YouTube Music's own API, by hand (InnerTube.kt, shared: the phone's full build, the computer and the iPhone), and
// NewPipe as ink music uses it (Pipe.kt: the JVM's alone). Never in the phone's play build (only
// "fullImplementation" takes it there). Multiplatform since 2026-11-07, as core is.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val ios = providers.gradleProperty("ios").orNull != null

kotlin {
    // the phone's bytecode level, so its Kotlin can take this in
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    jvmToolchain(21)
    if (ios) { iosArm64(); iosSimulatorArm64() }
    sourceSets {
        commonMain.dependencies {
            // the shared JSON, network and hashes
            implementation(project(":core"))
        }
        jvmMain.dependencies {
            // Android has its own org.json; the desktop brings one
            compileOnly("org.json:json:20240303")
            // YouTube's streams and search (GPL-3.0)
            api("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
        }
    }
}

java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
