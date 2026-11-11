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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var builtAt = 0L

    /** At the start, and again when the window comes back after ten minutes: your taste made again; Last.fm's monthly. */
    private var first = true

    fun opened() {
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
            if (b != null) build()
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

    fun tapped(key: String) = seen.tapped(key)

    /** How many picked it, 0 to 1 (one critic .2, four and more 1). */
    private fun picked(s: Picks.Shown) = (s.by.size / 4.0).coerceIn(.2, 1.0)
    /** How new, 1 today to 0 a month old (a pick with no date: half). */
    private fun fresh(s: Picks.Shown) = if (s.pick.at <= 0) .5 else (1 - (System.currentTimeMillis() - s.pick.at) / (30.0 * Seen.DAY)).coerceIn(0.0, 1.0)

    /** Picks in your order: seven near you, three a stretch, of every ten; passed-over ones stepping back. */
    fun rank(list: List<Picks.Shown>, t: Taste = taste.value): List<ForYou.Placed<Picks.Shown>> =
        ForYou.order(list.distinctBy { Curation.key(it.pick) }, { t.of(it.pick.artist) }, ::picked, ::fresh,
            { seen.fresh(key(it.pick)) }, Moment.issue().toLong())

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
