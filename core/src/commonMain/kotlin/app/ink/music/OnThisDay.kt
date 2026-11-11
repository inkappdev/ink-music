package app.ink.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * What you played on this day in years past (2026-11-11), from your listening on Last.fm or ListenBrainz (by name: the
 * public listens): each year back, one to ten, its songs that day, a day being the 24 hours round this time of it then.
 * For listen's "on this day" mix.
 */
object OnThisDay {
    data class Year(val year: Int, val songs: List<Pair<String, String>>)

    private const val DAY = 86_400_000L

    /** Each year back with something played (the newest first), up to `songs` songs a year. */
    suspend fun read(b: Brought, lastFmKey: String, now: Long = nowMs(), songs: Int = 30): List<Year> = withContext(Dispatchers.IO) {
        (1..10).mapNotNull { back ->
            // the same date that many years ago (365.2425 days a year: the date drifts under a day)
            val mid = now - (back * 365.2425 * DAY).toLong()
            val from = (mid - DAY / 2) / 1000; val to = (mid + DAY / 2) / 1000
            val got = runCatching { if (b.from == "last.fm") lastFm(b.user, lastFmKey, from, to, songs) else listenBrainz(b.user, from, to, songs) }
                .getOrDefault(emptyList())
            if (got.isEmpty()) null else Year(yearOf(mid), got.distinct())
        }
    }

    private fun lastFm(user: String, key: String, from: Long, to: Long, n: Int): List<Pair<String, String>> {
        if (key.isEmpty()) return emptyList()
        val o = JSONObject(Curation.get("https://ws.audioscrobbler.com/2.0/?method=user.getrecenttracks&user=${urlEncode(user)}&from=$from&to=$to&limit=$n&api_key=$key&format=json"))
        val r = o.optJSONObject("recenttracks") ?: return emptyList()
        // one listen comes back as an object, more as a list
        val list = r.optJSONArray("track")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: listOfNotNull(r.optJSONObject("track"))
        return list.filter { it.optJSONObject("@attr")?.optString("nowplaying") != "true" }
            .map { (it.optJSONObject("artist")?.optString("#text").orEmpty()) to it.optString("name") }.filter { it.first.isNotBlank() && it.second.isNotBlank() }
    }

    private fun listenBrainz(user: String, from: Long, to: Long, n: Int): List<Pair<String, String>> {
        // asked back from the day's end (min_ts and max_ts can't both be given), what's before its start let go
        val text = Curation.get("https://api.listenbrainz.org/1/user/${urlEncode(user)}/listens?max_ts=$to&count=${n * 2}")
        val l = JSONObject(text).optJSONObject("payload")?.optJSONArray("listens") ?: return emptyList()
        return (0 until l.length()).map { l.getJSONObject(it) }.filter { it.optLong("listened_at") >= from }
            .map { it.optJSONObject("track_metadata").let { m -> m?.optString("artist_name").orEmpty() to m?.optString("track_name").orEmpty() } }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }.take(n)
    }

    /** The calendar year a moment falls in (UTC: near enough for "on this day, 2023"). */
    private fun yearOf(ms: Long): Int {
        var days = ms / DAY
        var y = 1970
        while (true) { val len = if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 366 else 365; if (days < len) return y; days -= len; y++ }
    }
}
