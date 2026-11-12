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
/** A stream's own file name ("kexp160.aac"), what mpv calls a station before it says a song: not a song. */
private val STREAM_FILE = Regex("""^[\w.-]+\.(aac|mp3|ogg|opus|flac|m3u8?|pls|aacp)$""", RegexOption.IGNORE_CASE)

object Player {
    /** What's playing: the song, whether it moves, how long it is (ms; 0 while not known). */
    data class Now(val song: Song, val index: Int, val playing: Boolean, val duration: Long)

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue
    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> = _now
    /** What the station playing says it's playing (null: nothing said, or not a station). */
    val onAir = MutableStateFlow<OnAir.Said?>(null)
    /** Where in the song (ms), apart from [now] so its ticking redraws only what shows it. */
    val position = MutableStateFlow(0L)
    /** Why it won't play, said once (no libmpv). */
    val trouble = MutableStateFlow<String?>(null)
    /** On Linux with no libmpv: the line that installs it on this system ([NoSound] shows it, then [retry]). */
    val missing = MutableStateFlow<String?>(null)
    /** What's heard of the song playing: its format, depth and rate, where it came from (null till it's opened). */
    val heard = MutableStateFlow<Heard?>(null)
    /** The app's own volume, 0 to 100 (the Mac's is apart from it), and whether it's muted; kept. */
    val volume = MutableStateFlow(runCatching { File(Home.dir, "volume").readText().trim().toInt() }.getOrDefault(100).coerceIn(0, 100))
    val muted = MutableStateFlow(false)
    /** Shuffle (the songs after the one playing, in another order) and repeat (0 off, 1 all, 2 one); kept. */
    val shuffle = MutableStateFlow(File(Home.dir, "shuffle").exists())
    val repeat = MutableStateFlow(runCatching { File(Home.dir, "repeat").readText().trim().toInt() }.getOrDefault(0).coerceIn(0, 2))
    /**
     * Where the sound goes: an output (mpv's name for it; "auto", the Mac's own choice) and whether it's had alone
     * (exclusive: nothing else on the computer plays through it meanwhile, and on a Mac it's set to the file's own
     * sample rate, not mixed at the system's). Kept.
     */
    /**
     * The output, shared or alone, and the level: `even` by the files' replaygain tags; `measured` (2026-11-12, a tester:
     * loud songs down and quiet ones up, untagged or not), each song's loudness measured as it plays (EBU R128,
     * ffmpeg's loudnorm) and brought to `target` LUFS: -18 quieter, -14 as streaming services play, -11 louder.
     */
    data class Sound(val device: String = "auto", val exclusive: Boolean = false, val even: Boolean = true, val measured: Boolean = false, val target: Int = -14)
    private val soundFile = File(Home.dir, "sound.json")
    val sound = MutableStateFlow(runCatching { JSONObject(soundFile.readText()).let {
        Sound(it.optString("device", "auto"), it.optBoolean("exclusive"), it.optBoolean("even", true), it.optBoolean("measured"), it.optInt("target", -14))
    } }.getOrDefault(Sound()))

    /** Every output mpv can reach, as it names them and as they're called ("auto" first). */
    private fun everyOutput(): List<Pair<String, String>> {
        val list = mpv?.get("audio-device-list") ?: return emptyList()
        return runCatching {
            val a = JSONArray(list)
            (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("name") to it.optString("description") }
        }.getOrDefault(emptyList())
    }

    /**
     * The outputs to choose from: each one once. Linux's mpv lists every device by every way to it (PipeWire, PulseAudio,
     * each of ALSA's surround layouts, jack, openal, sdl: 28 for 4 outputs, "a ton of audio output options", 2026-11-08):
     * PipeWire's where it's there, else PulseAudio's, else ALSA's own; the Mac's and Windows' as they are. The one chosen
     * always among them.
     */
    fun outputs(): List<Pair<String, String>> {
        val all = everyOutput()
        val auto = all.filter { it.first == "auto" }
        val pw = all.filter { it.first.startsWith("pipewire/") }
        val pulse = all.filter { it.first.startsWith("pulse/") }
        val layouts = Regex("""^alsa/(front|surround\d+|sysdefault|pipewire)\b|^alsa$""")
        val shown = when {
            pw.isNotEmpty() -> auto + pw
            pulse.isNotEmpty() -> auto + pulse
            else -> all.filter { it.first !in setOf("jack", "openal", "sdl") && !layouts.containsMatchIn(it.first) }
        }
        val chosen = sound.value.device
        return shown + all.filter { it.first == chosen && shown.none { s -> s.first == chosen } }
    }

    fun sound(to: Sound) {
        val was = sound.value
        sound.value = to
        // alone: one deck has the output, so no handover under way
        if (to.exclusive && !was.exclusive) Crossfade.settle()
        decks.forEach { m ->
            if (to.device != was.device) m.set("audio-device", to.device)
            if (to.exclusive != was.exclusive) { m.set("audio-exclusive", if (to.exclusive) "yes" else "no"); m.command("ao-reload") }
            if (to.even != was.even || to.measured != was.measured) m.set("replaygain", if (to.even && !to.measured) "album" else "no")
        }
        if (to.measured != was.measured || to.target != was.target) rechain()
        scope.launch { runCatching { Home.write(soundFile, JSONObject().put("device", to.device).put("exclusive", to.exclusive).put("even", to.even)
            .put("measured", to.measured).put("target", to.target).toString()) } }
    }

    /**
     * The output played to: the one chosen, while it's there; unplugged (a USB DAC taken away), the computer's own, back
     * to the chosen one once it's plugged in again (2026-11-08: the FiiO away, every song failed at once and the queue ran
     * through a song a second). Looked at every 3 s.
     */
    internal fun deviceNow(): String {
        val chosen = sound.value.device
        if (chosen == "auto") return chosen
        val there = everyOutput()
        return if (there.isEmpty() || there.any { it.first == chosen }) chosen else "auto"
    }
    private var deviceWatch: kotlinx.coroutines.Job? = null
    private fun watchDevice() {
        if (deviceWatch != null) return
        deviceWatch = scope.launch {
            // the first look soon after the first deck opens (it's opened before mpv can list what's there)
            var wait = 300L
            while (true) {
                kotlinx.coroutines.delay(wait); wait = 3_000
                runCatching {
                    val want = deviceNow()
                    decks.forEach { m -> if (m.get("audio-device") != want) { m.set("audio-device", want); Log.d("output: $want") } }
                }
            }
        }
    }

    /** The queue in its own order while shuffled (back to it when shuffle's off). */
    private var unshuffled: List<Song>? = null

    /**
     * Two decks, turn about (Crossfade): the one playing, and the one free, where the next song's readied for a
     * transition. With transitions off the second only waits.
     */
    @Volatile private var mpv: Mpv? = null
    @Volatile private var spareDeck: Mpv? = null
    private val decks get() = listOfNotNull(mpv, spareDeck)
    /** The threads reading each deck's events (Player.listen), waited for before mpv is let go. */
    private val listeners = mutableListOf<Thread>()
    /** Even volume's level for a song that has none in its tags (dB). */
    const val UNTAGGED = -7f
    @Volatile private var paused = true
    @Volatile private var index = -1
    /** Automix's handover putting the queue in around the song it readied: each move shifts mpv's playlist-pos (become). */
    @Volatile private var rebuilding = false
    @Volatile private var duration = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kept = File(Home.dir, "queue.json")
    /** A place to go to once the song it's in has loaded (the queue brought back). */
    @Volatile private var pendingSeek = 0L

    /** Once libmpv's been installed: tried again, no restart. */
    fun retry() { if (mpv == null) start() }

    /** This system's way to install libmpv, from /etc/os-release's ID and ID_LIKE (Debian's name is Ubuntu's too). */
    private fun install(): String {
        val os = runCatching { File("/etc/os-release").readLines() }.getOrDefault(emptyList())
            .filter { it.startsWith("ID=") || it.startsWith("ID_LIKE=") }
            .flatMap { it.substringAfter('=').trim('"').split(' ') }
        return when {
            os.any { it == "fedora" || it == "rhel" } -> "sudo dnf install mpv-libs"
            os.any { it.startsWith("opensuse") || it == "suse" } -> "sudo zypper install libmpv2"
            os.any { it == "arch" } -> "sudo pacman -S mpv"
            os.any { it == "debian" || it == "ubuntu" } -> "sudo apt install libmpv2"
            else -> "install libmpv (often called libmpv2 or mpv-libs)"
        }
    }

    fun start() {
        mpv = try {
            deck(main = true)
        } catch (e: Throwable) {
            // on Linux the system's libmpv plays (it isn't carried in the app; its package asks for it, but a
            // package opened some other way may not have): said how to get it on this system
            if (!Home.mac && !Home.windows) missing.value = install()
            trouble.value = if (!Home.mac && !Home.windows) "can't play yet: ink music plays through mpv's library, and this computer hasn't it"
                else "can't play: ${e.message?.lowercase()}"
            null
        }
        mpv ?: return
        spareDeck = runCatching { deck(main = false) }.getOrNull()
        trouble.value = null
        missing.value = null
        NowPlaying.start()
        for (d in decks) {
            d.observe("pause", Mpv.FLAG)
            d.observe("playlist-pos", Mpv.INT64)
            d.observe("duration", Mpv.DOUBLE)
            d.observe("time-pos", Mpv.DOUBLE)
            // a station's stream says what it's playing (ICY), as its title
            d.observe("media-title", Mpv.STRING)
            // a YouTube song's stream found as it opens (they expire: never ahead of time)
            d.hook("on_load")
            listeners += Thread({ listen(d) }, "mpv").apply { isDaemon = true; start() }
        }
        Crossfade.start()
        // the queue as it was, once the library knows its songs (a server's come from disk in a moment)
        scope.launch {
            // (a queue of songs dropped from elsewhere comes back whole, the library or no)
            withTimeoutOrNull(10_000) { Library.songs.first { it.isNotEmpty() } }
            restore()
        }
    }

    /** A deck: mpv as ink music has it (the second silent and paused till a transition wants it). */
    private fun deck(main: Boolean): Mpv = Mpv(mapOf(
            "vid" to "no", "audio-display" to "no", "terminal" to "no", "idle" to "yes",
            "gapless-audio" to "yes",
            // even volume from the files' own tags: a record's level, a song's when it has none
            "replaygain" to if (sound.value.even && !sound.value.measured) "album" else "no", "replaygain-clip" to "no",
            // a song with no gain anywhere: about where most of a library sits (the phone's, ReplayGain's -18 LUFS
            // from today's masters), so an untagged song isn't the odd loud one out
            "replaygain-fallback" to UNTAGGED.toString(),
            // the Mac's media keys and Now Playing are ink's own (NowPlaying.kt): mpv's, as a library, is never told the song
            "media-controls" to if (NowPlaying.ours) "no" else "yes", "input-media-keys" to if (NowPlaying.ours) "no" else "yes",
            "audio-client-name" to "ink music", "title" to "ink music",
            "ytdl" to "no", "cache" to "yes",
            "volume" to volume.value.toString(), "volume-max" to "100",
            "audio-device" to deviceNow().also { watchDevice() }, "audio-exclusive" to if (sound.value.exclusive) "yes" else "no",
            "loop-playlist" to if (repeat.value == 1) "inf" else "no", "loop-file" to if (repeat.value == 2) "inf" else "no",
            // the equalizer, automix's bass (ffmpeg's filters, steered as they change)
            "af" to chain(),
            // -Dink.ao=null: silent, to try it without playing out loud
        ) + listOfNotNull(System.getProperty("ink.ao")?.let { "ao" to it }) + (if (main) emptyMap() else mapOf(
            "pause" to "yes", "volume" to "0", "media-controls" to "no", "input-media-keys" to "no",
            "loop-playlist" to "no", "loop-file" to "no",
        )))

    private fun restore() {
        try { restoring() } finally { restored = true }
    }

    // nothing written till the kept queue's been read: mpv's idle clock at start wrote an empty queue over it, and with
    // no songs on the computer (YouTube's and podcasts only) the read, 10 s on, found nothing (2026-10-31)
    @Volatile private var restored = false

    private fun restoring() {
        val o = runCatching { JSONObject(kept.readText()) }.getOrNull() ?: return
        val ids = o.optJSONArray("ids") ?: return
        val whole = o.optJSONArray("songs")
        val songs = (0 until ids.length()).mapNotNull { i ->
            Library.song(ids.optString(i)) ?: whole?.optJSONObject(i)?.let(::songOf)?.also { if (it.tube) YouTube.remember(it); if (it.station) Stations.remember(it) }
        }
        if (songs.isEmpty() || _queue.value.isNotEmpty()) return
        val at = songs.indexOfFirst { it.id == o.optString("id") }.takeIf { it >= 0 } ?: 0
        pendingSeek = if (at >= 0) o.optLong("position") else 0
        load(songs, at, go = false)
    }

    /** The queue, its song and where in it, written down (on a change, and every ten seconds playing). */
    private fun keep() {
        if (!restored) return
        val q = _queue.value
        val s = q.getOrNull(index)
        val text = written(q, s)
        scope.launch { runCatching { Home.write(kept, text) } }
    }
    private var keptAt = 0L
    private var placeAt = 0L

    /** The queue as kept: its ids, each song whole (YouTube's aren't in the library), the one playing and where. */
    private fun written(q: List<Song>, s: Song?) = JSONObject().put("ids", JSONArray(q.map { it.id }))
        .put("songs", JSONArray(q.map { if (it.tube || it.station || it.episode || Library.song(it.id) == null) it.json() else JSONObject() })).put("id", s?.id ?: "").put("position", position.value).toString()

    private fun listen(m: Mpv) {
        while (true) {
            val e = m.next()
            // the free deck: only its stream found as it opens, and its song readied (Crossfade)
            if (m !== mpv) when (e) {
                is Mpv.Hook -> {}
                is Mpv.Loaded -> { Crossfade.loaded(m); continue }
                is Mpv.Shutdown -> return
                else -> continue
            }
            when (e) {
                is Mpv.Changed -> when (e.name) {
                    "pause" -> {
                        paused = e.value as? Boolean ?: true
                        // an episode paused: where it was, kept
                        if (paused) _queue.value.getOrNull(index)?.let { Pods.keep(it, position.value, duration) }
                        tell()
                    }
                    "media-title" -> {
                        val s = _queue.value.getOrNull(index)
                        onAir.value = when {
                            s?.station != true -> null
                            // NTS's streams say only "stream": its own feed says the show instead (OnAir.nts)
                            OnAir.isNts(s.id) -> { OnAir.nts(s.id); onAir.value }
                            else -> (e.value as? String)?.takeIf { it != s.title && "://" !in it && !STREAM_FILE.containsMatchIn(it) }?.let { OnAir.parse(it, s.title) }
                        }
                    }
                    // read live, not the event's value: a handover's moves (become) left a trail of passing positions,
                    // each told to ListenBrainz as the song playing (the one 1, 2, 3 places in)
                    "playlist-pos" -> if (!rebuilding) { index = m.get("playlist-pos")?.toIntOrNull() ?: (e.value as? Long)?.toInt() ?: -1; heard.value = heard.value?.takeIf { it.id == _queue.value.getOrNull(index)?.id }; tell() }
                    "duration" -> { duration = ((e.value as? Double) ?: 0.0).times(1000).toLong(); tell() }
                    "time-pos" -> if (!casting) {
                        val ms = ((e.value as? Double) ?: 0.0).times(1000).toLong()
                        // a few times a second is plenty for a line and a clock
                        if (kotlin.math.abs(ms - position.value) >= 200 || ms < position.value) position.value = ms
                        heard(ms)
                        // how long this song has actually played (a jump isn't playing)
                        val step = ms - lastMs
                        if (!paused && step in 1..2_000) playedMs += step
                        lastMs = ms
                        if (!paused && System.currentTimeMillis() - keptAt > 10_000) { keptAt = System.currentTimeMillis(); keep() }
                        // preview: a taste of each song, then the next (Preview)
                        _queue.value.getOrNull(index)?.let { Preview.tick(it, ms, duration, paused) }
                        // an episode's place, every 15 s it plays
                        if (!paused && System.currentTimeMillis() - placeAt > 15_000) {
                            placeAt = System.currentTimeMillis()
                            _queue.value.getOrNull(index)?.takeIf { it.episode }?.let { Pods.keep(it, ms, duration) }
                        }
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
                is Mpv.Loaded -> {
                    // the equalizer moved while the song before played: its filters as they are now
                    if (staleChain) { staleChain = false; m.set("af", chain()) }
                    val s = _queue.value.getOrNull(index)
                    // an episode at its own speed (a song always at 1), from where you stopped in it
                    m.set("speed", if (s?.episode == true) "%.2f".format(java.util.Locale.ROOT, Podcasts.speed) else "1")
                    if (pendingSeek == 0L && s?.episode == true) Podcasts.place(s.id).takeIf { it > 5_000 }?.let { pendingSeek = it }
                    if (pendingSeek > 0) { val ms = pendingSeek; pendingSeek = 0; seek(ms) }
                    hear(m)
                }
                is Mpv.Shutdown -> return
                else -> {}
            }
        }
    }

    /**
     * What's heard, read off mpv once the song's opened (and again a moment on: a lossy stream's bitrate is known
     * once some has played). The depth is the file's own (its tags, the server's word) where known: mpv's decoder
     * hands 24-bit FLAC on as 32.
     */
    private fun hear(m: Mpv) {
        val s = _queue.value.getOrNull(index) ?: return
        scope.launch {
            for (wait in listOf(400L, 2_600L)) {
                kotlinx.coroutines.delay(wait)
                if (_queue.value.getOrNull(index)?.id != s.id) return@launch
                val raw = m.get("audio-codec-name") ?: continue
                val codec = when {
                    raw.startsWith("pcm_") -> "wav"
                    raw == "mp3float" -> "mp3"
                    raw.startsWith("aac") -> "aac"
                    else -> raw
                }
                val format = m.get("audio-params/format").orEmpty()
                val bits = s.bits.takeIf { it > 0 } ?: Regex("\\d+").find(format)?.value?.toIntOrNull()?.takeIf { codec in Heard.LOSSLESS && it <= 24 } ?: 0
                val gain = (m.get("current-tracks/audio/replaygain-album-gain") ?: m.get("current-tracks/audio/replaygain-track-gain"))?.toFloatOrNull()
                    ?: if (s.station) null else UNTAGGED
                heard.value = Heard(
                    id = s.id, codec = codec, bits = if (codec in Heard.LOSSLESS) bits else 0,
                    rate = m.get("audio-params/samplerate")?.toIntOrNull() ?: s.rate,
                    channels = m.get("audio-params/channel-count")?.toIntOrNull() ?: 0,
                    kbps = ((m.get("audio-bitrate")?.toDoubleOrNull() ?: 0.0) / 1000).toInt(),
                    from = when { s.server -> "server"; s.tube -> "youtube"; s.station -> "station"; else -> "computer" },
                    gain = gain,
                )
            }
        }
    }

    /**
     * The path past the file, as mpv says it now (for the signal path): its decoder, what it decodes to ("s16",
     * "s32", "floatp"), what it hands the system (format, rate, channels), the system's sound (mpv's name for it:
     * "coreaudio", "wasapi", "pipewire", "pulse", "alsa"), and the output (as it's called; "" for the system's choice).
     */
    data class Out(val decoder: String, val decoded: String, val format: String, val rate: Int, val channels: Int, val ao: String, val device: String)

    fun out(): Out? {
        val m = mpv ?: return null
        val chosen = m.get("audio-device").orEmpty()
        val device = if (chosen == "auto" || chosen.isEmpty()) "" else outputs().firstOrNull { it.first == chosen }?.second?.ifEmpty { chosen } ?: chosen
        return Out(
            decoder = m.get("current-tracks/audio/decoder-desc").orEmpty(),
            decoded = m.get("audio-params/format").orEmpty(),
            format = m.get("audio-out-params/format").orEmpty(),
            rate = m.get("audio-out-params/samplerate")?.toIntOrNull() ?: 0,
            channels = m.get("audio-out-params/channel-count")?.toIntOrNull() ?: 0,
            ao = m.get("current-ao").orEmpty(),
            device = device,
        )
    }

    /** The app's volume (0 to 100), kept; on a speaker, the speaker's. */
    fun volume(v: Int) {
        val to = v.coerceIn(0, 100)
        if (casting) return Casting.setVolume(to / 100f)
        volume.value = to
        if (muted.value && to > 0) mute(false)
        if (!Crossfade.fading) mpv?.set("volume", to.toString())
        scope.launch { runCatching { Home.write(File(Home.dir, "volume"), to.toString()) } }
    }

    fun mute(on: Boolean = !muted.value) { muted.value = on; decks.forEach { it.set("mute", if (on) "yes" else "no") } }

    /** Repeat's next step: off, all, one. */
    fun repeat() {
        val r = (repeat.value + 1) % 3
        repeat.value = r
        mpv?.set("loop-playlist", if (r == 1) "inf" else "no")
        mpv?.set("loop-file", if (r == 2) "inf" else "no")
        runCatching { File(Home.dir, "repeat").let { if (r == 0) it.delete() else it.writeText(r.toString()) } }
    }

    /** Shuffle on (the songs after this one in another order) or off (back to the queue's own order). */
    fun shuffle() {
        val on = !shuffle.value
        shuffle.value = on
        runCatching { File(Home.dir, "shuffle").let { if (on) it.writeText("") else it.delete() } }
        if (casting) return
        val q = _queue.value
        if (q.isEmpty() || index < 0) return
        val rest = if (on) { unshuffled = q; q.drop(index + 1).shuffled() } else {
            // the queue's own order on from the song playing
            val own = unshuffled ?: return
            unshuffled = null
            val at = own.indexOfFirst { it.id == q[index].id }
            val played = q.take(index + 1).map { it.id }.toSet()
            (if (at >= 0) own.drop(at + 1) else own).filter { it.id !in played } + q.drop(index + 1).filter { s -> own.none { it.id == s.id } }
        }
        reorder(rest)
    }

    /** The songs after the one playing replaced by these (mpv's playlist with them). */
    private fun reorder(rest: List<Song>) {
        val m = mpv ?: return
        val q = _queue.value
        for (i in q.size - 1 downTo index + 1) m.command("playlist-remove", i.toString())
        rest.forEach { m.command("loadfile", where(it), "append") }
        _queue.value = q.take(index + 1) + rest
        radioFrom = null
        keep()
    }

    // the server told what's playing, and that it was heard once it has played half, or four minutes
    // (as the phone does, and Last.fm counts); Navidrome passes it on to its own scrobbling
    private var toldNow: String? = null
    private var counted: String? = null
    private var toldTube: String? = null

    private fun heard(ms: Long) {
        // private listening: nothing told
        if (Private.on.value) return
        val n = _now.value ?: return
        val s = n.song
        // a YouTube song 30 s in: told to your history there, signed in (as its own app does)
        if (s.tube && ms >= 30_000 && toldTube != s.id) { toldTube = s.id; TubeAccount.played(s) }
        if (counted == s.id || n.duration <= 0 || s.station || s.episode) return
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
    @Volatile private var timingLength = 0L

    /** The song before written down as heard (30 s or more), and the clock started for this one. */
    private fun timeSong(s: Song?) {
        if (s?.id == timing?.id) return
        // an episode left (on to the next, or played out): where it got to kept
        timing?.takeIf { it.episode }?.let { Pods.keep(it, lastMs, timingLength) }
        timing?.takeIf { !it.station && !it.episode && !Private.on.value }?.let { t -> History.add(History.Heard(timingAt, t.id, t.title, t.artist, t.album, t.albumId, playedMs)) }
        timing = s; timingAt = System.currentTimeMillis(); playedMs = 0; lastMs = 0; timingLength = s?.duration ?: 0
    }

    /** Radio: when the queue's down to its last song, more like it at its end (a setting, on at first). */
    val radio = MutableStateFlow(runCatching { !File(Home.dir, "radio-off").exists() }.getOrDefault(true))
    // songs radio added: not seeds for its next round while there are songs you chose; and the last seed asked
    private val fromRadio = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var radioFrom: String? = null

    /** Where radio's songs come from: online, offline, or both (Radio.Mode; kept). */
    val radioMode = MutableStateFlow(runCatching { Radio.Mode.valueOf(File(Home.dir, "radio-mode").readText().trim()) }.getOrDefault(Radio.Mode.BOTH))
    fun radioMode(m: Radio.Mode) {
        radioMode.value = m
        runCatching { File(Home.dir, "radio-mode").writeText(m.name) }
        radioFrom = null
    }

    fun radio(on: Boolean) {
        radio.value = on
        runCatching { File(Home.dir, "radio-off").let { if (on) it.delete() else it.writeText("") } }
        radioFrom = null
        topUp()
    }

    private fun topUp() {
        val q = _queue.value
        // casting, where the speaker is (this computer's own player waits, paused)
        val index = if (casting) _now.value?.index ?: -1 else index
        if (!radio.value || q.isEmpty() || index < 0 || index < q.size - 2) return
        val last = q.last()
        // a station plays on forever: nothing to follow it
        if (last.station) return
        // nor after an episode: radio's songs aren't what follows a podcast
        if (last.episode) return
        if (radioFrom == last.id) return
        radioFrom = last.id
        fun seed(s: Song) = Radio.Seed(s.id, s.title, s.artist)
        // the last song, and the last two before it you chose (radio's own passed over): where the vibe is
        val chosen = q.dropLast(1).asReversed().filter { it.id !in fromRadio && it.id != last.id }.distinctBy { it.id }.take(2)
        val seeds = listOf(seed(last)) + chosen.map(::seed)
        val queued = q.flatMap { listOf(it.id, Radio.key(it.title, it.artist)) }.toHashSet()
        scope.launch {
            // a mix's queue (its first song the mix's): radio keeps to its tempo
            val more = Radio.after(seeds, queued, TempoMixes.keeping(q.firstOrNull()?.id), radioMode.value)
            if (more.isEmpty() || !radio.value || _queue.value.lastOrNull()?.id != last.id) return@launch
            fromRadio += more.map { it.id }
            // on a speaker: to its queue
            if (casting) return@launch javax.swing.SwingUtilities.invokeLater { Casting.extend(more) }
            val m = mpv ?: return@launch
            _queue.value = _queue.value + more
            more.forEach { m.command("loadfile", where(it), "append") }
            keep()
        }
    }

    private fun tell() {
        if (casting) return
        val q = _queue.value
        val s = q.getOrNull(index)
        timeSong(s)
        topUp()
        _now.value = s?.let { Now(it, index, !paused, duration.takeIf { d -> d > 0 } ?: it.duration) }
        if (s != null && s.id == timing?.id && duration > 0) timingLength = duration
        if (s != null && !s.station && !s.episode && toldNow != "${s.id}/$index" && !paused && !Private.on.value) {
            toldNow = "${s.id}/$index"; counted = null
            if (s.server) Server.scrobble(s.id, false)
            val said = said(s, duration.takeIf { it > 0 } ?: s.duration)
            scope.launch { ListenBrainz.playingNow(Home.dir, said); LastFm.playingNow(Home.dir, said) }
        }
        if (s != null) keep()
    }

    /** Radio from a song: it plays, and more like it follow as the queue runs out (radio on). */
    fun startRadio(s: Song) { if (!radio.value) radio(true); play(listOf(s)) }

    /** More after a song already playing (a mix's own songs, once they're read), if it's still the one playing. */
    fun follow(s: Song, songs: List<Song>) {
        if (casting) return Casting.extend(songs)
        val m = mpv ?: return
        if (_queue.value.getOrNull(index)?.id != s.id) return
        val there = _queue.value.map { it.id }.toSet()
        val more = songs.filter { it.id !in there }
        _queue.value = _queue.value + more
        more.forEach { m.command("loadfile", where(it), "append") }
        keep()
    }

    /** These songs, from `start`: the queue they become. */
    fun play(songs: List<Song>, start: Int = 0) {
        // a speaker chosen: there
        Casting.on.value?.let { to -> return Casting.play(to, songs, start, 0L) }
        pendingSeek = 0
        unshuffled = null
        if (shuffle.value && songs.size > 2) {
            // shuffled: the one chosen first (or any, from the start), the rest in another order
            val first = songs[start.coerceIn(songs.indices)]
            unshuffled = songs
            return load(listOf(first) + (songs - first).shuffled(), 0, go = true)
        }
        load(songs, start, go = true)
    }

    // ---- casting: a speaker plays, this computer's player waits, paused, and shows the speaker's song ----

    @Volatile private var casting = false
    @Volatile private var castPos = 0L
    @Volatile private var castAt = 0L
    @Volatile private var castMoving = false
    /** The queue this computer's player holds while a speaker plays (shown again if the cast's lost). */
    private var waiting: List<Song> = emptyList()
    private var ticking: kotlinx.coroutines.Job? = null

    /** A speaker takes the queue: this computer pauses, and up next is what the speaker was handed. */
    fun pauseHere(handed: List<Song>) {
        if (!casting) waiting = _queue.value
        casting = true
        Crossfade.settle()
        mpv?.set("pause", "yes")
        _queue.value = handed
        // the speaker says where it is when it changes; between, the clock runs on here
        ticking?.cancel()
        ticking = scope.launch {
            while (casting) {
                if (castMoving) position.value = castPos + (SystemClock.elapsedRealtime() - castAt)
                kotlinx.coroutines.delay(500)
            }
        }
    }

    /** What the speaker says it plays. */
    fun showCast(n: app.ink.music.Now) {
        if (!casting) return
        castPos = n.position; castAt = n.at; castMoving = n.moving
        position.value = n.position
        _now.value = Now(n.song, n.index, n.playing, n.duration)
        topUp()
    }

    /** Up next while casting: the speaker's queue. */
    fun showCastQueue(songs: List<Song>) { if (casting) _queue.value = songs }

    /** Casting over: this computer's own player shows again, where it waited. */
    fun castEnded() {
        casting = false
        ticking?.cancel()
        val m = mpv ?: return
        _queue.value = waiting
        index = m.get("playlist-pos")?.toIntOrNull() ?: -1
        tell()
    }

    /** Back from a speaker: its queue here, from the song it was on, where it was; playing if it was. */
    fun takeBack(songs: List<Song>, start: Int, at: Long, go: Boolean) { pendingSeek = at; load(songs, start, go) }

    private fun load(songs: List<Song>, start: Int, go: Boolean) {
        val m = mpv ?: return
        if (songs.isEmpty()) return
        Crossfade.settle()
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
        // on a speaker: after the speaker's song
        if (casting) return Casting.playNext(s)
        val m = mpv ?: return
        if (_queue.value.isEmpty() || index < 0) return play(listOf(s))
        _queue.value = _queue.value.toMutableList().apply { add(index + 1, s) }
        m.command("loadfile", where(s), "insert-next")
        keep()
    }

    /** Into the queue at `at` (a mix's arc, Mixes: each song read put where its tempo goes): appended, moved there. */
    fun insert(s: Song, at: Int) {
        if (casting) return Casting.extend(listOf(s))
        val m = mpv ?: return
        if (_queue.value.isEmpty() || index < 0) return play(listOf(s))
        val i = at.coerceIn(index + 1, _queue.value.size)
        _queue.value = _queue.value.toMutableList().apply { add(i, s) }
        m.command("loadfile", where(s), "append")
        if (i < _queue.value.size - 1) m.command("playlist-move", (_queue.value.size - 1).toString(), i.toString())
        keep()
    }

    /** At the queue's end (nothing playing: plays it). */
    fun add(s: Song) {
        if (casting) return Casting.extend(listOf(s))
        val m = mpv ?: return
        if (_queue.value.isEmpty() || index < 0) return play(listOf(s))
        _queue.value = _queue.value + s
        m.command("loadfile", where(s), "append")
        keep()
    }

    /** The queue's `i`th song, from its start. */
    fun jump(i: Int) {
        if (casting) return Casting.jump(i)
        val m = mpv ?: return
        if (i !in _queue.value.indices) return
        Crossfade.settle()
        m.set("playlist-pos", i.toString())
        m.set("pause", "no")
    }

    /**
     * A queued song moved from `from` to `to` (up next dragged into its order, 2026-11-10); the one playing plays on.
     * mpv's playlist-move puts it in the place of the entry at its second index, so one moved down goes before `to + 1`.
     */
    fun move(from: Int, to: Int) {
        // a speaker's queue is fixed but for its end
        if (casting) return
        val m = mpv ?: return
        val q = _queue.value
        if (from !in q.indices || to !in q.indices || from == to) return
        _queue.value = q.toMutableList().apply { add(to, removeAt(from)) }
        m.command("playlist-move", from.toString(), (if (from < to) to + 1 else to).toString())
        keep()
    }

    /** The queue's `i`th song out of it (the one playing: on to the next). */
    fun remove(i: Int) {
        // a speaker's queue is fixed but for its end
        if (casting) return
        val m = mpv ?: return
        if (i !in _queue.value.indices) return
        if (i == index) Crossfade.settle()
        _queue.value = _queue.value.toMutableList().apply { removeAt(i) }
        m.command("playlist-remove", i.toString())
        keep()
    }

    /** Where a song plays from: its file, or the server's stream. */
    internal fun where(s: Song) = when {
        // kept on the computer: from there, no signal needed (Offline)
        Offline.file(s.id) != null -> Offline.file(s.id)!!.path
        s.server -> Server.stream(s).orEmpty()
        s.tube -> "ytm://" + s.id.removePrefix("y")
        s.station -> s.path
        // an episode: its link (mpv reads it as it plays, and seeks in it)
        else -> s.path
    }

    fun toggle() { Crossfade.settle(); if (casting) return Casting.toggle(); mpv?.command("cycle", "pause") }

    fun next() { Crossfade.settle(); if (casting) return Casting.next(); mpv?.command("playlist-next") }

    /** Back: to the song's start, or (in its first seconds) the one before. */
    fun previous() {
        if (casting) return Casting.previous()
        Crossfade.settle()
        val m = mpv ?: return
        if (position.value > 3_000 || index <= 0) seek(0) else m.command("playlist-prev")
    }

    fun seek(ms: Long) {
        Crossfade.settle()
        position.value = ms
        if (casting) { castPos = ms; castAt = SystemClock.elapsedRealtime(); return Casting.seek(ms) }
        mpv?.command("seek", "%.3f".format(java.util.Locale.ROOT, ms / 1000.0), "absolute")
        NowPlaying.moved()
    }

    /** An episode's speed (1 to 2, or .8), kept for every episode after. */
    fun speed(v: Float) {
        Podcasts.speed = v
        if (_queue.value.getOrNull(index)?.episode == true) mpv?.set("speed", "%.2f".format(java.util.Locale.ROOT, v))
    }

    fun stop() {
        Crossfade.settle()
        mpv?.command("stop")
        _queue.value = emptyList()
        heard.value = null
        // remembered: the next start opens with nothing playing, as the phone does
        keep()
    }

    // ---- the two decks, for Crossfade ----

    internal fun active() = mpv
    internal fun spare() = spareDeck
    internal fun index() = index
    internal fun position() = position.value
    internal fun length() = duration
    internal fun isPaused() = paused
    /** Whether this computer plays (not a speaker). */
    internal fun mixable() = !casting && mpv != null && spareDeck != null

    /**
     * The handover: the readied deck, its song sounding, is the player now, at the queue's `at`: the queue around its
     * song again (it was readied with that one alone), repeat as set; the other deck, its song fading, let be.
     */
    internal fun become(b: Mpv, at: Int) {
        val a = mpv ?: return
        val q = _queue.value
        rebuilding = true
        mpv = b; spareDeck = a
        index = at
        duration = ((b.get("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        paused = false
        // the songs before it, each put in ahead of it; then the ones after
        for (j in 0 until at) { b.command("loadfile", where(q[j]), "append"); b.command("playlist-move", (j + 1).toString(), j.toString()) }
        for (j in at + 1 until q.size) b.command("loadfile", where(q[j]), "append")
        rebuilding = false
        b.set("loop-playlist", if (repeat.value == 1) "inf" else "no")
        b.set("loop-file", if (repeat.value == 2) "inf" else "no")
        // the system's media keys follow the deck that plays (mpv's own, where they're mpv's)
        if (!NowPlaying.ours) { a.set("media-controls", "no"); a.set("input-media-keys", "no"); b.set("media-controls", "yes"); b.set("input-media-keys", "yes") }
        heard.value = null
        hear(b)
        tell()
        NowPlaying.moved()
    }

    // ---- the filters in mpv's chain: the equalizer's bands, automix's bass held back for a moment ----

    @Volatile private var staleChain = false

    private fun chain(): String {
        val parts = mutableListOf<String>()
        // automix: the bass held back or handed across (highpass@bass), and the filter fade's top closing (lowpass@top)
        if (Crossfade.mode.value == Crossfade.AUTOMIX) { parts += "highpass@bass=f=10"; parts += "lowpass@top=f=20000" }
        val eq = Eq.state.value
        if (eq.on) parts += Eq.filters(eq.levels)
        // measured: each song brought to the level chosen as it plays, the loud down and the quiet up
        sound.value.takeIf { it.measured }?.let { parts += "loudnorm=I=${it.target}:TP=-1.5:LRA=11" }
        return if (parts.isEmpty()) "" else "@ink:lavfi=[${parts.joinToString(",")}]"
    }

    /** The chain set again on a deck (one not playing, or as a song opens: no moment lost). */
    internal fun chainOn(m: Mpv) { m.set("af", chain()) }

    /** The chain changed in kind (a filter more or less): set again on both. */
    internal fun rechain() { val c = chain(); decks.forEach { it.set("af", c) } }

    /**
     * The equalizer as it is now: turned on or off, the chain made again; a band moved, steered where it is (no reload,
     * no gap), and the chain as written made right as the next song opens.
     */
    private var eqOn = Eq.state.value.on
    fun equalize(s: Eq.State) {
        if (s.on != eqOn) { eqOn = s.on; return rechain() }
        if (!s.on) return
        for (m in decks) {
            m.command("af-command", "ink", "volume", "%.2fdB".format(java.util.Locale.ROOT, Eq.preamp(s.levels)), "volume@eqpre")
            s.levels.forEachIndexed { i, mb -> m.command("af-command", "ink", "g", "%.2f".format(java.util.Locale.ROOT, mb / 100.0), "equalizer@eq$i") }
        }
        staleChain = true
        spareDeck?.takeIf { Crossfade.fading.not() }?.let { if (it.get("idle-active") == "yes") chainOn(it) }
    }

    fun close() {
        timeSong(null)
        // where it was, written now (not on another thread: the app's about to go)
        _queue.value.getOrNull(index)?.let { s ->
            runCatching { Home.write(kept, written(_queue.value, s)) }
        }
        // mpv let go only once the listener's out of mpv_wait_event: destroying it meanwhile hung for good (Linux, Win+Q,
        // 2026-10-29). Told to quit, it says shutdown and the listener returns; if it never does, the process ends anyway
        Crossfade.settle()
        decks.zip(listeners).forEach { (m, l) ->
            runCatching { m.command("quit") }
            l.join(2000)
            if (!l.isAlive) m.close()
        }
        mpv = null; spareDeck = null
    }
}
