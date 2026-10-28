pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipe Extractor (the full build's YouTube Music) is published only there
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "ink-music"
// -PdesktopOnly: the Mac app alone (its GitHub Action has no Android SDK to configure the phone's)
if (providers.gradleProperty("desktopOnly").orNull == null) include(":app")
include(":desktop")
