package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A DJ set's tracklist as it's heard, both apps' (2026-11-12, Bronson: only while you're looking, every track still
 * there): what to ask and when, and the list kept in the set's order. Live, the moment playing is asked again two
 * minutes after a track's found (a track runs three to six), 45 s after a miss. Not looked at (the screen off, the app
 * away), nothing's asked; looked at again, the stretch missed is asked over, every 90 s of it, so the list has no gaps:
 * a set's a recording, any moment of it can be heard after. A track lands where it played, the latest arrival [added].
 */
class SetTracks {
    data class Track(val title: String, val artist: String, val at: Long)

    /** Every track heard in the set, in the order they played. */
    val heard = MutableStateFlow<List<Track>>(emptyList())
    /** The last one found (typed in as it comes). */
    val added = MutableStateFlow<Track?>(null)

    private var set: String? = null
    /** The furthest place asked (ms in the set). */
    private var asked = -1L
    /** When the moment playing is next asked (wall clock). */
    private var nextLive = 0L

    /** Another set (or none): an empty list. */
    fun reset(id: String?, pos: Long = 0, now: Long = 0) {
        set = id; heard.value = emptyList(); added.value = null
        asked = pos; nextLive = now + 15_000
    }

    /**
     * The places to ask now, oldest first: the stretch missed since the furthest asked, every 90 s, then the moment
     * playing if it's time. None while not `looking`.
     */
    fun due(id: String, pos: Long, now: Long, looking: Boolean): List<Long> {
        if (set != id) reset(id, pos, now)
        if (!looking) return emptyList()
        // gone back in the set: asked from there on
        if (pos < asked - 5_000) asked = pos
        val out = ArrayList<Long>()
        if (pos - asked > 120_000) {
            var p = asked + 90_000
            while (p < pos - 20_000 && out.size < 40) { out += p; p += 90_000 }
        }
        if (now >= nextLive || out.isNotEmpty()) out += pos
        return out
    }

    /** What was heard at `at` (null: nothing known); `live`, the moment playing (its next ask set by what came of it). */
    fun found(id: String, at: Long, title: String?, artist: String?, now: Long, live: Boolean) {
        if (set != id) return
        if (at > asked) asked = at
        if (live) nextLive = now + if (title != null) 120_000 else 45_000
        if (title.isNullOrBlank()) return
        val a = artist.orEmpty()
        // the same track heard again near where it was: once
        if (heard.value.any { it.title.equals(title, true) && it.artist.equals(a, true) && kotlin.math.abs(it.at - at) < 600_000 }) return
        val t = Track(title, a, at)
        heard.value = (heard.value + t).sortedBy { it.at }
        added.value = t
    }

    /** The track playing at `pos`, as far as it's known: the last heard at or just after it. */
    fun at(pos: Long): Track? = heard.value.lastOrNull { it.at <= pos + 15_000 }
}
