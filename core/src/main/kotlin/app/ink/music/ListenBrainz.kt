package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ListenBrainz (listenbrainz.org, MetaBrainz's open listening history): what you play, told there
 * by your user token. "Playing now" as a song starts; a listen once half of it, or four minutes,
 * has played (their rule). Every source but radio stations: the phone's files, your server's,
 * YouTube Music's. Sent from the player (on the phone its own process, so it goes on with the app
 * closed); a listen that can't be sent (no network) is kept and sent with the next. The token is kept
 * as a file in the app's folder (`dir`; the phone's two processes both read it), and goes only to
 * ListenBrainz. Shared by the phone and the desktop (the core module).
 */
object ListenBrainz {
    data class Account(val token: String, val user: String)

    private const val API = "https://api.listenbrainz.org/1"
    /** The app's version, said with each listen; and where what happened goes (each app's log). */
    @Volatile var version = ""
    @Volatile var log: (String) -> Unit = {}
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account

    private fun file(dir: File) = File(dir, "listenbrainz.json")
    private fun waiting(dir: File) = File(dir, "listenbrainz-waiting.jsonl")

    fun read(dir: File): Account? = runCatching {
        val o = JSONObject(file(dir).readText())
        Account(o.getString("token"), o.getString("user"))
    }.getOrNull()

    fun start(dir: File) { _account.value = read(dir) }

    enum class Connect { OK, WRONG, UNREACHABLE }

    /** The token tried (whose is it?), kept if it's someone's. */
    suspend fun connect(dir: File, token: String): Connect = withContext(Dispatchers.IO) {
        val t = token.trim()
        val o = runCatching { call("GET", "/validate-token", t, null) }.getOrElse { return@withContext Connect.UNREACHABLE }
        if (o?.optBoolean("valid") != true) return@withContext Connect.WRONG
        val a = Account(t, o.optString("user_name"))
        file(dir).writeText(JSONObject().put("token", a.token).put("user", a.user).toString())
        _account.value = a
        Connect.OK
    }

    fun disconnect(dir: File) {
        file(dir).delete(); waiting(dir).delete()
        _account.value = null
    }

    /** What's playing now (blocking: the player's thread). Not kept if it fails: it's only now. */
    fun playingNow(dir: File, s: Said) {
        val a = read(dir) ?: return
        runCatching { call("POST", "/submit-listens", a.token, JSONObject().put("listen_type", "playing_now").put("payload", JSONArray().put(s.json(null)))) }
            .onFailure { log("now: $it") }
    }

    /** A listen (blocking): sent, with any kept from before; kept to send later if it can't go. */
    @Synchronized fun listened(dir: File, s: Said, at: Long) {
        val a = read(dir) ?: return
        val w = waiting(dir)
        val before = runCatching { w.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() } }.getOrDefault(emptyList())
        val all = before + s.json(at / 1000)
        // more than one at once is an "import" (ListenBrainz's word for a batch); at most 1000 a request
        val ok = all.chunked(1000).all { batch ->
            runCatching {
                call("POST", "/submit-listens", a.token, JSONObject().put("listen_type", if (batch.size == 1) "single" else "import")
                    .put("payload", JSONArray(batch)))
            }.onFailure { log("listen: $it") }.isSuccess
        }
        if (ok) w.delete() else runCatching { w.writeText(all.takeLast(5_000).joinToString("") { it.toString() + "\n" }) }
        log("${s.artist} - ${s.title}: ${if (ok) "sent" else "kept"} (${all.size})")
    }

    /** A song as told: its words, how long, and where it came from. */
    data class Said(val title: String, val artist: String, val album: String, val ms: Long, val track: Int, val youtube: String?) {
        fun json(at: Long?): JSONObject {
            val info = JSONObject().put("media_player", "ink music").put("submission_client", "ink music")
                .put("submission_client_version", version)
            if (ms > 0) info.put("duration_ms", ms)
            if (track > 0) info.put("tracknumber", track)
            youtube?.let { info.put("music_service", "music.youtube.com").put("origin_url", "https://music.youtube.com/watch?v=$it") }
            val meta = JSONObject().put("artist_name", artist).put("track_name", title).put("additional_info", info)
            if (album.isNotEmpty()) meta.put("release_name", album)
            return JSONObject().put("track_metadata", meta).also { if (at != null) it.put("listened_at", at) }
        }
    }

    private fun call(method: String, path: String, token: String, body: JSONObject?): JSONObject? {
        val c = URL(API + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Token $token")
            c.setRequestProperty("User-Agent", AGENT)
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            // a refusal (a bad listen, a token gone) isn't worth keeping; only the network failing is
            if (code == 401) return JSONObject(text.ifEmpty { "{}" })
            if (code >= 500 || code == 429) throw java.io.IOException("listenbrainz answered $code")
            return runCatching { JSONObject(text) }.getOrNull()
        } finally { c.disconnect() }
    }
}
