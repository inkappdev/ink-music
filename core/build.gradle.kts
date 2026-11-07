// What the phone, the computer and the iPhone share that isn't YouTube's: plain Kotlin, no Android. Multiplatform since
// 2026-11-07 (the iPhone): what's the same everywhere in commonMain, what leans on Java in jvmMain (the phone's and the
// computer's, exactly as before), the iPhone's own in iosMain. Each app hands it a folder for its files.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// the iPhone's targets only where asked (-Pios, on a Mac): every other build stays the JVM's alone
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
            // dates and times everywhere (the iPhone has no java.time)
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
        }
        jvmMain.dependencies {
            // each app brings its own (the phone 1.9, the desktop with Compose): the JVM only compiles against it
            compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        if (ios) iosMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") }
        jvmMain.dependencies {
            // Android has its own org.json; the desktop brings one
            compileOnly("org.json:json:20240303")
        }
    }
}

java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
