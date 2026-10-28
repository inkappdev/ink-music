package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A song, from a folder on the computer or the server. Ids carry their source as the phone's do:
 * "l<hash of its path>" a file, its record "la<hash of the record's name and whose it is>"; "n<id>"
 * a server song, its record "na<id>".
 */
data class Song(
    val id: String,
    val path: String,          // the file ("" for a server song: its stream is asked for as it plays)
    val title: String,
    val artist: String,        // as said: "air, beth hirsch"
    val album: String,
    val albumId: String,
    val albumArtist: String,   // whose record it is: the album artist, else the first artist
    val duration: Long,        // ms
    val disc: Int,
    val track: Int,
    val year: Int,
    val bits: Int = 0,
    val rate: Int = 0,
    val format: String = "",
    val modified: Long = 0,    // the file's, so a library read again reads only what changed
    val art: String? = null,   // the server's cover id
) {
    val server get() = id.startsWith("n")
    /** From YouTube Music. */
    val tube get() = id.startsWith("y")

    /** As said: "flac 24/96", "flac 16/44.1", "mp3". */
    val quality get() = listOfNotNull(format.takeIf { it.isNotEmpty() },
        if (bits > 0 && rate > 0) "$bits/" + (rate / 1000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } else null).joinToString(" ")
}

fun Song.json(): JSONObject = JSONObject().put("id", id).put("path", path).put("title", title).put("artist", artist)
    .put("album", album).put("albumId", albumId).put("albumArtist", albumArtist).put("duration", duration)
    .put("disc", disc).put("track", track).put("year", year).put("bits", bits).put("rate", rate)
    .put("format", format).put("modified", modified).put("art", art ?: "")

fun songOf(o: JSONObject) = Song(o.getString("id"), o.getString("path"), o.getString("title"), o.getString("artist"),
    o.getString("album"), o.getString("albumId"), o.getString("albumArtist"), o.getLong("duration"), o.getInt("disc"),
    o.getInt("track"), o.getInt("year"), o.optInt("bits"), o.optInt("rate"), o.optString("format"), o.optLong("modified"),
    o.optString("art").ifEmpty { null })

/** A record: its songs in disc and track order. */
data class Album(val id: String, val title: String, val artist: String, val year: Int, val songs: List<Song>) {
    val length get() = songs.sumOf { it.duration }
}

/**
 * Every song in the music folder (~/Music, every folder under it), read by its tags (jaudiotagger)
 * and kept on disk, so the library opens at once and catches up behind: a file read again only when
 * it's new or changed. Records are grouped by name and album artist, so a compilation stays one.
 */
object Library {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums
    /** Read at least once (an empty library is then really empty). */
    val read = MutableStateFlow(false)
    @Volatile private var byId: Map<String, Song> = emptyMap()
    private var local: List<Song> = emptyList()
    private var remote: List<Song> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kept = File(Home.dir, "library.json")
    private val sounds = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "wv", "ape", "alac")

    /** ~/Music (-Dink.folder=... another, to try it on another library). */
    val folder: File get() = File(System.getProperty("ink.folder") ?: (System.getProperty("user.home") + "/Music"))
    /** The folder as said: ~/music. */
    val said: String get() = folder.path.replaceFirst(System.getProperty("user.home"), "~").lowercase()

    fun song(id: String): Song? = byId[id] ?: if (id.startsWith("y")) YouTube.known(id) else null

    // jaudiotagger says a great deal about every file it reads; held, or the logger (and its quiet) is collected
    private val quiet = java.util.logging.Logger.getLogger("org.jaudiotagger").apply { level = java.util.logging.Level.OFF }

    fun start() {
        scope.launch {
            val before = runCatching { JSONArray(kept.readText()).let { a -> (0 until a.length()).map { songOf(a.getJSONObject(it)) } } }
                .getOrDefault(emptyList())
            if (before.isNotEmpty()) { putLocal(before); read.value = true }
            val known = before.associateBy { it.path }
            val now = folder.walkTopDown().filter { it.isFile && it.extension.lowercase() in sounds && !it.name.startsWith("._") }
                .mapNotNull { f -> known[f.path]?.takeIf { it.modified == f.lastModified() } ?: tags(f) }.toList()
            putLocal(now)
            read.value = true
            if (now != before) runCatching {
                Home.write(kept, JSONArray().apply { now.forEach { put(it.json()) } }.toString())
            }
        }
    }

    /** A file as a song, by its tags; its name for its title when it has none. */
    private fun tags(f: File): Song? = try {
        val a = AudioFileIO.read(f)
        val t = a.tag
        fun tag(k: FieldKey) = runCatching { t?.getFirst(k) }.getOrNull()?.trim().orEmpty()
        val artist = said(tag(FieldKey.ARTIST))
        val album = tag(FieldKey.ALBUM)
        val albumArtist = said(tag(FieldKey.ALBUM_ARTIST)).takeIf { it != "unknown" } ?: artist.substringBefore(", ")
        val h = a.audioHeader
        Song(
            id = "l" + hash(f.path),
            path = f.path,
            title = tag(FieldKey.TITLE).ifEmpty { f.nameWithoutExtension },
            artist = artist,
            album = album,
            albumId = "la" + hash(plain(album) + "/" + plain(albumArtist)),
            albumArtist = albumArtist,
            duration = (h.preciseTrackLength * 1000).toLong(),
            disc = tag(FieldKey.DISC_NO).substringBefore('/').trim().toIntOrNull()?.coerceAtLeast(1) ?: 1,
            track = tag(FieldKey.TRACK).substringBefore('/').trim().toIntOrNull() ?: 0,
            year = Regex("""\d{4}""").find(tag(FieldKey.YEAR))?.value?.toInt() ?: 0,
            bits = h.bitsPerSample, rate = h.sampleRateAsNumber,
            format = f.extension.lowercase().let { if (it == "m4a" && h.isLossless) "alac" else it },
            modified = f.lastModified(),
        )
    } catch (e: Exception) { null }   // not a sound it can read: not shown

    @Synchronized private fun putLocal(songs: List<Song>) { local = songs; publish() }

    /** The server's songs, all of them (an empty list: signed out). */
    @Synchronized fun putServer(songs: List<Song>) { remote = songs; publish() }

    private fun publish() {
        val all = local + remote
        // a song with no record isn't shown as one
        val albums = all.filter { it.album.isNotEmpty() }.groupBy { it.albumId }.map { (id, songs) ->
            val first = songs.first()
            Album(
                id, first.album, songs.groupingBy { it.albumArtist }.eachCount().maxBy { it.value }.key,
                songs.maxOf { it.year }, songs.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() })),
            )
        }.sortedWith(compareBy({ sortName(it.artist) }, { it.year }, { it.title.lowercase() }))
        byId = all.associateBy { it.id }
        _songs.value = all.sortedBy { sortName(it.title) }
        _albums.value = albums
    }

    private fun hash(s: String) = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    private fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** "Air; Beth Hirsch" or "Air / Beth Hirsch" as said: "Air, Beth Hirsch". None is "unknown". */
    private fun said(s: String) = s.split(';', '\u0000').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "unknown" }

    /** Filed as a shop files it: "The" and "A" set aside, case ignored. */
    fun sortName(s: String) = s.lowercase().removePrefix("the ").removePrefix("a ").trim()
}

/** Where ink music keeps its own files: ~/Library/Application Support/ink music (a Mac), ~/.local/share/ink-music. */
object Home {
    val mac = System.getProperty("os.name").lowercase().startsWith("mac")
    val dir: File = (if (mac) File(System.getProperty("user.home"), "Library/Application Support/ink music")
        else File(System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"), "ink-music")).apply { mkdirs() }

    /** What may be thrown away and fetched again: ~/Library/Caches/ink music, ~/.cache/ink-music. */
    val cache: File = (if (mac) File(System.getProperty("user.home"), "Library/Caches/ink music")
        else File(System.getenv("XDG_CACHE_HOME") ?: (System.getProperty("user.home") + "/.cache"), "ink-music")).apply { mkdirs() }

    /** Written whole or not at all. */
    fun write(f: File, text: String) {
        val tmp = File(f.path + ".part")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}
