package app.ink.music

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Songs mixed by their tempo (2026-11-08, Bronson: "select a genre and a tempo and it creates a radio playlist that
 * mixes well together", then "lets build all three"): every song's tempo and key read (Tempos), and
 * - a genre at a tempo: its editors' list songs within 4% of it (half and double counted), so any two are within
 *   automix's 8% and every handover can be beats matched;
 * - your music at a tempo: the same from your own songs, the artists you play most first;
 * - a list mixed: all of it, in a DJ's order, each next the nearest the last in tempo and then in key.
 * A tempo mix begins as soon as three are found, more following as they're read, and tells the player its tempo
 * (files/mix-tempo.json) so radio, once the mix runs out, keeps to it. Automix turned on: the mix is its handovers.
 */
object Mixes {
    /** The tempos offered. */
    val TEMPOS = listOf(100, 110, 120, 125, 130, 140)

    /** A mix as it's found: what (a genre's name, "your music", a list's name), its tempo (0: a list in order), how far. */
    data class State(val title: String, val bpm: Int, val read: Int, val of: Int, val found: Int, val playing: Boolean, val done: Boolean,
        val songs: List<Song> = emptyList())

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** A genre at a tempo: its editors' list songs, found on YouTube Music. */
    fun genre(c: Context, genre: String, bpm: Int) = tempo(c, genre, bpm) {
        GenreSongs.of(it, genre).map { s -> { YouTubeMusic.find(s.pick.title, s.pick.artist) } }
    }

    /**
     * Your music at a tempo: everything you've touched, not only a folder (2026-11-08, Bronson: "for someone that doesnt
     * have a navidrome server i want that mix your music feature to be really cool"), the most yours first:
     * - what you've played here twice or more (any source: YouTube's streams too), the most played first, and your loves;
     * - your library (the phone's files, your server's), YouTube Music's liked songs and your uploads, the artists you
     *   play most first, a few of each in turn;
     * - your ten most played artists' top songs (their pages, kept), so it's not only what you know.
     * The same song once (by id, and by its name and artist across sources).
     */
    fun yours(c: Context, bpm: Int) = tempo(c, "your music", bpm) { ctx -> pool(ctx).map { s -> { s } } }

    /** How many songs "your music" has to go by, roughly (its section shows from 20). */
    fun yoursSize(): Int = Library.songs.value.size + YouTubeAccount.liked.value.size + Loved.tube.value.size +
        Listened.now.value.plays.count { !it.key.startsWith("a:") }

    private suspend fun pool(c: Context): List<Song> {
        val out = LinkedHashMap<String, Song>()
        val names = HashSet<String>()
        fun add(s: Song?) { if (s == null || s.station || s.episode || s.id in out) return; val k = Radio.key(s.title, s.artist); if (names.add(k)) out[s.id] = s }
        // played twice or more, the most first; with your loves among them
        val heard = History.read(c)
        val counts = heard.groupingBy { it.id }.eachCount()
        val last = heard.associateBy { it.id }
        val loved = Loved.ids.value
        counts.entries.filter { it.value >= 2 || it.key in loved }.sortedByDescending { it.value + if (it.key in loved) 3 else 0 }.forEach { (id, _) ->
            add(Library.song(id) ?: last[id]?.takeIf { id.startsWith("y") }?.let { h ->
                Song(id = id, uri = android.net.Uri.parse("ytm://" + id.removePrefix("y")), title = h.title, artist = h.artist, album = h.album,
                    albumId = id, albumArtist = h.artist, duration = h.ms, disc = 1, track = 0, year = 0, format = "youtube")
            })
        }
        Loved.tube.value.forEach(::add)
        loved.forEach { add(Library.song(it)) }
        // your library and YouTube's, the artists you play most first, a few each in turn
        val plays = Listened.now.value.plays
        val rest = (Library.songs.value + YouTubeAccount.liked.value + YouTubeAccount.uploads.value)
            .filter { (it.duration == 0L || it.duration > 60_000) && it.id !in out }.shuffled()
        val byArtist = rest.groupBy { it.artist.substringBefore(",").trim().lowercase() }
        val lists = byArtist.keys.sortedByDescending { plays["a:$it"] ?: 0 }.map { byArtist.getValue(it).toMutableList() }
        while (lists.any { it.isNotEmpty() } && out.size < 600) lists.forEach { l -> if (l.isNotEmpty()) add(l.removeAt(0)) }
        // your ten most played artists' top songs (their pages, kept; read where not)
        val top = plays.filterKeys { it.startsWith("a:") }.entries.sortedByDescending { it.value }.take(10).map { it.key.removePrefix("a:") }
        // side by side, five seconds in all (one at a time they held the mix's start 30 s)
        val tops = kotlinx.coroutines.withTimeoutOrNull(5_000) {
            coroutineScope { top.map { a -> async { runCatching { YouTubeAccount.artist(a).firstOrNull { it != null }?.top?.take(5) }.getOrNull().orEmpty() } }.awaitAll() }
        }.orEmpty()
        tops.flatten().forEach(::add)
        return out.values.toList()
    }

    /** A tempo mix: each candidate found and read, two at a time; within 4% of the tempo, sure of its beat, in. */
    private fun tempo(c: Context, title: String, bpm: Int, candidates: suspend (Context) -> List<() -> Song?>) {
        job?.cancel()
        val app = c.applicationContext
        if (Crossfade.mode(app) != Crossfade.AUTOMIX) Crossfade.set(app, Crossfade.AUTOMIX)
        job = scope.launch {
            _state.value = State(title, bpm, 0, 0, 0, false, false)
            val all = candidates(app)
            val chosen = ArrayList<Pair<Song, Tempos.Read>>()
            var begun = false
            var read = 0
            val t0 = System.currentTimeMillis()
            // six read at once, each taken as it comes (2026-11-10: four at a time waited on the slowest of each four, and
            // a hip-hop mix took a minute and more to begin, only a few in ten of a genre's songs at any one tempo)
            readEach(all, 6) { f -> f()?.let { s -> Tempos.of(app, s)?.let { s to it } } }.takeWhile { chosen.size < 60 }.collect { p ->
                read++
                val (song, m) = p ?: (null to null)
                // its start at the tempo, and its end near it (a half-time outro would leave the mix)
                if (song != null && m != null && m.usable && Tempos.fits(m.bpm, bpm.toDouble()) && Tempos.fits(m.outBpm, bpm.toDouble(), .06) && chosen.none { it.first.id == song.id }) {
                    chosen += song to m
                    // begun at two: the first plays while the rest are found (the second is its handover)
                    if (!begun && chosen.size >= 2) {
                        val ordered = dj(chosen)
                        withContext(Dispatchers.Main) { Playback.play(ordered.map { it.first }, 0) }
                        tell(app, bpm, ordered.first().first.id)
                        begun = true
                        android.util.Log.d("ink-picks", "mix $title at $bpm: begun after ${System.currentTimeMillis() - t0} ms, $read of ${all.size} read")
                    } else if (begun) withContext(Dispatchers.Main) { Playback.add(song) }
                }
                _state.value = State(title, bpm, read, all.size, chosen.size, begun, false, chosen.map { it.first })
                if (read % 10 == 0) Tempos.save(app)
            }
            if (!begun && chosen.isNotEmpty()) {
                val ordered = dj(chosen)
                withContext(Dispatchers.Main) { Playback.play(ordered.map { it.first }, 0) }
                tell(app, bpm, ordered.first().first.id)
            }
            _state.value = State(title, bpm, read, all.size, chosen.size, chosen.isNotEmpty(), true, chosen.map { it.first })
            Tempos.save(app)
        }
    }

    /**
     * A list mixed as a DJ's arc (2026-11-08, Bronson: "do the dj arc and can we make it autoplay quicker", then "do all
     * three"): begun as soon as three are read (four read at a time), the slowest start of them first; each read after
     * put where it meets its neighbours best, its start against the end before it and its end against the start after
     * (a song comes in on one and goes out on the other), leaning toward a climb, slowest to fastest (half and double
     * counted as one, [fold]); always beyond the next song (its handover's planned). The late ones too: no cool-down
     * pile at the end. The ones that couldn't be read last. 120 at most. Shuffle off, so the order holds.
     */
    fun list(c: Context, title: String, songs: List<Song>) {
        job?.cancel()
        val app = c.applicationContext
        if (Crossfade.mode(app) != Crossfade.AUTOMIX) Crossfade.set(app, Crossfade.AUTOMIX)
        job = scope.launch {
            val all = songs.distinctBy { it.id }.take(120)
            _state.value = State(title, 0, 0, all.size, 0, false, false)
            // what's in the player, in its order, from the start: index for index (nothing else is put before them)
            val queued = ArrayList<Pair<Song, Tempos.Read>>()
            val unread = ArrayList<Song>()
            val waiting = ArrayList<Pair<Song, Tempos.Read>>()
            var read = 0
            // six read at once, each taken as it comes (four at a time waited on the slowest of each four)
            readEach(all, 6) { s -> s to Tempos.of(app, s) }.collect { p ->
                read++
                val (s, r) = p ?: return@collect
                if (r?.usable == true) waiting += s to r else unread += s
                if (queued.isEmpty() && waiting.size >= 3) {
                    // begun: the slowest of the first ones read, then the next nearest
                    queued += waiting.sortedBy { fold(it.second.bpm) }; waiting.clear()
                    withContext(Dispatchers.Main) {
                        if (Playback.now.value?.shuffle == true) Playback.shuffle()
                        Playback.play(queued.map { it.first }, 0)
                    }
                } else if (queued.isNotEmpty() && waiting.isNotEmpty()) {
                    withContext(Dispatchers.Main) { waiting.forEach { place(it, queued) } }
                    waiting.clear()
                }
                _state.value = State(title, 0, read, all.size, queued.size + waiting.size, queued.isNotEmpty(), false, (queued + waiting).map { it.first })
                if (read % 20 == 0) Tempos.save(app)
            }
            Tempos.save(app)
            withContext(Dispatchers.Main) {
                // fewer than three read: what there is, slowest first, then the rest
                if (queued.isEmpty()) {
                    val arc = waiting.sortedBy { fold(it.second.bpm) }.map { it.first } + unread
                    if (arc.isNotEmpty()) { if (Playback.now.value?.shuffle == true) Playback.shuffle(); Playback.play(arc, 0) }
                } else {
                    waiting.forEach { place(it, queued) }
                    unread.forEach { Playback.add(it) }
                }
            }
            android.util.Log.d("ink-picks", "mix $title: " + queued.joinToString("  ") { (s, r) -> "${"%.0f".format(r.bpm)}>${"%.0f".format(r.outBpm)} ${s.title.take(14)}" })
            val found = queued.size + waiting.size
            _state.value = State(title, 0, all.size, all.size, found, found + unread.size > 0, true, (queued + waiting).map { it.first })
        }
    }

    /**
     * Each of `items` through `read`, `at` at a time, given back as each is done (not in their order): no waiting on the
     * slowest of a group.
     */
    private fun <T, R> readEach(items: List<T>, at: Int, read: suspend (T) -> R) = kotlinx.coroutines.flow.channelFlow {
        val gate = kotlinx.coroutines.sync.Semaphore(at)
        items.forEach { i -> launch { gate.acquire(); try { send(runCatching { read(i) }.getOrNull()) } finally { gate.release() } } }
    }.buffer(at)

    /**
     * A song read, put where it meets its neighbours best (the cheapest place to put it in, as a route is mended): its
     * start against the end before it, its end against the start after, less how the two met before; a step back down
     * the climb costs too, and a key that clashes a little. Never before the song after next.
     */
    private fun place(s: Pair<Song, Tempos.Read>, queued: ArrayList<Pair<Song, Tempos.Read>>) {
        val now = (Playback.now.value?.index ?: 0).coerceAtLeast(0)
        val from = (now + 2).coerceIn(1, queued.size)
        val r = s.second
        fun meet(out: Tempos.Read, into: Tempos.Read) = Tempos.apart(out.outBpm, into.bpm) +
            (if (out.outKey != null && into.key != null) Key.distance(out.outKey, into.key).coerceAtMost(4) else 1) * .005
        val at = (from..queued.size).minByOrNull { p ->
            val before = queued[p - 1].second
            val after = queued.getOrNull(p)?.second
            val back = (fold(before.bpm) - fold(r.bpm)).coerceAtLeast(0.0) / 100   // down the climb
            meet(before, r) + (after?.let { meet(r, it) - meet(before, it) } ?: 0.0) + back
        } ?: queued.size
        queued.add(at, s)
        Playback.insert(s.first, at)
    }

    /** A tempo as a DJ counts it: halved or doubled into 85 to 170 (a 65 is a 130's half time). */
    private fun fold(bpm: Double): Double { var b = bpm; while (b < 85) b *= 2; while (b >= 170) b /= 2; return b }

    fun stop() { job?.cancel(); _state.value = null }

    /** A DJ's order: from the first, each next the nearest in tempo, then in key (the wheel's neighbours first). */
    private fun dj(found: List<Pair<Song, Tempos.Read>>): List<Pair<Song, Tempos.Read>> {
        if (found.isEmpty()) return found
        val left = found.toMutableList()
        val out = arrayListOf(left.removeAt(0))
        while (left.isNotEmpty()) {
            val last = out.last().second
            val i = left.indices.minByOrNull { j ->
                val r = left[j].second
                Tempos.apart(r.bpm, last.outBpm) * 10 + (if (last.outKey != null && r.key != null) Key.distance(last.outKey, r.key).coerceAtMost(4) else 2) * .05
            } ?: 0
            out += left.removeAt(i)
        }
        return out
    }

    /** The player told the mix's tempo and its first song (radio keeps to it while that mix is the queue). */
    private fun tell(c: Context, bpm: Int, first: String) {
        runCatching { Keep.write(File(c.filesDir, TEMPO_FILE), JSONObject().put("bpm", bpm).put("first", first).toString()) }
    }

    const val TEMPO_FILE = "mix-tempo.json"

    /** The tempo a mix is keeping to, if the queue (its first song `first`) is that mix's: read by radio. */
    fun keeping(c: Context, first: String?): Double? = runCatching {
        val o = JSONObject(File(c.filesDir, TEMPO_FILE).readText())
        o.getDouble("bpm").takeIf { first != null && o.getString("first") == first }
    }.getOrNull()
}
