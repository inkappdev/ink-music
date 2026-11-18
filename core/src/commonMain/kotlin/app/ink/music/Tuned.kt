package app.ink.music

import kotlin.math.exp
import kotlin.math.min

/**
 * What TUNER heard from you (2026-11-18, Bronson: scroll through music videos and tune your taste by liking, saying
 * not for me, or just scrolling by): each clip's verdict, kept in `file`. Watched a while, its artist nearer (a little,
 * fading over two months); liked, nearer still (the like is a love too, elsewhere); swiped past in its first seconds,
 * a skip, as a song left at once is. Not for me goes where every not for me goes. A clip once shown isn't again for
 * a month. The taste's made with it (Fresh.build): brought in as "tuner", the skips with the player's.
 */
class Tuned(private val file: File) {
    /** Weakest to strongest: shown only, swiped past at once, watched a while, liked, not for me. */
    enum class Kind { SEEN, PASSED, KEPT, LIKED, NOPE }
    /** A clip heard: its id, artist, verdict, when, and the source it came from (TunerMix's). */
    data class Heard(val id: String, val artist: String, val kind: Kind, val at: Long, val from: String = "")

    private val guard = Guard()
    private val all = ArrayList<Heard>()

    init {
        runCatching {
            val a = JSONArray(file.readText())
            val old = nowMs() - 120 * DAY
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val h = Heard(o.getString("id"), o.getString("artist"), Kind.valueOf(o.getString("kind")), o.getLong("at"), o.optString("from"))
                if (h.at > old) all += h
            }
        }
    }

    /** A clip's verdict (the strongest kept), written down. */
    fun heard(id: String, artist: String, kind: Kind, from: String = "") = guard.run {
        val was = all.indexOfFirst { it.id == id }
        if (was >= 0 && all[was].kind.ordinal >= kind.ordinal && kind != Kind.NOPE) return@run
        val source = from.ifEmpty { if (was >= 0) all[was].from else "" }
        if (was >= 0) all.removeAt(was)
        all += Heard(id, artist, kind, nowMs(), source)
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("id", it.id).put("artist", it.artist).put("kind", it.kind.name).put("at", it.at).put("from", it.from)) }
        runCatching { file.getParentFile()?.mkdirs(); file.writeText(a.toString()) }
    }

    /** Shown in the last month (not shown again). */
    fun shown(id: String): Boolean = guard.run { all.any { it.id == id && nowMs() - it.at < 30 * DAY } }

    /** The artists it brought nearer, as a source the taste takes in (null: none yet). */
    fun brought(now: Long = nowMs()): Brought? = guard.run {
        val near = HashMap<String, Double>()
        for (h in all) {
            val w = when (h.kind) { Kind.KEPT -> .2; Kind.LIKED -> .4; else -> continue }
            near[h.artist] = min(.85, (near[h.artist] ?: 0.0) + w * exp(-(now - h.at).coerceAtLeast(0) / DAY.toDouble() / 60.0))
        }
        if (near.isEmpty()) null else Brought("tuner", "", now, near, emptyList())
    }

    /**
     * Each source's share for TunerMix: where it started, times how it's done with you (likes count twice, a clip
     * watched once, against swipes past, a not for me twice, a clip only seen a little), two of each assumed so a few
     * clips don't swing it; never under a tenth nor over three times.
     */
    fun shares(start: Map<String, Double>): Map<String, Double> = guard.run {
        start.mapValues { (from, base) ->
            val mine = all.filter { it.from == from }
            fun n(k: Kind) = mine.count { it.kind == k }.toDouble()
            val good = 2 * n(Kind.LIKED) + n(Kind.KEPT) + 2
            val bad = n(Kind.PASSED) + 2 * n(Kind.NOPE) + .3 * n(Kind.SEEN) + 2
            base * (good / bad).coerceIn(.1, 3.0)
        }
    }

    /** The clips swiped past at once, as skips. */
    fun skips(): List<Taste.Play> = guard.run { all.filter { it.kind == Kind.PASSED }.map { Taste.Play(it.artist, it.at, 2_000) } }

    private companion object { const val DAY = 86_400_000L }
}
