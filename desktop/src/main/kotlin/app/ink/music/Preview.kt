package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Preview (2026-11-12, a tester, after Chosic's preview and foobar2000's foo_preview): a taste of each song and on to the
 * next, to get through more of a library or a playlist in the time one song takes. Each song from somewhere between a
 * fifth and seven tenths of the way in, chosen afresh for each (2026-11-17, the tester: always "about a minute in" was
 * too foreseeable), [secs] of it, then the next: through the transition chosen (Crossfade, its handover at the taste's
 * end, the next coming in at its own), else straight on. Off at the start of each run.
 */
object Preview {
    /** How long each song plays; 0, preview off. */
    val secs = MutableStateFlow(0)
    val CHOICES = listOf(10, 20, 30)
    @Volatile private var song: String? = null
    @Volatile private var from = 0L
    /** Where a song's next taste begins, chosen when it's first asked for (the transition into it and the player's
     *  seek agree on it) and let go once it plays: played again, it's from somewhere new (2026-11-18, the tester:
     *  a song gone back to began at the very same place, as if fixed to it). */
    private val starts = ConcurrentHashMap<String, Long>()

    fun set(n: Int) { secs.value = n; song = null; starts.clear() }

    /** Off, then each length in turn: the bar's word. */
    fun next() = set(listOf(0, *CHOICES.toTypedArray()).let { l -> l[(l.indexOf(secs.value) + 1) % l.size] })

    /** Where `id`'s taste begins (ms), for a song this long. */
    fun startOf(id: String, duration: Long): Long = starts.getOrPut(id) { fresh(duration) }

    private fun fresh(duration: Long): Long {
        val n = secs.value * 1000L
        val at = (duration * (.2 + Math.random() * .5)).toLong()
        return at.coerceAtMost(duration - n - 3_000).coerceAtLeast(0)
    }

    /** The playing song's taste's end, where the transition hands over (null: preview off, or a station). */
    fun endOf(s: Song, duration: Long): Long? {
        val n = secs.value
        if (n <= 0 || s.station || duration <= 0) return null
        return (if (song == s.id) from else startOf(s.id, duration)) + n * 1000L
    }

    /** The player's position as it goes (its own thread): a new song sent to its taste, a song heard long enough left. */
    fun tick(s: Song, ms: Long, duration: Long, paused: Boolean) {
        val n = secs.value
        if (n <= 0 || s.station || duration <= 0) return
        if (song != s.id) {
            song = s.id
            from = starts.remove(s.id) ?: fresh(duration)
            // come in by a transition, it's there already
            if (ms < from - 1_000) Player.seek(from)
            return
        }
        // a transition hands over at the taste's end; a moment more given it before going on without one
        val grace = if (Crossfade.mode.value != Crossfade.OFF) 2_500L else 0L
        if (!paused && ms - from >= n * 1000L + grace) { song = "done:" + s.id; Player.next() }
    }
}
