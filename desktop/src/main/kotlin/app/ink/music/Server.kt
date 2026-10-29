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
                // whose each record is (a song says only who sings it)
                val recordArtist = HashMap<String, String>()
                var offset = 0
                while (true) {
                    val list = call(a, "getAlbumList2", "type" to "alphabeticalByName", "size" to 500, "offset" to offset)
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
                    val list = call(a, "search3", "query" to "", "songCount" to 500, "songOffset" to offset, "artistCount" to 0, "albumCount" to 0)
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
        )
    }

    /** Where a server song streams from, signed as whoever's signed in now. */
    fun stream(s: Song): String? = _account.value?.let { url(it, "stream", "id" to s.id.removePrefix("n")) }

    /** A record's cover from the server, at about `px` a side, as the server sends it (a JPEG, a PNG); null if there's none. */
    fun cover(art: String, px: Int): ByteArray? {
        val a = _account.value ?: return null
        val c = URL(url(a, "getCoverArt", "id" to art, "size" to px)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", AGENT)
        return try {
            if (c.responseCode != 200 || c.contentType?.startsWith("image") != true) null
            else c.inputStream.use { it.readBytes() }
        } catch (e: java.io.IOException) { null } finally { c.disconnect() }
    }

    /** The records added to the server last (the library's own, by its record ids). */
    suspend fun newest(n: Int): List<String> = withContext(Dispatchers.IO) {
        val a = _account.value ?: return@withContext emptyList()
        val list = runCatching { call(a, "getAlbumList2", "type" to "newest", "size" to n)?.optJSONObject("albumList2")?.optJSONArray("album") }
            .getOrNull() ?: return@withContext emptyList()
        (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("id") }.map { "na$it" }
    }

    /** A playlist on the server: its name, how many songs, and whether it's yours (only yours can change). */
    data class Playlist(val id: String, val name: String, val count: Int, val mine: Boolean)

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    /** The server's playlists, read anew. */
    fun readPlaylists() {
        val a = _account.value ?: return
        scope.launch {
            val list = runCatching { call(a, "getPlaylists")?.optJSONObject("playlists")?.optJSONArray("playlist") }.getOrNull() ?: return@launch
            _playlists.value = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .map { Playlist(it.optString("id"), it.optString("name").ifEmpty { "untitled" }, it.optInt("songCount"),
                    it.optString("owner").let { o -> o.isEmpty() || o == a.user }) }
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

    /** A song's star on the server, on or off (a love). */
    fun star(songId: String, on: Boolean) {
        val a = _account.value ?: return
        runCatching { call(a, if (on) "star" else "unstar", "id" to songId.removePrefix("n")) }
    }

    fun url(a: Account, method: String, vararg params: Pair<String, Any>): String {
        val q = (listOf("u" to a.user, "t" to a.token, "s" to a.salt, "v" to "1.16.1", "c" to "inkmusic", "f" to "json") + params)
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v.toString(), "UTF-8") }
        return "${a.base}/rest/$method?$q"
    }

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

    private fun kept(): List<Song>? = runCatching {
        val arr = JSONArray(songsFile.readText())
        (0 until arr.length()).map { i -> songOf(arr.getJSONObject(i)) }
    }.getOrNull()

    /** Navidrome files a song with no album tag under "[Unknown Album]": no record. */
    private fun record(name: String) = if (name == "[Unknown Album]") "" else name

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
