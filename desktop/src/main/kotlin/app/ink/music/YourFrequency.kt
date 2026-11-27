package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Your frequency on the computer (2026-11-27, the phone's since 2026-11-25, #16): what [FrequencyMix] chooses, found and
 * played. One process, so the sitting is kept here as it goes (each song played through, left in its first seconds, or
 * loved) and only the dials and each lane's record with you are kept on disk: frequency.json, frequency-learned.json.
 * The player asks [more] at the queue's end in place of radio, a few at a time so it turns quickly, and a song left at
 * once drops what's waiting after the next one and finds again ([Player]).
 */
object YourFrequency {
    /** How many come at a time: few, so what you do turns it within a song or two. */
    private const val BATCH = 4
    private val file = File(Home.dir, "frequency.json")
    private val learnedFile = File(Home.dir, "frequency-learned.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val dials = MutableStateFlow(read().let { FrequencyMix.Dials(it.optDouble("tuned", .5).takeIf { t -> !t.isNaN() } ?: .5, it.optString("mood")) })
    /** The frequency's first song this sitting (the queue's first while it plays), or null. */
    val first = MutableStateFlow<String?>(null)
    /** Starting: your songs being gathered. */
    val starting = MutableStateFlow(false)

    // this sitting: when it began, each song's lane and what brought it, how each went, what's been counted, the last given
    private var started = 0L
    private val given = LinkedHashMap<String, Pair<String, String>>()
    private val went = LinkedHashMap<String, FrequencyMix.Went>()
    private val counted = HashSet<String>()
    private val lately = ArrayList<String>()
    private var moodOf = ""
    private var moodSongs: List<Pair<String, String>> = emptyList()

    /** Whether what's playing is your frequency. */
    fun playing(): Boolean = first.value != null && Player.queue.value.firstOrNull()?.id == first.value

    /** A dial turned: kept, and if it's playing, the songs waiting dropped and found again. */
    fun turn(d: FrequencyMix.Dials) {
        dials.value = d
        runCatching { Keep.write(file, JSONObject().put("tuned", d.tuned).put("mood", d.mood).toString()) }
        if (playing()) Player.frequencyAgain()
    }

    /** Begun: your songs gathered, the first chosen and played; the player finds the rest. */
    fun start() {
        if (starting.value) return
        starting.value = true
        scope.launch {
            try {
                val pool = TempoMixes.pool().filter { !it.station && !it.episode && !Blocked.hides(it) }
                val ranked = ranked(pool)
                // the opening: one of yours you're near, a little chance among the best few
                val opening = ranked.take(6).shuffled().firstOrNull() ?: pool.shuffled().firstOrNull() ?: return@launch
                synchronized(this@YourFrequency) {
                    started = System.currentTimeMillis()
                    given.clear(); went.clear(); counted.clear(); lately.clear()
                    given[Radio.key(opening.title, opening.artist)] = FrequencyMix.YOURS to ""
                }
                first.value = opening.id
                Log.d("frequency: begun (${FrequencyMix.said(dials.value.tuned)}) on ${opening.title} - ${opening.artist}, ${pool.size} of yours")
                javax.swing.SwingUtilities.invokeLater { Player.play(listOf(opening), 0) }
            } finally { starting.value = false }
        }
    }

    /** Begun anew in a mood (the moods page): the mood chosen, kept, and played. */
    fun startIn(mood: String) { turn(dials.value.copy(mood = mood)); start() }

    /** Stopped (the player's stop): the sitting over, so the card shows PLAY again and the queue's end gets radio. */
    fun stopped() { first.value = null }

    /** A song left behind while your frequency plays (the player's): played through (90 s or more), or left at once. */
    fun heard(s: Song, ms: Long) {
        if (!playing() || s.station || s.episode) return
        val k = Radio.key(s.title, s.artist)
        val how = when { ms >= 90_000 -> FrequencyMix.How.THROUGH; ms in 1 until 30_000 -> FrequencyMix.How.LEFT; else -> return }
        synchronized(this) { went[k] = FrequencyMix.Went(s.artist, given[k]?.first.orEmpty(), given[k]?.second.orEmpty(), how, System.currentTimeMillis()) }
    }

    /** A love while your frequency plays: it steers what comes. */
    fun loved(s: Song, on: Boolean) {
        if (!on || !playing()) return
        val k = Radio.key(s.title, s.artist)
        synchronized(this) { went[k] = FrequencyMix.Went(s.artist, given[k]?.first.orEmpty(), given[k]?.second.orEmpty(), FrequencyMix.How.LOVED, System.currentTimeMillis()) }
    }

    /** Your songs, nearest you now first. */
    private fun ranked(pool: List<Song>): List<Song> {
        val taste = Fresh.taste.value
        val byKey = pool.associateBy { Radio.key(it.title, it.artist) }
        val loved = Loved.ids.value
        return FrequencyMix.yours(History.read().map { Triple(Radio.key(it.title, it.artist), it.artist, it.at) },
            pool.filter { it.id in loved }.map { Radio.key(it.title, it.artist) }.toSet(), taste::of).mapNotNull { (k, _) -> byKey[k] }
    }

    /**
     * The next few (blocking): the sitting so far, each lane's record brought up to date, seeds chosen (who you took to
     * this sitting, else the song playing; and one of yours you're near), what they bring found (similar, and Last.fm's
     * similar in your own library), with your songs and the mood's, and [FrequencyMix.pick] choosing.
     */
    fun more(queue: List<Song>, current: Int, queued: Set<String>): List<Song> {
        if (first.value == null) return emptyList()
        val d = dials.value
        val taste = Fresh.taste.value
        fun tasteOf(artist: String) = if (taste.refused(artist)) -1.0 else taste.of(artist)
        val sitting: FrequencyMix.Sitting
        var learned = learned()
        synchronized(this) {
            sitting = FrequencyMix.Sitting(went.values.sortedBy { it.at })
            went.forEach { (k, w) ->
                if (w.lane.isEmpty() || w.how == FrequencyMix.How.LOVED || k in counted) return@forEach
                learned = learned.with(w.lane, w.how == FrequencyMix.How.THROUGH); counted += k
            }
        }
        keepLearned(learned)

        val heard = History.read()
        val pool = TempoMixes.pool().filter { !it.station && !it.episode && !Blocked.hides(it) }
        val knownBy = pool.associateBy { Radio.key(it.title, it.artist) }
        val yours = FrequencyMix.yours(heard.map { Triple(Radio.key(it.title, it.artist), it.artist, it.at) }, emptySet(), ::tasteOf)
            .filter { it.first in knownBy }

        // the seeds: who you took to this sitting (their latest song), else the song playing; and one of yours you're near
        val last = queue.getOrNull(current) ?: queue.lastOrNull()
        val took = sitting.seeds(2).mapNotNull { a -> queue.lastOrNull { FrequencyMix.who(it.artist) == FrequencyMix.who(a) } }
        val anchor = yours.take(20).shuffled().firstOrNull()?.let { knownBy[it.first] }
        val seeds = (took.ifEmpty { listOfNotNull(last) } + listOfNotNull(anchor)).distinctBy { it.id }.take(3)
            .map { Radio.Seed(it.id, it.title, it.artist) }
        val tookTo = synchronized(this) { went.values.filter { it.how != FrequencyMix.How.LEFT }.map { FrequencyMix.who(it.artist) }.toSet() }

        // what each seed brings, side by side; the mood's songs, read once a sitting (Last.fm's tags)
        val ask = java.util.concurrent.Executors.newFixedThreadPool(seeds.size.coerceAtLeast(1) + 1)
        val asked = seeds.map { s ->
            ask.submit<List<Song>> {
                val theirs = runCatching { if (Local.on) (if (s.id.startsWith("y")) emptyList() else Server.similar(s.id, s.title, s.artist)) else Radio.related(s) }.getOrDefault(emptyList())
                val owned = runCatching { inLibrary(s) }.getOrDefault(emptyList())
                (0 until maxOf(theirs.size, owned.size)).flatMap { i -> listOfNotNull(owned.getOrNull(i), theirs.getOrNull(i)) }.distinctBy { it.id }
            }
        }
        val mood = InkMoods.ALL.firstOrNull { it.key == d.mood }?.takeIf { !Local.on }
        val moodAsk = mood?.let { m ->
            ask.submit<List<Pair<String, String>>> {
                if (moodOf == m.key) moodSongs
                else runCatching { InkMoods.songs(m, LastFm.key) }.getOrDefault(emptyList()).also { moodOf = m.key; moodSongs = it }
            }
        }
        val lists = asked.map { runCatching { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
        val moodFound = moodAsk?.let { runCatching { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()) }.orEmpty()
        ask.shutdownNow()

        // all of it as your frequency weighs it
        val heardKeys = heard.map { Radio.key(it.title, it.artist) }.toHashSet()
        val recentKeys = heard.filter { it.at > System.currentTimeMillis() - 2 * 86_400_000L }.map { Radio.key(it.title, it.artist) }.toHashSet()
        val songs = HashMap<String, Song>()
        val found = ArrayList<FrequencyMix.Found>()
        fun fine(k: String, title: String) = k !in queued && k !in recentKeys && !Radio.odd(title)
        lists.forEachIndexed { i, list ->
            val through = seeds.getOrNull(i)?.artist.orEmpty()
            list.forEachIndexed { r, s ->
                val k = Radio.key(s.title, s.artist)
                if (s.id in queued || !fine(k, s.title) || Blocked.hides(s)) return@forEachIndexed
                // an artist taken to this sitting isn't new any more, whatever the taste says yet
                val lane = when { k in heardKeys -> FrequencyMix.YOURS
                    taste.of(s.artist) >= ForYou.NEAR || FrequencyMix.who(s.artist) in tookTo -> FrequencyMix.NEAR; else -> FrequencyMix.FAR }
                songs.putIfAbsent(k, s)
                found += FrequencyMix.Found(k, s.artist, lane, 1.0 - r.toDouble() / (list.size + 1), through)
            }
        }
        yours.take(60).forEachIndexed { r, (k, score) ->
            val s = knownBy[k] ?: return@forEachIndexed
            if (s.id in queued || !fine(k, s.title)) return@forEachIndexed
            songs.putIfAbsent(k, s)
            found += FrequencyMix.Found(k, s.artist, FrequencyMix.YOURS, score.coerceIn(0.0, 1.0) * (1.0 - r / 120.0))
        }
        val moodRanked = moodFound.sortedByDescending { (_, a) -> taste.of(a) }.take(40)
        moodRanked.forEachIndexed { r, (t, a) ->
            val k = Radio.key(t, a)
            if (!fine(k, t) || Blocked.hides(t, a)) return@forEachIndexed
            found += FrequencyMix.Found(k, a, FrequencyMix.MOOD, 1.0 - r.toDouble() / (moodRanked.size + 1))
        }

        // chosen, kept apart from what's played lately, what's waiting and the last dozen it gave; then found to play
        val recent = synchronized(this) { went.values.sortedBy { it.at }.map { it.artist } } + queue.take(current + 1).takeLast(8).map { it.artist } +
            queue.drop(current + 1).map { it.artist } + synchronized(this) { lately.toList() }
        val laneCounts = synchronized(this) { given.values.groupingBy { it.first }.eachCount() }
        val picked = FrequencyMix.pick(found, d, ::tasteOf, sitting, learned, recent, BATCH + 2, taste.appetite, given0 = laneCounts)
        val out = ArrayList<Song>()
        for (f in picked) {
            if (out.size >= BATCH) break
            val title = moodFound.firstOrNull { Radio.key(it.first, it.second) == f.key }?.first
            val s = songs[f.key] ?: title?.let { runCatching { YouTube.find(it, f.artist) }.getOrNull() } ?: continue
            out += s
            synchronized(this) {
                given[f.key] = f.lane to f.through
                lately += s.artist
                while (lately.size > 12) lately.removeAt(0)
                while (given.size > 300) given.remove(given.keys.first())
            }
        }
        Log.d("frequency: ${sitting.size} this sitting; seeds ${seeds.joinToString { it.artist }}; " +
            out.joinToString { s -> "${s.title} - ${s.artist} (${given[Radio.key(s.title, s.artist)]?.first})" })
        return out
    }

    /**
     * What Last.fm hears as near a seed, found in your own library: its similar songs you have, then your songs by its
     * similar artists, two of each. Playable with no YouTube at all; none without a Last.fm key or a library.
     */
    private fun inLibrary(s: Radio.Seed): List<Song> {
        val lib = Library.songs.value.filter { !it.station && !it.episode }
        if (lib.isEmpty() || LastFm.key.isBlank()) return emptyList()
        val artist = s.artist.substringBefore(", ").trim()
        val byKey = lib.associateBy { Radio.key(it.title, it.artist) }
        val tracks = Similar.lastFmTracks(s.title, artist, LastFm.key).mapNotNull { byKey[Radio.key(it.title, it.artist)] }
        val byArtist = lib.groupBy { FrequencyMix.who(it.artist) }
        val rnd = java.util.Random(s.id.hashCode().toLong())
        val theirs = Similar.lastFmArtists(artist, LastFm.key, 20).flatMap { (a, _) -> byArtist[FrequencyMix.who(a)].orEmpty().shuffled(rnd).take(2) }
        return (tracks + theirs).filter { it.id != s.id }.distinctBy { it.id }
    }

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun learned(): FrequencyMix.Learned = runCatching {
        val o = JSONObject(learnedFile.readText())
        fun m(k: String) = o.optJSONObject(k)?.let { j -> j.keys().asSequence().associateWith { j.optInt(it) } }.orEmpty()
        FrequencyMix.Learned(m("good"), m("bad"))
    }.getOrDefault(FrequencyMix.Learned())

    private fun keepLearned(l: FrequencyMix.Learned) {
        runCatching { Keep.write(learnedFile, JSONObject().put("good", JSONObject(l.good)).put("bad", JSONObject(l.bad)).toString()) }
    }
}
