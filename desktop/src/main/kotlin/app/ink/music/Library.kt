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
    /** A radio station ([Stations]): live, no end, no record. */
    val station get() = id.startsWith("s")
    /** A podcast's episode ([Pods]): its link the path, its show the record. */
    val episode get() = id.startsWith("e")
    /** Past CD: more than 16 bits, or more than 48 kHz. */
    val hiRes get() = bits > 16 || rate > 48_000

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

    /**
     * ~/Music, or the folders chosen in settings, one a line (-Dink.folder=... another, to try it on another library);
     * several since 2026-11-10 (Reddit: music kept across drives and folders was half missing).
     */
    private val chosen = File(Home.dir, "folder")
    val folders: List<File> get() = System.getProperty("ink.folder")?.let { listOf(File(it)) }
        ?: runCatching { chosen.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().map(::File) }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: listOf(File(System.getProperty("user.home") + "/Music"))
    val folder: File get() = folders.first()
    /** Bumped when the folders change, so what shows them says the new ones. */
    val folderAt = MutableStateFlow(0)

    private fun keep(list: List<File>) {
        runCatching { Home.write(chosen, list.joinToString("\n") { it.path }) }
        folderAt.value++
        scan(fresh = true)
    }
    /** Only this folder for your files: read anew. */
    fun choose(f: File) = keep(listOf(f))
    /** Another folder beside the ones there (one inside another already read: nothing to add). */
    fun add(f: File) { if (folders.none { f.path == it.path }) keep(folders + f) }
    /** A folder let go (never the last). */
    fun remove(f: File) { if (folders.size > 1) keep(folders.filter { it.path != f.path }) }
    /** A folder as said: ~/music. */
    fun said(f: File): String = f.path.replaceFirst(System.getProperty("user.home"), "~").lowercase()
    /** The folders as said: ~/music, d:\\music. */
    val said: String get() = folders.joinToString(", ") { said(it) }

    fun song(id: String): Song? = byId[id] ?: when {
        id.startsWith("y") -> YouTube.known(id)
        id.startsWith("s") -> Stations.known(id)
        id.startsWith("e") -> Pods.song(id)
        else -> null
    }

    // jaudiotagger says a great deal about every file it reads; held, or the logger (and its quiet) is collected
    private val quiet = java.util.logging.Logger.getLogger("org.jaudiotagger").apply { level = java.util.logging.Level.OFF }

    fun start() = scan()

    private var scanning: kotlinx.coroutines.Job? = null
    private var scannedAt = 0L

    /** Read again when the app comes back to the front (a song added, a folder moved in), at most once a minute. */
    fun freshen() { if (System.currentTimeMillis() - scannedAt > 60_000) scan() }

    /**
     * The folder walked: what's kept shown at once, a file read again only when it's new or changed. `fresh`: a new
     * folder, nothing kept from the last.
     */
    fun scan(fresh: Boolean = false) {
        if (scanning?.isActive == true && !fresh) return
        scanning?.cancel()
        scannedAt = System.currentTimeMillis()
        scanning = scope.launch {
            val before = if (fresh) emptyList() else runCatching { JSONArray(kept.readText()).let { a -> (0 until a.length()).map { songOf(a.getJSONObject(it)) } } }
                .getOrDefault(emptyList())
            if (before.isNotEmpty() && local.isEmpty()) { putLocal(before); read.value = true }
            val known = (before + local).associateBy { it.path }
            // every folder walked, a file in two (one folder inside another) once
            val now = folders.asSequence().flatMap { it.walkTopDown() }.filter { it.isFile && it.extension.lowercase() in sounds && !it.name.startsWith("._") }
                .distinctBy { it.absolutePath }
                .mapNotNull { f -> known[f.path]?.takeIf { it.modified == f.lastModified() } ?: tags(f) }.toList()
            putLocal(now)
            read.value = true
            if (now != before) runCatching {
                Home.write(kept, JSONArray().apply { now.forEach { put(it.json()) } }.toString())
            }
        }
    }

    /** These files as songs (dropped on the window: anywhere, in the library or not); what isn't a sound is passed over. */
    fun read(files: List<File>): List<Song> {
        val known = local.associateBy { it.path }
        return files.filter { it.extension.lowercase() in sounds && !it.name.startsWith("._") }.mapNotNull { known[it.path] ?: tags(it) }
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
            // no record named: its own (2026-11-11, a tester's Video 1: every untagged file was one record, "" by "unknown",
            // and the first cover found for any showed on all of them)
            albumId = if (album.isBlank()) "lf" + hash(f.path) else "la" + hash(plain(album) + "/" + plain(albumArtist)),
            albumArtist = albumArtist,
            duration = (h.preciseTrackLength * 1000).toLong(),
            disc = tag(FieldKey.DISC_NO).substringBefore('/').trim().toIntOrNull()?.coerceAtLeast(1) ?: 1,
            track = tag(FieldKey.TRACK).substringBefore('/').trim().toIntOrNull() ?: 0,
            year = Regex("""\d{4}""").find(tag(FieldKey.YEAR))?.value?.toInt() ?: 0,
            bits = h.bitsPerSample, rate = h.sampleRateAsNumber,
            format = f.extension.lowercase().let { if (it == "m4a" && h.isLossless) "alac" else it },
            modified = f.lastModified(),
        )
    } catch (e: Exception) { byHand(f) }

    /**
     * A file jaudiotagger can't read (Opus; a FLAC with an ID3 tag in front): its tags read by hand (`Tags`), else its
     * name for its title, shown all the same (these were left out without a word, 2026-11-10).
     */
    private fun byHand(f: File): Song {
        val r = Tags.read(f)
        val artist = said(r?.of("ARTIST").orEmpty())
        val album = r?.of("ALBUM").orEmpty()
        val albumArtist = said(r?.of("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST").orEmpty()).takeIf { it != "unknown" } ?: artist.substringBefore(", ")
        return Song(
            id = "l" + hash(f.path),
            path = f.path,
            title = r?.of("TITLE")?.ifEmpty { null } ?: f.nameWithoutExtension,
            artist = artist,
            album = album,
            // no record named: its own (2026-11-11, a tester's Video 1: every untagged file was one record, "" by "unknown",
            // and the first cover found for any showed on all of them)
            albumId = if (album.isBlank()) "lf" + hash(f.path) else "la" + hash(plain(album) + "/" + plain(albumArtist)),
            albumArtist = albumArtist,
            duration = r?.durationMs ?: 0L,
            disc = r?.of("DISCNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            track = r?.of("TRACKNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
            year = Regex("""\d{4}""").find(r?.of("DATE", "YEAR", "ORIGINALDATE").orEmpty())?.value?.toInt() ?: 0,
            bits = r?.bits ?: 0, rate = r?.rate ?: 0,
            format = f.extension.lowercase(),
            modified = f.lastModified(),
        )
    }

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
    val windows = System.getProperty("os.name").lowercase().startsWith("windows")
    val dir: File = (when {
        mac -> File(System.getProperty("user.home"), "Library/Application Support/ink music")
        // Windows' own place for an app's things: %APPDATA%\ink music
        windows -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "ink music")
        else -> File(System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"), "ink-music")
    }).apply { mkdirs() }

    /**
     * What may be thrown away and fetched again: ~/Library/Caches/ink music, ~/.cache/ink-music, %LOCALAPPDATA%\ink\music.
     * Windows' was in the app's own folder (%LOCALAPPDATA%\ink music, where it's installed for you), and installing an
     * update uninstalls first and empties that folder: every cover, artist page and lyric fetched again each time
     * (2026-11-10). Moved beside it once, kept as it was.
     */
    val cache: File = (when {
        mac -> File(System.getProperty("user.home"), "Library/Caches/ink music")
        windows -> {
            val local = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            File(local, "ink\\music").also { now ->
                val was = File(local, "ink music\\cache")
                if (was.isDirectory && !now.exists()) runCatching { now.parentFile.mkdirs(); was.renameTo(now) }
            }
        }
        else -> File(System.getenv("XDG_CACHE_HOME") ?: (System.getProperty("user.home") + "/.cache"), "ink-music")
    }).apply { mkdirs() }

    /** Written whole or not at all. */
    fun write(f: File, text: String) {
        Keep.write(f, text)
    }
}
