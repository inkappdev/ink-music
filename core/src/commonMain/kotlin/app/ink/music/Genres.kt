package app.ink.music

/**
 * Which of INK's picks belong to a genre (2026-11-07, Bronson: a genre's page was only YouTube Music's playlists, "a
 * little bare"): each picked artist's top tags on Last.fm (listeners' own, the ones ink's moods use), read once an
 * artist and kept two months, against each of YouTube Music's genres' tags. An artist is in a genre when one of its
 * tags is among their top three (fans tag a favorite with everything further down). Blocking calls; the apps keep the
 * tags on disk ([save], [load]) and read the missing ones a few at a time.
 */
object Genres {
    /** YouTube Music's genres (their titles, lowercase) and the Last.fm tags each is told by. */
    private val TAGS = mapOf(
        "indie & alternative" to listOf("indie", "indie rock", "indie pop", "alternative", "alternative rock", "dream pop", "shoegaze",
            "post-punk", "bedroom pop", "lo-fi", "slacker rock", "art rock", "jangle pop", "noise pop", "math rock", "slowcore", "indie folk",
            "neo-psychedelia", "psychedelic", "garage rock", "emo", "sadcore", "chamber pop", "britpop", "new wave", "surf rock"),
        "dance & electronic" to listOf("electronic", "electronica", "house", "techno", "dance", "edm", "deep house", "uk garage",
            "drum and bass", "ambient", "idm", "disco", "nu disco", "trip-hop", "downtempo", "dubstep", "breakbeat", "electro", "french house"),
        "hip-hop" to listOf("hip-hop", "hip hop", "rap", "trap", "grime", "underground hip-hop", "boom bap", "drill", "conscious hip hop"),
        "r&b & soul" to listOf("rnb", "r&b", "soul", "neo-soul", "neo soul", "contemporary r&b", "funk", "alternative rnb"),
        "jazz" to listOf("jazz", "jazz fusion", "soul jazz", "nu jazz", "bebop", "free jazz", "spiritual jazz", "smooth jazz"),
        "rock" to listOf("rock", "hard rock", "classic rock", "garage rock", "punk", "punk rock", "post-punk", "stoner rock",
            "psychedelic rock", "grunge", "alternative rock", "indie rock", "post-hardcore", "pop punk", "surf rock"),
        "folk & acoustic" to listOf("folk", "indie folk", "acoustic", "singer-songwriter", "folk rock", "americana", "chamber folk", "freak folk"),
        "pop" to listOf("pop", "indie pop", "synthpop", "synth-pop", "electropop", "dance-pop", "art pop", "hyperpop", "bedroom pop"),
        "soundtracks & musicals" to listOf("soundtrack", "score", "musical", "film score"),
        "reggae & caribbean" to listOf("reggae", "dub", "dancehall", "ska", "soca", "roots reggae"),
        "blues" to listOf("blues", "blues rock", "delta blues", "electric blues"),
        "latin" to listOf("latin", "reggaeton", "latin pop", "cumbia", "salsa", "bachata", "latin rock"),
        "brazilian" to listOf("brazilian", "mpb", "bossa nova", "tropicalia", "samba", "baile funk"),
        "african" to listOf("afrobeat", "afrobeats", "african", "amapiano", "highlife"),
        "classical" to listOf("classical", "modern classical", "neoclassical", "contemporary classical", "piano"),
        "metal" to listOf("metal", "heavy metal", "black metal", "doom metal", "metalcore", "death metal", "sludge metal", "progressive metal"),
        "country & americana" to listOf("country", "americana", "alt-country", "bluegrass", "country rock"),
        "k-pop" to listOf("k-pop", "kpop"),
        "j-pop" to listOf("j-pop", "jpop", "city pop"),
    )

    /** Whether ink knows this genre's tags (the ones it doesn't, decades and the like, keep only YouTube's lists). */
    fun known(genre: String) = TAGS.containsKey(genre.lowercase())

    /** Every genre ink can tell an artist by (the computer's songs filtered by them, 2026-11-10). */
    val names: List<String> get() = TAGS.keys.toList()

    // an artist's top tags (lowercase, most given first) and when they were read
    private val kept = HashMap<String, Pair<List<String>, Long>>()
    private const val KEEP = 60L * 86_400_000

    /** An artist's top tags as kept (most given first), or null when they haven't been read (ink mapped places them by these). */
    fun tags(artist: String): List<String>? = kept[key(artist)]?.first

    /** A tag that tells this genre (its first): a genre picked placed on ink mapped's wheel as an artist of it would be. */
    fun tagOf(genre: String): String? = TAGS[genre.lowercase()]?.firstOrNull()

    /** The genres a tag tells (most of them one; "post-punk" is indie and rock both). */
    fun genresOf(tag: String): List<String> = TAGS.entries.filter { tag in it.value }.map { it.key }

    /** Whether an artist fits a genre: one of its tags among their top three. Unknown artists don't (yet). */
    fun fits(artist: String, genre: String): Boolean {
        val want = TAGS[genre.lowercase()] ?: return false
        val theirs = kept[key(artist)]?.first ?: return false
        return theirs.take(3).any { it in want }
    }

    /** Of these artists, the ones whose tags aren't kept (or are two months old). */
    fun missing(artists: Collection<String>, now: Long): List<String> =
        artists.map { it.substringBefore(", ").trim() }.filter { it.isNotEmpty() }.distinctBy(::key)
            .filter { a -> kept[key(a)]?.let { now - it.second > KEEP } ?: true }

    /**
     * An artist's top tags on Last.fm (blocking), kept: true when read (none is an answer too: kept, not asked again
     * for two months), false when Last.fm couldn't say (asked again next time).
     */
    fun read(artist: String, lastFmKey: String, now: Long): Boolean = runCatching {
        val o = JSONObject(Curation.get("https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags&artist=${urlEncode(artist)}&autocorrect=1&api_key=$lastFmKey&format=json"))
        if (o.has("error") && o.optInt("error") != 6) return false   // 6: no such artist, an answer
        val a = o.optJSONObject("toptags")?.optJSONArray("tag")
        val tags = if (a == null) emptyList() else (0 until minOf(a.length(), 8)).map { a.getJSONObject(it).optString("name").lowercase().trim() }
        kept[key(artist)] = tags to now
        true
    }.getOrDefault(false)

    /** The kept tags as text, for the app to keep on disk; and read back from it. */
    fun save(): String = JSONObject().apply {
        kept.forEach { (k, v) -> put(k, JSONObject().put("at", v.second).put("tags", JSONArray().apply { v.first.forEach { put(it) } })) }
    }.toString()

    fun load(text: String) = runCatching {
        val o = JSONObject(text)
        for (k in o.keys()) o.optJSONObject(k)?.let { e ->
            val a = e.optJSONArray("tags")
            kept[k] = (if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }) to e.optLong("at")
        }
    }

    private fun key(artist: String) = artist.substringBefore(", ").trim().lowercase()
}
