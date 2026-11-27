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
    /** Each artist's and source's record across sittings, and the sittings' times (FrequencyMemory). */
    private val memoryFile = File(Home.dir, "frequency-memory.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val dials = MutableStateFlow(read().let { FrequencyMix.Dials(it.optDouble("tuned", .5).takeIf { t -> !t.isNaN() } ?: .5, it.optString("mood")) })
    /** The frequency's first song this sitting (the queue's first while it plays), or null. */
    val first = MutableStateFlow<String?>(null)
    /** Starting: your songs being gathered. */
    val starting = MutableStateFlow(false)

    // this sitting: when it began, each song's lane and what brought it, how each went, what's been counted, the last given
    private var started = 0L
    private val given = LinkedHashMap<String, Triple<String, String, String>>()
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
                    given[Radio.key(opening.title, opening.artist)] = Triple(FrequencyMix.YOURS, "", "yours")
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
        // how it went by how much of it played (FrequencyMix.how); one played through a second time is kept, as a love a little less
        val (how, strength) = FrequencyMix.how(ms, s.duration) ?: return
        synchronized(this) {
            val again = how == FrequencyMix.How.THROUGH && went[k]?.how.let { it == FrequencyMix.How.THROUGH || it == FrequencyMix.How.KEPT }
            went[k] = if (again) FrequencyMix.Went(s.artist, given[k]?.first.orEmpty(), given[k]?.second.orEmpty(), FrequencyMix.How.KEPT, System.currentTimeMillis())
                else FrequencyMix.Went(s.artist, given[k]?.first.orEmpty(), given[k]?.second.orEmpty(), how, System.currentTimeMillis(), strength)
        }
    }

    /** A song put in one of your lists while your frequency plays: kept, it steers what comes as a love does. */
    fun listed(s: Song) {
        if (!playing()) return
        val k = Radio.key(s.title, s.artist)
        synchronized(this) { went[k] = FrequencyMix.Went(s.artist, given[k]?.first.orEmpty(), given[k]?.second.orEmpty(), FrequencyMix.How.KEPT, System.currentTimeMillis()) }
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
        val memory = memory()
        val now0 = System.currentTimeMillis()
        memory.sitting(started, now0)
        synchronized(this) {
            sitting = FrequencyMix.Sitting(went.values.sortedBy { it.at })
            went.forEach { (k, w) ->
                // each artist's and source's record across sittings (FrequencyMemory): a love or a keep counted apart, once
                val apart = w.how == FrequencyMix.How.LOVED || w.how == FrequencyMix.How.KEPT
                val ck = if (apart) "$k#${w.how.name.lowercase()}" else k
                if (ck !in counted) { memory.went(w.artist, given[k]?.third.orEmpty(), w.how, w.at, w.strength); if (apart) counted += ck }
                // a lane's record: played to near its end, or left in its first half minute
                val says = (w.how == FrequencyMix.How.THROUGH && w.strength >= .9) || (w.how == FrequencyMix.How.LEFT && w.strength >= .7)
                if (w.lane.isEmpty() || !says || k in counted) return@forEach
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
        // each source on its own (the phone's, 2026-11-27): YouTube's related (or your server's similar), Last.fm's similar in
        // your library and beyond it, and the seeds' similar artists by ListenBrainz and Last.fm
        val asked = seeds.map { s ->
            ask.submit<Ears> {
                val theirs = runCatching { if (Local.on) (if (s.id.startsWith("y")) emptyList() else Server.similar(s.id, s.title, s.artist)) else Radio.related(s) }.getOrDefault(emptyList())
                val lfm = runCatching { lastFm(s, beyond = !Local.on) }.getOrDefault(Ears())
                val lb = if (Local.on) emptyList() else runCatching { Similar.listenBrainzArtists(s.artist.substringBefore(", ").trim(), 20).map { it.first } }.getOrDefault(emptyList())
                Ears(related = theirs, owned = lfm.owned, beyond = lfm.beyond, named = lfm.named + lb, source = if (Local.on) "server" else "related")
            }
        }
        val mood = InkMoods.ALL.firstOrNull { it.key == d.mood }?.takeIf { !Local.on }
        val moodAsk = mood?.let { m ->
            ask.submit<List<Pair<String, String>>> {
                if (moodOf == m.key) moodSongs
                else runCatching { InkMoods.songs(m, LastFm.key) }.getOrDefault(emptyList()).also { moodOf = m.key; moodSongs = it }
            }
        }
        val lists = asked.map { runCatching { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(Ears()) }
        val moodFound = moodAsk?.let { runCatching { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()) }.orEmpty()
        ask.shutdownNow()

        // all of it as your frequency weighs it
        val heardKeys = heard.map { Radio.key(it.title, it.artist) }.toHashSet()
        val recentKeys = heard.filter { it.at > System.currentTimeMillis() - 2 * 86_400_000L }.map { Radio.key(it.title, it.artist) }.toHashSet()
        val songs = HashMap<String, Song>()
        val found = ArrayList<FrequencyMix.Found>()
        fun fine(k: String, title: String) = k !in queued && k !in recentKeys && !Radio.odd(title)
        // an artist taken to this sitting isn't new any more, whatever the taste says yet
        fun laneFor(k: String, artist: String) = when { k in heardKeys -> FrequencyMix.YOURS
            taste.of(artist) >= ForYou.NEAR || FrequencyMix.who(artist) in tookTo -> FrequencyMix.NEAR; else -> FrequencyMix.FAR }
        val beyond = HashMap<String, Pair<String, String>>()   // Last.fm's songs not in your library: found on YouTube if chosen
        val named = HashSet<String>()
        lists.forEachIndexed { i, ears ->
            val through = seeds.getOrNull(i)?.artist.orEmpty()
            named += ears.named.map(FrequencyMix::who)
            for ((list, source) in listOf(ears.related to ears.source, ears.owned to "lastfm")) list.forEachIndexed { r, s ->
                val k = Radio.key(s.title, s.artist)
                if (s.id in queued || !fine(k, s.title) || Blocked.hides(s)) return@forEachIndexed
                songs.putIfAbsent(k, s)
                found += FrequencyMix.Found(k, s.artist, laneFor(k, s.artist), 1.0 - r.toDouble() / (list.size + 1), through, source)
            }
            ears.beyond.forEachIndexed { r, t ->
                val k = Radio.key(t.title, t.artist)
                if (!fine(k, t.title) || Blocked.hides(t.title, t.artist)) return@forEachIndexed
                beyond.putIfAbsent(k, t.title to t.artist)
                found += FrequencyMix.Found(k, t.artist, laneFor(k, t.artist), (1.0 - r.toDouble() / (ears.beyond.size + 1)) * .9, through, "lastfm")
            }
        }
        yours.take(60).forEachIndexed { r, (k, score) ->
            val s = knownBy[k] ?: return@forEachIndexed
            if (s.id in queued || !fine(k, s.title)) return@forEachIndexed
            songs.putIfAbsent(k, s)
            found += FrequencyMix.Found(k, s.artist, FrequencyMix.YOURS, score.coerceIn(0.0, 1.0) * (1.0 - r / 120.0), source = "yours")
        }
        val moodRanked = moodFound.sortedByDescending { (_, a) -> taste.of(a) }.take(40)
        moodRanked.forEachIndexed { r, (t, a) ->
            val k = Radio.key(t, a)
            if (!fine(k, t) || Blocked.hides(t, a)) return@forEachIndexed
            found += FrequencyMix.Found(k, a, FrequencyMix.MOOD, 1.0 - r.toDouble() / (moodRanked.size + 1), source = "mood")
        }

        // chosen, kept apart from what's played lately, what's waiting and the last dozen it gave; then found to play
        val recent = synchronized(this) { went.values.sortedBy { it.at }.map { it.artist } } + queue.take(current + 1).takeLast(8).map { it.artist } +
            queue.drop(current + 1).map { it.artist } + synchronized(this) { lately.toList() }
        val laneCounts = synchronized(this) { given.values.groupingBy { it.first }.eachCount() }
        val agreed = FrequencyMix.agreed(found, named)
        // flow and aim (FrequencyFlow): the front runners' tempo, key and loudness against the song playing, and a new
        // artist's audience against where new is aimed
        val aim = memory.aim(runCatching { memory.stuck(heard.map { Triple(it.artist, it.at, it.ms) }, emptyMap()) }.getOrDefault(emptyList()))
        val nowSound = last?.let { sound(it, read = false) }
        fun pre(f: FrequencyMix.Found) = f.rank * memory.worth(f.source) + (f.ears - 1) * .4 + tasteOf(f.artist).coerceAtLeast(0.0)
        val front = agreed.filter { it.lane != FrequencyMix.YOURS }.sortedByDescending(::pre).take(10)
        val plus = java.util.concurrent.ConcurrentHashMap<String, Double>()
        val listened = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val look = java.util.concurrent.Executors.newFixedThreadPool(4)
        front.mapIndexed { i, f ->
            look.submit {
                val s = songs[f.key]
                val title = s?.title ?: beyond[f.key]?.first ?: moodFound.firstOrNull { Radio.key(it.first, it.second) == f.key }?.first ?: return@submit
                // tempo read from the audio for the first few only (each a second or two)
                val next = s?.let { sound(it, read = i < 4) } ?: FrequencyFlow.Sound(gain = FrequencyFlow.gain(title, f.artist))
                var b = FrequencyFlow.follows(nowSound, next)
                if (f.lane == FrequencyMix.FAR && LastFm.key.isNotBlank()) FrequencyFlow.listeners(title, f.artist, LastFm.key)?.let { l -> listened[f.key] = l; b += FrequencyFlow.aimed(l, aim) }
                plus[f.key] = b
            }
        }.forEach { runCatching { it.get(20, java.util.concurrent.TimeUnit.SECONDS) } }
        look.shutdownNow()
        val picked = FrequencyMix.pick(agreed, d, ::tasteOf, sitting, learned, recent, BATCH + 2, taste.appetite,
            given0 = laneCounts, memory = memory, near = plus)
        val out = ArrayList<Song>()
        for (f in picked) {
            if (out.size >= BATCH) break
            val title = moodFound.firstOrNull { Radio.key(it.first, it.second) == f.key }?.first ?: beyond[f.key]?.first
            val s = songs[f.key] ?: title?.let { runCatching { YouTube.find(it, f.artist) }.getOrNull() } ?: continue
            out += s
            // an artist new to you, brought: what stuck is counted from here (FrequencyMemory.stuck)
            if (f.lane == FrequencyMix.FAR && heard.none { FrequencyMix.who(it.artist) == FrequencyMix.who(s.artist) }) memory.brought(s.artist, f.source, now0, listened[f.key] ?: 0L)
            synchronized(this) {
                given[f.key] = Triple(f.lane, f.through, f.source)
                lately += s.artist
                while (lately.size > 12) lately.removeAt(0)
                while (given.size > 300) given.remove(given.keys.first())
            }
        }
        keepMemory(memory)
        Log.d("frequency: ${sitting.size} this sitting; seeds ${seeds.joinToString { it.artist }}; " +
            out.joinToString { s -> given[Radio.key(s.title, s.artist)].let { g -> "${s.title} - ${s.artist} (${g?.first}, ${g?.third})" } } +
            "; ${picked.count { it.ears > 1 }} of ${picked.size} agreed on")
        return out
    }

    /** What the sources found for one seed: YouTube's related (or the server's), Last.fm's in your library and beyond it, similar artists. */
    private class Ears(val related: List<Song> = emptyList(), val owned: List<Song> = emptyList(), val beyond: List<Similar.Track> = emptyList(),
        val named: List<String> = emptyList(), val source: String = "related")

    /**
     * What Last.fm hears as near a seed: its similar songs you have, then your songs by its similar artists, two of each;
     * `beyond`, its similar songs you don't have, found on YouTube if chosen; and its similar artists' names.
     */
    private fun lastFm(s: Radio.Seed, beyond: Boolean): Ears {
        if (LastFm.key.isBlank()) return Ears()
        val lib = Library.songs.value.filter { !it.station && !it.episode }
        val artist = s.artist.substringBefore(", ").trim()
        val byKey = lib.associateBy { Radio.key(it.title, it.artist) }
        val all = Similar.lastFmTracks(s.title, artist, LastFm.key)
        val tracks = all.mapNotNull { byKey[Radio.key(it.title, it.artist)] }
        val byArtist = lib.groupBy { FrequencyMix.who(it.artist) }
        val rnd = java.util.Random(s.id.hashCode().toLong())
        val similar = Similar.lastFmArtists(artist, LastFm.key, 20)
        val theirs = similar.flatMap { (a, _) -> byArtist[FrequencyMix.who(a)].orEmpty().shuffled(rnd).take(2) }
        val owned = (tracks + theirs).filter { it.id != s.id }.distinctBy { it.id }
        val outside = if (beyond) all.filter { Radio.key(it.title, it.artist) !in byKey }.take(30) else emptyList()
        return Ears(owned = owned, beyond = outside, named = similar.map { it.first })
    }

    /** A song's sound for flow: its tempo and key (kept, else read now where `read`), its loudness (Deezer). */
    private fun sound(s: Song, read: Boolean): FrequencyFlow.Sound {
        val t = Tempos.kept(s.id) ?: if (read) runCatching { Tempos.of(s) }.getOrNull() else null
        val g = if (s.station || s.episode) null else FrequencyFlow.gain(s.title, s.artist)
        return FrequencyFlow.Sound(t?.bpm ?: 0.0, t?.key, t?.outBpm ?: t?.bpm ?: 0.0, t?.outKey ?: t?.key, g)
    }

    /** What it remembers across sittings (FrequencyMemory). */
    fun memory(): FrequencyMemory = FrequencyMemory.of(runCatching { memoryFile.readText() }.getOrNull())
    private fun keepMemory(m: FrequencyMemory) { runCatching { Keep.write(memoryFile, m.json()) } }

    /** What stuck: the artists it brought you that you've since loved, or played through twice away from it. */
    fun stuck(): List<String> {
        val lovedAt = HashMap<String, Long>()
        val songs = Library.songs.value.associateBy { it.id } + TubeAccount.liked.value.associateBy { it.id }
        for (id in Loved.ids.value) {
            val s = songs[id] ?: continue
            val at = Loved.lovedAt(s) ?: continue
            val w = s.artist.substringBefore(", ")
            if (at > (lovedAt[w] ?: 0L)) lovedAt[w] = at
        }
        return memory().stuck(History.read().map { Triple(it.artist, it.at, it.ms) }, lovedAt)
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
