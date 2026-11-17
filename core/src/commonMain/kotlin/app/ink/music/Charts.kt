package app.ink.music

/**
 * CHARTS (2026-11-17, Bronson's screenshot of YouTube Music's genre top 50s: "lets put these somewhere with an ink spin"):
 * what's played most, read from YouTube Music's charts page for a place (its own charts: the top 100, today's, trending,
 * live; and where it has them, the United States so far, each genre's top 50), told apart by their titles and said ink's
 * way ("TOP 50 / HIP HOP / united states"), never as YouTube's. And a chart's moves: where each song was when you last
 * looked, a day or more before, so a chart says what climbed and what's new. Both apps; each keeps its own last looks.
 */
object Charts {
    enum class Kind(val word: String) { TOP("top"), TODAY("today"), TRENDING("trending"), LIVE("live"), GENRE("genre") }

    /** A chart: its playlist ("VL..."), what it is, how many it holds, its genre (lower case) and its place as it says it. */
    data class Chart(val id: String, val kind: Kind, val size: Int, val genre: String, val place: String) {
        /** Its playlist's link, to read its songs. */
        val url get() = "https://music.youtube.com/playlist?list=" + id.removePrefix("VL")
        /** Its number, as it's drawn big ("100", "50", "20"); today's has none. */
        val number get() = if (size > 0) "$size" else ""
        /** Its name, as said: "hip hop", "the top 100", "today's", "trending", "live". */
        val name get() = when (kind) {
            Kind.GENRE -> genre
            Kind.TOP -> "the top $size"
            Kind.TODAY -> "today's top"
            Kind.TRENDING -> "trending"
            Kind.LIVE -> "live performances"
        }
    }

    /** The places offered: the world, then the countries YouTube Music charts (the United States has the genres). */
    val PLACES: List<Pair<String, String>> = listOf(
        "ZZ" to "the world", "US" to "united states", "GB" to "united kingdom", "CA" to "canada", "AU" to "australia",
        "IE" to "ireland", "NZ" to "new zealand", "DE" to "germany", "FR" to "france", "ES" to "spain", "IT" to "italy",
        "NL" to "netherlands", "SE" to "sweden", "NO" to "norway", "PT" to "portugal", "BR" to "brazil", "MX" to "mexico",
        "AR" to "argentina", "CO" to "colombia", "CL" to "chile", "JP" to "japan", "KR" to "south korea", "IN" to "india",
        "ID" to "indonesia", "PH" to "philippines", "ZA" to "south africa", "NG" to "nigeria", "KE" to "kenya", "EG" to "egypt",
        "TR" to "turkey", "PL" to "poland",
    )

    fun placeName(code: String) = PLACES.firstOrNull { it.first == code }?.second ?: code.lowercase()

    private val GENRE = Regex("""^Top (\d+) (.+?) Music Videos (.+)$""", RegexOption.IGNORE_CASE)
    private val TOP = Regex("""^Top (\d+) (?:Music Videos|Songs) -? ?(.+)$""", RegexOption.IGNORE_CASE)
    private val TODAY = Regex("""^Daily Top (?:Music Videos|Songs) -? ?(.+)$""", RegexOption.IGNORE_CASE)
    private val TRENDING = Regex("""^Trending (\d+) -? ?(.+)$""", RegexOption.IGNORE_CASE)
    private val LIVE = Regex("""^Top (\d+) Live Performances -? ?(.+)$""", RegexOption.IGNORE_CASE)

    /** A chart from its playlist's title, if it's one of these. */
    fun parse(id: String, title: String): Chart? {
        val t = title.trim()
        LIVE.find(t)?.let { return Chart(id, Kind.LIVE, it.groupValues[1].toInt(), "", it.groupValues[2].trim()) }
        GENRE.find(t)?.let { return Chart(id, Kind.GENRE, it.groupValues[1].toInt(), it.groupValues[2].trim().lowercase(), it.groupValues[3].trim()) }
        TOP.find(t)?.let { return Chart(id, Kind.TOP, it.groupValues[1].toInt(), "", it.groupValues[2].trim()) }
        TODAY.find(t)?.let { return Chart(id, Kind.TODAY, 0, "", it.groupValues[1].trim()) }
        TRENDING.find(t)?.let { return Chart(id, Kind.TRENDING, it.groupValues[1].toInt(), "", it.groupValues[2].trim()) }
        return null
    }

    private val guard = Guard()
    private val kept = HashMap<String, Pair<Long, List<Chart>>>()

    /**
     * A place's charts, in ink's order: the top, today's, trending, live, then the genres (rock, pop, hip hop... as
     * YouTube lists them). [read]: the charts page's playlists for a place, each its id and title (InnerTube.chartLists,
     * where YouTube Music is). Kept an hour. Blocking.
     */
    fun of(code: String, read: (String) -> List<Pair<String, String>>): List<Chart> {
        guard.run { kept[code] }?.takeIf { nowMs() - it.first < 3_600_000 }?.let { return it.second }
        val found = runCatching { read(code) }.getOrDefault(emptyList())
            .mapNotNull { (id, title) -> parse(id, title) }.distinctBy { it.id }
            .sortedBy { it.kind.ordinal }
        if (found.isNotEmpty()) guard.run { kept[code] = nowMs() to found }
        return found
    }

    /**
     * Each song's move since the last look before (`before`, the ids in their order then): "new", "+3", "-2" or "" (where
     * it was). Nothing to compare with, all "".
     */
    fun moves(before: List<String>?, now: List<String>): List<String> {
        if (before.isNullOrEmpty()) return now.map { "" }
        val was = before.withIndex().associate { (i, id) -> id to i }
        return now.mapIndexed { i, id ->
            val w = was[id] ?: return@mapIndexed "new"
            when { w > i -> "+${w - i}"; w < i -> "-${i - w}"; else -> "" }
        }
    }

    /** A chart's looks as each app keeps them: the one before (compared with), and the latest (its ids and when). */
    data class Looks(val before: List<String>?, val at: Long, val ids: List<String>)

    /** Looked at again: the latest becomes the one before once it's a day old (so the moves are a day's, at least). */
    fun look(l: Looks?, now: List<String>, at: Long = nowMs()): Looks = when {
        now.isEmpty() -> l ?: Looks(null, at, now)
        l == null -> Looks(null, at, now)
        at - l.at >= DAY -> Looks(l.ids, at, now)
        else -> l.copy(ids = l.ids)
    }

    const val DAY = 20 * 3_600_000L
}
