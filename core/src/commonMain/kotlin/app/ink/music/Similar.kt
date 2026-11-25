package app.ink.music

import kotlin.concurrent.Volatile

/**
 * Who and what's near a song, from more than one ear (2026-11-12, Bronson: radio as good as Chosic's suggestions): Deezer's
 * radio for an artist (its own mix of them and their kin) and their top songs, and Last.fm's similar artists (listeners'
 * habits, the strongest signal of the lot; its similar *tracks* come back empty now, measured the same day). Each a
 * title and artist with how strongly it's meant; radio blends them with YouTube Music's own. Blocking; quiet on failure.
 */
object Similar {
    data class Track(val title: String, val artist: String, val weight: Double)

    private val deezerIds = HashMap<String, Long?>()

    /** Deezer's id for an artist, by name (the first whose name is theirs). */
    private fun deezerId(artist: String): Long? {
        val k = artist.lowercase().trim()
        synchronized(deezerIds) { if (deezerIds.containsKey(k)) return deezerIds[k] }
        val id = runCatching {
            val a = JSONObject(Curation.get("https://api.deezer.com/search/artist?q=${urlEncode(artist)}&limit=5")).optJSONArray("data") ?: return@runCatching null
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { Curation.folded(it.optString("name")) == Curation.folded(artist) }?.optLong("id")
        }.getOrNull()
        synchronized(deezerIds) { deezerIds[k] = id }
        return id
    }

    private fun tracks(url: String, weight: (Int, Int) -> Double): List<Track> = runCatching {
        val a = JSONObject(Curation.get(url)).optJSONArray("data") ?: return emptyList()
        (0 until a.length()).map { a.getJSONObject(it) }.mapIndexedNotNull { i, t ->
            val title = t.optString("title").ifBlank { return@mapIndexedNotNull null }
            val who = t.optJSONObject("artist")?.optString("name").orEmpty().ifBlank { return@mapIndexedNotNull null }
            Track(title, who, weight(i, a.length()))
        }
    }.getOrDefault(emptyList())

    /** Deezer's radio for an artist: their songs and their kin's, in its order (the first meant most). */
    fun deezerRadio(artist: String, limit: Int = 25): List<Track> {
        val id = deezerId(artist) ?: return emptyList()
        return tracks("https://api.deezer.com/artist/$id/radio?limit=$limit") { i, n -> 1.0 - i.toDouble() / (n + 1) }
    }

    /** An artist's best-known songs on Deezer. */
    fun deezerTop(artist: String, limit: Int = 3): List<Track> {
        val id = deezerId(artist) ?: return emptyList()
        return tracks("https://api.deezer.com/artist/$id/top?limit=$limit") { i, n -> 1.0 - i.toDouble() / (n + 1) }
    }

    /** Last.fm's artists most like this one, each with how alike (0 to 1); none without a key. */
    fun lastFmArtists(artist: String, key: String, limit: Int = 10): List<Pair<String, Double>> {
        if (key.isBlank()) return emptyList()
        return runCatching {
            val a = JSONObject(Curation.get("https://ws.audioscrobbler.com/2.0/?method=artist.getsimilar&artist=${urlEncode(artist)}&autocorrect=1&limit=$limit&api_key=$key&format=json"))
                .optJSONObject("similarartists")?.optJSONArray("artist") ?: return emptyList()
            (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("name") to (it.optString("match").toDoubleOrNull() ?: 0.0) }.filter { it.first.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /**
     * Last.fm's songs most like this one (title, artist, how alike 0 to 1; 2026-11-25, FREQUENCY finding the new in your
     * own library: local files have no similar of their own); none without a key.
     */
    fun lastFmTracks(title: String, artist: String, key: String, limit: Int = 60): List<Track> {
        if (key.isBlank() || title.isBlank() || artist.isBlank()) return emptyList()
        return runCatching {
            val a = JSONObject(Curation.get("https://ws.audioscrobbler.com/2.0/?method=track.getsimilar&track=${urlEncode(title)}&artist=${urlEncode(artist)}&autocorrect=1&limit=$limit&api_key=$key&format=json"))
                .optJSONObject("similartracks")?.optJSONArray("track") ?: return emptyList()
            (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { t ->
                val name = t.optString("name"); val who = t.optJSONObject("artist")?.optString("name").orEmpty()
                if (name.isBlank() || who.isBlank()) null else Track(name, who, t.optString("match").toDoubleOrNull() ?: 0.0)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Where ListenBrainz's answers and the artists' MusicBrainz ids are kept (each app's cache folder, told at start);
     * none told, ListenBrainz isn't asked.
     */
    @Volatile var dir: File? = null

    private const val LABS = "https://labs.api.listenbrainz.org/similar-artists/json"
    /** ListenBrainz's own pick of its algorithms (listening sessions over twenty years), as its artist pages use. */
    private const val ALGORITHM = "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"
    private const val DAY = 24L * 3600 * 1000
    private val labsGate = Any()

    /**
     * ListenBrainz's artists most like this one (2026-11-24: a second opinion beside Last.fm's, from what its listeners
     * play in one sitting), each with how alike (0 to 1, the nearest 1); by the artist's MusicBrainz id (Socials.id).
     * Kept a week an artist; asked one at a time. None without a folder, an id, or an answer.
     */
    fun listenBrainzArtists(artist: String, limit: Int = 10): List<Pair<String, Double>> {
        val d = dir ?: return emptyList()
        val id = Socials.id(d, artist) ?: return emptyList()
        val f = File(d, "lb-similar-$id")
        val kept = if (f.exists() && nowMs() - f.lastModified() < 7 * DAY) runCatching { f.readText() }.getOrNull() else null
        val text = kept ?: synchronized(labsGate) {
            runCatching {
                val r = http("$LABS?artist_mbids=$id&algorithm=$ALGORITHM", headers = mapOf("User-Agent" to AGENT, "Accept" to "application/json"),
                    connectMs = 8_000, readMs = 15_000)
                if (r.code != 200) throw IOException("${r.code} similar artists")
                r.text.also { t -> runCatching { d.mkdirs(); f.writeText(t) } }
            }.onFailure { ListenBrainz.log("similar to $artist: $it") }.getOrNull()
        } ?: return emptyList()
        return runCatching { parseListenBrainz(text) }.getOrDefault(emptyList()).take(limit)
    }

    /** Labs' answer (a list of artists, each a name and a score, the nearest first) as names and 0 to 1, the nearest 1. */
    fun parseListenBrainz(text: String): List<Pair<String, Double>> {
        val a = JSONArray(text)
        val all = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("name").trim() to it.optDouble("score", 0.0) }
            .filter { it.first.isNotEmpty() && it.second > 0 }.sortedByDescending { it.second }
        val top = all.firstOrNull()?.second ?: return emptyList()
        return all.map { it.first to it.second / top }
    }

    /**
     * Who's near an artist, as Last.fm's listeners and ListenBrainz's both hear it: one named by both first (above any
     * one alone), then the rest by how near; Last.fm's spelling of a name kept. One of them silent (no Last.fm key, no
     * MusicBrainz id), the other's list as it is.
     */
    fun artists(artist: String, key: String, limit: Int = 10): List<Pair<String, Double>> =
        blend(lastFmArtists(artist, key, limit), listenBrainzArtists(artist, limit), limit)

    /** "Fans also like" heard by more ears: YouTube's own list in a new order ([order], places in it) and who to add. */
    data class AlsoLike(val order: List<Int>, val agreed: Int, val extra: List<String>)

    /**
     * YouTube Music's "fans also like" (`names`, its order) with Last.fm's and ListenBrainz's near artists (`kin`, each 0
     * to 1; [artists]) (2026-11-24): those they also name first, the nearest first ([AlsoLike.agreed] of them), the rest
     * in YouTube's order; and up to `extra` they name that YouTube doesn't, yours (`preferred`) before the rest, each
     * set by how near. Names matched folded.
     */
    fun alsoLike(names: List<String>, kin: List<Pair<String, Double>>, extra: Int, preferred: (String) -> Boolean): AlsoLike {
        val near = HashMap<String, Double>()
        kin.forEach { (n, m) -> val k = Curation.folded(n); if ((near[k] ?: -1.0) < m) near[k] = m }
        val folded = names.map { Curation.folded(it) }
        val agreed = names.indices.filter { folded[it] in near }.sortedByDescending { near[folded[it]] }
        val rest = names.indices.filter { folded[it] !in near }
        val have = folded.toHashSet()
        val more = kin.filter { Curation.folded(it.first) !in have }.distinctBy { Curation.folded(it.first) }
            .sortedWith(compareByDescending<Pair<String, Double>> { preferred(it.first) }.thenByDescending { it.second })
            .take(extra.coerceAtLeast(0)).map { it.first }
        return AlsoLike(agreed + rest, agreed.size, more)
    }

    /** Two lists of near artists (each 0 to 1) as one: both named it, .6 and up; one alone, .55 at most. */
    fun blend(lastFm: List<Pair<String, Double>>, listenBrainz: List<Pair<String, Double>>, limit: Int): List<Pair<String, Double>> {
        if (listenBrainz.isEmpty()) return lastFm.take(limit)
        if (lastFm.isEmpty()) return listenBrainz.take(limit)
        val lb = listenBrainz.associate { Curation.folded(it.first) to it.second }
        val lf = lastFm.associate { Curation.folded(it.first) to it.second }
        val names = LinkedHashMap<String, String>()
        (lastFm + listenBrainz).forEach { names.putIfAbsent(Curation.folded(it.first), it.first) }
        return names.map { (k, name) ->
            val a = lf[k]; val b = lb[k]
            name to if (a != null && b != null) .6 + .4 * maxOf(a, b) else .55 * (a ?: b ?: 0.0)
        }.sortedByDescending { it.second }.take(limit)
    }

    /**
     * A song of the same name as a seed's by someone else (#7, 2026-11-24, a listener: a song's radio brought other songs
     * of its name before songs like it): a cover, or a namesake a search found. Titles as said (case, marks and a
     * bracketed or dashed tail aside); an artist the seed's when they're among the seed's credits, or the seed among theirs.
     */
    fun namesake(title: String, artist: String, seedTitle: String, seedArtist: String): Boolean {
        val t = bareTitle(title)
        if (t.isEmpty() || t != bareTitle(seedTitle)) return false
        val mine = credits(seedArtist); val theirs = credits(artist)
        return mine.isNotEmpty() && theirs.isNotEmpty() && mine.none { it in theirs }
    }

    private fun bareTitle(s: String) = s.lowercase().substringBefore(" (").substringBefore(" [").substringBefore(" - ").filter { it.isLetterOrDigit() }
    private fun credits(s: String) = s.split(Regex(""",|&| x | feat\.? | ft\.? | with | and """, RegexOption.IGNORE_CASE))
        .map { Curation.folded(it).filter { c -> c.isLetterOrDigit() } }.filter { it.isNotEmpty() }.toSet()
}
