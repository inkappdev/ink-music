package app.ink.music

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeping listen and INK fresh (2026-11-07): your taste ([Taste], made here from your listening), what's been shown
 * ([Seen]), when you were last here, and what's said not for you. Listen leads with what's new since you were here and
 * takes three of every ten from INK; INK puts its picks in your order, seven near you and three a stretch; every pick
 * says why; the shelves move with the time of day and what you open. All on the phone.
 */
object Fresh {
    lateinit var seen: Seen
        private set
    val taste = MutableStateFlow(Taste.of(emptyList()))
    /** When you left last, if it was a while ago (twenty minutes and more): what's new is new since then. */
    val since = MutableStateFlow<Long?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null
    private var builtAt = 0L

    /** The seen record read (Main starts it). */
    val ready: Boolean get() = ::seen.isInitialized

    fun start(c: Context) {
        if (app != null) return
        app = c.applicationContext
        seen = Seen(File(c.filesDir, "seen.json"))
    }

    /** ink music opened (Main.onStart): away long enough, what's new is since then; your taste made again. */
    fun opened(c: Context) {
        start(c)
        val left = runCatching { File(c.filesDir, "left-at").readText().trim().toLong() }.getOrNull()
        val now = System.currentTimeMillis()
        // away a while: what's new since then, and the shelves ordered afresh for the moment
        if (left != null && now - left > 20 * 60_000L) { since.value = left; Listen.visit++ }
        if (now - builtAt > 10 * 60_000L) build(c)
    }

    /** Left (Main.onStop): when, and what was seen, written down. */
    fun left(c: Context) {
        runCatching { File(c.filesDir, "left-at").writeText(System.currentTimeMillis().toString()) }
        if (::seen.isInitialized) scope.launch { seen.save() }
    }

    /** Your taste, made again from what's kept: listens, skips, loves, the library, your artists' fans' favorites. */
    fun build(c: Context) {
        builtAt = System.currentTimeMillis()
        val app = c.applicationContext
        scope.launch {
            val heard = History.read(app)
            val plays = heard.map { Taste.Play(it.artist, it.at, it.ms) }
            val skips = History.skips(app).map { Taste.Play(it.artist, it.at, it.ms) }
            val library = Library.songs.value
            val loved = (library.filter { it.id in Loved.ids.value } + Loved.tube.value + YouTubeAccount.liked.value).map { it.artist }
            val kept = library.map { it.albumArtist }.distinct()
            // first a taste without the fans' favorites (at once), then with them: each of your nearest's page as kept
            val first = Taste.of(plays, skips, loved, kept, refused = refused(app))
            taste.value = first
            val related = first.top(12).map { bareName ->
                val name = (heard.asReversed().firstOrNull { Taste.bare(it.artist.substringBefore(", ")) == bareName }?.artist?.substringBefore(", ")) ?: bareName
                async { name to (withTimeoutOrNull(4_000) { YouTubeAccount.artist(name, "").firstOrNull { it != null } }?.like.orEmpty().map { it.name }) }
            }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
            if (related.isNotEmpty()) taste.value = Taste.of(plays, skips, loved, kept, related, refused(app))
            android.util.Log.d("ink-picks", "taste: ${plays.size} listens, ${skips.size} skips, ${loved.size} loves, ${related.size} artists' fans; nearest ${taste.value.top(5)}")
        }
    }

    private fun refusedFile(c: Context) = File(c.filesDir, "not-for-me.json")
    private fun refused(c: Context): List<String> = runCatching {
        JSONArray(refusedFile(c).readText()).let { a -> (0 until a.length()).map { a.getString(it) } }
    }.getOrDefault(emptyList())

    /** Said not for you (a pick held): never shown again, and its artist further from you. */
    fun notForMe(c: Context, artist: String, key: String) {
        seen.hide(key)
        val list = (refused(c) + artist).distinct()
        runCatching { refusedFile(c).writeText(JSONArray(list).toString()) }
        build(c)
        android.util.Log.d("ink-picks", "not for me: $artist")
    }

    fun key(p: Curation.Pick) = "pick:" + Curation.key(p)

    fun tapped(key: String) {
        if (!::seen.isInitialized) return
        seen.tapped(key)
        android.util.Log.d("ink-picks", "opened $key")
    }

    /** How many picked it, 0 to 1 (one critic .2, four and more 1). */
    private fun picked(s: Picks.Shown) = (s.by.size / 4.0).coerceIn(.2, 1.0)
    /** How new, 1 today to 0 a month old (a pick with no date: half). */
    private fun fresh(s: Picks.Shown) = if (s.pick.at <= 0) .5 else (1 - (System.currentTimeMillis() - s.pick.at) / (30.0 * Seen.DAY)).coerceIn(0.0, 1.0)

    /** Picks in your order: seven near you, three a stretch, of every ten; passed-over ones stepping back. */
    fun rank(list: List<Picks.Shown>, t: Taste = taste.value): List<ForYou.Placed<Picks.Shown>> =
        ForYou.order(list.distinctBy { Curation.key(it.pick) }, { t.of(it.pick.artist) }, ::picked, ::fresh,
            { if (::seen.isInitialized) seen.fresh(key(it.pick)) else 1.0 }, Moment.issue().toLong())

    /**
     * Why it's here, when that's worth a line (2026-11-07: less text): a stretch, someone you love, whom your artists'
     * fans like. "You play them" goes without saying on a shelf of yours; who picked it, once, elsewhere. Null: nothing.
     */
    fun reason(s: Picks.Shown, stretch: Boolean, t: Taste = taste.value): String? {
        if (stretch) return "a stretch"
        return when (val w = t.why(s.pick.artist)) {
            Taste.Why.Loved -> "you love them"
            is Taste.Why.Like -> "if you like ${w.of.lowercase()}"
            else -> null
        }
    }

    // ---- the shelves' order ----

    /**
     * Listen's shelves in the moment's order: each by its usual place, moved up for the time of day and the kind of day
     * (mornings new music, evenings records and mixes, late nights the quiet ones, Fridays what's out, weekends whole
     * records), down when shown a week and never opened, up when opened lately; a day's own small shuffle among
     * near neighbors. Held for the visit (a shelf doesn't move under your thumb).
     */
    fun order(keys: List<String>, now: Moment.Now = Moment.now()): List<String> {
        val boost = HashMap<String, Double>()
        fun b(k: String, v: Double) { boost[k] = (boost[k] ?: 0.0) + v }
        when (now.part) {
            Moment.Part.MORNING -> { b("fromYours", -3.0); b("forYou", -3.0); b("newest", -8.0); b("fresh", -8.0); b("indie", -5.0); b("episodes", -6.0) }
            Moment.Part.DAY -> { b("madeForYou", -2.0); b("mixes", -3.0); b("because", -3.0); b("newToYou", -4.0) }
            Moment.Part.EVENING -> { b("tubeMixes", -4.0); b("mixes", -4.0); b("records", -8.0); b("yours", -10.0); b("because", -3.0) }
            Moment.Part.LATE -> { b("forgotten", -9.0); b("onRepeat", -6.0); b("stations", -14.0); b("inkRadio", -7.0); b("fromYours", 3.0); b("indie", 4.0) }
        }
        if (now.friday) { b("fromYours", -6.0); b("forYou", -5.0); b("fresh", -9.0); b("newest", -9.0) }
        if (now.weekend) { b("yours", -10.0); b("records", -8.0); b("forgotten", -4.0) }
        val day = Moment.issue().toLong()
        val place = keys.withIndex().associate { (i, k) ->
            val shelf = "shelf:$k"
            val use = if (!::seen.isInitialized) 0.0 else {
                val opened = seen.tappedAt(shelf)
                when {
                    opened > System.currentTimeMillis() - 14 * Seen.DAY -> -minOf(seen.taps(shelf), 4) * .75
                    seen.days(shelf) >= 7 && seen.taps(shelf) == 0 -> 4.0
                    else -> 0.0
                }
            }
            k to i + (boost[k] ?: 0.0) + use + java.util.Random(day * 7919 + k.hashCode()).nextDouble() * 1.5
        }
        return keys.sortedBy { place[it] }
    }

    // ---- the day's: picked once an issue, kept (files/issue.json) ----

    /** The day's pick of a kind: kept for the issue once chosen (`choose` given what's there), so it holds all day. */
    fun ofTheDay(c: Context, kind: String, choose: () -> String?): String? {
        val f = File(c.filesDir, "issue.json")
        val issue = Moment.issue()
        val o = runCatching { JSONObject(f.readText()) }.getOrNull()?.takeIf { it.optInt("issue") == issue } ?: JSONObject().put("issue", issue)
        o.optString(kind).takeIf { it.isNotEmpty() }?.let { return it }
        val picked = choose() ?: return null
        runCatching { f.writeText(o.put(kind, picked).toString()) }
        return picked
    }
}

/** A shelf counted: shown (once a day), and opened when something in it is tapped (seen first, never taken from it). */
fun Modifier.shelf(key: String): Modifier = this.pointerInput(key) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Initial)
            if (down.changes.none { it.pressed }) continue
            val start = down.changes.first().position
            var moved = false
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val ch = e.changes.first()
                if ((ch.position - start).getDistance() > 24f) moved = true
                if (!ch.pressed) break
            }
            if (!moved) Fresh.tapped("shelf:$key")
        }
    }
}

/** Said once a visit: this shelf was on screen. */
@Composable
fun ShelfShown(key: String) {
    LaunchedEffect(key) { if (Fresh.ready) Fresh.seen.shown("shelf:$key") }
}

/** One line of what's new since you were here: what it is, why, and where a tap takes you. */
class NewLine(val key: String, val title: String, val said: String, val go: () -> Unit)

/**
 * Listen's top (2026-11-07): what's new since you were here, a line each, four at most; each one shown is no longer
 * new next time. Said when you've been away (or first thing, with what's new at all).
 */
@Composable
fun SinceYouWereHere(since: Long?, lines: List<NewLine>) {
    if (lines.isEmpty()) return
    LaunchedEffect(lines.map { it.key }) { if (Fresh.ready) lines.forEach { Fresh.seen.shown(it.key) } }
    // it arrives as ink's things do: the heading, then each line rising and coming up 50 ms apart (once each; a line
    // come later arrives by itself), the block growing to hold it; under less motion, simply there
    val less by Moves.less.collectAsState()
    fun Modifier.coming(i: Int, key: String) = if (less) this else this.arrive(i, key)
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 14.dp)
        .then(if (less) Modifier else Modifier.animateContentSize(tween(Motion.ENTER, easing = Motion.expo)))) {
        BasicText((since?.let { Moment.since(it) } ?: "new today").uppercase(), style = headline(22.sp), modifier = Modifier.coming(0, "head"))
        lines.take(4).forEachIndexed { i, l ->
            Row(Modifier.fillMaxWidth().coming(i + 1, l.key).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Fresh.tapped(l.key); l.go() })
                .padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                BasicText("+", style = pixel(14.sp), modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(l.title.uppercase(), style = headline(17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    BasicText(l.said, style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * One of each on a page (2026-11-07: "new from your artists" had Fontaines D.C.'s record twice, side by side): the same
 * record (its cover, or its artist and name) shown once on a page, the first shelf keeping it; on a shelf, an artist
 * never twice in a row where another can go between.
 */
object Unique {
    private fun bare(s: String) = Curation.folded(s).removePrefix("the ").filter { it.isLetterOrDigit() }

    /** A cover's address as the same picture whatever size it's asked at. */
    private fun art(a: String?): String? = a?.takeIf { it.isNotBlank() }?.substringBefore('?')
        ?.replace(Regex("/\\d+x\\d+[a-z-]*\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE), "")
        ?.replace(Regex("=w\\d+-h\\d+.*$"), "")

    /** What makes two things the same record. */
    fun record(art: String?, artist: String, album: String): List<String> = listOfNotNull(
        art(art)?.let { "art:$it" },
        bare(album).takeIf { it.isNotEmpty() }?.let { "rec:" + bare(artist.substringBefore(",")) + "/" + it },
    )

    fun of(s: Song) = record(s.art, s.albumArtist.ifBlank { s.artist }, s.album)
    fun of(p: Picks.Shown) = record(p.art ?: p.pick.art, p.pick.artist, if (p.pick.record) p.pick.title else "")
    fun of(a: TubeAlbum) = record(a.art, a.artist, a.title)
    fun of(a: Album) = record(null, a.artist, a.title)

    /** A shelf's things not already on the page (nor twice on it), its artists spread apart; `taken` grows with them. */
    fun <T> shelf(items: List<T>, taken: MutableSet<String>, keys: (T) -> List<String>, perArtist: Boolean = false, artist: (T) -> String): List<T> {
        // a shelf of picks: one an artist (a song and its record are often two pictures of one thing)
        val artists = HashSet<String>()
        val kept = items.filter { item ->
            val k = keys(item)
            if (k.any { it in taken } || (perArtist && !artists.add(bare(artist(item).substringBefore(","))))) false else { taken.addAll(k); true }
        }
        return spread(kept) { bare(artist(it).substringBefore(",")) }
    }

    /** No artist twice in a row where it can be helped: the next one by someone else brought forward. */
    fun <T> spread(items: List<T>, artist: (T) -> String): List<T> {
        val out = items.toMutableList()
        for (i in 1 until out.size) {
            if (artist(out[i]) != artist(out[i - 1])) continue
            val j = (i + 1 until out.size).firstOrNull { artist(out[it]) != artist(out[i - 1]) } ?: break
            out.add(i, out.removeAt(j))
        }
        return out
    }
}
