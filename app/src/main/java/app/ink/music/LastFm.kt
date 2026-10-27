package app.ink.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Last.fm: what you play, scrobbled there, as ListenBrainz has it. Connected once by your Last.fm name and password
 * (Last.fm's own way for an app, auth.getMobileSession: the password goes to Last.fm, never kept here; its session
 * key is). "Now playing" as a song starts, a scrobble once half of it or four minutes has played (Last.fm's rule),
 * every source but radio, from the player's process (the app closed too); one that can't go is kept for the next.
 * Every call signed with ink music's own key and secret (BuildConfig, kept out of git: a build without them has none).
 */
object LastFm {
    data class Account(val name: String, val key: String)

    private const val API = "https://ws.audioscrobbler.com/2.0/"
    val available get() = BuildConfig.LASTFM_KEY.isNotEmpty() && BuildConfig.LASTFM_SECRET.isNotEmpty()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account

    private fun file(c: Context) = File(c.filesDir, "lastfm.json")
    private fun waiting(c: Context) = File(c.filesDir, "lastfm-waiting.jsonl")

    fun read(c: Context): Account? = runCatching {
        val o = JSONObject(file(c).readText())
        Account(o.getString("name"), o.getString("key"))
    }.getOrNull()

    fun start(c: Context) { _account.value = read(c) }

    enum class Connect { OK, WRONG, UNREACHABLE }

    /** Your name and password swapped for a session (the password not kept). */
    suspend fun connect(c: Context, name: String, password: String): Connect = withContext(Dispatchers.IO) {
        val o = runCatching { call(mapOf("method" to "auth.getMobileSession", "username" to name.trim(), "password" to password)) }
            .getOrElse { return@withContext Connect.UNREACHABLE }
        val s = o.optJSONObject("session") ?: return@withContext Connect.WRONG
        val a = Account(s.optString("name", name.trim()), s.getString("key"))
        file(c).writeText(JSONObject().put("name", a.name).put("key", a.key).toString())
        _account.value = a
        Connect.OK
    }

    fun disconnect(c: Context) { file(c).delete(); waiting(c).delete(); _account.value = null }

    /** What's playing now (blocking: the player's thread); not kept if it fails. */
    fun playingNow(c: Context, s: ListenBrainz.Said) {
        val a = read(c) ?: return
        if (!available) return
        runCatching { call(said(s) + mapOf("method" to "track.updateNowPlaying", "sk" to a.key)) }
            .onFailure { android.util.Log.d("ink-lastfm", "now: $it") }
    }

    /** A scrobble (blocking): sent, with any kept from before (fifty a call); kept to send later if it can't go. */
    @Synchronized fun listened(c: Context, s: ListenBrainz.Said, at: Long) {
        val a = read(c) ?: return
        if (!available) return
        val w = waiting(c)
        val before = runCatching { w.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() } }.getOrDefault(emptyList())
        val all = before + JSONObject(said(s) + mapOf("timestamp" to (at / 1000).toString()))
        val ok = all.chunked(50).all { batch ->
            val params = HashMap<String, String>()
            batch.forEachIndexed { i, o -> o.keys().forEach { k -> params["$k[$i]"] = o.getString(k) } }
            runCatching { call(params + mapOf("method" to "track.scrobble", "sk" to a.key)) }
                .onFailure { android.util.Log.d("ink-lastfm", "scrobble: $it") }.isSuccess
        }
        if (ok) w.delete() else runCatching { w.writeText(all.takeLast(5_000).joinToString("") { it.toString() + "\n" }) }
        android.util.Log.d("ink-lastfm", "${s.artist} - ${s.title}: ${if (ok) "scrobbled" else "kept"} (${all.size})")
    }

    private fun said(s: ListenBrainz.Said) = buildMap {
        put("artist", s.artist); put("track", s.title)
        if (s.album.isNotEmpty()) put("album", s.album)
        if (s.ms > 0) put("duration", (s.ms / 1000).toString())
        if (s.track > 0) put("trackNumber", s.track.toString())
    }

    /** A signed call (POST, JSON back); throws when Last.fm can't be reached or says it's busy, so the scrobble's kept. */
    private fun call(params: Map<String, String>): JSONObject {
        val all = params + ("api_key" to BuildConfig.LASTFM_KEY)
        // the signature: every parameter, sorted by name, name then value, the secret on the end, md5
        val sig = MessageDigest.getInstance("MD5").digest(
            (all.toSortedMap().entries.joinToString("") { it.key + it.value } + BuildConfig.LASTFM_SECRET).toByteArray(),
        ).joinToString("") { "%02x".format(it) }
        val body = (all + ("api_sig" to sig) + ("format" to "json")).entries
            .joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 20_000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("User-Agent", AGENT)
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            // 11 / 16: Last.fm busy, try later (kept); 29: too many at once; the rest (a bad song, a session gone) said and dropped
            val error = o.optInt("error")
            if (code >= 500 || error == 11 || error == 16 || error == 29) throw java.io.IOException("last.fm said ${o.optString("message", code.toString())}")
            return o
        } finally { c.disconnect() }
    }
}
