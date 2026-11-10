package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The computer's sleep timer (2026-11-10; the phone's had one, and a Reddit user asked of QuietPlay's): in so many
 * minutes, or at the song's end, the music fades out over ten seconds and pauses, its volume put back for the morning.
 */
object Sleep {
    const val END_OF_SONG = -1L
    /** When it ends (wall clock ms), END_OF_SONG, or 0: off. */
    val at = MutableStateFlow(0L)
    val CHOICES = listOf(15, 30, 45, 60, 90)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun set(minutes: Int?) {
        job?.cancel()
        at.value = 0L
        if (minutes == null) return
        if (minutes == 0) {
            at.value = END_OF_SONG
            job = scope.launch {
                // the next song begun (or the queue ended): paused at its start, the song before heard whole
                val was = Player.now.value?.index
                Player.now.map { it?.index }.distinctUntilChanged().drop(1).first { it != was }
                if (Player.now.value?.playing == true) Player.toggle()
                Player.seek(0)
                at.value = 0L
            }
            return
        }
        at.value = System.currentTimeMillis() + minutes * 60_000L
        job = scope.launch {
            delay(minutes * 60_000L - FADE)
            fadeOut()
            at.value = 0L
        }
    }

    private const val FADE = 10_000L

    /** Ten seconds down to nothing, paused, the volume back where it was. */
    private suspend fun fadeOut() {
        val v = Player.volume.value
        if (Player.now.value?.playing == true) {
            for (i in 1..50) { level(v * (50 - i) / 50); delay(FADE / 50) }
            Player.toggle()
        }
        delay(300)
        level(v)
    }

    /** The sound's level for the fade alone (not kept, not shown); on a speaker, or mid-blend, left be. */
    private fun level(v: Int) { if (Player.mixable() && !Crossfade.fading) Player.active()?.set("volume", v.coerceIn(0, 100).toString()) }

    /** One word's turn through them (the now column's): off, 15, 30, 60 minutes, the song's end, off. */
    fun next() = when (val a = at.value) {
        0L -> set(15)
        END_OF_SONG -> set(null)
        else -> when (((a - System.currentTimeMillis()) / 60_000).toInt()) {
            in 0..14 -> set(30)
            in 15..29 -> set(60)
            else -> set(0)
        }
    }

    /** As said in the menu: "sleep", "sleep · 23 min", "sleep · song's end". */
    fun said(now: Long = System.currentTimeMillis()): String = when (val a = at.value) {
        0L -> "sleep"
        END_OF_SONG -> "sleep · song's end"
        else -> "sleep · ${((a - now + 59_999) / 60_000).coerceAtLeast(1)} min"
    }
}
