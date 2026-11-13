package app.ink.music

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
}
