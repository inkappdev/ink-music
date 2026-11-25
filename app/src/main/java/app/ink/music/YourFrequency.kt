package app.ink.music

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Your frequency on the phone (2026-11-25, #16): what [FrequencyMix] chooses, found and played. The screens start it (its dials,
 * your songs to come back to, the first song); the player's process keeps it going (the queue's end asks [more] in
 * place of radio, a few at a time so it can turn quickly) and steers it (a song left in its first seconds drops the
 * frequency's songs waiting after the next one, and finds again with that in mind). Between the two, files:
 * frequency.json (the dials, when it began, its first song, each song's lane), frequency-known.json (your songs), frequency-loved.jsonl
 * (loves while it plays), frequency-learned.json (each lane's record with you, across sittings).
 */
object YourFrequency {
    private const val FILE = "frequency.json"
    private const val KNOWN = "frequency-known.json"
    private const val LOVED = "frequency-loved.jsonl"
    private const val LEARNED = "frequency-learned.json"
    /** How many come at a time: few, so what you do turns it within a song or two. */
    private const val BATCH = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- the screens ----

    val dials = MutableStateFlow(FrequencyMix.Dials())
    /** The frequency's first song this sitting (the queue's first while it plays), or null. */
    val first = MutableStateFlow<String?>(null)
    /** Starting: your songs being gathered. */
    val starting = MutableStateFlow(false)

    fun load(c: Context) {
        val o = read(c) ?: return
        dials.value = dialsOf(o)
        first.value = o.optString("first").ifEmpty { null }
    }

    /** A dial turned: kept, and if it's playing, the songs waiting dropped and found again. */
    fun turn(c: Context, d: FrequencyMix.Dials) {
        dials.value = d
        val o = read(c) ?: JSONObject()
        write(c, o.put("tuned", d.tuned).put("mood", d.mood))
        if (playing()) Playback.frequencyAgain()
    }

    /** Whether what's playing is your frequency. */
    fun playing(): Boolean = first.value != null && Playback.queue.value.firstOrNull()?.song?.id == first.value

    /** Begun: your songs gathered, the first chosen and played; the player finds the rest. */
    fun start(c: Context) {
        if (starting.value) return
        starting.value = true
        val app = c.applicationContext
        scope.launch {
            try {
                val pool = Mixes.pool(app).filter { !it.station && !it.episode && !Blocked.hides(it) }
                val taste = Fresh.taste.value
                val loved = Loved.ids.value
                val heard = History.read(app)
                val byKey = pool.associateBy { Radio.key(it.title, it.artist) }
                val ranked = FrequencyMix.yours(heard.map { Triple(Radio.key(it.title, it.artist), it.artist, it.at) },
                    pool.filter { it.id in loved }.map { Radio.key(it.title, it.artist) }.toSet(), taste::of)
                    .mapNotNull { (k, _) -> byKey[k] }
                // the opening: one of yours you're near, a little chance among the best few
                val opening = ranked.take(6).shuffled().firstOrNull() ?: pool.shuffled().firstOrNull() ?: return@launch
                Keep.write(File(app.filesDir, KNOWN), JSONArray().apply { (ranked + pool).distinctBy { it.id }.take(400).forEach { put(it.json()) } }.toString())
                File(app.filesDir, LOVED).delete()
                val d = dials.value
                write(app, JSONObject().put("tuned", d.tuned).put("mood", d.mood).put("started", System.currentTimeMillis())
                    .put("first", opening.id).put("given", JSONObject().put(Radio.key(opening.title, opening.artist), JSONArray().put(FrequencyMix.YOURS).put(""))))
                first.value = opening.id
                android.util.Log.d("ink-frequency", "begun (${FrequencyMix.said(d.tuned)}${if (d.mood.isNotEmpty()) ", " + d.mood else ""}) on ${opening.title} - ${opening.artist}, ${pool.size} of yours")
                kotlinx.coroutines.withContext(Dispatchers.Main) { Playback.unshuffle(); Playback.play(listOf(opening), 0) }
            } finally { starting.value = false }
        }
    }

    /** Begun anew in a mood (the moods page): the mood chosen, kept, and played. */
    fun startIn(c: Context, mood: String) {
        turn(c, dials.value.copy(mood = mood))
        start(c)
    }

    /**
     * Stopped (the player's stop, 2026-11-25, Bronson: the block "also needs to stop and reset"): the sitting over, its
     * first song forgotten, so the block shows PLAY again and the queue's end gets radio, not more of it. The needle and
     * the mood are kept.
     */
    fun stopped(c: Context) {
        if (first.value == null) return
        first.value = null
        val o = read(c) ?: return
        o.remove("first")
        write(c, o)
    }

    /** A love or an unlove while your frequency plays: it steers what comes. */
    fun loved(c: Context, s: Song, on: Boolean) {
        if (!on || !playing()) return
        runCatching { File(c.filesDir, LOVED).appendText(JSONObject().put("at", System.currentTimeMillis()).put("title", s.title).put("artist", s.artist).toString() + "\n") }
    }

    // ---- the player's process ----

    /** Whether a queue (its first song `firstId`) is your frequency's. */
    fun on(c: Context, firstId: String?): Boolean = firstId != null && read(c)?.optString("first") == firstId

    /** A song of the queue, as the player hands it over. */
    data class Queued(val id: String, val title: String, val artist: String, val ms: Long)

    /**
     * The next few (blocking): this sitting read from what's kept (played through, left at once, loved), each lane's
     * record brought up to date, seeds chosen (what you took to this sitting, else the last song; and one of yours you're
     * near), what they bring found, with your songs and the mood's, and [FrequencyMix.pick] choosing.
     */
    fun more(c: Context, queue: List<Queued>, current: Int, queued: Set<String>): List<Song> {
        val o = read(c) ?: return emptyList()
        val d = dialsOf(o)
        val started = o.optLong("started")
        val given = o.optJSONObject("given") ?: JSONObject()
        fun laneOf(k: String) = given.optJSONArray(k)?.optString(0).orEmpty()
        fun throughOf(k: String) = given.optJSONArray(k)?.optString(1).orEmpty()

        // this sitting: each song as it went (the latest word on each)
        val went = LinkedHashMap<String, FrequencyMix.Went>()
        val heard = History.read(c)
        heard.filter { it.at >= started }.forEach { h ->
            val k = Radio.key(h.title, h.artist)
            if (h.ms >= 90_000) went[k] = FrequencyMix.Went(h.artist, laneOf(k), throughOf(k), FrequencyMix.How.THROUGH, h.at)
        }
        History.skips(c).filter { it.at >= started }.forEach { h ->
            val k = Radio.key(h.title, h.artist)
            went[k] = FrequencyMix.Went(h.artist, laneOf(k), throughOf(k), FrequencyMix.How.LEFT, h.at)
        }
        runCatching { File(c.filesDir, LOVED).readLines() }.getOrDefault(emptyList()).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optLong("at") >= started }.forEach { l ->
                val k = Radio.key(l.optString("title"), l.optString("artist"))
                went[k] = FrequencyMix.Went(l.optString("artist"), laneOf(k), throughOf(k), FrequencyMix.How.LOVED, l.optLong("at"))
            }
        val sitting = FrequencyMix.Sitting(went.values.sortedBy { it.at })

        // each lane's record: what's newly gone through or left, counted once
        val counted = o.optJSONObject("counted") ?: JSONObject()
        var learned = learned(c)
        went.forEach { (k, w) ->
            if (w.lane.isEmpty() || w.how == FrequencyMix.How.LOVED || counted.has(k)) return@forEach
            learned = learned.with(w.lane, w.how == FrequencyMix.How.THROUGH); counted.put(k, true)
        }
        keepLearned(c, learned)

        // your taste as the screens last made it (Fresh: taste-near.json)
        val (near, appetite) = taste(c)
        fun nearness(artist: String) = Taste.names(artist).maxOfOrNull { near[it] ?: 0.0 } ?: 0.0
        val refused = Fresh.turnedDownNames(c).map(FrequencyMix::who).toSet()
        fun taste(artist: String) = if (FrequencyMix.who(artist) in refused) -1.0 else nearness(artist)

        // your songs, and which of them you're nearest now
        val known = runCatching { JSONArray(File(c.filesDir, KNOWN).readText()).let { a -> (0 until a.length()).map { songOf(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        val knownBy = known.associateBy { Radio.key(it.title, it.artist) }
        val yours = FrequencyMix.yours(heard.map { Triple(Radio.key(it.title, it.artist), it.artist, it.at) }, emptySet(), ::taste)
            .filter { it.first in knownBy }

        // the seeds: who you took to this sitting (their latest song), else the song playing; and one of yours you're near
        val last = queue.getOrNull(current) ?: queue.lastOrNull()
        val took = sitting.seeds(2).mapNotNull { a -> queue.lastOrNull { FrequencyMix.who(it.artist) == FrequencyMix.who(a) } }
        val anchor = yours.take(20).shuffled().firstOrNull()?.let { knownBy[it.first] }?.let { Queued(it.id, it.title, it.artist, it.duration) }
        val seeds = (took.ifEmpty { listOfNotNull(last) } + listOfNotNull(anchor)).distinctBy { it.id }.take(3)
            .map { Radio.Seed(it.id, it.title, it.artist, it.ms) }

        // the artists taken to this sitting: what they bring is near, not new
        val tookTo = went.values.filter { it.how != FrequencyMix.How.LEFT }.map { FrequencyMix.who(it.artist) }.toSet()
        // what each seed brings, side by side
        val just = JustYours.read(c)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(seeds.size.coerceAtLeast(1) + 1)
        val asked = seeds.map { s ->
            pool.submit<List<Song>> {
                runCatching { if (just) (if (s.id.startsWith("y")) emptyList() else Server.similar(c, s.id, s.title, s.artist)) else Radio.related(c, s) }.getOrDefault(emptyList())
            }
        }
        // the mood's songs, read once a sitting (Last.fm's tags), nearest you first
        var moodKept: JSONArray? = null
        val mood = InkMoods.ALL.firstOrNull { it.key == d.mood }?.takeIf { !just }
        val moodAsk = mood?.let { m ->
            pool.submit<List<Pair<String, String>>> {
                o.optJSONArray("moodSongs")?.takeIf { o.optString("moodOf") == m.key }?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } }
                    ?: runCatching {
                        if (LastFm.key.isBlank()) LastFm.key = BuildConfig.LASTFM_KEY
                        InkMoods.songs(m, LastFm.key)
                    }.getOrDefault(emptyList()).also { l -> moodKept = JSONArray().apply { l.forEach { (t, a) -> put(JSONArray().put(t).put(a)) } } }
            }
        }
        val lists = asked.map { runCatching { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
        val moodSongs = moodAsk?.let { runCatching { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()) }.orEmpty()
        pool.shutdownNow()

        // all of it as your frequency weighs it
        val heardKeys = heard.map { Radio.key(it.title, it.artist) }.toHashSet()
        val recentKeys = heard.filter { it.at > System.currentTimeMillis() - 2 * 86_400_000L }.map { Radio.key(it.title, it.artist) }.toHashSet()
        val songs = HashMap<String, Song>()
        val found = ArrayList<FrequencyMix.Found>()
        fun fine(k: String, title: String) = k !in queued && k !in recentKeys && (!Radio.odd(title))
        lists.forEachIndexed { i, list ->
            val through = seeds.getOrNull(i)?.artist.orEmpty()
            list.forEachIndexed { r, s ->
                val k = Radio.key(s.title, s.artist)
                if (s.id in queued || !fine(k, s.title) || Blocked.hides(s)) return@forEachIndexed
                // an artist taken to this sitting (played through, loved) isn't new any more, whatever the taste says yet
                val lane = when { k in heardKeys -> FrequencyMix.YOURS
                    nearness(s.artist) >= ForYou.NEAR || FrequencyMix.who(s.artist) in tookTo -> FrequencyMix.NEAR; else -> FrequencyMix.FAR }
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
        val moodRanked = moodSongs.sortedByDescending { (_, a) -> nearness(a) }.take(40)
        moodRanked.forEachIndexed { r, (t, a) ->
            val k = Radio.key(t, a)
            if (!fine(k, t)) return@forEachIndexed
            found += FrequencyMix.Found(k, a, FrequencyMix.MOOD, 1.0 - r.toDouble() / (moodRanked.size + 1))
        }

        // chosen, then found to play (the mood's on YouTube Music), each one's lane kept
        // kept apart from what's played lately and what's still waiting (the queue after the song playing comes last)
        // and the last dozen it gave (some taken off by a skip: they came straight back, 2026-11-25)
        val lately = o.optJSONArray("lately")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        // (the latest last: only the last stretch keeps an artist away, and the given ones had been first, so cut off)
        val recent = went.values.sortedBy { it.at }.map { it.artist } + queue.take(current + 1).takeLast(8).map { it.artist } +
            queue.drop(current + 1).map { it.artist } + lately
        // each lane's count this sitting, so the shares hold across batches
        val laneCounts = given.keys().asSequence().mapNotNull { given.optJSONArray(it)?.optString(0) }.groupingBy { it }.eachCount()
        val picked = FrequencyMix.pick(found, d, ::taste, sitting, learned, recent, BATCH + 2, appetite, given0 = laneCounts)
        val out = ArrayList<Song>()
        val givenArtists = ArrayList<String>()
        for (f in picked) {
            if (out.size >= BATCH) break
            val title = moodSongs.firstOrNull { Radio.key(it.first, it.second) == f.key }?.first
            val s = songs[f.key] ?: title?.let { runCatching { YouTubeMusic.find(it, f.artist) }.getOrNull() } ?: continue
            out += s
            given.put(f.key, JSONArray().put(f.lane).put(f.through))
            givenArtists += s.artist
        }
        // kept: the newest 300 lanes, what's counted, the mood's songs
        val keys = given.keys().asSequence().toList()
        keys.dropLast(300).forEach(given::remove)
        // written over what's there now (a dial may have turned meanwhile), unless another frequency sitting has begun since
        val now = read(c) ?: o
        if (now.optString("first") == o.optString("first")) {
            now.put("given", given).put("counted", counted).put("lately", JSONArray((lately + givenArtists).takeLast(12)))
            moodKept?.let { now.put("moodOf", mood?.key).put("moodSongs", it) }
            write(c, now)
        }
        android.util.Log.d("ink-frequency", "${sitting.size} this sitting; seeds ${seeds.joinToString { it.artist }}; " +
            out.joinToString { s -> "${s.title} - ${s.artist} (${given.optJSONArray(Radio.key(s.title, s.artist))?.optString(0)})" })
        return out
    }

    private fun dialsOf(o: JSONObject) = FrequencyMix.Dials(o.optDouble("tuned", .5).takeIf { !it.isNaN() } ?: .5, o.optString("mood"))

    private fun taste(c: Context): Pair<Map<String, Double>, Double> = runCatching {
        val o = JSONObject(File(c.filesDir, "taste-near.json").readText())
        val n = o.optJSONObject("near")
        (n?.keys()?.asSequence()?.associateWith { n.optDouble(it) }.orEmpty()) to o.optDouble("appetite", ForYou.STRETCH)
    }.getOrDefault(emptyMap<String, Double>() to ForYou.STRETCH)

    private fun learned(c: Context): FrequencyMix.Learned = runCatching {
        val o = JSONObject(File(c.filesDir, LEARNED).readText())
        fun m(k: String) = o.optJSONObject(k)?.let { j -> j.keys().asSequence().associateWith { j.optInt(it) } }.orEmpty()
        FrequencyMix.Learned(m("good"), m("bad"))
    }.getOrDefault(FrequencyMix.Learned())

    private fun keepLearned(c: Context, l: FrequencyMix.Learned) {
        runCatching { Keep.write(File(c.filesDir, LEARNED), JSONObject().put("good", JSONObject(l.good)).put("bad", JSONObject(l.bad)).toString()) }
    }

    @Synchronized private fun read(c: Context): JSONObject? = runCatching { JSONObject(File(c.filesDir, FILE).readText()) }.getOrNull()
    @Synchronized private fun write(c: Context, o: JSONObject) { runCatching { Keep.write(File(c.filesDir, FILE), o.toString()) } }
}
