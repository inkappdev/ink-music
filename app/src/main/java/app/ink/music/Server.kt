package app.ink.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Your own server: Navidrome, or any Subsonic one, over the Subsonic API. Signing in keeps the
 * address, the name and a salted token (md5 of the password and a random salt, as the API asks),
 * never the password. Every song comes down once (a few thousand in seconds) and is kept on disk,
 * so the library opens at once and catches up behind; songs stream, covers come by size.
 */
object Server {
    /** Who's signed in, where. `base` has no trailing slash. */
    data class Account(val base: String, val user: String, val salt: String, val token: String, val name: String)

    enum class SignIn { OK, WRONG, UNREACHABLE, NOT_A_SERVER }

    /** Android 17 asks before an app talks to the local network (a server at home); before it, INTERNET was enough. */
    val local: String? = if (android.os.Build.VERSION.SDK_INT >= 37) "android.permission.ACCESS_LOCAL_NETWORK" else null

    fun localAllowed(c: Context) = local == null || c.checkSelfPermission(local) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account
    /** Songs on the server, last read; and whether a read is under way. */
    val count = MutableStateFlow(0)
    val syncing = MutableStateFlow(false)
    /** Whether the server finds paths by how songs sound (OpenSubsonic's `sonicSimilarity`: Navidrome with AudioMuse-AI). */
    val sonic = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The server's libraries (Navidrome's music folders), id to name: a choice only when there's more than one. */
    val folders = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** The one library read (as the computer, 2026-10-30), or null: all of them. Kept in files/server-library.json. */
    val folder = MutableStateFlow<String?>(null)
    private fun folderFile(c: Context) = File(c.filesDir, "server-library.json")

    /** Only `id`'s songs (null: every library), read again. */
    fun choose(c: Context, id: String?) {
        folder.value = id
        runCatching { if (id == null) folderFile(c).delete() else folderFile(c).writeText(id) }
        sync()
    }

    // the chosen library, said to every ask that takes it
    private fun inFolder(): Array<Pair<String, Any>> = folder.value?.let { arrayOf<Pair<String, Any>>("musicFolderId" to it) } ?: emptyArray()
    private var app: Context? = null

    // kept as a file, not prefs: the player's process reads it too (scrobbles), and prefs each
    // process caches would miss a sign-in made in the other
    private fun accountFile(c: Context) = File(c.filesDir, "server-account.json")
    private fun songsFile(c: Context) = File(c.filesDir, "server-songs.json")

    fun read(c: Context): Account? = runCatching {
        val o = JSONObject(accountFile(c).readText())
        Account(o.getString("base"), o.getString("user"), o.getString("salt"), o.getString("token"), o.optString("name", "server"))
    }.getOrNull()

    /** The screens' process: the account and the songs kept, then a fresh read behind. */
    fun start(c: Context) {
        if (app != null) return
        app = c.applicationContext
        val a = read(c) ?: return
        _account.value = a
        scope.launch {
            kept(c)?.let { Library.putServer(it); count.value = it.size }
            Loved.putServer(c.getSharedPreferences("loved", Context.MODE_PRIVATE).getStringSet("server", emptySet())!!.toSet())
            sync()
        }
    }

    /** The player's process (the car, covers): the account and the songs kept, and the stars; no sync. */
    fun startQuiet(c: Context) {
        if (app != null) return
        app = c.applicationContext
        val a = read(c) ?: return
        _account.value = a
        kept(c)?.let { Library.putServer(it); count.value = it.size }
        Loved.putServer(c.getSharedPreferences("loved", Context.MODE_PRIVATE).getStringSet("server", emptySet())!!.toSet())
    }

    suspend fun signIn(c: Context, address: String, user: String, password: String): SignIn = withContext(Dispatchers.IO) {
        val base = address.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val a = Account(base, user.trim(), salt, md5(password + salt), "server")
        val answer = try { call(a, "ping") } catch (e: Exception) { return@withContext SignIn.UNREACHABLE }
            ?: return@withContext SignIn.NOT_A_SERVER
        if (answer.optString("status") != "ok") {
            // 40: wrong name or password; 41: token sign-in not allowed (LDAP servers)
            return@withContext if (answer.optJSONObject("error")?.optInt("code") in listOf(40, 41)) SignIn.WRONG else SignIn.NOT_A_SERVER
        }
        val named = a.copy(name = answer.optString("type").ifEmpty { "server" })
        accountFile(c).writeText(JSONObject().put("base", named.base).put("user", named.user).put("salt", named.salt)
            .put("token", named.token).put("name", named.name).toString())
        app = c.applicationContext
        _account.value = named
        scope.launch { sync() }
        SignIn.OK
    }

    /** The account written from a paired computer ([Sync]): read again, and the songs with it. */
    fun reload(c: Context) {
        val a = read(c) ?: return
        app = c.applicationContext
        _account.value = a
        scope.launch { sync() }
    }

    fun signOut(c: Context) {
        accountFile(c).delete()
        // a paired computer's sign-in isn't put back over this one
        runCatching { SyncPhone.kept.signed("server", System.currentTimeMillis()) }
        songsFile(c).delete()
        _account.value = null
        count.value = 0
        sonic.value = false
        Loved.putServer(emptySet())
        Library.putServer(emptyList())
    }

    /** Every song on the server, read anew: the records first (for their artists), then the songs. */
    fun sync() {
        val a = _account.value ?: return
        val c = app ?: return
        if (syncing.value) return
        syncing.value = true
        scope.launch {
            try {
                sonic.value = runCatching {
                    val ext = call(a, "getOpenSubsonicExtensions")?.optJSONArray("openSubsonicExtensions")
                    ext != null && (0 until ext.length()).any { ext.optJSONObject(it)?.optString("name") == "sonicSimilarity" }
                }.getOrDefault(sonic.value)
                // its libraries, for the choice of one (one gone from it: all again)
                if (folder.value == null) folder.value = runCatching { folderFile(c).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                runCatching {
                    val f = call(a, "getMusicFolders")?.optJSONObject("musicFolders")?.optJSONArray("musicFolder")
                    folders.value = f?.let { l -> (0 until l.length()).mapNotNull { l.optJSONObject(it) }.map { it.optString("id") to it.optString("name") } }.orEmpty()
                    if (folder.value != null && folders.value.none { it.first == folder.value }) { folder.value = null; folderFile(c).delete() }
                }
                                // whose each record is (a song says only who sings it)
                val recordArtist = HashMap<String, String>()
                var offset = 0
                while (true) {
                    val list = call(a, "getAlbumList2", "type" to "alphabeticalByName", "size" to 500, "offset" to offset, *inFolder())
                        ?.optJSONObject("albumList2")?.optJSONArray("album") ?: break
                    for (i in 0 until list.length()) list.optJSONObject(i)?.let { recordArtist[it.optString("id")] = it.optString("artist") }
                    if (list.length() < 500) break
                    offset += 500
                }
                val songs = ArrayList<Song>()
                val starred = HashSet<String>()
                val starredAt = HashMap<String, Long>()
                offset = 0
                while (true) {
                    val list = call(a, "search3", "query" to "", "songCount" to 500, "songOffset" to offset, "artistCount" to 0, "albumCount" to 0, *inFolder())
                        ?.optJSONObject("searchResult3")?.optJSONArray("song") ?: break
                    for (i in 0 until list.length()) list.optJSONObject(i)?.let {
                        songs += song(a, it, recordArtist)
                        if (it.optString("starred").isNotEmpty()) {
                            starred += "n" + it.optString("id")
                            runCatching { java.time.Instant.parse(it.optString("starred")).toEpochMilli() }.getOrNull()?.let { at -> starredAt["n" + it.optString("id")] = at }
                        }
                    }
                    if (list.length() < 500) break
                    offset += 500
                }
                if (songs.isNotEmpty() || offset == 0) {
                    Library.putServer(songs)
                    Loved.putServer(starred)
                    Loved.putServerDates(starredAt)
                    readPlaylists()
                    c.getSharedPreferences("loved", Context.MODE_PRIVATE).edit().putStringSet("server", starred).apply()
                    count.value = songs.size
                    keep(c, songs)
                }
            } catch (e: Exception) {
                // unreachable this time (away from home, say): what's kept stays
            } finally {
                syncing.value = false
            }
        }
    }

    private fun song(a: Account, o: JSONObject, recordArtist: Map<String, String>): Song {
        val id = o.optString("id")
        val album = o.optString("albumId")
        val artist = o.optString("displayArtist").ifEmpty { o.optString("artist") }.ifEmpty { "unknown" }
        return Song(
            id = "n$id",
            uri = Uri.parse(url(a, "stream", "id" to id)),
            title = o.optString("title").ifEmpty { "untitled" },
            artist = artist.replace("; ", ", ").replace(" • ", ", "),
            album = record(o.optString("album")),
            albumId = "na$album",
            albumArtist = (recordArtist[album] ?: o.optString("displayAlbumArtist").ifEmpty { artist.substringBefore(", ") })
                .ifEmpty { "unknown" },
            duration = o.optLong("duration") * 1000,
            disc = o.optInt("discNumber", 1).coerceAtLeast(1),
            track = o.optInt("track"),
            year = o.optInt("year"),
            art = o.optString("coverArt").ifEmpty { null },
            bits = o.optInt("bitDepth"),
            rate = o.optInt("samplingRate"),
            format = o.optString("suffix").lowercase(),
            mime = o.optString("contentType"),
            gain = gainOf(o, "trackGain", "trackPeak"),
            albumGain = gainOf(o, "albumGain", "albumPeak"),
            added = runCatching { java.time.Instant.parse(o.optString("created")).toEpochMilli() }.getOrDefault(0L),
        )
    }

    /** OpenSubsonic's ReplayGain (dB); a 0 gain with a 0 peak is none read. */
    private fun gainOf(o: JSONObject, gain: String, peak: String): Float? {
        val rg = o.optJSONObject("replayGain") ?: return null
        if (!rg.has(gain) || (rg.optDouble(gain) == 0.0 && rg.optDouble(peak, 0.0) == 0.0)) return null
        return rg.optDouble(gain).toFloat()
    }

    /** A record's cover from the server, at about `px` a side; null if there's none. */
    fun cover(art: String, px: Int): Bitmap? {
        val a = _account.value ?: app?.let(::read) ?: return null
        val c = URL(url(a, "getCoverArt", "id" to art, "size" to px)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        return try {
            if (c.responseCode != 200 || c.contentType?.startsWith("image") != true) null
            else c.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally { c.disconnect() }
    }

    /** A playlist on the server: its name, how many songs, and whether it's yours (only yours can change). */
    data class Playlist(val id: String, val name: String, val count: Int, val mine: Boolean, val art: String? = null)

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists
    /** Bumped on every change made here: a playlist's page reads it again. */
    val edited = MutableStateFlow(0)

    /** The server's playlists, read anew. */
    fun readPlaylists() {
        val a = _account.value ?: return
        scope.launch { readPlaylistsNow(a) }
    }

    private fun readPlaylistsNow(a: Account) {
        val list = runCatching { call(a, "getPlaylists")?.optJSONObject("playlists")?.optJSONArray("playlist") }.getOrNull() ?: return
        _playlists.value = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .map { Playlist(it.optString("id"), it.optString("name").ifEmpty { "untitled" }, it.optInt("songCount"),
                it.optString("owner").let { o -> o.isEmpty() || o == a.user }, it.optString("coverArt").ifEmpty { null }) }
            .sortedBy { Library.sortName(it.name) }
    }

    /** The server's playlists, asked now (blocking; the car's lists), else as last read. */
    fun playlistsNow(c: Context): List<Playlist> {
        val a = _account.value ?: read(c) ?: return emptyList()
        readPlaylistsNow(a)
        return _playlists.value
    }

    /** A playlist's songs, in its order (as the library has them, the phone's copy first; one the library hasn't read yet, made from the answer). */
    suspend fun playlist(id: String): List<Song> = withContext(Dispatchers.IO) {
        val a = _account.value ?: return@withContext emptyList()
        val entries = runCatching { call(a, "getPlaylist", "id" to id)?.optJSONObject("playlist")?.optJSONArray("entry") }.getOrNull()
            ?: return@withContext emptyList()
        (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.map { o ->
            val sid = "n" + o.optString("id")
            Library.mine(sid)?.let(Library::song) ?: Library.song(sid) ?: song(a, o, emptyMap())
        }
    }

    /** The id a song goes into a playlist by: the server's, or a phone song's twin there; null when the server hasn't it. */
    fun idOf(s: Song): String? = (if (s.server) s.id else Library.twin(s.id))?.removePrefix("n")

    /** A new playlist with `songs` in it, in order (none is fine); false when the server said no. */
    suspend fun makePlaylist(name: String, songs: List<Song>): Boolean = change {
        val params = listOf("name" to name) + songs.mapNotNull(::idOf).map { id -> "songId" to id }
        call(it, "createPlaylist", *params.toTypedArray())
    }

    /** `song` at the end of a playlist. */
    suspend fun addTo(playlist: String, song: Song): Boolean = change {
        call(it, "updatePlaylist", "playlistId" to playlist, "songIdToAdd" to (idOf(song) ?: return@change null))
    }

    /** The song at `index` (from 0) out of a playlist. */
    suspend fun removeFrom(playlist: String, index: Int): Boolean = change {
        call(it, "updatePlaylist", "playlistId" to playlist, "songIndexToRemove" to index)
    }

    suspend fun deletePlaylist(playlist: String): Boolean = change { call(it, "deletePlaylist", "id" to playlist) }

    /** A change to the server's playlists: made, then the list read again; true if the server said ok. */
    private suspend fun change(ask: (Account) -> JSONObject?): Boolean = withContext(Dispatchers.IO) {
        val a = _account.value ?: return@withContext false
        val ok = runCatching { ask(a)?.optString("status") == "ok" }.getOrDefault(false)
        if (ok) { readPlaylistsNow(a); edited.value++ }
        ok
    }

    /** The records added to the server last (the library's own, by its record ids). */
    suspend fun newest(n: Int): List<String> = withContext(Dispatchers.IO) {
        val a = _account.value ?: return@withContext emptyList()
        val list = runCatching { call(a, "getAlbumList2", "type" to "newest", "size" to n, *inFolder())?.optJSONObject("albumList2")?.optJSONArray("album") }
            .getOrNull() ?: return@withContext emptyList()
        (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("id") }.map { "na$it" }
    }

    /** A record as the server counts it: how often it's been played, by any app, ever. */
    data class Played(val face: Song, val plays: Int)

    /** The records played most on the server, all time (Navidrome counts every app's plays). */
    suspend fun mostPlayed(n: Int): List<Played> = withContext(Dispatchers.IO) {
        val a = _account.value ?: return@withContext emptyList()
        val list = runCatching { call(a, "getAlbumList2", "type" to "frequent", "size" to n, *inFolder())?.optJSONObject("albumList2")?.optJSONArray("album") }
            .getOrNull() ?: return@withContext emptyList()
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optInt("playCount") > 0 }.map { o ->
            // a record's face: enough of a song to show its cover and its name
            val face = Song(id = "na" + o.optString("id"), uri = Uri.EMPTY, title = o.optString("name"), artist = o.optString("artist"),
                album = o.optString("name"), albumId = "na" + o.optString("id"), albumArtist = o.optString("artist"),
                duration = 0, disc = 1, track = 0, year = o.optInt("year"), art = o.optString("coverArt").ifEmpty { null })
            Played(face, o.optInt("playCount"))
        }
    }

    /** Stars (or unstars) a server song: your love, as the server keeps it. */
    fun star(songId: String, on: Boolean) {
        val a = _account.value ?: return
        runCatching { call(a, if (on) "star" else "unstar", "id" to songId.removePrefix("n")) }
    }

    /**
     * Tells the server a song was heard (`done`: played enough to count, else "playing now"),
     * from the player's process. Nothing comes back that matters; a miss is let go.
     */
    /**
     * Songs like this one in your library (Navidrome asks Last.fm, then plays what you have), from
     * either process (blocking). A song not the server's is found there by its title and artist first.
     */
    fun similar(c: Context, id: String, title: String, artist: String): List<Song> {
        val a = _account.value ?: read(c) ?: return emptyList()
        return runCatching {
            val sid = if (id.startsWith("n")) id.removePrefix("n") else {
                val found = call(a, "search3", "query" to title, "songCount" to 10, "artistCount" to 0, "albumCount" to 0)
                    ?.optJSONObject("searchResult3")?.optJSONArray("song") ?: return emptyList()
                val who = artist.lowercase().substringBefore(",").trim()
                (0 until found.length()).mapNotNull { found.optJSONObject(it) }
                    .firstOrNull { it.optString("title").equals(title, true) && it.optString("artist").lowercase().contains(who) }
                    ?.optString("id") ?: return emptyList()
            }
            val list = call(a, "getSimilarSongs2", "id" to sid, "count" to 40)?.optJSONObject("similarSongs2")?.optJSONArray("song")
                ?: return emptyList()
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { song(a, it, emptyMap()) }.filter { it.id != "n$sid" }
        }.getOrDefault(emptyList())
    }

    /**
     * A path from one song to another, each a small step from the last by how they sound (the
     * server's `findSonicPath`), `from` first and `to` last; the phone's copy of a song where there's
     * one. Empty when the server found none, or doesn't find paths.
     */
    suspend fun path(from: Song, to: Song, count: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        val a = _account.value ?: return@withContext emptyList()
        val start = idOf(from) ?: return@withContext emptyList()
        val end = idOf(to) ?: return@withContext emptyList()
        val found = runCatching { call(a, "findSonicPath", "startSongId" to start, "endSongId" to end, "count" to count)?.optJSONArray("sonicMatch") }
            .getOrNull() ?: return@withContext emptyList()
        (0 until found.length()).mapNotNull { found.optJSONObject(it)?.optJSONObject("entry") }.map { o ->
            val sid = "n" + o.optString("id")
            Library.mine(sid)?.let(Library::song) ?: Library.song(sid) ?: song(a, o, emptyMap())
        }
    }

    fun scrobble(c: Context, songId: String, done: Boolean) {
        val a = read(c) ?: return
        runCatching { call(a, "scrobble", "id" to songId.removePrefix("n"), "submission" to done) }
    }

    fun url(a: Account, method: String, vararg params: Pair<String, Any>, client: String = "inkmusic"): String {
        val q = (listOf("u" to a.user, "t" to a.token, "s" to a.salt, "v" to "1.16.1", "c" to client, "f" to "json") + params)
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v.toString(), "UTF-8") }
        return "${a.base}/rest/$method?$q"
    }

    // ---- the queue kept on the server (Sync, away from the wifi) ----

    /** Who saved a queue: this app, so its own isn't offered back to it. */
    private const val QUEUE_CLIENT = "inkmusic-phone"

    /**
     * The queue saved on the server (Subsonic's savePlayQueue: its server songs, the one playing, where), so the other
     * device can carry on with it away from home. Nothing when what plays isn't the server's.
     */
    fun saveQueue(songs: List<Song>, index: Int, position: Long) {
        val a = _account.value ?: return
        val current = songs.getOrNull(index)?.takeIf { it.server } ?: return
        val from = (index - 50).coerceAtLeast(0)
        val ids = songs.subList(from, minOf(songs.size, index + 150)).filter { it.server }
        val params = ids.map { "id" to it.id.removePrefix("n") } + ("current" to current.id.removePrefix("n")) + ("position" to position)
        scope.launch {
            runCatching {
                val c = URL(url(a, "savePlayQueue", *params.toTypedArray(), client = QUEUE_CLIENT)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000; c.setRequestProperty("User-Agent", AGENT)
                try { c.inputStream.readBytes() } finally { c.disconnect() }
            }
        }
    }

    /** The queue the other device saved on the server: its songs (the ones here), which plays, where, when it was saved. */
    data class Saved(val songs: List<Song>, val index: Int, val position: Long, val at: Long)

    fun savedQueue(): Saved? = runCatching {
        val a = _account.value ?: return null
        val q = call(a, "getPlayQueue")?.optJSONObject("playQueue") ?: return null
        if (q.optString("changedBy") == QUEUE_CLIENT) return null
        val entries = q.optJSONArray("entry") ?: return null
        val songs = (0 until entries.length()).mapNotNull { Library.song("n" + entries.getJSONObject(it).optString("id")) }
        val index = songs.indexOfFirst { it.id == "n" + q.optString("current") }
        if (songs.isEmpty() || index < 0) return null
        Saved(songs, index, q.optLong("position"), runCatching { java.time.Instant.parse(q.optString("changed")).toEpochMilli() }.getOrDefault(0L))
    }.getOrNull()

    /** The answer's `subsonic-response`, or null when what came back isn't a Subsonic answer. Throws when unreachable. */
    private fun call(a: Account, method: String, vararg params: Pair<String, Any>): JSONObject? {
        val c = URL(url(a, method, *params)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", AGENT)
        return try {
            val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: return null
            runCatching { JSONObject(body).optJSONObject("subsonic-response") }.getOrNull()
        } finally { c.disconnect() }
    }

    private fun keep(c: Context, songs: List<Song>) = runCatching {
        songsFile(c).writeText(JSONArray().apply { songs.forEach { put(it.json()) } }.toString())
    }

    private fun kept(c: Context): List<Song>? = runCatching {
        val arr = JSONArray(songsFile(c).readText())
        (0 until arr.length()).map { i -> songOf(arr.getJSONObject(i)).let { it.copy(album = record(it.album)) } }
    }.getOrNull()

    /** Navidrome files a song with no album tag under "[Unknown Album]": no record. */
    private fun record(name: String) = if (name == "[Unknown Album]") "" else name

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
