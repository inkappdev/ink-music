package app.ink.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A song, from the phone's files or the server. Ids carry their source: "l123" a file (the media
 * index's id), "n<id>" a server song; records the same ("la..", "na..").
 */
data class Song(
    val id: String,
    val uri: Uri,              // where it plays from: the file, or the server's stream

    val title: String,
    val artist: String,        // as said: "air, beth hirsch"
    val album: String,
    val albumId: String,
    val albumArtist: String,   // whose record it is: the album artist, else the first artist
    val duration: Long,        // ms; 0 when the index couldn't read it (some FLACs): the player says once it plays
    val disc: Int,
    val track: Int,
    val year: Int,
    val art: String? = null,   // the server's cover id (a file's cover is read from the file)
    val bits: Int = 0,         // bit depth and sample rate (Hz), as the server says (0: not known)
    val rate: Int = 0,
    val format: String = "",   // "flac", "mp3": the file's own
    val mime: String = "",
    val gain: Float? = null,   // ReplayGain (dB), as the server read it from the file's tags
    val albumGain: Float? = null,
    val added: Long = 0,       // when it came in (ms): the phone's index's date added, the server's "created"; 0 not known
) {
    val server get() = id.startsWith("n")
    /** From YouTube Music (the full build only). */
    val tube get() = id.startsWith("y")
    /** A radio station ([Stations]): live, no end, no record. */
    val station get() = id.startsWith("s")
    /** Past CD: more than 16 bits, or more than 48 kHz. */
    val hiRes get() = bits > 16 || rate > 48_000
    /** As said: "flac 24/96", "flac 16/44.1", "mp3". */
    val quality get() = listOfNotNull(format.takeIf { it.isNotEmpty() },
        if (bits > 0 && rate > 0) "$bits/" + (rate / 1000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } else null).joinToString(" ")
}

/** A song as kept on disk (the server's songs, loved YouTube songs). */
fun Song.json(): JSONObject = JSONObject().put("id", id).put("uri", uri.toString()).put("title", title).put("artist", artist)
    .put("album", album).put("albumId", albumId).put("albumArtist", albumArtist).put("duration", duration)
    .put("disc", disc).put("track", track).put("year", year).put("art", art ?: "")
    .put("bits", bits).put("rate", rate).put("format", format).put("mime", mime)
    .apply { gain?.let { put("gain", it.toDouble()) }; albumGain?.let { put("albumGain", it.toDouble()) } }
    .put("added", added)

fun songOf(o: JSONObject) = Song(o.getString("id"), Uri.parse(o.getString("uri")), o.getString("title"), o.getString("artist"),
    o.getString("album"), o.getString("albumId"), o.getString("albumArtist"), o.getLong("duration"), o.getInt("disc"), o.getInt("track"),
    o.getInt("year"), o.optString("art").ifEmpty { null }, o.optInt("bits"), o.optInt("rate"), o.optString("format"), o.optString("mime"),
    o.optDouble("gain").takeIf { !it.isNaN() }?.toFloat(), o.optDouble("albumGain").takeIf { !it.isNaN() }?.toFloat(), o.optLong("added"))

/** A record: its songs in disc and track order. */
data class Album(val id: String, val title: String, val artist: String, val year: Int, val songs: List<Song>) {
    val length get() = songs.sumOf { it.duration }
}

/**
 * Every song: the phone's own (Android's media index: music, not ringtones or recordings, read
 * once and again whenever the index changes) and the server's ([Server]), as one library. Records
 * are grouped by album id; the record's artist is its album artist, so a compilation stays one.
 */
object Library {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums
    /**
     * Records you've hidden (#1, 2026-10-30: albums made of stray audio files), kept out of every tab, find and radio,
     * the files untouched; their songs still known by id (a queue that holds one plays on). Ids in files/hidden-records.json.
     */
    val hidden = MutableStateFlow<Set<String>>(emptySet())
    private val _hiddenAlbums = MutableStateFlow<List<Album>>(emptyList())
    val hiddenAlbums: StateFlow<List<Album>> = _hiddenAlbums
    private var hiddenFile: java.io.File? = null
    /** Read at least once (an empty library is then really empty). */
    val read = MutableStateFlow(false)
    private var byId: Map<String, Song> = emptyMap()
    private var local: List<Song> = emptyList()
    private var remote: List<Song> = emptyList()
    /** A phone's song that's on the server too: its server twin's id (its love is the twin's star). */
    @Volatile private var twins: Map<String, String> = emptyMap()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var again: Job? = null

    /** What reading the phone's music asks for: its own permission from Android 13, the storage one before (#2). */
    val permission = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(ctx: Context) =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun song(id: String): Song? = byId[id] ?: when {
        id.startsWith("y") -> YouTubeMusic.known(id)
        id.startsWith("s") -> Stations.known(id)
        else -> null
    }

    /** The server's copy of a phone's song, if it has one. */
    fun twin(id: String): String? = twins[id]

    /** A server song's copy on the phone, if the phone has it (played from there: no network). */
    fun mine(serverId: String): String? = twins.entries.firstOrNull { it.value == serverId }?.key

    /** The server's songs, all of them (an empty list: signed out). */
    @Synchronized fun putServer(songs: List<Song>) { remote = songs; publish() }

    /** Read the phone's songs again now (the folders chosen changed). */
    fun again(ctx: Context) { if (started) scope.launch { read(ctx.applicationContext) } }

    /** A record hidden from ink music, or shown again. */
    fun hide(ctx: Context, albumId: String, on: Boolean) {
        hidden.value = if (on) hidden.value + albumId else hidden.value - albumId
        runCatching { (hiddenFile ?: java.io.File(ctx.filesDir, "hidden-records.json")).writeText(org.json.JSONArray(hidden.value.toList()).toString()) }
        synchronized(this) { publish() }
    }

    fun start(ctx: Context) {
        Folders.load(ctx)
        if (hiddenFile == null) {
            val f = java.io.File(ctx.filesDir, "hidden-records.json").also { hiddenFile = it }
            hidden.value = runCatching { org.json.JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
        }
        if (started || !allowed(ctx)) return
        started = true
        val app = ctx.applicationContext
        scope.launch { read(app) }
        // a file added or tagged anew: read again, once the index has settled (it changes in bursts)
        app.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(self: Boolean) {
                    again?.cancel()
                    again = scope.launch { delay(1_500); read(app) }
                }
            })
    }

    /**
     * What counts as a song (#1, 2026-10-30: a library full of WhatsApp voice notes and clips): music, not a recording
     * (Android's own mark, from Android 12: 11 hasn't the column), and 30 seconds or more; nor anything kept where messaging apps and recorders keep their sound.
     * And, once you've chosen folders (settings, music folders), only what's in them ([Folders]).
     */
    private val SONGS = "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
        (if (android.os.Build.VERSION.SDK_INT >= 31) " AND ${MediaStore.Audio.Media.IS_RECORDING} = 0" else "") +
        // a length the index never read (0) isn't short: three of the S26's songs are so
        " AND (${MediaStore.Audio.Media.DURATION} >= 30000 OR ${MediaStore.Audio.Media.DURATION} = 0 OR ${MediaStore.Audio.Media.DURATION} IS NULL)"
    // the folders as those apps name them, matched whole (a band called Voices, a folder called Callum, stay)
    private val apps = listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.viber.voip", "whatsapp", "whatsapp business", "telegram", "signal")
    private val kept = setOf("recordings", "call recordings", "call", "voice recorder", "voice recordings", "sound recorder",
        "notifications", "ringtones", "alarms")

    private fun notMusic(path: String?): Boolean {
        val folders = path.orEmpty().lowercase().split('/').map { it.trim() }.filter { it.isNotEmpty() }
        return folders.any { it in kept || it in apps || apps.any { a -> it.startsWith("$a ") } }   // "whatsapp audio", "telegram audio"
    }

    private fun read(ctx: Context) {
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DISC_NUMBER, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DATE_ADDED,
        )
        val out = ArrayList<Song>()
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, SONGS, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(10)
                    if (notMusic(path)) continue
                    val chosen = Folders.chosen.value
                    if (chosen.isNotEmpty() && chosen.none { path.orEmpty().startsWith(it, ignoreCase = true) }) continue
                    val id = c.getLong(0)
                    val artist = said(c.getString(2))
                    // the index packs the disc into the track (1009: disc 1, track 9), or keeps it apart
                    val packed = c.getInt(7)
                    val disc = c.getString(9)?.substringBefore('/')?.trim()?.toIntOrNull() ?: (packed / 1000)
                    out += Song(
                        id = "l$id",
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        title = c.getString(1)?.trim().orEmpty().ifEmpty { "untitled" },
                        artist = artist,
                        album = c.getString(3)?.trim().orEmpty(),
                        albumId = "la" + c.getLong(4),
                        albumArtist = c.getString(5)?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown>" }?.let(::said)
                            ?: artist.substringBefore(", "),
                        duration = c.getLong(6).coerceAtLeast(0),
                        disc = disc.coerceAtLeast(1),
                        track = packed % 1000,
                        year = c.getInt(8),
                        added = c.getLong(11) * 1000,
                    )
                }
            }
        } catch (e: Exception) { return }   // the index unreadable this once: what's shown stays
        synchronized(this) { local = out; publish() }
        read.value = true
    }

    private fun publish() {
        // a record both on the phone and on the server (copied down from it) is shown once: the
        // phone's songs (no network to play them), the server's for any the phone hasn't
        val mine = local.filter { it.album.isNotEmpty() }.groupBy { recordKey(it) }
        val pairs = HashMap<String, String>()
        val theirs = remote.mapNotNull { s ->
            val there = mine[recordKey(s)] ?: return@mapNotNull s
            val same = there.firstOrNull { it.id !in pairs && sameSong(it, s) }
            if (same != null) { pairs[same.id] = s.id; null } else s.copy(albumId = there.first().albumId)
        }
        val all = local + theirs
        // a song with no record (Navidrome's "[Unknown Album]") isn't shown as one
        val albums = all.filter { it.album.isNotEmpty() }.groupBy { it.albumId }.map { (id, songs) ->
            val first = songs.first()
            Album(
                id, first.album, songs.groupingBy { it.albumArtist }.eachCount().maxBy { it.value }.key,
                songs.maxOf { it.year }, songs.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() })),
            )
        }.sortedWith(compareBy({ sortName(it.artist) }, { it.year }, { it.title.lowercase() }))
        // the twins stay findable by id (a queue kept, a playlist), though not listed
        byId = (all + remote.filter { it.id in pairs.values }).associateBy { it.id }
        twins = pairs
        val off = hidden.value
        _songs.value = all.filter { it.albumId !in off }.sortedBy { sortName(it.title) }
        _albums.value = albums.filter { it.id !in off }
        _hiddenAlbums.value = albums.filter { it.id in off }
        Loved.relink()
    }

    /** The same record wherever it's from: its name and whose it is, case and marks aside. */
    private fun recordKey(s: Song) = plain(s.album) + "/" + plain(s.albumArtist)

    /** The same song on one record: the same name, and the same place unless one doesn't know its place. */
    private fun sameSong(a: Song, b: Song) = plain(a.title) == plain(b.title) &&
        (a.track == 0 || b.track == 0 || (a.disc == b.disc && a.track == b.track))

    private fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** "Air; Beth Hirsch" as said: "Air, Beth Hirsch". "<unknown>" is no one. */
    private fun said(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown>" }
        ?.split(';').orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "unknown" }

    /** Filed as a shop files it: "The" and "A" set aside, case ignored. */
    fun sortName(s: String) = s.lowercase().removePrefix("the ").removePrefix("a ").trim()
}

/**
 * The phone's folders that hold your music, chosen in settings (Bronson, 2026-10-30, after #1): their paths as the
 * index keeps them ("Music/Phone/"), in files/folders.json. None chosen: every folder (less what [Library] leaves out).
 */
object Folders {
    val chosen = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    private var file: java.io.File? = null

    fun load(c: Context) {
        if (file != null) return
        val f = java.io.File(c.filesDir, "folders.json").also { file = it }
        chosen.value = runCatching { org.json.JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    /** A folder picked in Android's own chooser: its tree's path ("primary:Music/Phone" is "Music/Phone/"); the whole card, none. */
    fun pathOf(tree: android.net.Uri): String? = runCatching {
        android.provider.DocumentsContract.getTreeDocumentId(tree).substringAfter(':').trim('/').takeIf { it.isNotEmpty() }?.let { "$it/" }
    }.getOrNull()

    fun add(c: Context, path: String) = set(c, (chosen.value.filterNot { path.startsWith(it, true) || it.startsWith(path, true) } + path).sorted())
    fun remove(c: Context, path: String) = set(c, chosen.value - path)

    private fun set(c: Context, paths: List<String>) {
        chosen.value = paths
        runCatching { (file ?: java.io.File(c.filesDir, "folders.json")).writeText(org.json.JSONArray(paths).toString()) }
        Library.again(c)
    }
}
