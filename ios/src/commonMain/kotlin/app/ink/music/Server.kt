package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A record: your server's, or YouTube Music's (`youtube`: its browse id, its cover a link). */
data class Album(val id: String, val title: String, val artist: String, val cover: String?, val year: Int, val youtube: Boolean = false)
/**
 * A song, an episode or a station: `kind` says which ("server", "episode", "station"); `url` the stream where it isn't
 * the server's; `cover` a server cover id, or a picture's own link (http...).
 */
data class Song(val id: String, val title: String, val artist: String, val album: String, val albumId: String, val cover: String?,
                val duration: Long, val track: Int, val disc: Int, val kind: String = "server", val url: String? = null)

/**
 * Your Navidrome (Subsonic), as the phone and the computer read it: signed in once (a token and its salt kept, never the
 * password), the records, a record's songs, each song's stream as it is (raw) and its cover.
 */
object Server {
    data class Account(val base: String, val user: String, val token: String, val salt: String)

    val account = MutableStateFlow(load())

    /** The sign-in as the phone and the computer keep it (server-account.json, carried by sync), else as kept here before. */
    private fun load(): Account? {
        runCatching {
            val o = JSONObject(File(Here.dir, "server-account.json").readText())
            return Account(o.getString("base"), o.getString("user"), o.getString("token"), o.getString("salt"))
        }
        val base = Store.get("server.base") ?: return null
        return Account(base, Store.get("server.user") ?: return null, Store.get("server.token") ?: return null, Store.get("server.salt") ?: return null)
    }

    /** The sign-in file written by sync from the other device: read again. */
    fun reload() { account.value = load() }

    private fun keep(a: Account?) {
        val f = File(Here.dir, "server-account.json")
        if (a == null) { f.delete(); return }
        Keep.write(f, JSONObject().put("base", a.base).put("user", a.user).put("salt", a.salt).put("token", a.token).put("name", "server").toString())
    }

    private fun q(s: String) = s.encodeToByteArray().joinToString("") { b ->
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() && b >= 0 || c in "-_.~") c.toString() else "%" + (b.toInt() and 0xff).toString(16).uppercase().padStart(2, '0')
    }

    fun url(a: Account, method: String, vararg params: Pair<String, Any>): String =
        "${a.base}/rest/$method?u=${q(a.user)}&t=${a.token}&s=${a.salt}&v=1.16.1&c=inkmusic-iphone&f=json" +
            params.joinToString("") { (k, v) -> "&$k=${q(v.toString())}" }

    private suspend fun call(method: String, vararg params: Pair<String, Any>, a: Account? = account.value): JsonObject {
        val acc = a ?: error("not signed in")
        val o = Json.parseToJsonElement(fetch(url(acc, method, *params)).decodeToString()).jsonObject["subsonic-response"]!!.jsonObject
        if (o["status"]?.jsonPrimitive?.contentOrNull != "ok") error(o["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "the server said no")
        return o
    }

    /** Signed in: the address as typed (http:// put in front if it has none), checked with a ping, then kept. */
    suspend fun signIn(address: String, user: String, password: String) {
        var base = address.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://$base"
        val salt = (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val a = Account(base, user.trim(), md5(password + salt), salt)
        call("ping", a = a)
        keep(a)
        account.value = a
    }

    fun signOut() { listOf("server.base", "server.user", "server.token", "server.salt").forEach { Store.set(it, null) }; keep(null); account.value = null }

    private fun str(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull

    /** Every record, in the server's order ("alphabeticalByName", "newest"), 500 a page. */
    suspend fun albums(type: String): List<Album> {
        val out = ArrayList<Album>()
        while (out.size < 5000) {
            val page = (call("getAlbumList2", "type" to type, "size" to 500, "offset" to out.size)["albumList2"]?.jsonObject?.get("album") as? JsonArray).orEmpty()
            page.map { it.jsonObject }.forEach { out += album(it) }
            if (page.size < 500) break
        }
        return out
    }

    /** One page of records in the server's order ("newest", "frequent", "recent", "starred", "random"). */
    suspend fun shelf(type: String, n: Int = 20): List<Album> =
        (call("getAlbumList2", "type" to type, "size" to n)["albumList2"]?.jsonObject?.get("album") as? JsonArray).orEmpty().map { album(it.jsonObject) }

    private fun album(a: JsonObject) =
        Album(str(a, "id")!!, str(a, "name") ?: str(a, "title").orEmpty(), str(a, "artist").orEmpty(), str(a, "coverArt"), a["year"]?.jsonPrimitive?.intOrNull ?: 0)

    private fun song(s: JsonObject) =
        Song(str(s, "id")!!, str(s, "title").orEmpty(), str(s, "artist").orEmpty(), str(s, "album").orEmpty(), str(s, "albumId").orEmpty(),
            str(s, "coverArt"), (s["duration"]?.jsonPrimitive?.intOrNull ?: 0) * 1000L, s["track"]?.jsonPrimitive?.intOrNull ?: 0,
            s["discNumber"]?.jsonPrimitive?.intOrNull ?: 1)

    /** A record's songs, in their order. */
    suspend fun songs(albumId: String): List<Song> =
        (call("getAlbum", "id" to albumId)["album"]?.jsonObject?.get("song") as? JsonArray).orEmpty().map { song(it.jsonObject) }
            .sortedWith(compareBy({ it.disc }, { it.track }))

    /** A record by its id (a shelf's, a pick found). */
    suspend fun record(id: String): Album? = call("getAlbum", "id" to id)["album"]?.jsonObject?.let(::album)

    /** Records and songs by a search. */
    suspend fun search(q: String, records: Int = 20, songs: Int = 30): Pair<List<Album>, List<Song>> {
        val r = call("search3", "query" to q, "albumCount" to records, "songCount" to songs, "artistCount" to 0)["searchResult3"]?.jsonObject
        return (r?.get("album") as? JsonArray).orEmpty().map { album(it.jsonObject) } to (r?.get("song") as? JsonArray).orEmpty().map { song(it.jsonObject) }
    }

    /** A critic's pick found on your server: the record (or the song's record) of that name by that artist, or null. */
    suspend fun find(title: String, artist: String, record: Boolean): Album? {
        val t = Search.fold(title.substringBefore(" (").substringBefore(" [")); val a = Search.fold(artist)
        fun same(name: String, by: String) = Search.fold(name.substringBefore(" (").substringBefore(" [")) == t &&
            Search.fold(by).let { it == a || it.startsWith("$a ") || a.startsWith("$it ") }
        val (albums, songs) = runCatching { search("$artist $title", 10, 10) }.getOrDefault(emptyList<Album>() to emptyList())
        return if (record) albums.firstOrNull { same(it.title, it.artist) }
        else songs.firstOrNull { same(it.title, it.artist) }?.let { s -> runCatching { record(s.albumId) }.getOrNull() }
    }

    fun stream(s: Song): String? = s.url ?: account.value?.let { url(it, "stream", "id" to s.id, "format" to "raw") }
    fun cover(id: String, size: Int): String? = if (id.startsWith("http")) id else account.value?.let { url(it, "getCoverArt", "id" to id, "size" to size) }

    /** Told the server it was heard (its play counts, its "recent"; a ListenBrainz link there too). */
    suspend fun scrobble(id: String) { runCatching { call("scrobble", "id" to id, "submission" to true) } }
}
