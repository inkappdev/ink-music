package app.ink.music

/**
 * ink radio: the stations chosen by hand, the phone's own list (ink-radio.json, in the app), the stars first. A
 * station is a song whose stream never ends: "s" and its id, its city where an artist would be.
 */
object Radio {
    data class Station(val song: Song, val city: String, val line: String, val genre: String, val star: Boolean)

    private var all: List<Station>? = null

    fun stations(): List<Station> = all ?: runCatching {
        val a = JSONObject(bundleText("ink-radio", "json") ?: "{}").getJSONArray("stations")
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val id = "s" + o.getString("id")
            val city = o.optString("city").substringBefore(" (")
            Station(Song(id, o.getString("name"), city, "", id, null, 0, 0, 1, kind = "station", url = o.getString("url")),
                city, o.optString("line"), o.optString("genre"), o.optBoolean("star"))
        }.sortedByDescending { it.star }
    }.getOrDefault(emptyList()).also { all = it }
}
