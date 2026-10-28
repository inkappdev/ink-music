package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What plays, and the queue: mpv's own playlist holds the same songs in the same order, so one
 * song runs into the next with no gap (a live record stays one). The phone's player is a service
 * behind a session; here it's this, in the app.
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
        Thread({ listen(m) }, "mpv").apply { isDaemon = true }.start()
    }

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
                    }
                }
                is Mpv.Shutdown -> return
                else -> {}
            }
        }
    }

    private fun tell() {
        val q = _queue.value
        val s = q.getOrNull(index)
        _now.value = s?.let { Now(it, index, !paused, duration.takeIf { d -> d > 0 } ?: it.duration) }
    }

    /** These songs, from `start`: the queue they become. */
    fun play(songs: List<Song>, start: Int = 0) {
        val m = mpv ?: return
        if (songs.isEmpty()) return
        _queue.value = songs
        m.command("stop")
        m.command("playlist-clear")
        songs.forEach { m.command("loadfile", it.path, "append") }
        position.value = 0
        m.set("playlist-pos", start.toString())
        m.set("pause", "no")
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

    fun close() { mpv?.close(); mpv = null }
}
