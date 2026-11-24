package app.ink.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Extensions (the full build only): addresses of your own that a YouTube or server song's stream is asked of
 * first. Each is an address with the song in it: {title} {artist} {album} {duration} (seconds) {id} filled in where
 * they're written, else the same added as its query. It answers with the song's audio (a one-byte look says audio or
 * octet-stream), or with JSON giving the audio's link: {"url": "https://..."} or {"data": {"url": "https://..."}}, those
 * two shapes only, the link looked at the same way; all within 5 s. Anything else, and the song plays from where it
 * would have. Every new one starts
 * off: the song's title, artist, record and length go to that address only once you turn it on. Kept in
 * files/extensions.json (the screens write it, the player reads it: two processes, so a file, never prefs).
 */
object Extensions {
    const val available = true

    data class Source(val url: String, val name: String = "", val on: Boolean = false)
    data class Track(val title: String, val artist: String, val album: String, val durationMs: Long, val id: String)

    private const val TIMEOUT = 5_000
    private const val FORGET = 5 * 60_000L
    private const val LOG = "ink-extension"

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources

    private fun file(ctx: Context) = File(ctx.filesDir, "extensions.json")

    // the player's copy, read again only when the screens have written the file since
    @Volatile private var read = -1L to emptyList<Source>()
    private fun current(ctx: Context): List<Source> {
        val f = file(ctx)
        val at = if (f.exists()) f.lastModified() else 0L
        if (read.first != at) read = at to load(f)
        return read.second
    }

    private fun load(f: File): List<Source> = runCatching {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { Source(it.getString("url"), it.optString("name"), it.optBoolean("on", false)) }
    }.getOrDefault(emptyList())

    fun start(ctx: Context) { _sources.value = load(file(ctx)) }

    private fun save(ctx: Context, list: List<Source>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("url", it.url).put("name", it.name).put("on", it.on)) }
        file(ctx).writeText(a.toString())
        _sources.value = list
    }

    /** Only http and https addresses; null when it isn't one. */
    fun clean(url: String): String? = url.trim().takeIf {
        val u = runCatching { Uri.parse(it) }.getOrNull()
        (u?.scheme == "http" || u?.scheme == "https") && !u.host.isNullOrBlank()
    }

    /** A new one, always off. False when it isn't an address, or is there already. */
    fun add(ctx: Context, url: String, name: String): Boolean {
        val u = clean(url) ?: return false
        if (_sources.value.any { it.url == u }) return false
        save(ctx, _sources.value + Source(u, name.trim(), on = false))
        return true
    }

    /** Its address or name changed; a changed address is a new one to the song, so it goes off again. */
    fun edit(ctx: Context, old: String, url: String, name: String): Boolean {
        val u = clean(url) ?: return false
        if (u != old && _sources.value.any { it.url == u }) return false
        save(ctx, _sources.value.map { if (it.url == old) Source(u, name.trim(), on = it.on && u == old) else it })
        return true
    }

    fun turn(ctx: Context, url: String, on: Boolean) = save(ctx, _sources.value.map { if (it.url == url) it.copy(on = on) else it })

    fun remove(ctx: Context, url: String) = save(ctx, _sources.value.filterNot { it.url == url })

    /** Any turned on (the player asks before finding the song: none on, nothing waited for). */
    fun anyOn(ctx: Context) = current(ctx).any { it.on }

    // an address that didn't answer (gone, refused, too slow) left alone a while; a song one address had
    // nothing for left alone a while for that address only, the others still asked
    private val down = ConcurrentHashMap<String, Long>()
    private val missed = ConcurrentHashMap<String, Long>()
    // a song's stream found, kept for its seeks (each opens it again, and must get the same one)
    private val found = ConcurrentHashMap<String, Pair<Uri, Long>>()

    private fun recent(m: ConcurrentHashMap<String, Long>, key: String): Boolean {
        val at = m[key] ?: return false
        if (System.currentTimeMillis() - at < FORGET) return true
        m.remove(key); return false
    }

    enum class Answer { AUDIO, NOTHING, UNREACHABLE }
    /** What an address said; [stream] is what to play when it's audio: the address itself, or the link its JSON gave. */
    data class Probe(val answer: Answer, val why: String, val stream: String? = null)

    private class Reply(val code: Int, val type: String, val body: ByteArray)
    private const val MOST = 64 * 1024

    /** One ask of an address, by [until] at the latest (past it, the connection cut): a byte of it, or (unranged) up to 64 KB. */
    private fun ask(url: String, until: Long, ranged: Boolean): Reply {
        val left = (until - System.currentTimeMillis()).toInt()
        if (left <= 0) throw java.net.SocketTimeoutException("too slow")
        val c = URL(url).openConnection() as HttpURLConnection
        // the whole look held to the time, however it's spent (a read timeout set after connecting is never used, and
        // connecting and reading each get it): past it, the connection cut, and that's no answer
        val cut = watchdog.schedule({ runCatching { c.disconnect() } }, left.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            c.connectTimeout = left
            c.readTimeout = left
            c.instanceFollowRedirects = true
            if (ranged) c.setRequestProperty("Range", "bytes=0-0")
            val code = c.responseCode
            val type = c.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val body = if (code in 200..299) runCatching { c.inputStream.use { readSome(it) } }.getOrDefault(ByteArray(0)) else ByteArray(0)
            if (System.currentTimeMillis() >= until) throw java.net.SocketTimeoutException("too slow")
            return Reply(code, type, body)
        } finally {
            cut.cancel(false)
            runCatching { c.disconnect() }
        }
    }

    private fun readSome(i: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val b = ByteArray(8192)
        while (out.size() < MOST) { val n = i.read(b, 0, minOf(b.size, MOST - out.size())); if (n < 0) break; out.write(b, 0, n) }
        return out.toByteArray()
    }

    private fun audio(r: Reply) = (r.code == 200 || r.code == 206) && (r.type.startsWith("audio/") || r.type == "application/octet-stream")

    /**
     * The link in an extension's JSON answer, the only two shapes read: {"url": "https://..."}, or
     * {"data": {"url": "https://..."}}. Nothing else is looked for (no manifests, base64 or DASH); http and https only.
     */
    fun linkIn(body: String): String? = runCatching {
        val o = JSONObject(body.trim())
        (o.optString("url").ifBlank { null } ?: o.optJSONObject("data")?.optString("url")?.ifBlank { null })
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }.getOrNull()

    /**
     * One byte asked of the address, all of it within the time: audio (or octet-stream) is the song's stream. Or a JSON
     * answer (said application/json, or its body a JSON object) with a link in it ([linkIn]), and that link asked the
     * same way: audio, and the link is the stream. Any other answer, nothing for this song; no answer from the address
     * (refused, gone, too slow), the address is down. Plain Java, so tested on the JVM.
     */
    fun probe(target: String, timeout: Int = TIMEOUT): Probe {
        val until = System.currentTimeMillis() + timeout
        val r = try { ask(target, until, ranged = true) } catch (e: Exception) { return Probe(Answer.UNREACHABLE, "unreachable (${e.javaClass.simpleName})") }
        if (audio(r)) return Probe(Answer.AUDIO, "playing from it", target)
        if (r.code !in 200..299) return Probe(Answer.NOTHING, "nothing for this song (http ${r.code})")
        val json = r.type == "application/json" || r.body.firstOrNull { !it.toInt().toChar().isWhitespace() } == '{'.code.toByte()
        if (!json) return Probe(Answer.NOTHING, "nothing for this song (not audio: ${r.type.ifEmpty { "no type" }})")
        // a server that honored the one-byte range gave only the JSON's first byte: asked again for all of it
        val body = if (r.code == 206) try { ask(target, until, ranged = false).body } catch (e: Exception) {
            return Probe(Answer.UNREACHABLE, "unreachable (${e.javaClass.simpleName})")
        } else r.body
        val link = linkIn(String(body, Charsets.UTF_8)) ?: return Probe(Answer.NOTHING, "nothing for this song (json without a url)")
        val l = try { ask(link, until, ranged = true) } catch (e: Exception) { return Probe(Answer.NOTHING, "its link didn't answer (${e.javaClass.simpleName})") }
        return if (audio(l)) Probe(Answer.AUDIO, "playing from its link", link)
        else Probe(Answer.NOTHING, "its link wasn't audio (${if (l.code !in 200..299) "http ${l.code}" else l.type.ifEmpty { "no type" }})")
    }
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "ink-extension").apply { isDaemon = true } }

    /** The address with the song in it: in its {placeholders}, or else as its query. */
    fun address(url: String, t: Track): String {
        val fields = linkedMapOf("title" to t.title, "artist" to t.artist, "album" to t.album,
            "duration" to (t.durationMs / 1000).toString(), "id" to t.id)
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return if (fields.keys.any { "{$it}" in url }) fields.entries.fold(url) { u, (k, v) -> u.replace("{$k}", enc(v)) }
        else url + (if ('?' in url) "&" else "?") + fields.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /**
     * The song's stream from the first extension on that has it, or null (the song plays as it would have). Run on
     * the player's loading thread: it waits at most 5 s an address. Failures said at debug only, by host, never the song.
     */
    fun stream(ctx: Context, key: String, t: Track): Uri? {
        found[key]?.let { (u, at) -> if (System.currentTimeMillis() - at < 3_600_000L) return u else found.remove(key) }
        if (found.size > 500) found.clear()
        // one ask a song at a time: the song's start joins the look already begun for it (asked ahead, as the one
        // before it played), rather than asking again
        val mine = java.util.concurrent.CompletableFuture<Uri?>()
        val theirs = asking.putIfAbsent(key, mine)
        if (theirs != null) return runCatching { theirs.get(TIMEOUT * 3L, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
        try { mine.complete(find(ctx, key, t)) } catch (e: Throwable) { mine.complete(null) } finally { asking.remove(key) }
        return mine.getNow(null)
    }
    private val asking = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Uri?>>()

    /**
     * Whether a song's answer is known without asking anyone: its stream kept, or every extension on is down or has
     * said it hasn't this song. The player looks up YouTube's own stream alongside only when it isn't.
     */
    fun answered(ctx: Context, key: String): Boolean {
        found[key]?.let { (_, at) -> if (System.currentTimeMillis() - at < 3_600_000L) return true }
        return current(ctx).filter { it.on }.all { recent(down, it.url) || recent(missed, it.url + "\n" + key) }
    }

    private fun find(ctx: Context, key: String, t: Track): Uri? {
        for (s in current(ctx).filter { it.on }) {
            val host = runCatching { java.net.URI(s.url).host }.getOrNull().orEmpty()
            if (recent(down, s.url) || recent(missed, s.url + "\n" + key)) continue
            val target = address(s.url, t)
            val p = probe(target)
            android.util.Log.d(LOG, "$host: ${p.why}")
            when (p.answer) {
                Answer.AUDIO -> return Uri.parse(p.stream ?: target).also { found[key] = it to System.currentTimeMillis() }
                Answer.NOTHING -> missed[s.url + "\n" + key] = System.currentTimeMillis()
                Answer.UNREACHABLE -> down[s.url] = System.currentTimeMillis()
            }
        }
        return null
    }
}
