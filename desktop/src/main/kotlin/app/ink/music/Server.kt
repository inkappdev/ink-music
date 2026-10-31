package app.ink.music

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
 * Your own server: Navidrome, or any Subsonic one, over the Subsonic API (the phone's Server.kt, the
 * parts the Mac needs so far). Signing in keeps the address, the name and a salted token (md5 of the
 * password and a random salt, as the API asks), never the password. Every song comes down once and is
 * kept on disk, so the library opens at once and catches up behind; songs stream as they are (the
 * originals: a computer's on the home network), covers come by size.
 */
object Server {
    /** Who's signed in, where. `base` has no trailing slash. */
    data class Account(val base: String, val user: String, val salt: String, val token: String, val name: String)

    enum class SignIn { OK, WRONG, UNREACHABLE, NOT_A_SERVER }

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account
    /** Songs on the server, last read; and whether a read is under way. */
    val count = MutableStateFlow(0)
    val syncing = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountFile = File(Home.dir, "server-account.json")
    private val songsFile = File(Home.dir, "server-songs.json")
    private val folderFile = File(Home.dir, "server-library.json")

    /** The server's libraries (Navidrome's, its music folders), id to name: a choice only when there's more than one. */
    val folders = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** The one library read (a tester's ask, 2026-10-30), or null: all of them. */
    val folder = MutableStateFlow(runCatching { folderFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() })

    /** Only `id`'s songs (null: every library), read again. */
    fun choose(id: String?) {
        folder.value = id
        runCatching { if (id == null) folderFile.delete() else Home.write(folderFile, id) }
        sync()
    }

    // the chosen library, said to every ask that takes it
    private fun inFolder(): Array<Pair<String, Any>> = folder.value?.let { arrayOf<Pair<String, Any>>("musicFolderId" to it) } ?: emptyArray()
    private const val AGENT = "ink music (desktop)"

    private fun read(): Account? = runCatching {
        val o = JSONObject(accountFile.readText())
        Account(o.getString("base"), o.getString("user"), o.getString("salt"), o.getString("token"), o.optString("name", "server"))
    }.getOrNull()

    /** The account and the songs kept, then a fresh read behind. */
    fun start() {
        val a = read() ?: return
        _account.value = a
        scope.launch {
            kept()?.let { Library.putServer(it); count.value = it.size }
            sync()
        }
    }

    suspend fun signIn(address: String, user: String, password: String): SignIn = withContext(Dispatchers.IO) {
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
        Home.write(accountFile, JSONObject().put("base", named.base).put("user", named.user).put("salt", named.salt)
            .put("token", named.token).put("name", named.name).toString())
        _account.value = named
        sync()
        SignIn.OK
    }

    fun signOut() {
        accountFile.delete()
        // a paired phone's sign-in isn't put back over this one
        SyncHub.kept.signed("server", System.currentTimeMillis())
        songsFile.delete()
        _account.value = null
        count.value = 0
        Library.putServer(emptyList())
    }

    /** Every song on the server, read anew: the records first (for their artists), then the songs. */
    fun sync() {
        val a = _account.value ?: return
        if (syncing.value) return
        syncing.value = true
        scope.launch {
            try {
                // its libraries, for the choice of one (and one gone from it: all again)
                runCatching {
                    val f = call(a, "getMusicFolders")?.optJSONObject("musicFolders")?.optJSONArray("musicFolder")
                    folders.value = f?.let { l -> (0 until l.length()).mapNotNull { l.optJSONObject(it) }.map { it.optString("id") to it.optString("name") } }.orEmpty()
                    if (folder.value != null && folders.value.none { it.first == folder.value }) { folder.value = null; folderFile.delete() }
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
                        // a star is a love (the phone's and every other app's): when it was made, where it says
                        if (it.optString("starred").isNotEmpty()) {
                            starred += "n" + it.optString("id")
                            runCatching { java.time.Instant.parse(it.optString("starred")).toEpochMilli() }.getOrNull()?.let { at -> starredAt["n" + it.optString("id")] = at }
                        }
                    }
                    if (list.length() < 500) break
                    offset += 500
                }
                if (songs.isNotEmpty() || offset == 0) {
                    readPlaylists()
                    Loved.putServer(starred, starredAt)
                    Library.putServer(songs)
                    count.value = songs.size
                    runCatching { Home.write(songsFile, JSONArray().apply { songs.forEach { put(it.json()) } }.toString()) }
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
            path = "",   // its stream asked for as it plays ([stream]): the link carries the sign-in
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
            bits = o.optInt("bitDepth"),
            rate = o.optInt("samplingRate"),
            format = o.optString("suffix").lowercase(),
            art = o.optString("coverArt").ifEmpty { null },
            // when the server took it in (its "created"), for albums' added order
            modified = runCatching { java.time.Instant.parse(o.optString("created")).toEpochMilli() }.getOrDefault(0L),
        )
    }

    /** Where a server song streams from, signed as whoever's signed in now. */
    fun stream(s: Song, mp3: Boolean = false): String? = _account.value?.let {
        if (mp3) url(it, "stream", "id" to s.id.removePrefix("n"), "format" to "mp3", "maxBitRate" to 320) else url(it, "stream", "id" to s.id.removePrefix("n"))
    }

    /** A record's cover from the server, at about `px` a side, as the server sends it (a JPEG, a PNG); null if there's none. */
    /**
     * A record's cover at about `px`. A few at a time: Navidrome turns away covers asked for all at once ("429 Too
     * Many Requests"; Bronson's Mac, 2026-10-29, the albums page after a rescan changed every cover's link: 271 of
     * 1,941 refused), so a turned-away one waits and asks again. Null for no picture; [Busy] when the server couldn't
     * say (away, slow, still busy), so it's asked again later rather than taken as none.
     */
    fun cover(art: String, px: Int): ByteArray? {
        val a = _account.value ?: return null
        covering.acquire()
        try {
            repeat(4) { i ->
                val c = URL(url(a, "getCoverArt", "id" to art, "size" to px)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", AGENT)
                try {
                    val code = c.responseCode
                    reached()
                    if (code == 429 || code == 503) { Thread.sleep(400L shl i); return@repeat }
                    return if (code != 200 || c.contentType?.startsWith("image") != true) null else c.inputStream.use { it.readBytes() }
                } catch (e: java.io.IOException) {
                    failed(e)
                    if (i >= 1) throw Busy()
                } finally { c.disconnect() }
            }
            throw Busy()
        } finally { covering.release() }
    }

    /** The server couldn't say whether there's a cover: asked again later. */
    class Busy : java.io.IOException("the server's busy or away")
    private val covering = java.util.concurrent.Semaphore(4)

    /** The records added to the server last (the library's own, by its record ids). */
    suspend fun newest(n: Int): List<String> = withContext(Dispatchers.IO) {
        val a = _account.value ?: return@withContext emptyList()
        val list = runCatching { call(a, "getAlbumList2", "type" to "newest", "size" to n, *inFolder())?.optJSONObject("albumList2")?.optJSONArray("album") }
            .getOrNull() ?: return@withContext emptyList()
        (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("id") }.map { "na$it" }
    }

    /** A playlist on the server: its name, how many songs, and whether it's yours (only yours can change). */
    data class Playlist(val id: String, val name: String, val count: Int, val mine: Boolean, val art: String? = null)

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    /** The server's playlists, read anew. */
    fun readPlaylists() {
        val a = _account.value ?: return
        scope.launch {
            val list = runCatching { call(a, "getPlaylists")?.optJSONObject("playlists")?.optJSONArray("playlist") }.getOrNull() ?: return@launch
            _playlists.value = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .map { Playlist(it.optString("id"), it.optString("name").ifEmpty { "untitled" }, it.optInt("songCount"),
                    it.optString("owner").let { o -> o.isEmpty() || o == a.user }, it.optString("coverArt").ifEmpty { null }) }
                .sortedBy { Library.sortName(it.name) }
        }
    }

    /** A playlist's songs, in its order (the library's own where it has them; one it hasn't read yet, made from the answer). */
    suspend fun playlist(id: String): List<Song> = withContext(Dispatchers.IO) {
        val a = _account.value ?: return@withContext emptyList()
        val entries = runCatching { call(a, "getPlaylist", "id" to id)?.optJSONObject("playlist")?.optJSONArray("entry") }.getOrNull()
            ?: return@withContext emptyList()
        (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.map { o -> Library.song("n" + o.optString("id")) ?: song(a, o, emptyMap()) }
    }

    /** Tells the server a song was heard (`done`: played enough to count, else "playing now"). A miss is let go. */
    fun scrobble(songId: String, done: Boolean) {
        val a = _account.value ?: return
        scope.launch { runCatching { call(a, "scrobble", "id" to songId.removePrefix("n"), "submission" to done) } }
    }

    /**
     * Songs like this one in your library (Navidrome asks Last.fm, then plays what you have; blocking). A song not
     * the server's is found there by its title and artist first.
     */
    fun similar(id: String, title: String, artist: String): List<Song> {
        val a = _account.value ?: return emptyList()
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
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { Library.song("n" + it.optString("id")) ?: song(a, it, emptyMap()) }
                .filter { it.id != "n$sid" }
        }.getOrDefault(emptyList())
    }

    data class Played(val face: Song, val plays: Int)

    /** The records played most on the server, all time (Navidrome counts every app's plays). */
    suspend fun mostPlayed(n: Int): List<Played> = withContext(Dispatchers.IO) {
        val a = _account.value ?: return@withContext emptyList()
        val list = runCatching { call(a, "getAlbumList2", "type" to "frequent", "size" to n, *inFolder())?.optJSONObject("albumList2")?.optJSONArray("album") }
            .getOrNull() ?: return@withContext emptyList()
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optInt("playCount") > 0 }.map { o ->
            // a record's face: enough of a song to show its cover and its name
            val face = Song(id = "na" + o.optString("id"), path = "", title = o.optString("name"), artist = o.optString("artist"),
                album = o.optString("name"), albumId = "na" + o.optString("id"), albumArtist = o.optString("artist"),
                duration = 0, disc = 1, track = 0, year = o.optInt("year"), art = o.optString("coverArt").ifEmpty { null })
            Played(face, o.optInt("playCount"))
        }
    }

    /** A song's star on the server, on or off (a love). */
    fun star(songId: String, on: Boolean) {
        val a = _account.value ?: return
        runCatching { call(a, if (on) "star" else "unstar", "id" to songId.removePrefix("n")) }
    }

    fun url(a: Account, method: String, vararg params: Pair<String, Any>, client: String = "inkmusic"): String {
        val q = (listOf("u" to a.user, "t" to a.token, "s" to a.salt, "v" to "1.16.1", "c" to client, "f" to "json") + params)
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v.toString(), "UTF-8") }
        return "${a.base}/rest/$method?$q"
    }

    // ---- the queue kept on the server (Sync, away from the wifi) ----

    /** Who saved a queue: this app, so its own isn't offered back to it. */
    private const val QUEUE_CLIENT = "inkmusic-desktop"

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
            val code = c.responseCode
            reached()
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: return null
            runCatching { JSONObject(body).optJSONObject("subsonic-response") }.getOrNull()
        } catch (e: java.io.IOException) { failed(e); throw e } finally { c.disconnect() }
    }

    /**
     * macOS keeping ink music off the home network (Bronson's Mac, 2026-10-29: every new build is a new app to it, and
     * till it's allowed again under Local Network every connection fails "No route to host", the covers blank and
     * nothing said). True while that's so; said on listen and the library, with where to allow it.
     */
    val shut = MutableStateFlow(false)
    private fun reached() { if (shut.value) shut.value = false }
    private fun failed(e: java.io.IOException) {
        if (Home.mac && (e is java.net.NoRouteToHostException || e.message?.contains("No route to host", ignoreCase = true) == true)) shut.value = true
    }

    private fun kept(): List<Song>? = runCatching {
        val arr = JSONArray(songsFile.readText())
        (0 until arr.length()).map { i -> songOf(arr.getJSONObject(i)) }
    }.getOrNull()

    /** Navidrome files a song with no album tag under "[Unknown Album]": no record. */
    private fun record(name: String) = if (name == "[Unknown Album]") "" else name

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
