package app.ink.music

/** A YouTube Music record found: its link, name, artist and cover (the full build's only). */
data class TubeAlbum(val url: String, val title: String, val artist: String, val art: String?)

/** A mood or genre on YouTube Music's own page of them (chill, focus...; african to soundtracks); its `params` open it. */
data class TubeMood(val title: String, val params: String, val genre: Boolean)

/** What a YouTube Music search found: songs, records, artists, playlists. */
data class TubeFound(
    val songs: List<Song> = emptyList(), val albums: List<TubeAlbum> = emptyList(),
    val artists: List<TubeArtist> = emptyList(), val lists: List<TubeAlbum> = emptyList(),
)

/** An artist on YouTube Music: their name and photo (the onboarding's faces), and their page ("UC...") when known. */
data class TubeArtist(val name: String, val face: String?, val id: String = "")

/** Who's signed in to YouTube Music: their name, and their handle or email. */
data class TubeWho(val name: String, val handle: String)

/** One of your playlists on YouTube Music: its id (no "VL"), name, what it says of itself, cover. */
data class TubeList(val id: String, val name: String, val said: String, val art: String?)

/**
 * An artist as YouTube Music knows them, for their page: their photo (wide), what's said of them,
 * how many listen a month, their best-known songs, records and singles, and the artists like them.
 */
data class ArtistInfo(
    val id: String, val name: String, val photo: String?, val about: String, val audience: String,
    val top: List<Song>, val albums: List<TubeAlbum>, val singles: List<TubeAlbum>, val like: List<TubeArtist>,
)

/**
 * YouTube Music playlists hidden from ink music (#1, 2026-10-30: a signed-in library brings every playlist, music or
 * not): out of lists and find, your YouTube untouched. Their ids, kept in files/youtube-hidden.json.
 */
object HiddenLists {
    val ids = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    private var file: java.io.File? = null

    fun load(c: android.content.Context) {
        if (file != null) return
        val f = java.io.File(c.filesDir, "youtube-hidden.json").also { file = it }
        ids.value = runCatching { org.json.JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
    }

    fun set(id: String, hidden: Boolean) {
        ids.value = if (hidden) ids.value + id else ids.value - id
        runCatching { file?.writeText(org.json.JSONArray(ids.value.toList()).toString()) }
    }
}
