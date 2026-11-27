package app.ink.music

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
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Songs mixed by their tempo (the phone's Mixes, 2026-11-08): a genre at a tempo (its editors' lists), your music at a
 * tempo (what you play, love and keep), a list as a DJ's arc. Each song's start and end read (Tempos); a tempo mix
 * takes a song whose start is within 4% and its end within 6%, so any two can be beats matched; begun once three are
 * found, more following. Automix turned on. A tempo mix tells radio its tempo (mix-tempo.json), radio keeping to it.
 */
object TempoMixes {
    val TEMPOS = listOf(100, 110, 120, 125, 130, 140)

    data class State(val title: String, val bpm: Int, val read: Int, val of: Int, val found: Int, val playing: Boolean, val done: Boolean,
        val songs: List<Song> = emptyList())

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Automix while the mix plays: lent, your own transition given back when something else is played (Crossfade.lend). */
    private fun automix() = Crossfade.lend()

    /** Played as the mix: the player told it's the mix's (so the transition lent isn't given back by it). */
    private fun playMix(songs: List<Song>) { Crossfade.inMix = true; try { Player.play(songs) } finally { Crossfade.inMix = false } }

    /** A genre at a tempo: its editors' list songs, found on YouTube Music. */
    fun genre(genre: String, bpm: Int) = tempo(genre, bpm) { Picks.genre(genre).map { s -> { YouTube.find(s.pick.title, s.pick.artist) } } }

    /** Your music at a tempo: played twice or more and loved first, then your library and YouTube's liked songs. */
    fun yours(bpm: Int) = tempo("your music", bpm) { pool().map { s -> { s } } }

    fun yoursSize() = Library.songs.value.size + TubeAccount.liked.value.size

    internal fun pool(): List<Song> {
        val out = LinkedHashMap<String, Song>()
        val names = HashSet<String>()
        fun add(s: Song?) { if (s == null || s.station || s.episode || s.id in out) return; if (names.add(Radio.key(s.title, s.artist))) out[s.id] = s }
        val counts = History.read().groupingBy { it.id }.eachCount()
        val loved = Loved.ids.value
        counts.entries.filter { it.value >= 2 || it.key in loved }.sortedByDescending { it.value + if (it.key in loved) 3 else 0 }.forEach { add(Library.song(it.key)) }
        loved.forEach { add(Library.song(it)) }
        val plays = Listened.now.value.plays
        val rest = (Library.songs.value + TubeAccount.liked.value).filter { (it.duration == 0L || it.duration > 60_000) && it.id !in out }.shuffled()
        val byArtist = rest.groupBy { it.artist.substringBefore(",").trim().lowercase() }
        val lists = byArtist.keys.sortedByDescending { plays["a:$it"] ?: 0 }.map { byArtist.getValue(it).toMutableList() }
        while (lists.any { it.isNotEmpty() } && out.size < 600) lists.forEach { l -> if (l.isNotEmpty()) add(l.removeAt(0)) }
        return out.values.toList()
    }

    private fun tempo(title: String, bpm: Int, candidates: suspend () -> List<() -> Song?>) {
        job?.cancel()
        automix()
        job = scope.launch {
            _state.value = State(title, bpm, 0, 0, 0, false, false)
            val all = candidates()
            val chosen = ArrayList<Pair<Song, Tempos.Read>>()
            var begun = false
            var read = 0
            // six read at once, each taken as it comes (2026-11-10, as the phone's: four at a time waited on the slowest of
            // each four, and a hip-hop mix took a minute and more to begin); begun at two
            readEach(all, 6) { f -> f()?.let { s -> Tempos.of(s)?.let { s to it } } }.takeWhile { chosen.size < 60 }.collect { p ->
                read++
                val (song, m) = p ?: (null to null)
                if (song != null && m != null && m.usable && Tempos.fits(m.bpm, bpm.toDouble()) && Tempos.fits(m.outBpm, bpm.toDouble(), .06) && chosen.none { it.first.id == song.id }) {
                    chosen += song to m
                    if (!begun && chosen.size >= 2) {
                        val ordered = dj(chosen)
                        javax.swing.SwingUtilities.invokeAndWait { playMix(ordered.map { it.first }) }
                        tell(bpm, ordered.first().first.id)
                        begun = true
                    } else if (begun) javax.swing.SwingUtilities.invokeAndWait { Player.add(song) }
                }
                _state.value = State(title, bpm, read, all.size, chosen.size, begun, false, chosen.map { it.first })
                if (read % 20 == 0) Tempos.save()
            }
            if (!begun && chosen.isNotEmpty()) {
                val ordered = dj(chosen)
                javax.swing.SwingUtilities.invokeAndWait { playMix(ordered.map { it.first }) }
                tell(bpm, ordered.first().first.id)
            }
            _state.value = State(title, bpm, read, all.size, chosen.size, chosen.isNotEmpty(), true, chosen.map { it.first })
            Tempos.save()
        }
    }

    /** Each of `items` through `read`, `at` at a time, given back as each is done (not in their order). */
    private fun <T, R> readEach(items: List<T>, at: Int, read: suspend (T) -> R) = kotlinx.coroutines.flow.channelFlow {
        val gate = kotlinx.coroutines.sync.Semaphore(at)
        items.forEach { i -> launch { gate.acquire(); try { send(runCatching { read(i) }.getOrNull()) } finally { gate.release() } } }
    }.buffer(at)

    /** A list as a DJ's arc: begun once three are read, the slowest first; each read after put where it meets its neighbours best. */
    /**
     * A list mix's shape (2026-11-10, as the phone's): building up (slowest to fastest, as it always was), steady (round
     * the tempo most of them share) or winding down (fastest to slowest). The last one chosen kept.
     */
    enum class Shape(val word: String) { WAVES("waves"), BUILD("build up"), STEADY("steady"), WIND("wind down") }
    private val shapeFile = File(Home.dir, "mix-shape")
    // waves for whoever hasn't chosen (2026-11-15: a long build up was the same rhythm song after song)
    val shape = kotlinx.coroutines.flow.MutableStateFlow(runCatching { Shape.valueOf(shapeFile.readText().trim()) }.getOrDefault(Shape.WAVES))
    fun shape(s: Shape) { shape.value = s; runCatching { shapeFile.writeText(s.name) } }

    /** The first ones read, in the shape's order: slowest first, fastest first, or nearest the middle first. */
    private fun opening(found: List<Pair<Song, Tempos.Read>>, s: Shape): List<Pair<Song, Tempos.Read>> = when (s) {
        Shape.WAVES -> waves(found)
        Shape.BUILD -> found.sortedBy { fold(it.second.bpm) }
        Shape.WIND -> found.sortedByDescending { fold(it.second.bpm) }
        Shape.STEADY -> { val mid = found.map { fold(it.second.bpm) }.sorted().let { it[it.size / 2] }; found.sortedBy { kotlin.math.abs(fold(it.second.bpm) - mid) } }
    }

    fun list(title: String, songs: List<Song>, shp: Shape = shape.value) {
        job?.cancel()
        automix()
        job = scope.launch {
            // drawn afresh from the whole list each time (2026-11-17, a tester: a long list's first 120 every time, the same
            // mix): shuffled, every song already read taken (no wait for those, 400 at most), and 120 still to read
            var fresh = 0
            val all = songs.distinctBy { it.id }.shuffled().filter { s -> Tempos.kept(s.id) != null || fresh++ < 120 }.take(400)
            _state.value = State(title, 0, 0, all.size, 0, false, false)
            val queued = ArrayList<Pair<Song, Tempos.Read>>()
            val unread = ArrayList<Song>()
            val waiting = ArrayList<Pair<Song, Tempos.Read>>()
            var read = 0
            readEach(all, 6) { s -> s to Tempos.of(s) }.collect { p ->
                read++
                val (s, r) = p ?: return@collect
                if (r?.usable == true) waiting += s to r else unread += s
                if (queued.isEmpty() && waiting.size >= 3) {
                    queued += opening(waiting, shp); waiting.clear()
                    javax.swing.SwingUtilities.invokeAndWait {
                        if (Player.shuffle.value) Player.shuffle()
                        playMix(queued.map { it.first })
                    }
                } else if (queued.isNotEmpty() && waiting.isNotEmpty()) {
                    javax.swing.SwingUtilities.invokeAndWait { waiting.forEach { place(it, queued, shp) } }
                    waiting.clear()
                }
                _state.value = State(title, 0, read, all.size, queued.size + waiting.size, queued.isNotEmpty(), false, (queued + waiting).map { it.first })
                if (read % 20 == 0) Tempos.save()
            }
            Tempos.save()
            javax.swing.SwingUtilities.invokeAndWait {
                if (queued.isEmpty()) {
                    val arc = opening(waiting, shp).map { it.first } + unread
                    if (arc.isNotEmpty()) { if (Player.shuffle.value) Player.shuffle(); playMix(arc) }
                } else {
                    waiting.forEach { place(it, queued, shp) }
                    // waves: laid out once every song is read, the songs not yet played put in their order (the one playing
                    // and those before it as they are)
                    if (shp == Shape.WAVES) {
                        val now = Player.index().coerceAtLeast(0)
                        val playing = Player.queue.value.getOrNull(now)?.id
                        val at = queued.indexOfFirst { it.first.id == playing }.let { if (it < 0) -1 else it }
                        val rest = queued.drop(at + 1)
                        if (rest.size > 2) Player.upNext(waves(rest, after = queued.getOrNull(at)).map { it.first } + unread)
                        else unread.forEach { Player.add(it) }
                    } else unread.forEach { Player.add(it) }
                }
            }
            val found = queued.size + waiting.size
            _state.value = State(title, 0, all.size, all.size, found, found + unread.size > 0, true, (queued + waiting).map { it.first })
        }
    }

    private fun place(s: Pair<Song, Tempos.Read>, queued: ArrayList<Pair<Song, Tempos.Read>>, shape: Shape = Shape.BUILD) {
        // waves: at the end for now, each in its place once all are read (list)
        if (shape == Shape.WAVES) { queued.add(s); Player.add(s.first); return }
        val now = Player.index().coerceAtLeast(0)
        val from = (now + 2).coerceIn(1, queued.size)
        val r = s.second
        fun meet(out: Tempos.Read, into: Tempos.Read) = Tempos.apart(out.outBpm, into.bpm) +
            (if (out.outKey != null && into.key != null) Key.distance(out.outKey, into.key).coerceAtMost(4) else 1) * .005
        val at = (from..queued.size).minByOrNull { p ->
            val before = queued[p - 1].second
            val after = queued.getOrNull(p)?.second
            // against the shape: down a build, up a wind down; steady minds only how two meet
            val back = when (shape) {
                Shape.BUILD -> (fold(before.bpm) - fold(r.bpm)).coerceAtLeast(0.0) / 100
                Shape.WIND -> (fold(r.bpm) - fold(before.bpm)).coerceAtLeast(0.0) / 100
                Shape.STEADY -> 0.0
            }
            meet(before, r) + (after?.let { meet(r, it) - meet(before, it) } ?: 0.0) + back
        } ?: queued.size
        queued.add(at, s)
        Player.insert(s.first, at)
    }

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

    /**
     * Waves (2026-11-15, after a tester's "Heartbeat Method": nearest tempo after nearest tempo, or one long build, was "groups
     * of songs with overly similar rhythms ... sequences that feel too predictable"): the list in waves of about thirty,
     * each a run of its fast songs, a drop to its slow ones and a stretch round the middle, in one of three orders (fast,
     * slow, middle; slow, middle, fast; middle, fast, slow), the waves themselves in any order. Fast and slow are this
     * list's own (its quickest 43%, its slowest 20%), dealt out across the waves at random. Within a wave each next song in
     * a key that sits with the last where one can be (the same, a step round the wheel, its relative), the same key thrice
     * running put off, the same artist twice running too; the fast run from its quickest down, the drop from its
     * slowest up, the middle nearest the middle first. [after]: what plays before it, to follow on from.
     */
    private fun waves(found: List<Pair<Song, Tempos.Read>>, after: Pair<Song, Tempos.Read>? = null, random: java.util.Random = java.util.Random()): List<Pair<Song, Tempos.Read>> {
        if (found.size < 4) return found.sortedBy { fold(it.second.bpm) }
        val sorted = found.sortedBy { fold(it.second.bpm) }
        val n = sorted.size
        val slowN = (n * .20).toInt().coerceAtLeast(1); val fastN = (n * .43).toInt().coerceAtLeast(1)
        val slow = sorted.take(slowN).shuffled(random); val fast = sorted.takeLast(fastN).shuffled(random)
        val mid = sorted.subList(slowN, (n - fastN).coerceAtLeast(slowN)).shuffled(random)
        val count = (n / 30).coerceAtLeast(1)
        fun deal(pool: List<Pair<Song, Tempos.Read>>) = List(count) { w -> pool.filterIndexed { i, _ -> i % count == w }.toMutableList() }
        val fasts = deal(fast); val slows = deal(slow); val mids = deal(mid)
        val middle = fold(sorted[n / 2].second.bpm)
        val out = ArrayList<Pair<Song, Tempos.Read>>()
        val waves = (0 until count).map { w ->
            val blocks = listOf(Phase.FAST to fasts[w], Phase.SLOW to slows[w], Phase.MID to mids[w])
            val order = when (random.nextInt(3)) { 0 -> listOf(0, 1, 2); 1 -> listOf(1, 2, 0); else -> listOf(2, 0, 1) }
            order.map { blocks[it] }
        }.shuffled(random)
        var last = after
        var sameKey = 0
        for (wave in waves) for ((phase, pool) in wave) while (pool.isNotEmpty()) {
            val lastKey = last?.second?.outKey
            val fits = pool.filter { lastKey == null || it.second.key == null || Key.distance(lastKey, it.second.key!!) <= 1 }.ifEmpty { pool }
            val lastWho = last?.first?.artist?.lowercase()?.substringBefore(",")?.trim()
            val pick = fits.minByOrNull { c ->
                val b = fold(c.second.bpm)
                var score = when (phase) { Phase.FAST -> -b; Phase.SLOW -> b; Phase.MID -> kotlin.math.abs(b - middle) * 2 }
                if (sameKey >= 1 && lastKey != null && c.second.key != null && Key.distance(lastKey, c.second.key!!) == 0) score += 40
                if (lastWho != null && c.first.artist.lowercase().substringBefore(",").trim() == lastWho) score += 60
                score + random.nextDouble() * 3
            }!!
            pool.remove(pick)
            sameKey = if (lastKey != null && pick.second.key != null && Key.distance(lastKey, pick.second.key!!) == 0) sameKey + 1 else 0
            out += pick; last = pick
        }
        return out
    }
    private enum class Phase { FAST, SLOW, MID }

    private fun fold(bpm: Double): Double { var b = bpm; while (b < 85) b *= 2; while (b >= 170) b /= 2; return b }

    private fun tell(bpm: Int, first: String) { runCatching { Keep.write(File(Home.dir, "mix-tempo.json"), JSONObject().put("bpm", bpm).put("first", first).toString()) } }

    /** The tempo a mix is keeping to, if the queue (its first song `first`) is that mix's: read by radio. */
    fun keeping(first: String?): Double? = runCatching {
        val o = JSONObject(File(Home.dir, "mix-tempo.json").readText())
        o.getDouble("bpm").takeIf { first != null && o.getString("first") == first }
    }.getOrNull()
}
