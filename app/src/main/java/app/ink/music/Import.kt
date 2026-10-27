package app.ink.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bringing playlists in from Spotify, by a file the listener already has the right to (Spotify's own API is
 * closed to small apps, and its pages aren't ours to read): a CSV from Exportify (exportify.app: one playlist a
 * file, or all of them zipped as CSVs), or the playlists file of Spotify's own data download ("Playlist1.json",
 * every playlist in one). Each song matched to one that plays: your own (the phone's, your server's) first, else
 * YouTube Music's (the full build). Then made where you keep playlists, or played as it is.
 */
object Import {
    /** A song as the file says it. */
    data class Said(val title: String, val artist: String, val album: String)

    /** A playlist as the file has it. */
    data class Listed(val name: String, val songs: List<Said>)

    /** The playlists in a file (one for an Exportify CSV, every one for Spotify's own). */
    suspend fun read(c: Context, uri: Uri): List<Listed> = withContext(Dispatchers.IO) {
        val text = c.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return@withContext emptyList()
        val name = runCatching {
            c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull().orEmpty().substringBeforeLast('.').replace('_', ' ').ifEmpty { "from spotify" }
        if (text.trimStart().startsWith("{")) spotify(text) else listOfNotNull(csv(text, name))
    }

    /** Spotify's own download: {"playlists": [{"name", "items": [{"track": {"trackName", "artistName", "albumName"}}]}]}. */
    private fun spotify(text: String): List<Listed> {
        val lists = JSONObject(text).optJSONArray("playlists") ?: return emptyList()
        return (0 until lists.length()).mapNotNull { i ->
            val l = lists.optJSONObject(i) ?: return@mapNotNull null
            val items = l.optJSONArray("items") ?: JSONArray()
            val songs = (0 until items.length()).mapNotNull { j ->
                val t = items.optJSONObject(j)?.optJSONObject("track") ?: return@mapNotNull null
                Said(t.optString("trackName"), t.optString("artistName"), t.optString("albumName")).takeIf { it.title.isNotBlank() }
            }
            Listed(l.optString("name").ifEmpty { "playlist ${i + 1}" }, songs).takeIf { songs.isNotEmpty() }
        }
    }

    /** The same artist, near enough (YouTube's first answer can be someone else's song of the same name). */
    private fun same(a: String, b: String): Boolean {
        fun n(x: String) = x.lowercase().substringBefore(",").substringBefore(" & ").filter { it.isLetterOrDigit() }
        val x = n(a); val y = n(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }

    /** Exportify's CSV: a header row naming its columns ("Track Name", "Artist Name(s)", "Album Name", ...). */
    private fun csv(text: String, name: String): Listed? {
        val rows = rows(text)
        val head = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return null
        fun col(vararg names: String) = head.indexOfFirst { h -> names.any { h == it } }
        val title = col("track name", "title", "name").takeIf { it >= 0 } ?: return null
        val artist = col("artist name(s)", "artist name", "artist", "artists")
        val album = col("album name", "album")
        val songs = rows.drop(1).mapNotNull { r ->
            Said(r.getOrNull(title).orEmpty(), r.getOrNull(artist).orEmpty().substringBefore(';').substringBefore(", "), r.getOrNull(album).orEmpty())
                .takeIf { it.title.isNotBlank() }
        }
        return Listed(name, songs).takeIf { songs.isNotEmpty() }
    }

    /** CSV rows, quotes and all ("a, b" and "say ""hi""" kept whole; a quoted field may hold a line break). */
    private fun rows(text: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>(); val field = StringBuilder(); var quoted = false; var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> { row.add(field.toString()); field.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString()); field.clear()
                    if (row.any { it.isNotEmpty() }) out.add(row)
                    row = ArrayList()
                }
                else -> field.append(ch)
            }
            i++
        }
        row.add(field.toString()); if (row.any { it.isNotEmpty() }) out.add(row)
        return out
    }

    /**
     * Each song as one that plays, in order (null where none was found): your own first (by title and artist,
     * the way the indie chart matches), else YouTube Music's, eight asked at a time; [found] told as it goes.
     */
    suspend fun match(songs: List<Said>, found: (Int) -> Unit = {}): List<Song?> = coroutineScope {
        val mine = Library.songs.value.associateBy { Radio.key(it.title, it.artist) }
        var done = 0
        songs.chunked(8).flatMap { batch ->
            batch.map { s ->
                async(Dispatchers.IO) {
                    (mine[Radio.key(s.title, s.artist)] ?: if (YouTubeMusic.available) YouTubeMusic.find(s.title, s.artist)?.takeIf { same(it.artist, s.artist) } else null)
                        .also { synchronized(this@Import) { done++; found(done) } }
                }
            }.awaitAll()
        }
    }
}
