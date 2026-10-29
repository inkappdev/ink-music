import java.time.LocalDate
import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// ink music on a Mac (Linux and Windows later): the same look, the library from a folder and the
// server, sound by libmpv (bundled into the app by the GitHub Action: native/macos-arm64)
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// the phone's version for now (a Mac package's must start above 0)
version = "1.4.9"

kotlin { jvmToolchain(21) }

// the two fonts and ink radio's stations, the app's own files (never a copy that could drift); and the keys the build was given
val keys = layout.buildDirectory.dir("generated/keys")
sourceSets.main { resources.srcDir("../app/src/main/res/font"); resources.srcDir("../app/src/main/assets"); resources.srcDir(keys) }

// Last.fm's key and secret, from keystore.properties (here) or the environment (the GitHub Action's secrets),
// written for the app to read; never in git
val writeKeys by tasks.registering {
    val from = rootProject.file("keystore.properties")
    val out = keys.map { it.file("keys.properties") }
    val version = project.version.toString()
    // which build this is (the Action's commit, else "here"), said at the foot of settings: every build is 1.4.9 to macOS
    val build = (System.getenv("GITHUB_SHA")?.take(7) ?: "here") + " " + LocalDate.now()
    inputs.property("env", listOf(System.getenv("LASTFM_KEY").orEmpty(), System.getenv("LASTFM_SECRET").orEmpty()).joinToString())
    inputs.property("build", build)
    if (from.exists()) inputs.file(from)
    outputs.file(out)
    doLast {
        val p = Properties()
        if (from.exists()) from.inputStream().use { p.load(it) }
        val key = System.getenv("LASTFM_KEY") ?: p.getProperty("lastfmKey").orEmpty()
        val secret = System.getenv("LASTFM_SECRET") ?: p.getProperty("lastfmSecret").orEmpty()
        out.get().asFile.apply { parentFile.mkdirs() }.writeText("lastfmKey=$key\nlastfmSecret=$secret\nversion=$version\nbuild=$build\n")
    }
}
tasks.named("processResources") { dependsOn(writeKeys) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // libmpv's client API, called by hand (LGPL / Apache-2.0)
    implementation("net.java.dev.jna:jna:5.19.1")
    // a file's tags and cover (LGPL)
    implementation("net.jthink:jaudiotagger:3.0.1")
    // speakers found on the network (mDNS; Apache-2.0)
    implementation("org.jmdns:jmdns:3.6.4")
    implementation("org.json:json:20240303")
    // YouTube Music: its API by hand (the phone's InnerTube.kt, shared), its streams through NewPipe's extractor (GPL-3.0)
    implementation(project(":youtube"))
    implementation(project(":core"))
}

compose.desktop {
    application {
        mainClass = "app.ink.music.MainKt"
        jvmArgs += listOf("-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua")
        nativeDistributions {
            // each made on its own system (the Action's): the Mac's dmg, Windows' msi, Linux's deb and rpm
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "ink music"
            packageVersion = project.version.toString()
            description = "A music player in ink's look"
            vendor = "ink"
            licenseFile.set(rootProject.file("LICENSE"))
            // libmpv and what it needs, put there by the build (native/<os>-<arch>/), read at run time
            // from compose.application.resources.dir
            appResourcesRootDir.set(project.layout.projectDirectory.dir("native"))
            // what the app reaches for (Compose's suggestRuntimeModules), and the naming and sql jaudiotagger and JmDNS use
            modules("java.instrument", "java.net.http", "java.sql", "java.naming", "jdk.dynalink", "jdk.unsupported")
            windows {
                // installed for you alone (no administrator), in the start menu, its own upgrade id so a new one replaces the old
                perUserInstall = true
                menu = true
                shortcut = true
                menuGroup = "ink"
                upgradeUuid = "6f1c2a9e-3b7d-4e55-9a41-1c0d8e2f7b63"
                project.file("ink-music.ico").takeIf { it.exists() }?.let { iconFile.set(it) }
            }
            linux {
                // a package's name can't have a space
                packageName = "ink-music"
                debMaintainer = "hello@inkhome.app"
                menuGroup = "AudioVideo"
                appCategory = "Audio"
                project.file("src/main/resources/ink-music.png").takeIf { it.exists() }?.let { iconFile.set(it) }
            }
            macOS {
                bundleID = "app.ink.music"
                appCategory = "public.app-category.music"
                project.file("ink-music.icns").takeIf { it.exists() }?.let { iconFile.set(it) }
                // macOS 15 asks before an app reaches the home network (the server, the speakers); without this it
                // never asks, and every connection there fails. The speakers are found as Cast services
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>ink music plays the music on your own server, and on your speakers, on your network.</string>
                        <key>NSBonjourServices</key>
                        <array><string>_googlecast._tcp</string><string>_inkmusic._tcp</string></array>
                    """.trimIndent()
                }
            }
        }
    }
}
