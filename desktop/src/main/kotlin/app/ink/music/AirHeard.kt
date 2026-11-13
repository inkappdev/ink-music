package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a station's playing when it doesn't say (2026-11-12, Bronson: "some stations don't have the data and don't
 * display songs"): twelve seconds of its stream heard ([Recognize], Shazam's fingerprint, as a DJ set's are, a second
 * connection to the stream) and the song found said where the station's words would go, marked heard
 * ([OnAir.Said.heard]). Never while the station names its own songs; a show's name (NTS) kept beside what's heard.
 * Asked 20 s after a station starts saying nothing, again 2 min after a song's found (songs run three or four), 45 s
 * after a miss (talk, an ad, a jingle), and only every 3 min after four misses running (a talk station). A song heard
 * and then missed is let go, so one long over isn't still shown. Only what's on screen: never in your history, never
 * scrobbled (a wrong guess would be a listen you never had).
 */
object AirHeard {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start() {
        if (started || Local.on) return
        started = true
        scope.launch {
            var id: String? = null
            var next = 0L
            var misses = 0
            while (true) {
                delay(3_000)
                val n = Player.now.value
                val s = n?.song?.takeIf { it.station && n.playing }
                if (s == null) { if (n?.song?.station != true) id = null; continue }
                val now = System.currentTimeMillis()
                if (s.id != id) { id = s.id; next = now + 20_000; misses = 0 }
                val said = Player.onAir.value
                // the station says its own song: nothing to hear, and asked about again only once it stops
                if (said != null && !said.heard && !said.artist.isNullOrBlank()) { next = now + 20_000; misses = 0; continue }
                if (now < next) continue
                val found = runCatching { Recognize.at(Player.where(s), 0.0) }.getOrNull()
                val after = System.currentTimeMillis()
                // the station changed (or stopped) while it was heard: what was heard isn't this one's
                if (Player.now.value?.song?.id != id) continue
                val was = Player.onAir.value
                // the station began saying its song meanwhile: its word wins
                if (was != null && !was.heard && !was.artist.isNullOrBlank()) continue
                val show = was?.show ?: was?.takeIf { !it.heard && it.artist == null }?.title
                if (found != null) {
                    misses = 0
                    next = after + 120_000
                    Player.onAir.value = OnAir.Said(found.title, found.artist, heard = true, show = show)
                } else {
                    misses++
                    next = after + if (misses >= 4) 180_000 else 45_000
                    // what was heard before is likely over: back to the show's name, or nothing
                    if (was?.heard == true) Player.onAir.value = show?.let { OnAir.Said(it, null) }
                }
            }
        }
    }
}
