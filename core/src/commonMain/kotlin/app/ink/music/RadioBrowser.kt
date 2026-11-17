package app.ink.music

/**
 * radio-browser.info, more of it (2026-11-17, a listener on Reddit: "thousands more", and no way to them past twelve
 * genres and a station's name in find): its stations by name, genre tag, country and state, in the order chosen (most
 * played, trending now, most liked), a page at a time; its tags (tidied: one of each spelling, no "music" or "fm", no
 * languages or countries) and its countries and their states, with how many stations each. Both apps, each drawing its
 * own; tidied as the apps' own lists were: a stream that plays here (http, not HLS), 64 kbps or better where it's said,
 * none of SomaFM's (its terms refuse apps like this), none of North Korea's. Blocking: off the main thread.
 */
object RadioBrowser {
    private const val BASE = "https://all.api.radio-browser.info/json"

    /** A station as the list has it: `place` its state or city where said, else its country. */
    data class Station(
        val id: String, val name: String, val url: String, val place: String, val country: String, val code: String,
        val tags: List<String>, val bitrate: Int, val codec: String,
    ) {
        /** Its place and quality, as said under it: "lisbon · 128k". */
        val said: String get() = listOfNotNull(place.ifEmpty { null }, bitrate.takeIf { it > 0 }?.let { "${it}k" }).joinToString(" · ")
    }

    data class Tag(val word: String, val variants: List<String>, val count: Int)
    data class Country(val name: String, val code: String, val count: Int) {
        /** Its name as said ("The United Kingdom Of Great Britain And Northern Ireland" is "united kingdom"). */
        val short: String get() = (SHORT[code] ?: name.removePrefix("The ").substringBefore(" (").substringBefore(",")).lowercase()
    }
    private val SHORT = mapOf(
        "US" to "united states", "GB" to "united kingdom", "RU" to "russia", "KR" to "south korea", "IR" to "iran", "VE" to "venezuela",
        "BO" to "bolivia", "TZ" to "tanzania", "SY" to "syria", "MD" to "moldova", "VN" to "vietnam", "LA" to "laos", "TW" to "taiwan",
        "CD" to "dr congo", "NL" to "netherlands", "PH" to "philippines", "AE" to "uae", "CZ" to "czechia", "DO" to "dominican republic",
    )
    data class State(val name: String, val count: Int)

    enum class Order(val word: String, val key: String) {
        PLAYED("most played", "clickcount"), TRENDING("trending now", "clicktrend"), LIKED("most liked", "votes"),
    }

    /** What's asked: any of a name, a genre's tag (its spellings), a country (its code) and a state. */
    data class Ask(
        val name: String? = null, val tag: Tag? = null, val tagLike: String? = null, val code: String? = null, val state: String? = null,
        val order: Order = Order.PLAYED,
    )

    private val guard = Guard()
    private val pages = HashMap<String, List<Station>>()
    private var countriesKept: List<Country>? = null
    private val statesKept = HashMap<String, List<State>>()

    private fun get(path: String): String? = runCatching {
        val r = http("$BASE/$path", headers = mapOf("User-Agent" to AGENT), connectMs = 8_000, readMs = 15_000)
        if (r.code == 200) r.text else null
    }.getOrNull()

    /** Whether a station's stream plays here and is one to offer. */
    private fun fits(o: JSONObject): Boolean {
        val stream = o.optString("url_resolved").ifEmpty { o.optString("url") }
        val name = o.optString("name").trim().lowercase()
        val kbps = o.optInt("bitrate")
        return stream.startsWith("http") && o.optInt("hls") == 0 && ".m3u8" !in stream.lowercase() && (kbps == 0 || kbps >= 64) &&
            name.isNotEmpty() && "somafm" !in name && "somafm" !in stream && o.optString("countrycode") != "KP"
    }

    private fun station(o: JSONObject): Station {
        val country = o.optString("country").trim()
        val state = o.optString("state").trim()
        return Station(
            id = o.optString("stationuuid"), name = o.optString("name").trim().replace(Regex("\\s+"), " "),
            url = o.optString("url_resolved").ifEmpty { o.optString("url") },
            place = (state.ifEmpty { country }).lowercase(), country = country, code = o.optString("countrycode"),
            tags = o.optString("tags").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            bitrate = o.optInt("bitrate"), codec = o.optString("codec").lowercase(),
        )
    }

    private fun read(text: String?): List<JSONObject> = text?.let { t ->
        runCatching { JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } }.getOrNull()
    }.orEmpty()

    /**
     * A page of stations for what's asked (`page` from 0, `size` each), the order chosen; `leaveOut`, names (lower case)
     * to leave out (ink radio's own, found as themselves). One of each name. Kept for the run.
     */
    fun stations(ask: Ask, page: Int = 0, size: Int = 40, leaveOut: Collection<String> = emptyList()): List<Station> {
        val key = "$ask|$page|$size"
        guard.run { pages[key] }?.let { return it }
        // a genre asked by each of its spellings (exactly), together; else in one ask
        val asks = ask.tag?.variants?.map { v -> listOf("tag" to v, "tagExact" to "true") } ?: listOf(emptyList())
        val found = asks.flatMap { tagPart ->
            val q = buildList {
                addAll(tagPart)
                ask.tagLike?.let { add("tag" to it) }
                ask.name?.let { add("name" to it) }
                ask.code?.let { add("countrycode" to it) }
                ask.state?.let { add("state" to it); add("stateExact" to "true") }
                add("order" to ask.order.key); add("reverse" to "true"); add("hidebroken" to "true"); add("hls" to "false")
                // a page as the list counts it (a few of it left out once read: pages never overlap)
                add("offset" to "${page * size}"); add("limit" to "$size")
            }
            read(get("stations/search?" + q.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }))
        }
        val sortKey = when (ask.order) { Order.PLAYED -> "clickcount"; Order.TRENDING -> "clicktrend"; Order.LIKED -> "votes" }
        val seen = HashSet<String>()
        val out = (if (asks.size > 1) found.sortedByDescending { it.optInt(sortKey) } else found).asSequence()
            .filter(::fits)
            .filter { o -> val n = o.optString("name").trim().lowercase(); leaveOut.none { it.length >= 4 && it in n } }
            .filter { seen.add(it.optString("name").trim().lowercase()) }
            .map(::station).take(size).toList()
        if (found.isNotEmpty()) guard.run { pages[key] = out }
        return out
    }

    /**
     * The genres offered, chosen by hand, each with the spellings stations tag it by (radio-browser's own most-used tags are
     * news, talk, places, languages and a presenter's name more than they're music, tried 2026-11-17): the twelve the
     * radio tab had first, then the rest.
     */
    val GENRES: List<Tag> = listOf(
        "lofi" to listOf("lofi", "lo-fi"), "jazz" to listOf("jazz"), "indie" to listOf("indie"), "classical" to listOf("classical"),
        "ambient" to listOf("ambient"), "electronic" to listOf("electronic"), "hip hop" to listOf("hip hop", "hip-hop", "hiphop"),
        "soul" to listOf("soul"), "rock" to listOf("rock"), "folk" to listOf("folk"), "house" to listOf("house", "deep house"),
        "reggae" to listOf("reggae", "roots reggae"),
        "pop" to listOf("pop"), "dance" to listOf("dance"), "techno" to listOf("techno"), "drum and bass" to listOf("drum and bass", "drum & bass", "dnb"),
        "dubstep" to listOf("dubstep"), "disco" to listOf("disco", "nu disco"), "funk" to listOf("funk"), "r&b" to listOf("rnb", "r&b"),
        "blues" to listOf("blues"), "country" to listOf("country"), "metal" to listOf("metal", "heavy metal"), "punk" to listOf("punk"),
        "alternative" to listOf("alternative", "alternative rock"), "classic rock" to listOf("classic rock"), "oldies" to listOf("oldies"),
        "60s" to listOf("60s", "60's"), "70s" to listOf("70s", "70's"), "80s" to listOf("80s", "80's"), "90s" to listOf("90s", "90's"),
        "chillout" to listOf("chillout", "chill"), "lounge" to listOf("lounge"), "downtempo" to listOf("downtempo"), "trance" to listOf("trance"),
        "experimental" to listOf("experimental"), "psychedelic" to listOf("psychedelic"), "new wave" to listOf("new wave"),
        "synthwave" to listOf("synthwave", "retrowave"), "latin" to listOf("latin"), "salsa" to listOf("salsa"), "reggaeton" to listOf("reggaeton"),
        "bossa nova" to listOf("bossa nova"), "afrobeats" to listOf("afrobeats", "afrobeat"), "world" to listOf("world", "world music"),
        "k-pop" to listOf("kpop", "k-pop"), "j-pop" to listOf("jpop", "j-pop"), "anime" to listOf("anime"), "gospel" to listOf("gospel"),
        "soundtrack" to listOf("soundtrack", "soundtracks"), "news" to listOf("news"), "talk" to listOf("talk"),
    ).map { (w, v) -> Tag(w, v, 0) }

    /** One of a tag's spellings, as compared: "lo-fi", "lofi" and "lo fi" one; "hip hop", "hip-hop", "hiphop" one; "r&b" "rnb". */
    private fun same(t: String) = t.lowercase().replace("&", "n").replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /** A genre by its word, its spellings (the offered one's, else as said). */
    fun tag(word: String): Tag {
        val k = same(word)
        return GENRES.firstOrNull { g -> g.variants.any { same(it) == k } } ?: Tag(word, listOf(word.lowercase()), 0)
    }

    /** Countries with stations, most first. */
    fun countries(): List<Country> {
        guard.run { countriesKept }?.let { return it }
        val out = read(get("countries?order=stationcount&reverse=true&hidebroken=true"))
            .map { Country(it.optString("name").trim(), it.optString("iso_3166_1").ifEmpty { it.optString("countrycode") }, it.optInt("stationcount")) }
            .filter { it.code.length == 2 && it.count > 0 && it.code != "KP" }
        if (out.isNotEmpty()) guard.run { countriesKept = out }
        return out
    }

    /** A country's states (or regions, or cities, as its stations say), most stations first. */
    fun states(code: String): List<State> {
        guard.run { statesKept[code] }?.let { return it }
        val name = countries().firstOrNull { it.code == code }?.name ?: return emptyList()
        val out = read(get("states/${urlEncode(name).replace("+", "%20")}/?order=stationcount&reverse=true&hidebroken=true"))
            .map { State(it.optString("name").trim(), it.optInt("stationcount")) }
            .filter { it.name.isNotEmpty() && it.count >= 2 }
            .groupBy { it.name.lowercase() }.values.map { s -> State(s.first().name, s.sumOf { it.count }) }
            .sortedByDescending { it.count }.take(30)
        if (out.isNotEmpty()) guard.run { statesKept[code] = out }
        return out
    }

    /** A country by its name as typed ("portugal", "united states"), if it's one. */
    fun countryNamed(q: String): Country? {
        val k = fold(q.trim())
        val code = mapOf("usa" to "US", "us" to "US", "uk" to "GB", "england" to "GB", "scotland" to "GB", "wales" to "GB", "holland" to "NL")[k]
        if (code != null) return countries().firstOrNull { it.code == code }
        if (k.length < 3) return null
        return countries().firstOrNull { fold(it.name) == k || fold(it.short) == k }
            ?: countries().firstOrNull { (fold(it.short).startsWith(k) || fold(it.name).startsWith(k)) && k.length >= 5 }
    }

    /**
     * What's typed, looked for every way (the radio tab's search, find's): by name, by genre tag, and in a country or a
     * state of that name; each a few at most, none twice.
     */
    data class Found(val byName: List<Station>, val tagged: List<Station>, val placed: List<Station>, val place: String?) {
        val all get() = byName + tagged + placed
    }

    fun search(q: String, each: Int = 12, leaveOut: Collection<String> = emptyList()): Found {
        val t = q.trim()
        if (t.isEmpty()) return Found(emptyList(), emptyList(), emptyList(), null)
        val byName = stations(Ask(name = t), size = each, leaveOut = leaveOut)
        val seen = byName.map { it.id }.toMutableSet()
        val tagged = stations(Ask(tagLike = t.lowercase()), size = each, leaveOut = leaveOut).filter { seen.add(it.id) }
        val country = countryNamed(t)
        val placed = if (country != null) stations(Ask(code = country.code), size = each, leaveOut = leaveOut).filter { seen.add(it.id) }
            else stations(Ask(state = t), size = each, leaveOut = leaveOut).filter { seen.add(it.id) }
        return Found(byName, tagged, placed, country?.name ?: t.takeIf { placed.isNotEmpty() })
    }

    /**
     * A stream's own link pasted (2026-11-17: a station the list hasn't): an http(s) link that isn't a page of a service
     * ink music reads otherwise (YouTube, Spotify and the rest go to find's links).
     */
    fun isStream(text: String): Boolean {
        val t = text.trim()
        if (!Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(t)) return false
        if (PastedLink.isLink(t)) return false
        val path = t.substringAfter("://").substringAfter('/', "").substringBefore('?').lowercase()
        return !Regex("\\.(html?|aspx?|jpe?g|png|gif|webp|pdf)$").containsMatchIn(path)
    }

    /** A pasted stream as a station: its name its host's, till it says its own. */
    fun pasted(url: String): Station {
        val host = url.trim().substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.")
        return Station("u" + url.trim().hashCode().toUInt().toString(16), host, url.trim(), "your link", "", "", emptyList(), 0, "")
    }
}
