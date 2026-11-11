package app.ink.music

import kotlin.math.pow

/**
 * Your listening elsewhere, brought in (2026-11-11: someone new was a stranger to ink music, however many years they'd
 * played): from Last.fm or ListenBrainz, read by your name alone, each artist a strength from 0 to 1, and your most
 * played songs. Kept as a file each, so [Taste] counts them from the first day. `from` is said in the picks' reasons
 * ("from your last.fm").
 */
data class Brought(val from: String, val user: String, val at: Long, val artists: Map<String, Double>, val songs: List<Pair<String, String>>) {
    companion object {
        /**
         * Each range's artists with their play counts, and the most that range may say (all time .85, the year .95, the
         * month all of it): an artist as near as its share of that range's top's plays, to the 0.8 (the twentieth most
         * played still near, the hundredth faint), the strongest range wins; a song of theirs loved, .15 at least (counted,
         * not near on its own). The short ranges asked for their top few only (a month's tenth artist, played three
         * times, isn't yours). (First a square root and .55 for a loved song: 183 of 201 artists counted near, one loved
         * song as much as weekly plays; then .3 and whole ranges: 136 of 275, 2026-11-11.)
         */
        fun weigh(ranges: List<Pair<List<Pair<String, Double>>, Double>>, loved: List<String>): Map<String, Double> {
            val out = HashMap<String, Double>()
            for ((counts, most) in ranges) {
                val top = counts.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: continue
                for ((name, n) in counts) if (name.isNotBlank() && n > 0) out[name] = maxOf(out[name] ?: 0.0, most * (n / top).pow(.8))
            }
            for (name in loved) if (name.isNotBlank()) out[name] = maxOf(out[name] ?: 0.0, .15)
            return out
        }

        fun read(f: File, from: String): Brought? = runCatching {
            val o = JSONObject(f.readText())
            val a = o.getJSONObject("artists")
            val artists = HashMap<String, Double>()
            a.keys().forEach { artists[it] = a.getDouble(it) }
            val s = o.optJSONArray("songs")
            val songs = (0 until (s?.length() ?: 0)).map { i -> s!!.getJSONArray(i).let { it.getString(0) to it.getString(1) } }
            // one kept before the weighing changed (no "from" in it) counts as old: brought in again at once
            Brought(o.optString("from", from), o.getString("user"), if (o.has("from")) o.getLong("at") else 0L, artists, songs)
        }.getOrNull()

        fun write(f: File, b: Brought) {
            val a = JSONObject(); b.artists.forEach { (k, v) -> a.put(k, v) }
            val s = JSONArray(); b.songs.forEach { (ar, ti) -> s.put(JSONArray().put(ar).put(ti)) }
            runCatching { Keep.write(f, JSONObject().put("from", b.from).put("user", b.user).put("at", b.at).put("artists", a).put("songs", s).toString()) }
        }
    }
}
