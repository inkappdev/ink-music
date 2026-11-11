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

    /**
     * Your listening as Last.fm has kept it, brought in (2026-11-11: someone new knew nothing to ink music, however many
     * years they'd scrobbled): whom you play most, of all time, the last year and the last month, and whose songs you
     * loved, each artist a strength from 0 to 1; and your most played songs. Read by your name alone (Last.fm's public
     * reads, the app's key), kept in lastfm-taste.json, so Taste counts them from the first day.
     */
    data class Brought(val user: String, val at: Long, val artists: Map<String, Double>, val songs: List<Pair<String, String>>)

    private fun broughtFile(dir: File) = File(dir, "lastfm-taste.json")

    fun brought(dir: File): Brought? = runCatching {
        val o = JSONObject(broughtFile(dir).readText())
        val a = o.getJSONObject("artists")
        val artists = HashMap<String, Double>()
        a.keys().forEach { artists[it] = a.getDouble(it) }
        val s = o.optJSONArray("songs")
        val songs = (0 until (s?.length() ?: 0)).map { i -> s!!.getJSONArray(i).let { it.getString(0) to it.getString(1) } }
        Brought(o.getString("user"), o.getLong("at"), artists, songs)
    }.getOrNull()

    fun forget(dir: File) { broughtFile(dir).delete() }

    /** Read from Last.fm and kept (null: no such name, nothing played, or Last.fm unreachable). */
    suspend fun bringIn(dir: File, user: String): Brought? = withContext(Dispatchers.IO) {
        if (key.isEmpty() || user.isBlank()) return@withContext null
        fun read(method: String, extra: String) =
            JSONObject(Curation.get("$API?method=$method&user=${urlEncode(user.trim())}&api_key=$key&format=json$extra"))
        val artists = HashMap<String, Double>()
        runCatching {
            // the further back, the less it says of now: all time .85 at most, the year .95, the month all of it
            for ((period, most, limit) in listOf(Triple("overall", .85, 200), Triple("12month", .95, 100), Triple("1month", 1.0, 50))) {
                val list = read("user.gettopartists", "&period=$period&limit=$limit").optJSONObject("topartists")?.optJSONArray("artist") ?: continue
                val counts = (0 until list.length()).map { list.getJSONObject(it).let { a -> a.optString("name") to a.optString("playcount").toDoubleOrNull().orZero() } }
                val top = counts.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: continue
                // the square root: the tenth most played is still near (an artist played a quarter as much, half as near)
                for ((name, n) in counts) if (name.isNotBlank()) artists[name] = maxOf(artists[name] ?: 0.0, most * kotlin.math.sqrt(n / top))
            }
        }.onFailure { log("bring in: $it"); return@withContext null }
        // whose songs you loved there: near, at least
        runCatching {
            val loved = read("user.getlovedtracks", "&limit=200").optJSONObject("lovedtracks")?.optJSONArray("track")
            for (i in 0 until (loved?.length() ?: 0)) {
                val name = loved!!.getJSONObject(i).optJSONObject("artist")?.optString("name").orEmpty()
                if (name.isNotBlank()) artists[name] = maxOf(artists[name] ?: 0.0, .55)
            }
        }
        val songs = runCatching {
            val t = read("user.gettoptracks", "&period=overall&limit=100").optJSONObject("toptracks")?.optJSONArray("track")
            (0 until (t?.length() ?: 0)).map { i -> t!!.getJSONObject(i).let { (it.optJSONObject("artist")?.optString("name").orEmpty()) to it.optString("name") } }
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
        }.getOrDefault(emptyList())
        if (artists.isEmpty()) return@withContext null
        val b = Brought(user.trim(), nowMs(), artists, songs)
        val o = JSONObject().put("user", b.user).put("at", b.at)
        val a = JSONObject(); artists.forEach { (k, v) -> a.put(k, v) }
        val s = JSONArray(); songs.forEach { (ar, ti) -> s.put(JSONArray().put(ar).put(ti)) }
        runCatching { Keep.write(broughtFile(dir), o.put("artists", a).put("songs", s).toString()) }
        log("brought in ${artists.size} artists, ${songs.size} songs from $user")
        b
    }
    private fun Double?.orZero() = this ?: 0.0

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
