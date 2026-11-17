package app.ink.music

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Artists and songs you never want suggested (2026-11-15, the computer's, after a tester: "block certain recommendations
 * that keep appearing repeatedly"; Metrolist 14 hides artists the same way): blocked from a held song, "block artist" or
 * "block song". Kept out of what ink suggests: similar songs (the player's process reads the file again when it changes),
 * listen's shelves; what you look for yourself still shows it. files/blocked.json, the computer's shape; unblocked in
 * settings.
 */
object Blocked {
    data class Kept(val artists: List<String> = emptyList(), val songs: List<Said> = emptyList())
    data class Said(val id: String, val title: String, val artist: String)

    private val _kept = MutableStateFlow(Kept())
    val kept: StateFlow<Kept> = _kept
    private var readAt = -1L
    private var artistKeys: Set<String> = emptySet()
    private var songKeys: Set<String> = emptySet()

    private fun file(c: Context) = File(c.filesDir, "blocked.json")

    /** Read again where the file's changed (the other process blocked something), else as it was. */
    @Synchronized fun load(c: Context): Kept {
        val f = file(c)
        val at = if (f.exists()) f.lastModified() else 0L
        if (at != readAt) {
            readAt = at
            val k = runCatching {
                val o = JSONObject(f.readText())
                val a = o.optJSONArray("artists") ?: JSONArray()
                val s = o.optJSONArray("songs") ?: JSONArray()
                Kept((0 until a.length()).map { a.getString(it) },
                    (0 until s.length()).map { s.getJSONObject(it) }.map { Said(it.optString("id"), it.optString("title"), it.optString("artist")) })
            }.getOrDefault(Kept())
            keys(k)
            _kept.value = k
        }
        return _kept.value
    }

    private fun keys(k: Kept) {
        artistKeys = k.artists.map { it.lowercase().trim() }.toHashSet()
        songKeys = k.songs.flatMap { listOf(it.id, Radio.key(it.title, it.artist)) }.filter { it.isNotEmpty() }.toHashSet()
    }

    /** Each of a song's artists, as compared: "A, B & C feat. D" is four. */
    private fun whoAll(artist: String) = artist.lowercase()
        .split(Regex(""",|&| x | feat\.? | ft\.? | featuring | with """)).map { it.trim() }.filter { it.isNotEmpty() }

    /** Whether this is kept out: its artist (any of them) blocked, or the song itself. Read as last loaded. */
    fun hides(title: String, artist: String, id: String = ""): Boolean =
        (artistKeys.isNotEmpty() && whoAll(artist).any { it in artistKeys }) ||
            (songKeys.isNotEmpty() && ((id.isNotEmpty() && id in songKeys) || Radio.key(title, artist) in songKeys))

    fun hides(s: Song) = hides(s.title, s.artist, s.id)

    /** Whether any of these artists is blocked. */
    fun artist(name: String) = artistKeys.isNotEmpty() && whoAll(name).any { it in artistKeys }

    fun blockArtist(c: Context, name: String) {
        val who = name.substringBefore(", ").trim().ifEmpty { return }
        val k = load(c); set(c, k.copy(artists = (k.artists + who).distinctBy { it.lowercase() }))
    }
    fun blockSong(c: Context, s: Song) { val k = load(c); set(c, k.copy(songs = (k.songs + Said(s.id, s.title, s.artist)).distinctBy { it.id })) }
    fun unblockArtist(c: Context, name: String) { val k = load(c); set(c, k.copy(artists = k.artists.filterNot { it.equals(name, true) })) }
    fun unblockSong(c: Context, id: String) { val k = load(c); set(c, k.copy(songs = k.songs.filterNot { it.id == id })) }

    @Synchronized private fun set(c: Context, k: Kept) {
        keys(k); _kept.value = k
        runCatching {
            Keep.write(file(c), JSONObject().put("artists", JSONArray(k.artists))
                .put("songs", JSONArray(k.songs.map { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist) })).toString())
        }
        readAt = file(c).lastModified()
    }
}

/** A list of suggestions as shown: what's blocked left out, at once when something's blocked. */
@Composable
fun <T> List<T>.unblocked(said: (T) -> Pair<String, String>): List<T> {
    val c = LocalContext.current
    remember { Blocked.load(c) }
    val k by Blocked.kept.collectAsState()
    return remember(this, k) { if (k.artists.isEmpty() && k.songs.isEmpty()) this else filterNot { said(it).let { (t, a) -> Blocked.hides(t, a) } } }
}
