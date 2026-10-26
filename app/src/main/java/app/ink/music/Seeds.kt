package app.ink.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the listen page grows from: the artists you picked (on first open, or in settings since),
 * each with their photo and, once found, their best-known song (their mix starts from it); the
 * radio genres you like; and whether the first open's welcome has been seen. Kept on the phone.
 */
object Seeds {
    data class Artist(val name: String, val face: String?, val hit: Song? = null)

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists
    private val _genres = MutableStateFlow<List<String>>(emptyList())
    val genres: StateFlow<List<String>> = _genres
    private val _welcomed = MutableStateFlow(true)
    val welcomed: StateFlow<Boolean> = _welcomed
    private var app: Context? = null

    private fun prefs(c: Context) = c.getSharedPreferences("seeds", Context.MODE_PRIVATE)

    fun start(c: Context) {
        if (app != null) return
        app = c.applicationContext
        val p = prefs(c)
        // a phone that already had ink music (it can read the files, or is signed in) isn't welcomed again
        _welcomed.value = p.getBoolean("welcomed", false) || Library.allowed(c) || Server.read(c) != null
        _artists.value = runCatching {
            val a = JSONArray(p.getString("artists", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                Artist(o.getString("name"), o.optString("face").ifEmpty { null }, o.optJSONObject("hit")?.let(::songOf))
            }
        }.getOrDefault(emptyList())
        _artists.value.mapNotNull { it.hit }.forEach(YouTubeMusic::remember)
        _genres.value = p.getString("genres", "")!!.split(',').filter { it.isNotBlank() }
    }

    fun setArtists(list: List<Artist>) { _artists.value = list; keep() }
    fun setGenres(list: List<String>) { _genres.value = list; keep() }

    /** An artist's best-known song, found: kept with them. */
    fun found(name: String, hit: Song) {
        _artists.value = _artists.value.map { if (it.name == name) it.copy(hit = hit) else it }
        keep()
    }

    fun welcome() { _welcomed.value = true; app?.let { prefs(it).edit().putBoolean("welcomed", true).apply() } }

    private fun keep() {
        val c = app ?: return
        prefs(c).edit()
            .putString("artists", JSONArray().apply {
                _artists.value.forEach { a -> put(JSONObject().put("name", a.name).put("face", a.face ?: "").apply { a.hit?.let { put("hit", it.json()) } }) }
            }.toString())
            .putString("genres", _genres.value.joinToString(","))
            .apply()
    }

    /**
     * Artists to offer on first open: well-loved ones across kinds of music, so most people see a
     * few of theirs (and "find" for the rest). A library's own most-heard come first, ahead of these.
     */
    val offered = listOf(
        "Taylor Swift", "Kendrick Lamar", "Billie Eilish", "Frank Ocean", "Radiohead", "Phoebe Bridgers",
        "SZA", "The Beatles", "Daft Punk", "Fleetwood Mac", "Tyler, The Creator", "Lana Del Rey",
        "Bad Bunny", "Beyoncé", "Tame Impala", "Arctic Monkeys", "Olivia Rodrigo", "Mitski",
        "Bon Iver", "Rosalía", "The Weeknd", "Fred again..", "Japanese Breakfast", "Charli xcx",
        "Mac DeMarco", "Steve Lacy", "Clairo", "Nina Simone", "Miles Davis", "Bob Dylan",
    )
}
