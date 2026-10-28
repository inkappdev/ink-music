// YouTube Music's own API, by hand (InnerTube.kt), and NewPipe as ink music uses it (Pipe.kt): plain Kotlin, no Android, so the phone's full build and the
// desktop share it. Never in the phone's play build (only "fullImplementation" takes it there).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// the phone's bytecode level, so its Kotlin can take this in
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }

dependencies {
    // Android has its own org.json; the desktop brings one
    compileOnly("org.json:json:20240303")
    // YouTube's streams and search (GPL-3.0)
    api("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
