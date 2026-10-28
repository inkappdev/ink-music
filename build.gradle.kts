plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; this only pins the Kotlin version it uses
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // the Mac app (desktop/): Kotlin on the JVM, Compose Multiplatform's window
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}
