package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Local files only (settings, 2026-11-10; a Reddit report: near 1 GB, and a player kept to local files "works wonders"):
 * your files, your own server, lyrics and your phone kept; YouTube Music, INK's picks and charts, radio, podcasts,
 * music videos, live sets, Discord and the catalogs' covers never started, the network left alone, the INK, radio and
 * podcasts places not in the rail. Read once as ink music opens: everything online is held back where it starts
 * (each one's `if (Local.on) return`), so it takes the whole way the next time it opens.
 */
object Local {
    private val file = File(Home.dir, "local-only")

    /** This run's: read as ink music opened. */
    val on: Boolean = file.exists()

    /** As chosen in settings, the next run's. */
    val wanted = MutableStateFlow(on)

    fun choose(to: Boolean) {
        wanted.value = to
        runCatching { if (to) file.writeText("") else file.delete() }
    }
}
