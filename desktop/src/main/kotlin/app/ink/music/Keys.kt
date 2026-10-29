package app.ink.music

/**
 * What the build was given and git never sees: Last.fm's key and secret for ink music (keystore.properties beside
 * the phone's, or the GitHub Action's secrets), written into the app as keys.properties. A build without them has
 * no Last.fm, as the phone's.
 */
object Keys {
    /** Which build this is: its commit and day ("36d3a01 2026-10-29"), shown at the foot of settings. */
    var build = ""
    /** This build's version, the phone's ("2.0.0"): what an update is newer than. */
    var version = ""

    fun start() {
        val p = java.util.Properties()
        runCatching { Keys::class.java.getResourceAsStream("/keys.properties")?.use { p.load(it) } }
        LastFm.key = p.getProperty("lastfmKey").orEmpty()
        LastFm.secret = p.getProperty("lastfmSecret").orEmpty()
        ListenBrainz.version = p.getProperty("version").orEmpty()
        build = p.getProperty("build").orEmpty()
        version = p.getProperty("version").orEmpty()
    }
}
