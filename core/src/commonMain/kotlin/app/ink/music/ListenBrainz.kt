package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.concurrent.Volatile
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

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

    /**
     * Your listening as ListenBrainz has kept it, brought in ([Brought], 2026-11-11): its public statistics, by your name
     * alone: whom you play most, of all time, this year and this month, whose songs you loved (feedback), your most
     * played songs. ListenBrainz works its statistics out in batches: a range not worked out yet answers empty (a new
     * account, the month's first days), and is left out. Kept in listenbrainz-taste.json.
     */
    private fun broughtFile(dir: File) = File(dir, "listenbrainz-taste.json")

    fun brought(dir: File): Brought? = Brought.read(broughtFile(dir), "listenbrainz")

    fun forget(dir: File) { broughtFile(dir).delete() }

    /** Read from ListenBrainz and kept (null: no such name, nothing worked out yet, or ListenBrainz unreachable). */
    suspend fun bringIn(dir: File, user: String): Brought? = withContext(Dispatchers.IO) {
        if (user.isBlank()) return@withContext null
        val u = urlEncode(user.trim())
        // an empty answer (not worked out yet) or a missing name: nothing from that read
        fun read(path: String): JSONObject? = runCatching { Curation.get("$API$path").takeIf { it.isNotBlank() }?.let { JSONObject(it) } }
            .onFailure { log("bring in $path: $it") }.getOrNull()
        fun counts(range: String, count: Int) = read("/stats/user/$u/artists?range=$range&count=$count")?.optJSONObject("payload")?.optJSONArray("artists")
            ?.let { l -> (0 until l.length()).map { l.getJSONObject(it).let { a -> a.optString("artist_name") to a.optDouble("listen_count", 0.0) } } }.orEmpty()
        val ranges = listOf(counts("all_time", 200) to .85, counts("this_year", 50) to .95, counts("this_month", 20) to 1.0)
        val loved = read("/feedback/user/$u/get-feedback?score=1&count=200&metadata=true")?.optJSONArray("feedback")
            ?.let { f -> (0 until f.length()).map { f.getJSONObject(it).optJSONObject("track_metadata")?.optString("artist_name").orEmpty() } }.orEmpty()
        val songs = read("/stats/user/$u/recordings?range=all_time&count=100")?.optJSONObject("payload")?.optJSONArray("recordings")
            ?.let { r -> (0 until r.length()).map { r.getJSONObject(it).let { x -> x.optString("artist_name") to x.optString("track_name") } } }
            .orEmpty().filter { it.first.isNotBlank() && it.second.isNotBlank() }
        val artists = Brought.weigh(ranges, loved)
        if (artists.isEmpty()) return@withContext null
        val b = Brought("listenbrainz", user.trim(), nowMs(), artists, songs)
        Brought.write(broughtFile(dir), b)
        log("brought in ${artists.size} artists, ${songs.size} songs from $user")
        b
    }

    /** What's playing now (blocking: the player's thread). Not kept if it fails: it's only now. */
    fun playingNow(dir: File, s: Said) {
        val a = read(dir) ?: return
        runCatching { call("POST", "/submit-listens", a.token, JSONObject().put("listen_type", "playing_now").put("payload", JSONArray().put(s.json(null)))) }
            .onFailure { log("now: $it") }
    }

    /** A listen (blocking): sent, with any kept from before; kept to send later if it can't go. */
    private val guard = Guard()
    fun listened(dir: File, s: Said, at: Long) = guard.run { listened0(dir, s, at) }
    private fun listened0(dir: File, s: Said, at: Long) {
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
        val h = mutableMapOf("Authorization" to "Token $token", "User-Agent" to AGENT)
        if (body != null) h["Content-Type"] = "application/json"
        val r = http(API + path, method, h, body?.toString()?.encodeToByteArray(), 10_000, 20_000, follow = 0)
        val code = r.code; val text = r.text
        // a refusal (a bad listen, a token gone) isn't worth keeping; only the network failing is
        if (code == 401) return JSONObject(text.ifEmpty { "{}" })
        if (code >= 500 || code == 429) throw IOException("listenbrainz answered $code")
        return runCatching { JSONObject(text) }.getOrNull()
    }
}
