package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What plays, and the queue: mpv's own playlist holds the same songs in the same order, so one
 * song runs into the next with no gap (a live record stays one). The phone's player is a service
 * behind a session; here it's this, in the app. The queue, its song and the place in it are kept
 * (queue.json), so the app opens where it was, paused.
 */
object Player {
    /** What's playing: the song, whether it moves, how long it is (ms; 0 while not known). */
    data class Now(val song: Song, val index: Int, val playing: Boolean, val duration: Long)

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue
    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> = _now
    /** Where in the song (ms), apart from [now] so its ticking redraws only what shows it. */
    val position = MutableStateFlow(0L)
    /** Why it won't play, said once (no libmpv). */
    val trouble = MutableStateFlow<String?>(null)

    private var mpv: Mpv? = null
    @Volatile private var paused = true
    @Volatile private var index = -1
    @Volatile private var duration = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kept = File(Home.dir, "queue.json")
    /** A place to go to once the song it's in has loaded (the queue brought back). */
    @Volatile private var pendingSeek = 0L

    fun start() {
        mpv = try {
            Mpv(mapOf(
                "vid" to "no", "audio-display" to "no", "terminal" to "no", "idle" to "yes",
                "gapless-audio" to "yes",
                // even volume from the files' own tags: a record's level, a song's when it has none
                "replaygain" to "album", "replaygain-clip" to "no",
                // the Mac's media keys and Now Playing (mpv does both, asked to even as a library)
                "media-controls" to "yes", "input-media-keys" to "yes",
                "audio-client-name" to "ink music", "title" to "ink music",
                "ytdl" to "no", "cache" to "yes",
                // -Dink.ao=null: silent, to try it without playing out loud
            ) + listOfNotNull(System.getProperty("ink.ao")?.let { "ao" to it }))
        } catch (e: Throwable) { trouble.value = "can't play: ${e.message?.lowercase()}"; null }
        val m = mpv ?: return
        m.observe("pause", Mpv.FLAG)
        m.observe("playlist-pos", Mpv.INT64)
        m.observe("duration", Mpv.DOUBLE)
        m.observe("time-pos", Mpv.DOUBLE)
        // a YouTube song's stream found as it opens (they expire: never ahead of time)
        m.hook("on_load")
        Thread({ listen(m) }, "mpv").apply { isDaemon = true }.start()
        // the queue as it was, once the library knows its songs (a server's come from disk in a moment)
        scope.launch {
            withTimeoutOrNull(10_000) { Library.songs.first { it.isNotEmpty() } } ?: return@launch
            restore()
        }
    }

    private fun restore() {
        val o = runCatching { JSONObject(kept.readText()) }.getOrNull() ?: return
        val ids = o.optJSONArray("ids") ?: return
        val whole = o.optJSONArray("songs")
        val songs = (0 until ids.length()).mapNotNull { i ->
            Library.song(ids.optString(i)) ?: whole?.optJSONObject(i)?.let(::songOf)?.also { if (it.tube) YouTube.remember(it) }
        }
        if (songs.isEmpty() || _queue.value.isNotEmpty()) return
        val at = songs.indexOfFirst { it.id == o.optString("id") }.takeIf { it >= 0 } ?: 0
        pendingSeek = if (at >= 0) o.optLong("position") else 0
        load(songs, at, go = false)
    }

    /** The queue, its song and where in it, written down (on a change, and every ten seconds playing). */
    private fun keep() {
        val q = _queue.value
        val s = q.getOrNull(index)
        val text = written(q, s)
        scope.launch { runCatching { Home.write(kept, text) } }
    }
    private var keptAt = 0L

    /** The queue as kept: its ids, each song whole (YouTube's aren't in the library), the one playing and where. */
    private fun written(q: List<Song>, s: Song?) = JSONObject().put("ids", JSONArray(q.map { it.id }))
        .put("songs", JSONArray(q.map { if (it.tube) it.json() else JSONObject() })).put("id", s?.id ?: "").put("position", position.value).toString()

    private fun listen(m: Mpv) {
        while (true) {
            when (val e = m.next()) {
                is Mpv.Changed -> when (e.name) {
                    "pause" -> { paused = e.value as? Boolean ?: true; tell() }
                    "playlist-pos" -> { index = (e.value as? Long)?.toInt() ?: -1; tell() }
                    "duration" -> { duration = ((e.value as? Double) ?: 0.0).times(1000).toLong(); tell() }
                    "time-pos" -> {
                        val ms = ((e.value as? Double) ?: 0.0).times(1000).toLong()
                        // a few times a second is plenty for a line and a clock
                        if (kotlin.math.abs(ms - position.value) >= 200 || ms < position.value) position.value = ms
                        heard(ms)
                        // how long this song has actually played (a jump isn't playing)
                        val step = ms - lastMs
                        if (!paused && step in 1..2_000) playedMs += step
                        lastMs = ms
                        if (!paused && System.currentTimeMillis() - keptAt > 10_000) { keptAt = System.currentTimeMillis(); keep() }
                    }
                }
                is Mpv.Hook -> {
                    val url = m.get("stream-open-filename").orEmpty()
                    if (!url.startsWith("ytm://")) m.proceed(e.id)
                    // found on a thread of its own: the events (the clock, the line) go on meanwhile
                    else Thread {
                        YouTube.stream("y" + url.removePrefix("ytm://"))?.let { m.set("stream-open-filename", it) }
                        m.proceed(e.id)
                    }.start()
                }
                is Mpv.Loaded -> if (pendingSeek > 0) { val ms = pendingSeek; pendingSeek = 0; seek(ms) }
                is Mpv.Shutdown -> return
                else -> {}
            }
        }
    }

    // the server told what's playing, and that it was heard once it has played half, or four minutes
    // (as the phone does, and Last.fm counts); Navidrome passes it on to its own scrobbling
    private var toldNow: String? = null
    private var counted: String? = null

    private fun heard(ms: Long) {
        val n = _now.value ?: return
        val s = n.song
        if (counted == s.id || n.duration <= 0) return
        if (ms >= n.duration / 2 || ms >= 240_000) {
            counted = s.id
            if (s.server) Server.scrobble(s.id, true)
            val at = System.currentTimeMillis() - ms
            scope.launch { val said = said(s, n.duration); ListenBrainz.listened(Home.dir, said, at); LastFm.listened(Home.dir, said, at) }
        }
    }

    /** A song as ListenBrainz and Last.fm are told it. */
    private fun said(s: Song, length: Long) =
        ListenBrainz.Said(s.title, s.artist, s.album, length, s.track, s.id.takeIf { s.tube }?.removePrefix("y"))

    // the song playing as History will write it: since when, and how long it has played
    private var timing: Song? = null
    private var timingAt = 0L
    @Volatile private var playedMs = 0L
    @Volatile private var lastMs = 0L

    /** The song before written down as heard (30 s or more), and the clock started for this one. */
    private fun timeSong(s: Song?) {
        if (s?.id == timing?.id) return
        timing?.let { t -> History.add(History.Heard(timingAt, t.id, t.title, t.artist, t.album, t.albumId, playedMs)) }
        timing = s; timingAt = System.currentTimeMillis(); playedMs = 0; lastMs = 0
    }

    /** Radio: when the queue's down to its last song, more like it at its end (a setting, on at first). */
    val radio = MutableStateFlow(runCatching { !File(Home.dir, "radio-off").exists() }.getOrDefault(true))
    // songs radio added: not seeds for its next round while there are songs you chose; and the last seed asked
    private val fromRadio = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var radioFrom: String? = null

    fun radio(on: Boolean) {
        radio.value = on
        runCatching { File(Home.dir, "radio-off").let { if (on) it.delete() else it.writeText("") } }
        radioFrom = null
        topUp()
    }

    private fun topUp() {
        val q = _queue.value
        if (!radio.value || q.isEmpty() || index < 0 || index < q.size - 2) return
        val last = q.last()
        if (radioFrom == last.id) return
        radioFrom = last.id
        fun seed(s: Song) = Radio.Seed(s.id, s.title, s.artist)
        // the last song, and the last two before it you chose (radio's own passed over): where the vibe is
        val chosen = q.dropLast(1).asReversed().filter { it.id !in fromRadio && it.id != last.id }.distinctBy { it.id }.take(2)
        val seeds = listOf(seed(last)) + chosen.map(::seed)
        val queued = q.flatMap { listOf(it.id, Radio.key(it.title, it.artist)) }.toHashSet()
        scope.launch {
            val more = Radio.after(seeds, queued)
            if (more.isEmpty() || !radio.value || _queue.value.lastOrNull()?.id != last.id) return@launch
            fromRadio += more.map { it.id }
            val m = mpv ?: return@launch
            _queue.value = _queue.value + more
            more.forEach { m.command("loadfile", where(it), "append") }
            keep()
        }
    }

    private fun tell() {
        val q = _queue.value
        val s = q.getOrNull(index)
        timeSong(s)
        topUp()
        _now.value = s?.let { Now(it, index, !paused, duration.takeIf { d -> d > 0 } ?: it.duration) }
        if (s != null && toldNow != "${s.id}/$index" && !paused) {
            toldNow = "${s.id}/$index"; counted = null
            if (s.server) Server.scrobble(s.id, false)
            val said = said(s, duration.takeIf { it > 0 } ?: s.duration)
            scope.launch { ListenBrainz.playingNow(Home.dir, said); LastFm.playingNow(Home.dir, said) }
        }
        if (s != null) keep()
    }

    /** These songs, from `start`: the queue they become. */
    fun play(songs: List<Song>, start: Int = 0) { pendingSeek = 0; load(songs, start, go = true) }

    private fun load(songs: List<Song>, start: Int, go: Boolean) {
        val m = mpv ?: return
        if (songs.isEmpty()) return
        _queue.value = songs
        m.command("stop")
        m.command("playlist-clear")
        m.set("pause", if (go) "no" else "yes")
        songs.forEach { m.command("loadfile", where(it), "append") }
        position.value = 0
        m.set("playlist-pos", start.coerceIn(songs.indices).toString())
    }

    /** After the song playing (nothing playing: plays it). */
    fun playNext(s: Song) {
        val m = mpv ?: return
        if (_queue.value.isEmpty() || index < 0) return play(listOf(s))
        _queue.value = _queue.value.toMutableList().apply { add(index + 1, s) }
        m.command("loadfile", where(s), "insert-next")
        keep()
    }

    /** At the queue's end (nothing playing: plays it). */
    fun add(s: Song) {
        val m = mpv ?: return
        if (_queue.value.isEmpty() || index < 0) return play(listOf(s))
        _queue.value = _queue.value + s
        m.command("loadfile", where(s), "append")
        keep()
    }

    /** The queue's `i`th song, from its start. */
    fun jump(i: Int) {
        val m = mpv ?: return
        if (i !in _queue.value.indices) return
        m.set("playlist-pos", i.toString())
        m.set("pause", "no")
    }

    /** The queue's `i`th song out of it (the one playing: on to the next). */
    fun remove(i: Int) {
        val m = mpv ?: return
        if (i !in _queue.value.indices) return
        _queue.value = _queue.value.toMutableList().apply { removeAt(i) }
        m.command("playlist-remove", i.toString())
        keep()
    }

    /** Where a song plays from: its file, or the server's stream. */
    private fun where(s: Song) = when {
        s.server -> Server.stream(s).orEmpty()
        s.tube -> "ytm://" + s.id.removePrefix("y")
        else -> s.path
    }

    fun toggle() { mpv?.command("cycle", "pause") }

    fun next() { mpv?.command("playlist-next") }

    /** Back: to the song's start, or (in its first seconds) the one before. */
    fun previous() {
        val m = mpv ?: return
        if (position.value > 3_000 || index <= 0) seek(0) else m.command("playlist-prev")
    }

    fun seek(ms: Long) {
        position.value = ms
        mpv?.command("seek", "%.3f".format(java.util.Locale.ROOT, ms / 1000.0), "absolute")
    }

    fun stop() {
        mpv?.command("stop")
        _queue.value = emptyList()
    }

    fun close() {
        timeSong(null)
        // where it was, written now (not on another thread: the app's about to go)
        _queue.value.getOrNull(index)?.let { s ->
            runCatching { Home.write(kept, written(_queue.value, s)) }
        }
        mpv?.close(); mpv = null
    }
}
