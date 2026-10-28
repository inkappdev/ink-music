// What the phone and the desktop share that isn't YouTube's (that's the youtube module): plain Kotlin, no Android,
// in every build of the phone, play's too. Each app hands it a folder for its files.
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
    // each app brings its own (the phone 1.9, the desktop with Compose): this only compiles against it
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
