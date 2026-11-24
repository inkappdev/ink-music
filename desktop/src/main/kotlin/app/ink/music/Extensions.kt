package app.ink.music

/**
 * Extensions, the phone's on the computer (2026-11-24): addresses of your own that a YouTube or server song's stream is
 * asked of first, the song played from the first that has it, else as it would have. The whole of it is core's
 * ([YourExtensions]); this is the computer's hold on it: its files in the app's own folder, its log, each song known
 * by its id (a server song's address is signed anew each time it's made, so it can't be the key).
 */
object Extensions {
    private val core = YourExtensions { Log.d("extension $it") }

    val sources get() = core.sources
    val said get() = core.said
    /** Those that send lossless by default (their manifest says): settings says what they use in data. */
    val lossless get() = core.lossless

    @Volatile private var started = false
    fun start() { if (!started) { started = true; core.start(Home.dir) } }
    fun refreshSaid() = core.refreshSaid(Home.dir)
    fun clean(url: String) = YourExtensions.clean(url)
    fun add(url: String, name: String) = core.add(Home.dir, url, name)
    fun edit(old: String, url: String, name: String) = core.edit(Home.dir, old, url, name)
    fun turn(url: String, on: Boolean) = core.turn(Home.dir, url, on)
    fun remove(url: String) = core.remove(Home.dir, url)
    fun anyOn() = core.anyOn(Home.dir)

    /** The extension the song's playing stream came from (its name, else its host), or "" when none did. */
    fun by(s: Song) = core.by(s.id).orEmpty()

    /** Whether a song is one an extension's asked for: a YouTube or server song (one kept here never reaches the player as either). */
    fun asks(s: Song) = (s.tube || s.server) && !s.station && !s.episode

    /**
     * The song's stream from the first extension on that has it, or null (it plays as it would have). Waits up to 5 s
     * an address: never on the player's own thread. Asked twice at once (ahead, and as it opens), the second joins the first.
     */
    fun stream(s: Song): String? {
        if (!asks(s) || !anyOn()) return null
        // at the quality chosen (its "high" or "low" where the extension has them; original, its own default)
        return core.stream(Home.dir, s.id, YourExtensions.Track(s.title, s.artist, s.album, s.duration, s.id.drop(1)), Quality.chosen.value)
    }

    /** Whether the song's answer is known without asking anyone (kept, or none on has it): YouTube's own not looked up alongside then. */
    fun answered(s: Song) = core.answered(Home.dir, s.id)

    /**
     * The song an address mpv's opening is for, when an extension would be asked for it: a YouTube song's ytm://, or
     * a server song's stream (its id in it), found in the queue, else the library. Null for anything else.
     */
    fun songOf(url: String, queue: List<Song>): Song? {
        val id = when {
            url.startsWith("ytm://") -> "y" + url.removePrefix("ytm://")
            "/rest/stream" in url -> Regex("[?&]id=([^&]+)").find(url)?.groupValues?.get(1)
                ?.let { "n" + java.net.URLDecoder.decode(it, "UTF-8") } ?: return null
            else -> return null
        }
        return queue.firstOrNull { it.id == id } ?: Library.song(id)
    }

    // the next song asked ahead while one plays, so it starts at once (its own thread: a slow one holds up nothing else)
    private val ahead = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "ink-ext-ahead").apply { isDaemon = true } }
    // and, found, downloaded whole while this one plays (an extension's host may send a song no faster than it plays:
    // Lossless's did, 2026-11-24, and it buffered), so it plays from the file
    fun askAhead(s: Song?) { if (s != null && asks(s) && Offline.file(s.id) == null && anyOn()) ahead.execute { runCatching { if (stream(s) != null) core.keepAhead(Home.dir, s.id) } } }
}
