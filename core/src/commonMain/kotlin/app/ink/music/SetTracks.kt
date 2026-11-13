package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A DJ set's tracklist as it's heard, both apps' (2026-11-12, Bronson: only while you're looking, every track still
 * there): what to ask and when, and the list kept in the set's order. Live, the moment playing is asked again two
 * minutes after a track's found (a track runs three to six), 45 s after a miss. Not looked at (the screen off, the app
 * away), nothing's asked; looked at again, the stretch missed is asked over, every 90 s of it, so the list has no gaps:
 * a set's a recording, any moment of it can be heard after. A track lands where it played, the latest arrival [added].
 * Each set's list kept in `dir` (sets-heard.json, the last 40): back to a set, its sleeve as it was, and on from the
 * furthest asked; a set never heard, begun partway, asked from its start.
 */
class SetTracks(var dir: File? = null) {
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

    /** Another set (or none): its list as kept, asked on from the furthest it was; a new one from its start. */
    fun reset(id: String?, pos: Long = 0, now: Long = 0) {
        set = id; added.value = null; nextLive = now + 15_000
        val kept = id?.let { keptOf(it) }
        heard.value = kept?.second.orEmpty()
        asked = kept?.first ?: 0L
    }

    private val file get() = dir?.let { File(it, "sets-heard.json") }

    private fun all(): JSONObject = runCatching { JSONObject(file!!.readText()) }.getOrDefault(JSONObject())

    private fun keptOf(id: String): Pair<Long, List<Track>>? = runCatching {
        val o = all().optJSONObject(id) ?: return null
        val a = o.getJSONArray("tracks")
        o.getLong("asked") to (0 until a.length()).map { a.getJSONObject(it) }.map { Track(it.getString("t"), it.getString("a"), it.getLong("at")) }
    }.getOrNull()

    /** This set's list written down, the oldest sets let go past 40. */
    private fun keep() {
        val id = set ?: return
        val f = file ?: return
        runCatching {
            val o = all()
            o.put(id, JSONObject().put("asked", asked).put("when", nowMs())
                .put("tracks", JSONArray().apply { heard.value.forEach { put(JSONObject().put("t", it.title).put("a", it.artist).put("at", it.at)) } }))
            val ids = o.keys().asSequence().toList()
            if (ids.size > 40) ids.sortedBy { o.optJSONObject(it)?.optLong("when") ?: 0L }.take(ids.size - 40).forEach { o.remove(it) }
            dir?.mkdirs(); f.writeText(o.toString())
        }
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

    /**
     * What was heard at `at` (null: nothing known); `live`, the moment playing (its next ask set by what came of it).
     * The track, if it's one new to this set's list (the apps keep every one in a list of their own).
     */
    fun found(id: String, at: Long, title: String?, artist: String?, now: Long, live: Boolean): Track? {
        if (set != id) return null
        if (at > asked) asked = at
        if (live) nextLive = now + if (title != null) 120_000 else 45_000
        if (title.isNullOrBlank()) { keep(); return null }
        val a = artist.orEmpty()
        // the same track heard again near where it was: once
        if (heard.value.any { it.title.equals(title, true) && it.artist.equals(a, true) && kotlin.math.abs(it.at - at) < 600_000 }) return null
        val t = Track(title, a, at)
        heard.value = (heard.value + t).sortedBy { it.at }
        added.value = t
        keep()
        return t
    }

    /** The track playing at `pos`, as far as it's known: the last heard at or just after it. */
    fun at(pos: Long): Track? = heard.value.lastOrNull { it.at <= pos + 15_000 }
}
