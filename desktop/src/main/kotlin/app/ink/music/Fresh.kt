package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File

/**
 * Your taste on the computer, as the phone keeps it (2026-11-11: the computer had none; INK's picks came in the critics'
 * order and a Last.fm import changed nothing here): [Taste] made from what's played here and synced from the phone
 * (History), loves, YouTube Music's likes, the library, your artists' fans' favorites and your Last.fm listening
 * brought in; what's been shown ([Seen]); what's said not for you. INK's picks then in your order, seven near you and
 * three a stretch, each saying why. The phone's own is Fresh.kt in the app.
 */
object Fresh {
    val seen = Seen(File(Home.dir, "seen.json"))
    val taste = MutableStateFlow(Taste.of(emptyList()))
    /** Your listening brought in from Last.fm and ListenBrainz (listen's "new from your artists", your favorites mix). */
    val brought = MutableStateFlow<List<Brought>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var builtAt = 0L

    /** At the start, and again when the window comes back after ten minutes: your taste made again; Last.fm's monthly. */
    private var first = true

    /** A visit: a new one at the start and on coming back after twenty minutes away (listen's shelves ordered anew). */
    val visit = MutableStateFlow(0)
    /** When you left last, if it was a while ago: what's new is new since then (listen's "since you were here"). */
    val since = MutableStateFlow<Long?>(null)
    private val leftFile = File(Home.dir, "left-at")

    /** The window put away or left (focus gone): when, and what was seen, written down. */
    fun away() {
        runCatching { leftFile.writeText(System.currentTimeMillis().toString()) }
        left()
    }

    fun opened() {
        // away a while (the window left twenty minutes and more): what's new is since then, the shelves ordered afresh
        val left = runCatching { leftFile.readText().trim().toLong() }.getOrNull()
        if (left != null && System.currentTimeMillis() - left > 20 * 60_000L && since.value != left) { since.value = left; visit.value++ }
        // local files only: no picks to order, nothing brought in
        if (Local.on) return
        // the library read in after the start: made again then, its loves and records counted
        if (first) { first = false; scope.launch { Library.songs.first { it.isNotEmpty() }; build() } }
        val now = System.currentTimeMillis()
        if (now - builtAt > 10 * 60_000L) build()
        // Last.fm and ListenBrainz connected: brought in the first time and then each month
        LastFm.account.value?.let { a -> if ((LastFm.brought(Home.dir)?.at ?: 0L) < now - 30 * Seen.DAY) bringIn(a.name) }
        ListenBrainz.account.value?.let { a -> if ((ListenBrainz.brought(Home.dir)?.at ?: 0L) < now - 30 * Seen.DAY) bringIn(a.user, brainz = true) }
    }

    /** Your listening on Last.fm (or ListenBrainz) brought in by name, then your taste made again (null: nothing found). */
    fun bringIn(user: String, brainz: Boolean = false, done: (Brought?) -> Unit = {}) {
        scope.launch {
            val b = if (brainz) ListenBrainz.bringIn(Home.dir, user) else LastFm.bringIn(Home.dir, user)
            if (b != null) { build(); Discover.addFavorites() }
            kotlinx.coroutines.withContext(Dispatchers.Main) { done(b) }
        }
    }

    /** Left (the app quitting): what was seen, written down. */
    fun left() { runCatching { seen.save() } }

    /** Your taste, made again from what's kept: listens, loves, the library, your artists' fans' favorites, Last.fm. */
    fun build() {
        builtAt = System.currentTimeMillis()
        scope.launch {
            val heard = History.read()
            // the computer keeps listens only (30 s and more): no skips to go by here
            val plays = heard.map { Taste.Play(it.artist, it.at, it.ms) }
            val skips = emptyList<Taste.Play>()
            val library = Library.songs.value
            val loved = (library.filter { it.id in Loved.ids.value } + Loved.tube.value + TubeAccount.liked.value).map { it.artist }
            val kept = library.map { it.albumArtist.ifBlank { it.artist } }.distinct()
            val brought = listOfNotNull(LastFm.brought(Home.dir), ListenBrainz.brought(Home.dir))
            this@Fresh.brought.value = brought
            fun of(related: Map<String, List<String>>) = Taste.of(plays, skips, loved, kept, related, refused(), brought = brought)
            val first = of(emptyMap())
            taste.value = first
            // then with whom your nearest's fans also like (each one's page as kept, or read)
            val related = first.top(12).map { bareName ->
                val name = heard.asReversed().firstOrNull { Taste.bare(it.artist.substringBefore(", ")) == bareName }?.artist?.substringBefore(", ")
                    ?: brought.firstNotNullOfOrNull { src -> src.artists.keys.firstOrNull { Taste.bare(it) == bareName } } ?: bareName
                async { name to (withTimeoutOrNull(4_000) { Artists.of(name).firstOrNull { it != null } }?.like.orEmpty().map { it.name }) }
            }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
            if (related.isNotEmpty()) taste.value = of(related)
            System.err.println("ink taste: ${plays.size} listens, ${loved.size} loves, ${related.size} artists' fans, ${brought.sumOf { it.artists.size }} brought in; nearest ${taste.value.top(5)}")
        }
    }

    private val refusedFile = File(Home.dir, "not-for-me.json")
    private fun refused(): List<String> = runCatching {
        JSONArray(refusedFile.readText()).let { a -> (0 until a.length()).map { a.getString(it) } }
    }.getOrDefault(emptyList())

    /** Said not for you: never shown again, and its artist further from you. */
    fun notForMe(artist: String, key: String) {
        seen.hide(key)
        runCatching { Keep.write(refusedFile, JSONArray((refused() + artist).distinct()).toString()) }
        build()
    }

    fun key(p: Curation.Pick) = "pick:" + Curation.key(p)

    /**
     * Listen's shelves in the moment's order, as the phone's: each by its usual place, moved up for the time of day and
     * the kind of day (mornings new music, evenings records and mixes, late nights the quiet ones, Fridays what's out,
     * weekends whole records), down when shown a week and never opened, up when opened lately; a day's small shuffle.
     */
    fun order(keys: List<String>, now: Moment.Now = Moment.now()): List<String> {
        val boost = HashMap<String, Double>()
        fun b(k: String, v: Double) { boost[k] = (boost[k] ?: 0.0) + v }
        when (now.part) {
            Moment.Part.MORNING -> { b("fromYours", -3.0); b("forYou", -3.0); b("newest", -8.0); b("episodes", -6.0) }
            Moment.Part.DAY -> { b("madeForYou", -2.0); b("mixes", -3.0); b("because", -3.0); b("newToYou", -4.0) }
            Moment.Part.EVENING -> { b("liveSets", -3.0); b("tubeMixes", -4.0); b("mixes", -4.0); b("yours", -5.0); b("because", -3.0) }
            Moment.Part.LATE -> { b("liveSets", -5.0); b("forgotten", -9.0); b("inkRadio", -7.0); b("fromYours", 3.0) }
        }
        if (now.friday) { b("fromYours", -6.0); b("forYou", -5.0); b("newest", -9.0) }
        if (now.weekend) { b("liveSets", -2.0); b("yours", -5.0); b("forgotten", -4.0) }
        val day = Moment.issue().toLong()
        val place = keys.withIndex().associate { (i, k) ->
            val shelf = "shelf:$k"
            val opened = seen.tappedAt(shelf)
            val use = when {
                opened > System.currentTimeMillis() - 14 * Seen.DAY -> -minOf(seen.taps(shelf), 4) * .75
                seen.days(shelf) >= 7 && seen.taps(shelf) == 0 -> 4.0
                else -> 0.0
            }
            k to i + (boost[k] ?: 0.0) + use + java.util.Random(day * 7919 + k.hashCode()).nextDouble() * 1.5
        }
        return keys.sortedBy { place[it] }
    }

    fun tapped(key: String) = seen.tapped(key)

    /** How many picked it, 0 to 1 (one critic .2, four and more 1). */
    private fun picked(s: Picks.Shown) = (s.by.size / 4.0).coerceIn(.2, 1.0)
    /** How new, 1 today to 0 a month old (a pick with no date: half). */
    private fun fresh(s: Picks.Shown) = if (s.pick.at <= 0) .5 else (1 - (System.currentTimeMillis() - s.pick.at) / (30.0 * Seen.DAY)).coerceIn(0.0, 1.0)

    /** Picks in your order: of every ten, most near you, the rest a stretch (as many as your appetite says: one to five); passed-over ones stepping back. */
    fun rank(list: List<Picks.Shown>, t: Taste = taste.value): List<ForYou.Placed<Picks.Shown>> =
        ForYou.order(list.distinctBy { Curation.key(it.pick) }, { t.of(it.pick.artist) }, ::picked, ::fresh,
            { seen.fresh(key(it.pick)) }, Moment.issue().toLong(), stretch = t.appetite)

    /** Why it's here, when that's worth a line: a stretch, someone you love, whom your artists' fans like, Last.fm's. */
    fun reason(s: Picks.Shown, stretch: Boolean, t: Taste = taste.value): String? {
        if (stretch) return "a stretch"
        return when (val w = t.why(s.pick.artist)) {
            Taste.Why.Loved -> "you love them"
            is Taste.Why.Like -> "if you like ${w.of.lowercase()}"
            is Taste.Why.Brought -> "from your ${w.from}"
            else -> null
        }
    }
}
