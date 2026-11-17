package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Artists and songs you never want suggested (2026-11-15, a tester: "block certain recommendations that keep appearing
 * repeatedly"; Metrolist 14 hides artists the same way): blocked from a song's menu, "block artist" or "block song".
 * Kept out of everything ink suggests: similar songs (radio), listen's shelves, the critics' picks, new videos; what
 * you look for yourself (find, a record's page, your own lists) still shows it. Kept in blocked.json; unblocked in
 * settings.
 */
object Blocked {
    data class Kept(val artists: List<String> = emptyList(), val songs: List<Song> = emptyList())

    /** A blocked song, as said: its id and its title and artist (the same song found again by another id). */
    data class Song(val id: String, val title: String, val artist: String)

    private val file get() = File(Home.dir, "blocked.json")
    private val _kept = MutableStateFlow(read())
    val kept: StateFlow<Kept> = _kept

    private var artistKeys = keysOf(_kept.value)
    private var songKeys = songKeysOf(_kept.value)

    /** Each of a song's artists, as compared: "A, B & C feat. D" is four. */
    private fun whoAll(artist: String) = artist.lowercase()
        .split(Regex(""",|&| x | feat\.? | ft\.? | featuring | with """)).map { it.trim() }.filter { it.isNotEmpty() }

    private fun keysOf(k: Kept) = k.artists.map { it.lowercase().trim() }.toHashSet()
    private fun songKeysOf(k: Kept) = k.songs.flatMap { listOf(it.id, Radio.key(it.title, it.artist)) }.filter { it.isNotEmpty() }.toHashSet()

    fun artist(name: String) = artistKeys.isNotEmpty() && whoAll(name).any { it in artistKeys }

    /** Whether this is kept out: its artist (any of them) blocked, or the song itself. */
    fun hides(title: String, artist: String, id: String = ""): Boolean =
        artist(artist) || (songKeys.isNotEmpty() && ((id.isNotEmpty() && id in songKeys) || Radio.key(title, artist) in songKeys))

    fun hides(s: app.ink.music.Song) = hides(s.title, s.artist, s.id)

    fun blockArtist(name: String) {
        val who = name.substringBefore(", ").trim().ifEmpty { return }
        set(_kept.value.copy(artists = (_kept.value.artists + who).distinctBy { it.lowercase() }))
    }

    fun blockSong(s: app.ink.music.Song) =
        set(_kept.value.copy(songs = (_kept.value.songs + Song(s.id, s.title, s.artist)).distinctBy { it.id }))

    fun unblockArtist(name: String) = set(_kept.value.copy(artists = _kept.value.artists.filterNot { it.equals(name, true) }))
    fun unblockSong(id: String) = set(_kept.value.copy(songs = _kept.value.songs.filterNot { it.id == id }))

    /**
     * What's blocked as plain text, a line each (2026-11-17, a tester: the list grew down the settings page, and was to
     * be copied out to keep or pass on): artists as named, songs "artist - title".
     */
    fun artistsText() = _kept.value.artists.joinToString("\n")
    fun songsText() = _kept.value.songs.joinToString("\n") { "${it.artist} - ${it.title}" }

    /** Artists typed or pasted back, a line each: what's blocked now. */
    fun artistsFrom(text: String) =
        set(_kept.value.copy(artists = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }))

    /** Songs typed or pasted back, "artist - title" a line: one blocked already keeps its id; a line without " - ", left out. */
    fun songsFrom(text: String) {
        val had = _kept.value.songs.associateBy { "${it.artist} - ${it.title}".lowercase() }
        val songs = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { l ->
            had[l.lowercase()] ?: l.split(" - ", limit = 2).takeIf { it.size == 2 && it.all { p -> p.isNotBlank() } }?.let { Song("", it[1].trim(), it[0].trim()) }
        }.distinctBy { it.id.ifEmpty { Radio.key(it.title, it.artist) } }
        set(_kept.value.copy(songs = songs))
    }

    private fun set(k: Kept) {
        artistKeys = keysOf(k); songKeys = songKeysOf(k)
        _kept.value = k
        runCatching {
            Keep.write(file, JSONObject().put("artists", JSONArray(k.artists))
                .put("songs", JSONArray(k.songs.map { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist) })).toString())
        }
    }

    private fun read(): Kept = runCatching {
        val o = JSONObject(file.readText())
        val a = o.optJSONArray("artists") ?: JSONArray()
        val s = o.optJSONArray("songs") ?: JSONArray()
        Kept((0 until a.length()).map { a.getString(it) },
            (0 until s.length()).map { s.getJSONObject(it) }.map { Song(it.optString("id"), it.optString("title"), it.optString("artist")) })
    }.getOrDefault(Kept())
}

/** A list of suggestions as shown: what's blocked left out, at once when something's blocked (no rebuild waited for). */
@androidx.compose.runtime.Composable
fun <T> StateFlow<List<T>>.shown(said: (T) -> Pair<String, String>): List<T> {
    val all by collectAsState()
    val k by Blocked.kept.collectAsState()
    return androidx.compose.runtime.remember(all, k) { if (k.artists.isEmpty() && k.songs.isEmpty()) all else all.filterNot { said(it).let { (t, a) -> Blocked.hides(t, a) } } }
}

/** A suggestion that is itself a list of songs (a mix, because you loved): its songs as shown. */
@androidx.compose.runtime.Composable
fun shownSongs(songs: List<Song>): List<Song> {
    val k by Blocked.kept.collectAsState()
    return androidx.compose.runtime.remember(songs, k) { if (k.artists.isEmpty() && k.songs.isEmpty()) songs else songs.filterNot { Blocked.hides(it) } }
}
