package app.ink.music

/**
 * What an artist on tour is playing (2026-11-24): the songs that come up most in their latest setlists (the last ten
 * shows with songs listed), from setlist.fm's API (its key in the build; blank: never asked, nothing shown). Its terms:
 * non-commercial use only, its attribution shown wherever its data is (the link each answer carries: the artist's
 * setlists page), kept a short while only (a day here). One request at a time, over half a second apart (its keys
 * are held to two a second and 1,440 a day; fewer than that asked here in a day). The artist by their MusicBrainz id
 * ([Socials.id]). A song played from tape isn't counted (it wasn't live); a cover keeps whose song it is. Blocking.
 */
object Setlists {
    /** setlist.fm's API key (the build's; blank: not asked). */
    @Volatile var key = ""
    /** Told what was asked and how it went (the apps' logs). */
    @Volatile var trace: ((String) -> Unit)? = null

    /** One song as a setlist has it: its name, and whose it is when it's a cover. */
    data class Sung(val title: String, val cover: String? = null)
    /** One show: its day (yyyy-MM-dd) and the songs played, in order. */
    data class Show(val day: String, val songs: List<Sung>)
    /** A page of setlists: the shows, and the link to credit (the artist's page on setlist.fm). */
    data class Page(val shows: List<Show>, val credit: String)
    /** A song they're playing: how many of the shows it was in. */
    data class Played(val title: String, val times: Int, val cover: String? = null)
    /** What they're playing: the songs, the most played first; out of how many shows, the latest's day; the credit. */
    data class Heard(val songs: List<Played>, val shows: Int, val latest: String, val credit: String) {
        fun json(): JSONObject = JSONObject().put("shows", shows).put("latest", latest).put("credit", credit)
            .put("songs", JSONArray(songs.map { s -> JSONObject().put("title", s.title).put("times", s.times).apply { s.cover?.let { put("cover", it) } } }))

        companion object {
            fun of(o: JSONObject): Heard? = runCatching {
                val a = o.getJSONArray("songs")
                Heard((0 until a.length()).map { a.getJSONObject(it) }.map { Played(it.getString("title"), it.getInt("times"), it.optString("cover").ifEmpty { null }) },
                    o.getInt("shows"), o.optString("latest"), o.optString("credit").ifEmpty { HOME })
            }.getOrNull()
        }
    }

    const val HOME = "https://www.setlist.fm"
    private const val API = "https://api.setlist.fm/rest/1.0"
    private const val DAY = 24L * 3600 * 1000
    private val gate = Signal()
    private var last = 0L

    /** The request for an artist's setlists, newest first (twenty to a page). */
    fun request(mbid: String, page: Int = 1): String = "$API/artist/${urlEncode(mbid)}/setlists?p=$page"

    /** An answer read: each show with songs (an upcoming one, or one nobody has filled in yet, left out), newest first. */
    fun parse(text: String): Page {
        val o = JSONObject(text)
        val lists = o.optJSONArray("setlist") ?: JSONArray()
        var credit = ""
        val shows = (0 until lists.length()).mapNotNull { lists.optJSONObject(it) }.mapNotNull { s ->
            if (credit.isEmpty()) credit = s.optJSONObject("artist")?.optString("url").orEmpty()
            // the API's sets sit in "sets" { "set": [...] }; its docs' example has them as "set" right on the setlist
            val sets = s.optJSONObject("sets")?.optJSONArray("set") ?: s.optJSONArray("set") ?: return@mapNotNull null
            val songs = (0 until sets.length()).mapNotNull { sets.optJSONObject(it)?.optJSONArray("song") }.flatMap { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .filter { !it.optBoolean("tape") && it.optString("name").isNotBlank() }
                    .map { Sung(it.optString("name").trim(), it.optJSONObject("cover")?.optString("name")?.ifBlank { null }) }
            }
            if (songs.isEmpty()) null else Show(dayOf(s.optString("eventDate")), songs)
        }
        return Page(shows, credit.ifEmpty { HOME })
    }

    /** setlist.fm's dd-MM-yyyy as yyyy-MM-dd (as it was, if it isn't that). */
    private fun dayOf(d: String): String {
        val p = d.split('-')
        return if (p.size == 3 && p[2].length == 4) "${p[2]}-${p[1].padStart(2, '0')}-${p[0].padStart(2, '0')}" else d
    }

    /**
     * The songs that come up most in the latest `shows` shows: each counted once a show, the most played first; a tie,
     * the one played earlier in the set first (on average), as a set runs. At most `most`.
     */
    fun rank(page: Page, shows: Int = 10, most: Int = 20): Heard? {
        val latest = page.shows.sortedByDescending { it.day }.take(shows)
        if (latest.isEmpty()) return null
        class Count(val title: String, val cover: String?) { var times = 0; var at = 0.0 }
        val counts = LinkedHashMap<String, Count>()
        for (show in latest) {
            val seen = HashSet<String>()
            show.songs.forEachIndexed { n, s ->
                val k = Search.fold(s.title)
                if (k.isEmpty() || !seen.add(k)) return@forEachIndexed
                val c = counts.getOrPut(k) { Count(s.title, s.cover) }
                c.times++
                // where in the set, 0 the first, 1 the last
                c.at += if (show.songs.size > 1) n.toDouble() / (show.songs.size - 1) else 0.0
            }
        }
        val songs = counts.values.sortedWith(compareByDescending<Count> { it.times }.thenBy { it.at / it.times })
            .take(most).map { Played(it.title, it.times, it.cover) }
        return Heard(songs, latest.size, latest.first().day, page.credit)
    }

    /**
     * What `mbid` is playing: kept a day in `dir`, else asked; a failure, what was kept if anything (else null), asked
     * again next time. Null too with no key.
     */
    fun of(dir: File, mbid: String): Heard? {
        if (key.isBlank() || mbid.isBlank()) return null
        val f = File(dir, "setlists-" + sha1(mbid.encodeToByteArray()).hex() + ".json")
        val kept = if (f.exists()) runCatching { f.readText() }.getOrNull() else null
        if (kept != null && nowMs() - f.lastModified() < DAY) return if (kept.isBlank()) null else runCatching { Heard.of(JSONObject(kept)) }.getOrNull()
        val stale = kept?.takeIf { it.isNotBlank() }?.let { runCatching { Heard.of(JSONObject(it)) }.getOrNull() }
        val r = runCatching { ask(request(mbid)) }.getOrElse { trace?.invoke("$mbid: $it"); return stale }
        // none listed (404): nothing to show, kept so for the day
        if (r.code == 404) { runCatching { dir.mkdirs(); f.writeText("") }; return null }
        if (r.code != 200) { trace?.invoke("$mbid: ${r.code}"); return stale }
        val heard = runCatching { rank(parse(r.text)) }.getOrElse { trace?.invoke("$mbid: unread $it"); return stale }
        trace?.invoke("$mbid: ${heard?.songs?.size ?: 0} songs from ${heard?.shows ?: 0} shows")
        runCatching { dir.mkdirs(); f.writeText(heard?.json()?.toString().orEmpty()) }
        return heard
    }

    /** One request, 600 ms after the last at least (two a second is setlist.fm's most), one at a time. */
    private fun ask(url: String): Reply = gate.run {
        while (true) { val wait = last + 600 - nowMs(); if (wait <= 0) break; gate.await(wait) }
        try {
            http(url, headers = mapOf("x-api-key" to key, "Accept" to "application/json", "User-Agent" to AGENT), connectMs = 8_000, readMs = 15_000)
        } finally { last = nowMs() }
    }
}
