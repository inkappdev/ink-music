package app.ink.music

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bringing playlists in from Spotify, the parts both apps share (2026-11-23, the computer's import): your playlists and a
 * playlist's songs read as Spotify's own web player reads them (with its passes: the phone's sign-in, or the ones it hands
 * the computer), and the files Spotify's songs come in otherwise (Exportify's CSV, Spotify's own data download).
 */
object SpotifyLibrary {
    /** A song as Spotify or a file says it. */
    data class Said(val title: String, val artist: String, val album: String)
    /** One of your playlists on Spotify: its id ("liked" for your liked songs), its name, whose it is. */
    data class Theirs(val id: String, val name: String, val by: String)
    /** A playlist as a file has it. */
    data class Listed(val name: String, val songs: List<Said>)

    const val LIKED_ID = "liked"
    private const val LIBRARY = "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"
    private const val PLAYLIST = "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763"
    private const val LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"

    /** Your playlists, liked songs first; throws when Spotify wouldn't say. Blocking. */
    fun playlists(pass: SpotifyCanvas.Pass): List<Theirs> {
        val out = mutableListOf(Theirs(LIKED_ID, "liked songs", ""))
        var offset = 0
        while (true) {
            val vars = JSONObject().put("filters", JSONArray().put("Playlists")).put("order", JSONObject.NULL).put("textFilter", "")
                .put("features", JSONArray(listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS")))
                .put("limit", 50).put("offset", offset).put("flatten", true).put("expandedFolders", JSONArray())
                .put("folderUri", JSONObject.NULL).put("includeFoldersWhenFlattening", false)
            val lib = SpotifyCanvas.ask("libraryV3", LIBRARY, vars, pass).getJSONObject("data").getJSONObject("me").getJSONObject("libraryV3")
            val items = lib.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val w = items.optJSONObject(i)?.optJSONObject("item") ?: continue
                val d = w.optJSONObject("data") ?: continue
                if (d.optString("__typename") != "Playlist") continue
                val name = d.optString("name").ifBlank { continue }
                out += Theirs(w.optString("_uri").substringAfterLast(':'), name, d.optJSONObject("ownerV2")?.optJSONObject("data")?.optString("name").orEmpty())
            }
            offset += items.length()
            if (items.length() == 0 || offset >= lib.optInt("totalCount", offset)) break
        }
        return out
    }

    /** A playlist's songs (or your liked ones), all of them, [got] told how many as each page comes. Throws when Spotify wouldn't say. Blocking. */
    fun songs(id: String, pass: SpotifyCanvas.Pass, got: (Int) -> Unit = {}): List<Said> {
        val out = mutableListOf<Said>()
        var offset = 0
        while (true) {
            val (items, total) = if (id == LIKED_ID) {
                val t = SpotifyCanvas.ask("fetchLibraryTracks", LIKED, JSONObject().put("offset", offset).put("limit", 100), pass)
                    .getJSONObject("data").getJSONObject("me").getJSONObject("library").getJSONObject("tracks")
                (t.optJSONArray("items") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("track")?.optJSONObject("data") } } to t.optInt("totalCount")
            } else {
                val c = SpotifyCanvas.ask("fetchPlaylist", PLAYLIST, JSONObject().put("uri", "spotify:playlist:$id").put("offset", offset).put("limit", 100)
                    .put("enableWatchFeedEntrypoint", false), pass).getJSONObject("data").getJSONObject("playlistV2").getJSONObject("content")
                (c.optJSONArray("items") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("itemV2")?.optJSONObject("data") } } to c.optInt("totalCount")
            }
            items.forEach { d ->
                val title = d.optString("name").ifBlank { return@forEach }
                val artist = d.optJSONObject("artists")?.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("profile")?.optString("name").orEmpty()
                out += Said(title, artist, d.optJSONObject("albumOfTrack")?.optString("name").orEmpty())
            }
            offset += 100
            got(out.size)
            if (items.isEmpty() || offset >= total) break
        }
        return out
    }

    /** The playlists in a file's text: Spotify's own download (every playlist in it), else an Exportify CSV (one, `name`). */
    fun parse(text: String, name: String): List<Listed> =
        if (text.trimStart().startsWith("{")) download(text) else listOfNotNull(csv(text, name))

    /** Spotify's own download: {"playlists": [{"name", "items": [{"track": {"trackName", "artistName", "albumName"}}]}]}. */
    private fun download(text: String): List<Listed> {
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

    /** The same artist, near enough (a catalog's first answer can be someone else's song of the same name). */
    fun sameArtist(a: String, b: String): Boolean {
        fun n(x: String) = x.lowercase().substringBefore(",").substringBefore(" & ").filter { it.isLetterOrDigit() }
        val x = n(a); val y = n(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }
}
