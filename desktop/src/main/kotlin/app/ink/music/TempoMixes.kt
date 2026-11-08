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

    private fun automix() { if (Crossfade.mode.value != Crossfade.AUTOMIX) Crossfade.set(Crossfade.AUTOMIX) }

    /** A genre at a tempo: its editors' list songs, found on YouTube Music. */
    fun genre(genre: String, bpm: Int) = tempo(genre, bpm) { Picks.genre(genre).map { s -> { YouTube.find(s.pick.title, s.pick.artist) } } }

    /** Your music at a tempo: played twice or more and loved first, then your library and YouTube's liked songs. */
    fun yours(bpm: Int) = tempo("your music", bpm) { pool().map { s -> { s } } }

    fun yoursSize() = Library.songs.value.size + TubeAccount.liked.value.size

    private fun pool(): List<Song> {
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
            for (group in all.chunked(4)) {
                val got = coroutineScope { group.map { f -> async { f()?.let { s -> Tempos.of(s)?.let { s to it } } } }.awaitAll() }
                read += group.size
                for (p in got) {
                    val (song, m) = p ?: continue
                    if (!m.usable || !Tempos.fits(m.bpm, bpm.toDouble()) || !Tempos.fits(m.outBpm, bpm.toDouble(), .06) || chosen.any { it.first.id == song.id }) continue
                    chosen += song to m
                    if (!begun && chosen.size >= 3) {
                        val ordered = dj(chosen)
                        javax.swing.SwingUtilities.invokeAndWait { Player.play(ordered.map { it.first }) }
                        tell(bpm, ordered.first().first.id)
                        begun = true
                    } else if (begun) javax.swing.SwingUtilities.invokeAndWait { Player.add(song) }
                }
                _state.value = State(title, bpm, read, all.size, chosen.size, begun, false, chosen.map { it.first })
                if (read % 20 == 0) Tempos.save()
                if (chosen.size >= 60) break
            }
            if (!begun && chosen.isNotEmpty()) {
                val ordered = dj(chosen)
                javax.swing.SwingUtilities.invokeAndWait { Player.play(ordered.map { it.first }) }
                tell(bpm, ordered.first().first.id)
            }
            _state.value = State(title, bpm, read, all.size, chosen.size, chosen.isNotEmpty(), true, chosen.map { it.first })
            Tempos.save()
        }
    }

    /** A list as a DJ's arc: begun once three are read, the slowest first; each read after put where it meets its neighbours best. */
    fun list(title: String, songs: List<Song>) {
        job?.cancel()
        automix()
        job = scope.launch {
            val all = songs.distinctBy { it.id }.take(120)
            _state.value = State(title, 0, 0, all.size, 0, false, false)
            val queued = ArrayList<Pair<Song, Tempos.Read>>()
            val unread = ArrayList<Song>()
            val waiting = ArrayList<Pair<Song, Tempos.Read>>()
            var read = 0
            for (group in all.chunked(4)) {
                val got = coroutineScope { group.map { s -> async { s to Tempos.of(s) } }.awaitAll() }
                read += group.size
                for ((s, r) in got) if (r?.usable == true) waiting += s to r else unread += s
                if (queued.isEmpty() && waiting.size >= 3) {
                    queued += waiting.sortedBy { fold(it.second.bpm) }; waiting.clear()
                    javax.swing.SwingUtilities.invokeAndWait {
                        if (Player.shuffle.value) Player.shuffle()
                        Player.play(queued.map { it.first })
                    }
                } else if (queued.isNotEmpty() && waiting.isNotEmpty()) {
                    javax.swing.SwingUtilities.invokeAndWait { waiting.forEach { place(it, queued) } }
                    waiting.clear()
                }
                _state.value = State(title, 0, read, all.size, queued.size + waiting.size, queued.isNotEmpty(), false, (queued + waiting).map { it.first })
                if (read % 20 == 0) Tempos.save()
            }
            Tempos.save()
            javax.swing.SwingUtilities.invokeAndWait {
                if (queued.isEmpty()) {
                    val arc = waiting.sortedBy { fold(it.second.bpm) }.map { it.first } + unread
                    if (arc.isNotEmpty()) { if (Player.shuffle.value) Player.shuffle(); Player.play(arc) }
                } else {
                    waiting.forEach { place(it, queued) }
                    unread.forEach { Player.add(it) }
                }
            }
            val found = queued.size + waiting.size
            _state.value = State(title, 0, all.size, all.size, found, found + unread.size > 0, true, (queued + waiting).map { it.first })
        }
    }

    private fun place(s: Pair<Song, Tempos.Read>, queued: ArrayList<Pair<Song, Tempos.Read>>) {
        val now = Player.index().coerceAtLeast(0)
        val from = (now + 2).coerceIn(1, queued.size)
        val r = s.second
        fun meet(out: Tempos.Read, into: Tempos.Read) = Tempos.apart(out.outBpm, into.bpm) +
            (if (out.outKey != null && into.key != null) Key.distance(out.outKey, into.key).coerceAtMost(4) else 1) * .005
        val at = (from..queued.size).minByOrNull { p ->
            val before = queued[p - 1].second
            val after = queued.getOrNull(p)?.second
            meet(before, r) + (after?.let { meet(r, it) - meet(before, it) } ?: 0.0) + (fold(before.bpm) - fold(r.bpm)).coerceAtLeast(0.0) / 100
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

    private fun fold(bpm: Double): Double { var b = bpm; while (b < 85) b *= 2; while (b >= 170) b /= 2; return b }

    private fun tell(bpm: Int, first: String) { runCatching { Keep.write(File(Home.dir, "mix-tempo.json"), JSONObject().put("bpm", bpm).put("first", first).toString()) } }

    /** The tempo a mix is keeping to, if the queue (its first song `first`) is that mix's: read by radio. */
    fun keeping(first: String?): Double? = runCatching {
        val o = JSONObject(File(Home.dir, "mix-tempo.json").readText())
        o.getDouble("bpm").takeIf { first != null && o.getString("first") == first }
    }.getOrNull()
}
