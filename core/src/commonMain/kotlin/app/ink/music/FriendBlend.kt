package app.ink.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A blend with a friend (2026-11-18, Bronson: "a blend with a friend"): their Last.fm name, nothing more (their top
 * artists, top songs and loves are public there; no sign-in, no ask of them). In turn: songs you both play (their
 * favorites by artists near you), songs from them (their favorites by artists you've never played), songs from you
 * (your favorites by artists they don't play), for when they blend back. How in tune you are: how many of their top
 * fifty artists are near you.
 */
object FriendBlend {
    /** A song in a blend, and whose side it's from: "both", "theirs", "yours". */
    data class Pick(val artist: String, val title: String, val side: String)

    /** Their listening as Last.fm has it: artists by how much (0 to 1), songs, loves. */
    data class Theirs(val user: String, val artists: List<String>, val songs: List<Pair<String, String>>)

    private const val API = "https://ws.audioscrobbler.com/2.0/"

    /** A friend's last six months and loves (null: no such name, or nothing played). */
    suspend fun read(user: String): Theirs? = withContext(Dispatchers.Default) {
        val key = LastFm.key
        if (key.isEmpty() || user.isBlank()) return@withContext null
        fun read(method: String, extra: String) = runCatching {
            JSONObject(Curation.get("$API?method=$method&user=${urlEncode(user.trim())}&api_key=$key&format=json$extra"))
        }.getOrNull()
        fun pairs(o: JSONObject?, outer: String) = o?.optJSONObject(outer)?.optJSONArray("track")?.let { t ->
            (0 until t.length()).mapNotNull { i -> t.optJSONObject(i)?.let { (it.optJSONObject("artist")?.let { a -> a.optString("name").ifEmpty { a.optString("#text") } }.orEmpty()) to it.optString("name") } }
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
        }.orEmpty()
        val artists = read("user.gettopartists", "&period=6month&limit=100")?.optJSONObject("topartists")?.optJSONArray("artist")
            ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) } }.orEmpty()
        if (artists.isEmpty()) return@withContext null
        val songs = pairs(read("user.gettoptracks", "&period=6month&limit=150"), "toptracks") + pairs(read("user.getlovedtracks", "&limit=100"), "lovedtracks")
        Theirs(user.trim(), artists, songs.distinctBy { (it.first + "|" + it.second).lowercase() })
    }

    /** How in tune, 0 to 100: their top fifty artists near you (`near`, Taste.of), a near one counting more. */
    fun inTune(theirs: Theirs, near: (String) -> Double): Int =
        (theirs.artists.take(50).sumOf { minOf(1.0, near(it) * 2) } / minOf(50, theirs.artists.size).coerceAtLeast(1) * 100).toInt()

    /** The blend: theirs and yours in turn (both, theirs, yours, theirs...), one song an artist till all have had one; `mine` your favorites. */
    fun mix(theirs: Theirs, near: (String) -> Double, mine: List<Pair<String, String>>, size: Int = 50): List<Pick> {
        val theirArtists = theirs.artists.map { Curation.folded(it) }.toSet()
        val both = theirs.songs.filter { near(it.first) >= .2 }.map { Pick(it.first, it.second, "both") }
        val fromThem = theirs.songs.filter { near(it.first) < .05 }.map { Pick(it.first, it.second, "theirs") }
        val fromYou = mine.filter { Curation.folded(it.first) !in theirArtists }.map { Pick(it.first, it.second, "yours") }
        fun once(l: List<Pick>) = l.distinctBy { Curation.folded(it.artist) } + (l - l.distinctBy { Curation.folded(it.artist) }.toSet())
        val turns = listOf(once(both), once(fromThem), once(fromYou), once(fromThem)).map { it.toMutableList() }
        val out = ArrayList<Pick>()
        while (out.size < size && turns.any { it.isNotEmpty() }) for (t in turns) { if (t.isNotEmpty() && out.size < size) out += t.removeAt(0) }
        return out.distinctBy { (it.artist + "|" + it.title).lowercase() }
    }
}
