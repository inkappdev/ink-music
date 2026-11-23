package app.ink.music

import org.json.JSONArray
import org.json.JSONObject

/**
 * Your artists on tour (2026-11-23): their upcoming shows, from every source there's a way to ask, put together. Spotify's
 * artist pages (the web player's own query, with the passes a Spotify sign-in gives: no key) and Ticketmaster's Discovery
 * API (its key in the build); a show both list is one (the same artist, day and city), its ticket link kept from either.
 * Shown by the show, never by where it came from. A city chosen once ([Place], found by name through Open-Meteo's free
 * geocoding) says what's near: no location permission.
 */
object Tours {
    /** One show: the artist as you know them, its day (yyyy-MM-dd), where, and its tickets' page. */
    data class Show(
        val artist: String, val day: String, val venue: String, val city: String, val country: String,
        val url: String, val lat: Double? = null, val lon: Double? = null,
    ) {
        fun json(): JSONObject = JSONObject().put("artist", artist).put("day", day).put("venue", venue).put("city", city)
            .put("country", country).put("url", url).apply { lat?.let { put("lat", it) }; lon?.let { put("lon", it) } }

        companion object {
            fun of(o: JSONObject) = Show(o.optString("artist"), o.optString("day"), o.optString("venue"), o.optString("city"),
                o.optString("country"), o.optString("url"), o.optDouble("lat").takeIf { !it.isNaN() }, o.optDouble("lon").takeIf { !it.isNaN() })
        }
    }

    /** A city chosen for "near you": its name as shown, where it is. */
    data class Place(val name: String, val lat: Double, val lon: Double) {
        fun json(): JSONObject = JSONObject().put("name", name).put("lat", lat).put("lon", lon)
        companion object {
            fun of(o: JSONObject): Place? = runCatching { Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon")) }.getOrNull()
        }
    }

    /** Ticketmaster's Discovery API key (the build's; blank: not asked). */
    @Volatile var ticketmasterKey = ""

    /** Cities by name, for choosing one: Open-Meteo's geocoding (free, no key), the biggest first. */
    fun cities(name: String): List<Place> = runCatching {
        if (name.trim().length < 2) return emptyList()
        val r = http("https://geocoding-api.open-meteo.com/v1/search?name=${urlEncode(name.trim())}&count=8&language=en&format=json")
        if (r.code != 200) return emptyList()
        val a = JSONObject(r.text).optJSONArray("results") ?: return emptyList()
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val said = listOf(o.optString("name"), o.optString("admin1"), o.optString("country_code").uppercase())
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            Place(said, o.getDouble("latitude"), o.getDouble("longitude"))
        }
    }.getOrDefault(emptyList())

    /** How far apart two places are, in km (the great circle). */
    fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = Math.toRadians(bLat - aLat); val g = Math.toRadians(bLon - aLon)
        val h = Math.sin(r / 2).let { it * it } + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(g / 2).let { it * it }
        return 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
    }

    /** The shows within `within` km of the place (one with no place known left out), soonest first. */
    fun near(shows: List<Show>, place: Place, within: Double = 160.0): List<Show> =
        shows.filter { s -> s.lat != null && s.lon != null && km(place.lat, place.lon, s.lat, s.lon) <= within }.sortedBy { it.day }

    /** Several sources' shows as one list: a show listed twice (artist, day, city) once, the first source's kept. */
    fun merge(vararg lists: List<Show>): List<Show> {
        val seen = HashSet<String>()
        return lists.flatMap { it }.filter { seen.add(Search.fold(it.artist) + "|" + it.day + "|" + Search.fold(it.city)) }.sortedBy { it.day }
    }

    private fun sameArtist(found: String, artist: String): Boolean {
        val a = Search.fold(found); val b = Search.fold(artist)
        return a.isNotEmpty() && (a == b || a.startsWith("$b ") || b.startsWith("$a "))
    }

    /**
     * Ticketmaster's upcoming music events for an artist (the ones with them among the event's acts), each with its venue
     * and tickets' page. Empty without a key, or if it wouldn't say. Blocking.
     */
    fun ticketmaster(artist: String): List<Show> {
        val key = ticketmasterKey.ifBlank { return emptyList() }
        return runCatching {
            val r = http("https://app.ticketmaster.com/discovery/v2/events.json?apikey=$key&keyword=${urlEncode(artist)}" +
                "&classificationName=music&size=100&sort=date,asc", connectMs = 8_000, readMs = 12_000)
            if (r.code != 200) return emptyList()
            val events = JSONObject(r.text).optJSONObject("_embedded")?.optJSONArray("events") ?: return emptyList()
            (0 until events.length()).mapNotNull { events.optJSONObject(it) }.mapNotNull { e ->
                val acts = e.optJSONObject("_embedded")?.optJSONArray("attractions")
                val theirs = acts != null && (0 until acts.length()).any { sameArtist(acts.optJSONObject(it)?.optString("name").orEmpty(), artist) }
                if (!theirs) return@mapNotNull null
                val day = e.optJSONObject("dates")?.optJSONObject("start")?.optString("localDate").orEmpty().ifEmpty { return@mapNotNull null }
                val v = e.optJSONObject("_embedded")?.optJSONArray("venues")?.optJSONObject(0)
                val loc = v?.optJSONObject("location")
                Show(artist, day, v?.optString("name").orEmpty(), v?.optJSONObject("city")?.optString("name").orEmpty(),
                    v?.optJSONObject("country")?.optString("countryCode").orEmpty(), e.optString("url"),
                    loc?.optString("latitude")?.toDoubleOrNull(), loc?.optString("longitude")?.toDoubleOrNull())
            }
        }.getOrDefault(emptyList())
    }

    // the web player's queries for an artist found by name and their page; hashes read again from its scripts when stale
    private const val SEARCH = "searchArtists"
    private const val OVERVIEW = "queryArtistOverview"
    @Volatile private var hashes = HashMap<String, String>()

    private fun ask(operation: String, vars: JSONObject, pass: SpotifyCanvas.Pass): JSONObject {
        val hash = hashes[operation] ?: SpotifyCanvas.hashOf(operation, pass)?.also { hashes[operation] = it } ?: error("$operation: no hash")
        return runCatching { SpotifyCanvas.ask(operation, hash, vars, pass) }.getOrElse { e ->
            if ("PersistedQuery" !in e.message.orEmpty()) throw e
            val fresh = SpotifyCanvas.hashOf(operation, pass) ?: throw e
            hashes[operation] = fresh
            SpotifyCanvas.ask(operation, fresh, vars, pass)
        }
    }

    /** The artist's Spotify uri, by the web player's search: the first by that very name. */
    private fun spotifyArtist(artist: String, pass: SpotifyCanvas.Pass): String? {
        val vars = JSONObject().put("searchTerm", artist).put("offset", 0).put("limit", 10).put("numberOfTopResults", 5).put("includeAudiobooks", false)
        val items = ask(SEARCH, vars, pass).optJSONObject("data")?.optJSONObject("searchV2")?.optJSONObject("artists")?.optJSONArray("items") ?: return null
        return (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optJSONObject("data") }
            .firstOrNull { sameArtist(it.optJSONObject("profile")?.optString("name").orEmpty(), artist) }?.optString("uri")?.takeIf { it.startsWith("spotify:artist:") }
    }

    /**
     * The artist's upcoming concerts on their Spotify page (the web player's own query, signed in), each with its venue,
     * city and the ticket seller's page where it names one. Throws if Spotify couldn't be asked, so none isn't kept as
     * none. Blocking.
     */
    fun spotify(artist: String, pass: SpotifyCanvas.Pass): List<Show> {
        val uri = spotifyArtist(artist, pass) ?: return emptyList()
        val vars = JSONObject().put("uri", uri).put("locale", "").put("includePrerelease", false)
        val root = ask(OVERVIEW, vars, pass)
        val concerts = root.optJSONObject("data")?.optJSONObject("artistUnion")?.optJSONObject("goods")?.optJSONObject("events")
            ?.optJSONObject("concerts")?.optJSONArray("items") ?: JSONArray()
        return (0 until concerts.length()).mapNotNull { concerts.optJSONObject(it)?.let { c -> c.optJSONObject("data") ?: c } }.mapNotNull { c ->
            val day = (c.optJSONObject("date")?.optString("isoString") ?: c.optString("startDate")).orEmpty().take(10).ifEmpty { return@mapNotNull null }
            val venue = c.optJSONObject("venue")
            val loc = venue?.optJSONObject("location")
            val coord = loc?.optJSONObject("coordinates")
            // the ticket seller's own page where it names one; else the concert's page
            val partner = c.optJSONObject("offers")?.optJSONArray("items")?.optJSONObject(0)?.optString("url")
                ?: c.optJSONArray("partnerLinks")?.optJSONObject(0)?.optString("url")
            val id = c.optString("uri").substringAfterLast(':')
            val url = partner?.takeIf { it.startsWith("http") } ?: if (id.isNotEmpty()) "https://open.spotify.com/concert/$id" else return@mapNotNull null
            Show(artist, day, venue?.optString("name").orEmpty(), loc?.optString("name").orEmpty(), "", url,
                coord?.optDouble("latitude")?.takeIf { !it.isNaN() }, coord?.optDouble("longitude")?.takeIf { !it.isNaN() })
        }
    }

    fun write(shows: List<Show>): String = JSONArray(shows.map { it.json() }).toString()
    fun read(text: String): List<Show> = runCatching { JSONArray(text).let { a -> (0 until a.length()).map { Show.of(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
}
