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

data class Album(val id: String, val title: String, val artist: String, val cover: String?, val year: Int)
data class Song(val id: String, val title: String, val artist: String, val album: String, val albumId: String, val cover: String?,
                val duration: Long, val track: Int, val disc: Int)

/**
 * Your Navidrome (Subsonic), as the phone and the computer read it: signed in once (a token and its salt kept, never the
 * password), the records, a record's songs, each song's stream as it is (raw) and its cover.
 */
object Server {
    data class Account(val base: String, val user: String, val token: String, val salt: String)

    val account = MutableStateFlow(load())

    private fun load(): Account? {
        val base = Store.get("server.base") ?: return null
        return Account(base, Store.get("server.user") ?: return null, Store.get("server.token") ?: return null, Store.get("server.salt") ?: return null)
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
        Store.set("server.base", a.base); Store.set("server.user", a.user); Store.set("server.token", a.token); Store.set("server.salt", a.salt)
        account.value = a
    }

    fun signOut() { listOf("server.base", "server.user", "server.token", "server.salt").forEach { Store.set(it, null) }; account.value = null }

    private fun str(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull

    /** Every record, in the server's order ("alphabeticalByName", "newest"), 500 a page. */
    suspend fun albums(type: String): List<Album> {
        val out = ArrayList<Album>()
        while (out.size < 5000) {
            val page = (call("getAlbumList2", "type" to type, "size" to 500, "offset" to out.size)["albumList2"]?.jsonObject?.get("album") as? JsonArray).orEmpty()
            page.map { it.jsonObject }.forEach { a ->
                out += Album(str(a, "id")!!, str(a, "name") ?: str(a, "title").orEmpty(), str(a, "artist").orEmpty(), str(a, "coverArt"), a["year"]?.jsonPrimitive?.intOrNull ?: 0)
            }
            if (page.size < 500) break
        }
        return out
    }

    /** A record's songs, in their order. */
    suspend fun songs(albumId: String): List<Song> =
        (call("getAlbum", "id" to albumId)["album"]?.jsonObject?.get("song") as? JsonArray).orEmpty().map { it.jsonObject }.map { s ->
            Song(str(s, "id")!!, str(s, "title").orEmpty(), str(s, "artist").orEmpty(), str(s, "album").orEmpty(), str(s, "albumId").orEmpty(),
                str(s, "coverArt"), (s["duration"]?.jsonPrimitive?.intOrNull ?: 0) * 1000L, s["track"]?.jsonPrimitive?.intOrNull ?: 0,
                s["discNumber"]?.jsonPrimitive?.intOrNull ?: 1)
        }.sortedWith(compareBy({ it.disc }, { it.track }))

    fun stream(s: Song): String? = account.value?.let { url(it, "stream", "id" to s.id, "format" to "raw") }
    fun cover(id: String, size: Int): String? = account.value?.let { url(it, "getCoverArt", "id" to id, "size" to size) }
}
