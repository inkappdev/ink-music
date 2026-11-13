package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Preview (2026-11-12, a tester, after Chosic's preview and foobar2000's foo_preview): a taste of each song and on to the
 * next, to get through more of a library or a playlist in the time one song takes. Each song from about a third of the
 * way in (past the intro, where the hook usually is), [secs] of it, then the next. Off at the start of each run.
 */
object Preview {
    /** How long each song plays; 0, preview off. */
    val secs = MutableStateFlow(0)
    val CHOICES = listOf(10, 20, 30)
    @Volatile private var song: String? = null
    @Volatile private var from = 0L

    fun set(n: Int) { secs.value = n; song = null }

    /** The player's position as it goes (its own thread): a new song sent to its middle, a song heard long enough left. */
    fun tick(s: Song, ms: Long, duration: Long, paused: Boolean) {
        val n = secs.value
        if (n <= 0 || s.station || duration <= 0) return
        if (song != s.id) {
            song = s.id
            from = (duration * .35).toLong().coerceAtMost(duration - n * 1000L - 3_000).coerceAtLeast(0)
            if (ms < from - 1_000) Player.seek(from)
            return
        }
        if (!paused && ms - from >= n * 1000L) { song = "done:" + s.id; Player.next() }
    }
}
