package app.ink.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Last.fm: what you play, scrobbled there, as ListenBrainz has it. Connected once by your Last.fm name and password
 * (Last.fm's own way for an app, auth.getMobileSession: the password goes to Last.fm, never kept here; its session
 * key is). "Now playing" as a song starts, a scrobble once half of it or four minutes has played (Last.fm's rule),
 * every source but radio, from the player (on the phone its own process: the app closed too); one that can't go is
 * kept for the next. Every call signed with ink music's own key and secret (each app sets them: kept out of git, a
 * build without them has none). Shared by the phone and the desktop (the core module).
 */
object LastFm {
    data class Account(val name: String, val key: String)

    private const val API = "https://ws.audioscrobbler.com/2.0/"
    @Volatile var key = ""
    @Volatile var secret = ""
    @Volatile var log: (String) -> Unit = {}
    val available get() = key.isNotEmpty() && secret.isNotEmpty()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account

    private fun file(dir: File) = File(dir, "lastfm.json")
    private fun waiting(dir: File) = File(dir, "lastfm-waiting.jsonl")

    fun read(dir: File): Account? = runCatching {
        val o = JSONObject(file(dir).readText())
        Account(o.getString("name"), o.getString("key"))
    }.getOrNull()

    fun start(dir: File) { _account.value = read(dir) }

    enum class Connect { OK, WRONG, UNREACHABLE }

    /** Your name and password swapped for a session (the password not kept). */
    suspend fun connect(dir: File, name: String, password: String): Connect = withContext(Dispatchers.IO) {
        val o = runCatching { call(mapOf("method" to "auth.getMobileSession", "username" to name.trim(), "password" to password)) }
            .getOrElse { return@withContext Connect.UNREACHABLE }
        val s = o.optJSONObject("session") ?: return@withContext Connect.WRONG
        val a = Account(s.optString("name", name.trim()), s.getString("key"))
        file(dir).writeText(JSONObject().put("name", a.name).put("key", a.key).toString())
        _account.value = a
        Connect.OK
    }

    fun disconnect(dir: File) { file(dir).delete(); waiting(dir).delete(); _account.value = null }

    /** What's playing now (blocking: the player's thread); not kept if it fails. */
    fun playingNow(dir: File, s: ListenBrainz.Said) {
        val a = read(dir) ?: return
        if (!available) return
        runCatching { call(said(s) + mapOf("method" to "track.updateNowPlaying", "sk" to a.key)) }
            .onFailure { log("now: $it") }
    }

    /** A scrobble (blocking): sent, with any kept from before (fifty a call); kept to send later if it can't go. */
    private val guard = Guard()
    fun listened(dir: File, s: ListenBrainz.Said, at: Long) = guard.run { listened0(dir, s, at) }
    private fun listened0(dir: File, s: ListenBrainz.Said, at: Long) {
        val a = read(dir) ?: return
        if (!available) return
        val w = waiting(dir)
        val before = runCatching { w.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() } }.getOrDefault(emptyList())
        val all = before + JSONObject(said(s) + mapOf("timestamp" to (at / 1000).toString()))
        val ok = all.chunked(50).all { batch ->
            val params = HashMap<String, String>()
            batch.forEachIndexed { i, o -> o.keys().forEach { k -> params["$k[$i]"] = o.getString(k) } }
            runCatching { call(params + mapOf("method" to "track.scrobble", "sk" to a.key)) }
                .onFailure { log("scrobble: $it") }.isSuccess
        }
        if (ok) w.delete() else runCatching { w.writeText(all.takeLast(5_000).joinToString("") { it.toString() + "\n" }) }
        log("${s.artist} - ${s.title}: ${if (ok) "scrobbled" else "kept"} (${all.size})")
    }

    /**
     * A love (or its undoing) said on Last.fm too, as track.love / track.unlove (blocking), so it's there beside the
     * scrobbles for anything that reads your loved tracks; not kept if it fails (the heart here is what holds it).
     */
    fun love(dir: File, artist: String, title: String, on: Boolean) {
        val a = read(dir) ?: return
        if (!available || artist.isBlank() || title.isBlank()) return
        runCatching { call(mapOf("method" to if (on) "track.love" else "track.unlove", "artist" to artist, "track" to title, "sk" to a.key)) }
            .onSuccess { log("$artist - $title: ${if (on) "loved" else "unloved"}") }
            .onFailure { log("love: $it") }
    }

    private fun said(s: ListenBrainz.Said) = buildMap {
        put("artist", s.artist); put("track", s.title)
        if (s.album.isNotEmpty()) put("album", s.album)
        if (s.ms > 0) put("duration", (s.ms / 1000).toString())
        if (s.track > 0) put("trackNumber", s.track.toString())
    }

    /** A signed call (POST, JSON back); throws when Last.fm can't be reached or says it's busy, so the scrobble's kept. */
    private fun call(params: Map<String, String>): JSONObject {
        val all = params + ("api_key" to key)
        // the signature: every parameter, sorted by name, name then value, the secret on the end, md5
        val sig = md5((all.entries.sortedBy { it.key }.joinToString("") { it.key + it.value } + secret).encodeToByteArray()).hex()
        val body = (all + ("api_sig" to sig) + ("format" to "json")).entries
            .joinToString("&") { urlEncode(it.key) + "=" + urlEncode(it.value) }
        val r = http(API, "POST", mapOf("Content-Type" to "application/x-www-form-urlencoded", "User-Agent" to AGENT), body.encodeToByteArray(), 10_000, 20_000, follow = 0)
        val code = r.code
        val o = runCatching { JSONObject(r.text) }.getOrDefault(JSONObject())
        // 11 / 16: Last.fm busy, try later (kept); 29: too many at once; the rest (a bad song, a session gone) said and dropped
        val error = o.optInt("error")
        if (code >= 500 || error == 11 || error == 16 || error == 29) throw IOException("last.fm said ${o.optString("message", code.toString())}")
        return o
    }
}
