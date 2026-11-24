package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Extensions, the phone's and the computer's (one of these each: the phone's full build its Extensions, the computer's
 * its own): addresses of your own that a YouTube or server song's stream is asked of first. Each is an address with the
 * song in it: {title} {artist} {album} {duration} (seconds) {id} filled in where they're written, else the same added as
 * its query. It answers with the song's audio (a one-byte look says audio or octet-stream), or with JSON giving the
 * audio's link: {"url": "https://..."} or {"data": {"url": "https://..."}}, those two shapes only, the link looked at the
 * same way; all within 5 s. Or it's of the search-and-stream kind (Echo Music's: its manifest.json says so), the song
 * searched for there and its stream asked by its id ([searchProbe]); an encrypted stream never. Anything else, and the
 * song plays from where it would have. Every new one starts off: the song's title, artist, record and length go to that
 * address only once you turn it on. Kept in the folder each app hands it: extensions.json (the list) and
 * extensions-said.json (what each said last), files, as the phone's two processes both read them. [log] is said to at
 * debug only, by host, never the song.
 */
class YourExtensions(private val log: (String) -> Unit = {}) {
    data class Source(val url: String, val name: String = "", val on: Boolean = false)
    data class Track(val title: String, val artist: String, val album: String, val durationMs: Long, val id: String)

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources

    private fun file(dir: File) = File(dir, "extensions.json")

    // the player's copy, read again only when the screens have written the file since
    @Volatile private var read = -1L to emptyList<Source>()
    private fun current(dir: File): List<Source> {
        val f = file(dir)
        val at = if (f.exists()) f.lastModified() else 0L
        if (read.first != at) read = at to load(f)
        return read.second
    }

    private fun load(f: File): List<Source> = runCatching {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { Source(it.getString("url"), it.optString("name"), it.optBoolean("on", false)) }
    }.getOrDefault(emptyList())

    fun start(dir: File) {
        _sources.value = load(file(dir))
        _said.value = readSaid(dir)
        named(dir)
    }

    /**
     * Each one's manifest read (off the main thread; only the address's own, nothing of a song, so for one still off
     * too): those without a name take its ("Lossless", "Tido"), and those that send lossless by default are known
     * ([lossless], for settings' note on the data they use).
     */
    private fun named(dir: File) {
        val all = _sources.value
        if (all.isEmpty()) return
        Thread {
            val kinds = all.mapNotNull { s -> manifestOf(s.url)?.let { s.url to it } }.toMap()
            _lossless.value = kinds.filterValues { it.lossless }.keys
            val names = all.filter { it.name.isBlank() }.mapNotNull { s -> kinds[s.url]?.name?.ifBlank { null }?.let { s.url to it } }.toMap()
            if (names.isNotEmpty()) synchronized(this) {
                save(dir, _sources.value.map { s -> names[s.url]?.takeIf { s.name.isBlank() }?.let { s.copy(name = it) } ?: s })
            }
        }.apply { isDaemon = true }.start()
    }

    private val _lossless = MutableStateFlow<Set<String>>(emptySet())
    /** The addresses whose manifest says they send lossless by default (read as settings opens). */
    val lossless: StateFlow<Set<String>> = _lossless

    private fun manifestOf(url: String): Kind? = runCatching {
        if ('{' in url || '?' in url) return null
        val r = ask(base(url) + "/manifest.json", System.currentTimeMillis() + TIMEOUT, ranged = false)
        if (r.code != 200) return null
        kindIn(String(r.body, Charsets.UTF_8)).takeIf { it.searches || it.name.isNotEmpty() }
    }.getOrNull()

    // what each said last, for settings: written by the player, read by the screens
    data class Said(val why: String, val at: Long, val found: Boolean)
    private val _said = MutableStateFlow<Map<String, Said>>(emptyMap())
    val said: StateFlow<Map<String, Said>> = _said
    private fun saidFile(dir: File) = File(dir, "extensions-said.json")

    private fun readSaid(dir: File): Map<String, Said> = runCatching {
        val o = JSONObject(saidFile(dir).readText())
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Said(it.optString("why"), it.optLong("at"), it.optBoolean("found")) } }
    }.getOrDefault(emptyMap())

    /** The screens read it again (settings open). */
    fun refreshSaid(dir: File) { _said.value = readSaid(dir) }

    private val lastSaid = ConcurrentHashMap<String, Said>()
    private fun tell(dir: File, url: String, p: Probe) {
        lastSaid[url] = Said(p.why, System.currentTimeMillis(), p.answer == Answer.AUDIO)
        runCatching {
            val o = JSONObject()
            lastSaid.forEach { (k, v) -> o.put(k, JSONObject().put("why", v.why).put("at", v.at).put("found", v.found)) }
            val f = saidFile(dir); val t = File(f.path + ".new"); t.writeText(o.toString()); t.renameTo(f)
        }
        // in one process (the computer) the screens see it at once
        _said.value = lastSaid.toMap()
    }

    private fun save(dir: File, list: List<Source>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("url", it.url).put("name", it.name).put("on", it.on)) }
        file(dir).writeText(a.toString())
        _sources.value = list
    }

    /** A new one, always off. False when it isn't an address, or is there already. */
    fun add(dir: File, url: String, name: String): Boolean {
        val u = clean(url) ?: return false
        if (_sources.value.any { it.url == u }) return false
        save(dir, _sources.value + Source(u, name.trim(), on = false))
        named(dir)
        return true
    }

    /** Its address or name changed; a changed address is a new one to the song, so it goes off again. */
    fun edit(dir: File, old: String, url: String, name: String): Boolean {
        val u = clean(url) ?: return false
        if (u != old && _sources.value.any { it.url == u }) return false
        save(dir, _sources.value.map { if (it.url == old) Source(u, name.trim(), on = it.on && u == old) else it })
        return true
    }

    fun turn(dir: File, url: String, on: Boolean) = save(dir, _sources.value.map { if (it.url == url) it.copy(on = on) else it })

    fun remove(dir: File, url: String) = save(dir, _sources.value.filterNot { it.url == url })

    /** Any turned on (the player asks before finding the song: none on, nothing waited for). */
    fun anyOn(dir: File) = current(dir).any { it.on }

    // an address that didn't answer (gone, refused, too slow) left alone a while; a song one address had
    // nothing for left alone a while for that address only, the others still asked
    private val down = ConcurrentHashMap<String, Long>()
    private val missed = ConcurrentHashMap<String, Long>()
    // a song's stream found, kept for its seeks (each opens it again, and must get the same one)
    private val found = ConcurrentHashMap<String, Pair<String, Long>>()
    // and which of yours gave it: its name, else its address's host (said in the player's signal path)
    private val gave = ConcurrentHashMap<String, String>()

    /** The extension a song's playing stream came from (its name, else its host), or null when none did. */
    fun by(key: String): String? = gave[key]?.takeIf { found[key] != null }

    private fun recent(m: ConcurrentHashMap<String, Long>, key: String): Boolean {
        val at = m[key] ?: return false
        if (System.currentTimeMillis() - at < FORGET) return true
        m.remove(key); return false
    }

    enum class Answer { AUDIO, NOTHING, UNREACHABLE }
    /** What an address said; [stream] is what to play when it's audio: the address itself, or the link its JSON gave. */
    data class Probe(val answer: Answer, val why: String, val stream: String? = null)

    /**
     * The song's stream from the first extension on that has it, or null (the song plays as it would have). Run on
     * the player's loading thread: it waits at most 5 s an address. [key] is the song as the player knows it (its
     * address on the phone, its id on the computer): the same key, the same stream for an hour.
     */
    fun stream(dir: File, key: String, t: Track, level: Int = 0): String? {
        // downloaded ahead (keepAhead): played from here, nothing waited on
        kept(dir, key)?.let { return "file://" + it.absolutePath }
        // (found at another quality than the network's now, asked again)
        if (foundAt[key] != null && foundAt[key] != level) found.remove(key)
        found[key]?.let { (u, at) -> if (System.currentTimeMillis() - at < 3_600_000L) return u else found.remove(key) }
        if (found.size > 500) { found.clear(); gave.clear() }
        // one ask a song at a time: the song's start joins the look already begun for it (asked ahead, as the one
        // before it played), rather than asking again
        val mine = java.util.concurrent.CompletableFuture<String?>()
        val theirs = asking.putIfAbsent(key, mine)
        if (theirs != null) return runCatching { theirs.get(TIMEOUT * 3L, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
        try { mine.complete(find(dir, key, t, level)) } catch (e: Throwable) { mine.complete(null) } finally { asking.remove(key) }
        return mine.getNow(null)
    }
    private val asking = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<String?>>()

    /**
     * Whether a song's answer is known without asking anyone: its stream kept, or every extension on is down or has
     * said it hasn't this song. The player looks up YouTube's own stream alongside only when it isn't.
     */
    fun answered(dir: File, key: String): Boolean {
        found[key]?.let { (_, at) -> if (System.currentTimeMillis() - at < 3_600_000L) return true }
        return current(dir).filter { it.on }.all { recent(down, it.url) || recent(missed, it.url + "\n" + key) }
    }

    // ---- a song downloaded ahead ----

    // an extension's host may fetch a song from its own source as it's asked (Lossless's: its parts not fetched yet came
    // at about the speed they play, 2026-11-24, and the song buffered): the next one found is downloaded whole while
    // this one plays, a few kept (the oldest let go), and played from the file
    /** Where the songs downloaded ahead go (a cache the system may clear: the phone's cache folder); unset, beside the list. */
    @Volatile var aheadIn: File? = null
    private fun aheadDir(dir: File) = File(aheadIn ?: dir, "extensions-ahead").apply { mkdirs() }
    private fun fileOf(key: String) = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun kept(dir: File, key: String): File? = File(aheadDir(dir), fileOf(key)).takeIf { it.isFile && it.length() > 0 }
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val downloads = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "ink-ext-download").apply { isDaemon = true } }

    /**
     * The song's stream, found by an extension already ([stream]), downloaded whole into a file of its own, apart from
     * the player (one at a time); four kept. Only where the player chose to (on wifi, or as it was told it may).
     */
    fun keepAhead(dir: File, key: String) {
        val link = found[key]?.first ?: return
        if (kept(dir, key) != null || !downloading.add(key)) return
        downloads.execute {
            val d = aheadDir(dir); val f = File(d, fileOf(key)); val part = File(d, f.name + ".part")
            val t0 = System.currentTimeMillis()
            try {
                // its whole length known from the first answer, and asked for again from where it got to when the host
                // lets go early (2026-11-24: Lossless's closed some after 30 s, 1.5 MB of a 30 MB song kept as if whole:
                // it played ten seconds and "skipped"); kept only once whole, else let go and the song streams
                var total = -1L
                part.delete()
                for (attempt in 0 until 8) {
                    if (System.currentTimeMillis() - t0 > 5 * 60_000L) break
                    val got = part.length()
                    val c = URL(link).openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                    if (got > 0) c.setRequestProperty("Range", "bytes=$got-")
                    val code = runCatching { c.responseCode }.getOrElse { -1 }
                    if (code !in 200..299) { log("download ahead: http $code"); if (code in 400..499) break else continue }
                    // asked from partway and given it whole: begun again
                    val from = if (got > 0 && code == 200) { part.delete(); 0L } else got
                    if (total < 0) total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: c.contentLengthLong.takeIf { it > 0 }?.plus(from) ?: -1L
                    runCatching { c.inputStream.use { i -> java.io.FileOutputStream(part, true).use { o -> i.copyTo(o, 64 * 1024) } } }
                    c.disconnect()
                    if (total < 0 || part.length() >= total) break
                }
                if (total > 0 && part.length() < total) { log("download ahead: only ${part.length() / 1024} of ${total / 1024} KB, let go"); return@execute }
                if (part.renameTo(f)) log("downloaded ahead: ${f.length() / 1024} KB in ${(System.currentTimeMillis() - t0) / 1000} s")
                d.listFiles()?.filter { !it.name.endsWith(".part") }?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
            } catch (e: Exception) {
                log("download ahead: ${e.javaClass.simpleName}")
            } finally {
                part.delete(); downloading.remove(key)
            }
        }
    }

    private val kinds = ConcurrentHashMap<String, Pair<Kind, Long>>()

    /** An address's kind, its manifest asked once an hour; an address with {placeholders} or a query is always plain. Null: it didn't answer. */
    private fun kindOf(url: String): Kind? {
        if ('{' in url || '?' in url) return plain
        kinds[url]?.let { (k, at) -> if (System.currentTimeMillis() - at < 3_600_000L) return k }
        val r = try { ask(base(url) + "/manifest.json", System.currentTimeMillis() + TIMEOUT, ranged = false) } catch (e: Exception) { return null }
        val k = if (r.code == 200) kindIn(String(r.body, Charsets.UTF_8)) else plain
        kinds[url] = k to System.currentTimeMillis()
        return k
    }

    private val foundAt = ConcurrentHashMap<String, Int>()

    private fun find(dir: File, key: String, t: Track, level: Int): String? {
        for (s in current(dir).filter { it.on }) {
            val host = runCatching { java.net.URI(s.url).host }.getOrNull().orEmpty()
            if (recent(down, s.url) || recent(missed, s.url + "\n" + key)) continue
            val kind = kindOf(s.url)
            val target = address(s.url, t)
            val p = when {
                kind == null -> Probe(Answer.UNREACHABLE, "unreachable (no manifest answer)")
                kind.searches -> searchProbe(s.url, kind.at(level), t)
                else -> probe(target)
            }
            log("$host: ${p.why}")
            tell(dir, s.url, p)
            when (p.answer) {
                Answer.AUDIO -> return (p.stream ?: target).also {
                    found[key] = it to System.currentTimeMillis(); foundAt[key] = level; gave[key] = s.name.trim().ifEmpty { kind?.name.orEmpty() }.ifEmpty { host }
                }
                Answer.NOTHING -> missed[s.url + "\n" + key] = System.currentTimeMillis()
                Answer.UNREACHABLE -> down[s.url] = System.currentTimeMillis()
            }
        }
        return null
    }

    /**
     * What an address is: of the search-and-stream kind (Echo Music's extensions: its manifest.json says "resources"
     * holding "search" and "stream"), with the quality its manifest gives by default; or not, and asked as above.
     */
    class Kind(val searches: Boolean, val quality: String? = null, val name: String = "",
               /** Its quality setting's choices, as its manifest names them ("lossless", "high", "low"; "HIRES", "LOSSLESS"...). */
               val options: List<String> = emptyList(),
               /** Asked for less than original (the network's quality): a lossless answer anyway is passed over. */
               val small: Boolean = false) {
        /** It sends lossless by default (its default quality says so). */
        val lossless get() = quality?.lowercase()?.let { q -> listOf("lossless", "hires", "hi-res", "max", "flac", "24").any { it in q } } == true

        /**
         * The quality to ask for at the app's own choice for the network it's on (0 original, 1 high, 2 data saver, as
         * both apps' Quality): original its default; high its "high" if it has one; data saver its "low", else "high".
         */
        fun at(level: Int): Kind {
            fun named(w: String) = options.firstOrNull { it.equals(w, true) }
            val q = when (level) { 1 -> named("high"); 2 -> named("low") ?: named("high"); else -> null } ?: quality
            return Kind(searches, q, name, options, small = level > 0)
        }
    }

    // what's the same for everyone: the asking and the reading of answers, plain Java, so tested on the JVM
    companion object {
        private const val TIMEOUT = 5_000
        private const val FORGET = 5 * 60_000L
        private const val MOST = 64 * 1024
        private val plain = Kind(false)

        /**
         * Only http and https addresses (with a host); null when it isn't one. Read by hand, not by java.net.URI: an
         * address with {placeholders} in it is one.
         */
        fun clean(url: String): String? = url.trim().takeIf {
            val m = Regex("^(https?)://([^/?#]*)").find(it) ?: return@takeIf false
            val authority = m.groupValues[2].substringAfterLast('@')
            val host = if (authority.startsWith("[")) authority.substringBefore(']').drop(1) else authority.substringBefore(':')
            host.isNotBlank()
        }

        private class Answered(val code: Int, val type: String, val body: ByteArray)

        /** One ask of an address, by [until] at the latest (past it, the connection cut): its headers, and unless they say audio, a byte of it, or (unranged) up to 64 KB. */
        private fun ask(url: String, until: Long, ranged: Boolean): Answered {
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
                // audio said by its headers is answer enough: its byte not waited for (a stream's host may hold it back
                // seconds while it fetches the song); anything else read, as it may be JSON with the link
                val said = (code == 200 || code == 206) && (type.startsWith("audio/") || type == "application/octet-stream")
                val body = if (code in 200..299 && !said) runCatching { c.inputStream.use { readSome(it) } }.getOrDefault(ByteArray(0)) else ByteArray(0)
                if (System.currentTimeMillis() >= until) throw java.net.SocketTimeoutException("too slow")
                return Answered(code, type, body)
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

        private fun audio(r: Answered) = (r.code == 200 || r.code == 206) && (r.type.startsWith("audio/") || r.type == "application/octet-stream")

        private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "ink-extension").apply { isDaemon = true } }

        /**
         * The link in an extension's JSON answer: a plain link named url, directUrl, streamUrl or mediaUrl, at the top or
         * under "data". Nothing else is looked for (no manifests, base64 or DASH); http and https only.
         */
        fun linkIn(body: String): String? = runCatching { linkOf(JSONObject(body.trim())) }.getOrNull()

        // the plain link's names, as addons give it (LastWave's client reads the same)
        private val LINKS = listOf("url", "directUrl", "streamUrl", "streamURL", "mediaUrl")
        private fun linkOf(o: JSONObject): String? =
            (LINKS.firstNotNullOfOrNull { k -> o.optString(k).takeIf { isLink(it) } }
                ?: o.optJSONObject("data")?.let { d -> LINKS.firstNotNullOfOrNull { k -> d.optString(k).takeIf { isLink(it) } } })
        private fun isLink(s: String) = s.startsWith("https://") || s.startsWith("http://")

        /** An answer saying its stream is encrypted (a DRM'd one, for an app to decrypt): never played. */
        /** An answer saying its stream is lossless ("format": "flac"/"alac"/"wav", or a quality saying lossless or hi-res). */
        fun losslessIn(body: String): Boolean = runCatching {
            val o = JSONObject(body.trim()).let { it.optJSONObject("data") ?: it }
            o.optString("format").lowercase() in setOf("flac", "alac", "wav") ||
                listOf("quality", "audioQuality").any { k -> o.optString(k).lowercase().let { q -> "lossless" in q || "hi_res" in q || "hires" in q } }
        }.getOrDefault(false)

        fun encrypted(body: String): Boolean = runCatching {
            val o = JSONObject(body.trim()); o.optBoolean("encrypted") || o.optJSONObject("data")?.optBoolean("encrypted") == true
        }.getOrDefault(false)

        /**
         * One byte asked of the address, all of it within the time: audio (or octet-stream) is the song's stream. Or a JSON
         * answer (said application/json, or its body a JSON object) with a link in it ([linkIn]), and that link asked the
         * same way: audio, and the link is the stream. Any other answer, nothing for this song; no answer from the address
         * (refused, gone, too slow), the address is down.
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
            val text = String(body, Charsets.UTF_8)
            if (encrypted(text)) return Probe(Answer.NOTHING, "nothing for this song (its stream is encrypted)")
            val link = linkIn(text) ?: return Probe(Answer.NOTHING, "nothing for this song (json without a url)")
            val l = try { ask(link, until, ranged = true) } catch (e: Exception) { return Probe(Answer.NOTHING, "its link didn't answer (${e.javaClass.simpleName})") }
            return if (audio(l)) Probe(Answer.AUDIO, "playing from its link", link)
            else Probe(Answer.NOTHING, "its link wasn't audio (${if (l.code !in 200..299) "http ${l.code}" else l.type.ifEmpty { "no type" }})")
        }

        /** The address with the song in it: in its {placeholders}, or else as its query. */
        fun address(url: String, t: Track): String {
            val fields = linkedMapOf("title" to t.title, "artist" to t.artist, "album" to t.album,
                "duration" to (t.durationMs / 1000).toString(), "id" to t.id)
            return if (fields.keys.any { "{$it}" in url }) fields.entries.fold(url) { u, (k, v) -> u.replace("{$k}", enc(v)) }
            else url + (if ('?' in url) "&" else "?") + fields.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        }

        // the address as given, without an ending of the kind's own pasted with it: its manifest's (as Echo Music's
        // extensions hand theirs out, ".../manifest.json"), or its search's or stream's
        private val ENDINGS = listOf("/manifest.json", "/manifest", "/search", "/stream")
        fun base(url: String): String {
            var u = url.trim().substringBefore('#').substringBefore('?').trimEnd('/')
            ENDINGS.firstOrNull { u.endsWith(it, ignoreCase = true) }?.let { u = u.dropLast(it.length).trimEnd('/') }
            return u
        }

        /** Read from a manifest.json: the search-and-stream kind, or plain. */
        fun kindIn(body: String): Kind = runCatching {
            val o = JSONObject(body.trim())
            val res = o.optJSONArray("resources") ?: return plain
            val has = (0 until res.length()).map { res.optString(it) }
            if ("search" !in has || "stream" !in has) return plain
            val settings = o.optJSONArray("settings")
            val setting = settings?.let { a -> (0 until a.length()).map { a.optJSONObject(it) }.firstOrNull { it?.optString("key") == "quality" } }
            val quality = setting?.optString("default")?.ifBlank { null }
            val options = setting?.optJSONArray("options")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("value")?.ifBlank { null } } }.orEmpty()
            Kind(true, quality, o.optString("name").trim().take(40), options)
        }.getOrDefault(plain)

        // a title or name as compared: no accents, case, (brackets), " - remastered" or "feat." parts, nor anything but letters and digits
        private fun norm(s: String): String {
            var x = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            x = x.replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ").substringBefore(" - ")
            x = x.replace(Regex("\\s(feat\\.?|ft\\.?|featuring)\\s.*"), "")
            return x.filter { it.isLetterOrDigit() }
        }

        /**
         * The id of the song in a search's answer ({"tracks": [{"id", "title", "artist", "duration" (s)}]}): the same title
         * and one of its artists, and the same length within 6 s when both are known (closest first). Null: none is it.
         */
        fun pick(body: String, t: Track): String? = picked(body, t)?.optString("id")

        private fun picked(body: String, t: Track): JSONObject? = runCatching {
            val tracks = JSONObject(body.trim()).optJSONArray("tracks") ?: return null
            val title = norm(t.title).ifEmpty { return null }
            val artists = t.artist.split(Regex(",|&| and | x | feat\\.? | ft\\.? | with ", RegexOption.IGNORE_CASE))
                .map { norm(it) }.filter { it.length >= 2 }
            val secs = t.durationMs / 1000
            (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it) }
                .filter { it.optString("id").isNotBlank() && norm(it.optString("title")) == title }
                .filter { c -> val a = norm(c.optString("artist")); artists.isEmpty() && secs > 0 || artists.any { it in a || (a.length >= 2 && a in it) } }
                .map { it to (if (secs > 0 && it.optLong("duration") > 0) kotlin.math.abs(it.optLong("duration") - secs) else 0L) }
                .filter { it.second <= 6 }
                .minByOrNull { it.second }?.first
        }.getOrNull()

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /**
         * A search-and-stream extension asked for the song, all within the time: {base}/search?q=title artist, the song
         * picked from it ([pick]), its link from the result itself or else {base}/stream/{id} ([linkIn]; its quality, and
         * stereo: atmos=none), never an encrypted one, and that link looked at by one byte. Not connecting at all, the address is down; anything else, nothing for this song.
         */
        fun searchProbe(url: String, kind: Kind, t: Track, timeout: Int = TIMEOUT): Probe {
            val until = System.currentTimeMillis() + timeout
            val b = base(url)
            fun failed(e: Exception, what: String) =
                if (e is java.net.ConnectException || e is java.net.UnknownHostException || e is java.net.NoRouteToHostException)
                    Probe(Answer.UNREACHABLE, "unreachable (${e.javaClass.simpleName})")
                else Probe(Answer.NOTHING, "$what didn't answer (${e.javaClass.simpleName})")
            val q = listOf(t.title, t.artist).filter { it.isNotBlank() }.joinToString(" ")
            val s = try { ask("$b/search?q=${enc(q)}${kind.quality?.let { "&quality=${enc(it)}" } ?: ""}&atmos=none", until, ranged = false) } catch (e: Exception) { return failed(e, "its search") }
            if (s.code != 200) return Probe(Answer.NOTHING, "nothing for this song (search http ${s.code})")
            val hit = picked(String(s.body, Charsets.UTF_8), t) ?: return Probe(Answer.NOTHING, "nothing for this song (not in its search)")
            // a search result may carry its stream's link itself: no stream asked for then
            // (only a name that means the stream: a result's plain "url" may be its page on the service)
            val link = listOf("streamURL", "streamUrl", "directUrl").firstNotNullOfOrNull { k -> hit.optString(k).takeIf { isLink(it) } }
                ?.takeIf { !hit.optBoolean("encrypted") && !(kind.small && losslessIn(hit.toString())) } ?: run {
                val st = try { ask("$b/stream/${enc(hit.optString("id"))}?${kind.quality?.let { "quality=${enc(it)}&" } ?: ""}atmos=none", until, ranged = false) }
                    catch (e: Exception) { return failed(e, "its stream") }
                if (st.code != 200) return Probe(Answer.NOTHING, "nothing for this song (stream http ${st.code})")
                val text = String(st.body, Charsets.UTF_8)
                if (encrypted(text)) return Probe(Answer.NOTHING, "nothing for this song (its stream is encrypted)")
                // a smaller quality asked for, and lossless sent anyway (Lossless's sends nothing else, 2026-11-24): passed
                // over, so the song plays from where it would have at the quality chosen, not a gigabyte an hour
                if (kind.small && losslessIn(text)) return Probe(Answer.NOTHING, "nothing for this song (only lossless, a smaller quality chosen)")
                linkIn(text) ?: return Probe(Answer.NOTHING, "nothing for this song (stream without a url)")
            }
            val l = try { ask(link, until, ranged = true) } catch (e: Exception) { return Probe(Answer.NOTHING, "its link didn't answer (${e.javaClass.simpleName})") }
            return if (audio(l)) Probe(Answer.AUDIO, "playing from its search", link)
            else Probe(Answer.NOTHING, "its link wasn't audio (${if (l.code !in 200..299) "http ${l.code}" else l.type.ifEmpty { "no type" }})")
        }
    }
}
