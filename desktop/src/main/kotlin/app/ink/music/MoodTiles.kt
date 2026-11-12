package app.ink.music

import kotlinx.datetime.toJavaLocalDate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ink's moods here ([InkMoods]): each mood's songs and a cover for its tile kept for the week (Last.fm's tags, iTunes'
 * cover, a few asks at a time), and its station: the first songs found on YouTube Music at once, the rest behind.
 */
object Moodboard {
    data class Kept(val songs: List<Pair<String, String>>, val art: String?)

    val kept = MutableStateFlow<Map<String, Kept>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(Home.cache, "ink-moods.json")
    private var started = false

    private fun week() = java.time.LocalDate.now().toEpochDay() / 7

    /** Which week each mood was made in (a mood made this week isn't made again; last week's shows till it is). */
    private val madeIn = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val writing = kotlinx.coroutines.sync.Mutex()

    /**
     * Each mood's songs and cover, kept for the week. Three made at a time, right now's first, each kept on the disk as
     * it's made, and last week's shown till this week's comes (2026-11-09: one after another, and only kept once all
     * twelve were, the first look of a week took about 50 seconds, and leaving before the end kept nothing).
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val o = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (o != null) {
                kept.value = read(o)
                // a mood's own week, else the file's (one written before each mood said its own)
                InkMoods.ALL.forEach { m -> o.optJSONObject(m.key)?.let { madeIn[m.key] = it.optLong("week", o.optLong("week")) } }
            }
            val now = week()
            val todo = (InkMoods.now() + InkMoods.ALL).distinct().filter { madeIn[it.key] != now || it.key !in kept.value }
            if (todo.isEmpty()) return@launch
            val asking = kotlinx.coroutines.sync.Semaphore(3)
            coroutineScope {
                todo.forEach { m ->
                    launch {
                        asking.acquire()
                        try {
                            val songs = InkMoods.songs(m, LastFm.key, now)
                            if (songs.isEmpty()) return@launch
                            // its tile's cover: the first of its songs iTunes (or Deezer) knows, a moment between asks
                            val art = songs.take(4).firstNotNullOfOrNull { (t, a) -> Curation.cover(t, a, false).also { kotlinx.coroutines.delay(400) } }
                                ?: kept.value[m.key]?.art
                            writing.lock()
                            try {
                                madeIn[m.key] = now
                                kept.value = kept.value + (m.key to Kept(songs, art))
                                runCatching { Home.write(file, write(kept.value).toString()) }
                            } finally { writing.unlock() }
                        } finally { asking.release() }
                    }
                }
            }
        }
    }

    private fun read(o: JSONObject): Map<String, Kept> = InkMoods.ALL.mapNotNull { m ->
        val k = o.optJSONObject(m.key) ?: return@mapNotNull null
        val a = k.optJSONArray("songs") ?: return@mapNotNull null
        m.key to Kept((0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }, k.optString("art").ifEmpty { null })
    }.toMap()

    private fun write(all: Map<String, Kept>) = JSONObject().put("week", week()).apply {
        all.forEach { (key, k) ->
            put(key, JSONObject().put("art", k.art ?: "").put("week", madeIn[key] ?: 0L)
                .put("songs", JSONArray(k.songs.map { JSONArray(listOf(it.first, it.second)) })))
        }
    }

    /** The mood as a station: its songs in a new order each time, the first few found at once, the rest after. */
    suspend fun play(m: InkMoods.Mood): Boolean {
        val songs = kept.value[m.key]?.songs?.shuffled() ?: withContext(Dispatchers.IO) { InkMoods.songs(m, LastFm.key, week()) }.shuffled()
        if (songs.isEmpty()) return false
        val first = find(songs.take(8))
        if (first.isEmpty()) return false
        if (!Player.radio.value) Player.radio(true)
        Player.play(first)
        scope.launch {
            songs.drop(8).take(40).chunked(8).forEach { group -> find(group).takeIf { it.isNotEmpty() }?.let { more -> withContext(Dispatchers.Main) { Player.follow(Player.queue.value.lastOrNull() ?: return@withContext, more) } } }
        }
        return true
    }

    private suspend fun find(group: List<Pair<String, String>>): List<Song> = withContext(Dispatchers.IO) {
        coroutineScope { group.map { (t, a) -> async { YouTube.find(t, a) } }.awaitAll().filterNotNull() }
    }
}

/** Right now: three of ink's moods for the hour and the day, big, each a station; then all of them as tiles. */
@Composable
fun InkMoodsShelf() {
    LaunchedEffect(Unit) { Moodboard.start() }
    val kept by Moodboard.kept.collectAsState()
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    fun go(m: InkMoods.Mood) = scope.launch { said = "${m.name}: finding its songs"; said = if (Moodboard.play(m)) null else "${m.name}: couldn't find its songs" }
    // one row of tiles (2026-10-29: the three as words and the rest as tiles didn't belong together): right
    // now's three bigger, then the rest
    Column {
        Section("RIGHT NOW", said ?: "moods for ${Moods.said()}, then the rest")
        val now = remember { InkMoods.now() }
        Shelf(verticalAlignment = Alignment.Bottom) {
            items(now, key = { "now" + it.key }) { m -> MoodTile(m, kept[m.key]?.art, 250.dp) { go(m) } }
            items(InkMoods.ALL.filter { it !in now }, key = { it.key }) { m -> MoodTile(m, kept[m.key]?.art) { go(m) } }
        }
    }
}

/** A mood as a tile: a cover of its songs in the dither, its name big on it, its line under it. */
@Composable
fun MoodTile(m: InkMoods.Mood, art: String?, side: androidx.compose.ui.unit.Dp = 170.dp, onClick: () -> Unit) {
    Column(Modifier.width(side).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.BottomStart) {
            if (art != null) Covered(remember(art) { faceSong("mood " + m.key, art, photo = true) }, Modifier.fillMaxSize(), px = 340)
            // its name on a black tab in the corner (a sleeve's label: pale dithers swallowed it)
            BasicText(m.name.uppercase(), style = headline(if (side > 200.dp) 30.sp else 22.sp), maxLines = 2,
                modifier = Modifier.background(Ink.black).padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        BasicText(m.line, style = pixel(12.sp, Ink.mid), maxLines = 2)
    }
}

/** ink's festivals here: the file read (it comes with the app), and a festival's station (each artist's best known song in turn). */
object Fests {
    val all: List<Festivals.Festival> by lazy {
        Festivals.read(runCatching { Fests::class.java.getResourceAsStream("/ink-festivals.json")!!.bufferedReader().use { it.readText() } }.getOrDefault("{}"))
    }

    suspend fun play(f: Festivals.Festival): Boolean {
        val songs = withContext(Dispatchers.IO) {
            coroutineScope {
                f.artists.take(16).map { name ->
                    async { kotlinx.coroutines.withTimeoutOrNull(10_000) { Artists.of(name, "").firstOrNull { it != null } }?.top?.filter { it.tube }?.take(2).orEmpty() }
                }.awaitAll()
            }.let { each -> (0 until 2).flatMap { i -> each.mapNotNull { it.getOrNull(i) } } }
        }
        if (songs.isEmpty()) return false
        if (!Player.radio.value) Player.radio(true)
        Player.play(songs)
        return true
    }
}

/**
 * Festivals soon: one row of posters, each a festival on now or within two months, set as a festival's poster is (its
 * name, when and where, the lineup as type, the first names biggest); a click opens the festival (2026-10-28:
 * a row of faces each took too much of the page).
 */
@Composable
fun FestivalsShelf(open: (Page) -> Unit) {
    val soon = remember { Festivals.soon(Fests.all) }
    if (soon.isEmpty()) return
    Column {
        Section("FESTIVALS SOON", "who's playing, to know before you go")
        Shelf(soon.size, 250.dp, gap = 16.dp) { each ->
            items(soon, key = { it.name }) { f -> FestivalPoster(f, Modifier.width(each)) { open(Page.Festival(f.name)) } }
        }
    }
}

/** A festival as its poster: when and where small, its name big, the lineup in type that shrinks down the bill, its dates at the foot. */
@Composable
fun FestivalPoster(f: Festivals.Festival, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.aspectRatio(.8f).border(1.dp, Ink.line).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(16.dp)) {
        BasicText("${f.when_()} · ${f.city}", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        BasicText(f.name.uppercase(), style = headline(26.sp).copy(lineHeight = 28.sp), maxLines = 2)
        Spacer(Modifier.height(14.dp))
        // the bill: the first two big, the next four smaller, the rest counted
        f.artists.take(2).forEach { BasicText(it.uppercase(), style = headline(17.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        Spacer(Modifier.height(4.dp))
        f.artists.drop(2).take(4).forEach { BasicText(it.uppercase(), style = headline(12.sp, Ink.mid), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        if (f.artists.size > 6) BasicText("and ${f.artists.size - 6} more", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.weight(1f))
        BasicText(dates(f), style = pixel(12.sp, Ink.mid))
    }
}

/** "nov 2 – 8", "dec 11 – 12", "nov 30 – dec 2". */
fun dates(f: Festivals.Festival): String {
    val m = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)
    val (a, b) = f.start.toJavaLocalDate() to f.end.toJavaLocalDate()
    return (if (a.month == b.month) "${m.format(a)} – ${b.dayOfMonth}" else "${m.format(a)} – ${m.format(b)}").lowercase()
}

/**
 * A festival's page, set as its poster is (2026-11-10, Bronson: "more fun and editorial, interactive, beautiful, maybe
 * artist images"): where and when small, its name huge, how long till it big beside it and PLAY THE LINEUP; the ones on
 * it you play, said; the headliners as big photo cards (who, how many listen, a line about them, play, their page); then
 * the bill in type that shrinks down it, names you play lit, each a click to their page, the one under the mouse shown
 * beside it (their photo, a line about them, play). The bill's names come in one after another.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun FestivalPage(name: String, open: (Page) -> Unit, onClose: () -> Unit) {
    val f = remember(name) { Fests.all.firstOrNull { it.name == name } } ?: return Close(onClose)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val heard by Listened.now.collectAsState()
    // the artists on it you've played
    val yours = remember(f, heard) { f.artists.filter { (heard.most("a:" + it.lowercase()) ?: 0.0) > 0 } }
    // top of the bill: two, three on a long one, all of a short one
    val heads = f.artists.take(when { f.artists.size <= 3 -> f.artists.size; f.artists.size >= 9 -> 3; else -> 2 })
    val bill = f.artists.drop(heads.size)
    // the bill's name under the mouse, shown beside it (the first till one is)
    var over by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        Column(Modifier.fillMaxSize().feather(scroll).verticalScroll(scroll).padding(start = SIDE, end = SIDE, top = 6.dp, bottom = 56.dp)) {
            // the poster's top: where and when, the name huge; how long till it, and the lineup to play
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 900.dp
                val big = if (wide) 96.sp else 64.sp
                @Composable
                fun Soon() = Column(horizontalAlignment = if (wide) Alignment.End else Alignment.Start) {
                    BasicText(f.when_().uppercase(), style = headline(44.sp))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (f.link.isNotBlank()) Word("the lineup, announced", pixel(13.sp, Ink.mid), touch = 8.dp) { browse(f.link) }
                        Word("PLAY THE LINEUP", headline(24.sp), touch = 8.dp) {
                            scope.launch { said = "finding their songs"; said = if (Fests.play(f)) null else "couldn't find their songs" }
                        }
                        // where to buy them, the festival's own site (the festivals file says where)
                        if (f.tickets.isNotBlank()) Word("TICKETS", headline(24.sp, Ink.mid), touch = 8.dp) { browse(f.tickets) }
                    }
                    said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp)) }
                }
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            BasicText("${f.city} · ${dates(f)} · ${f.artists.size} artists".lowercase(), style = pixel(13.sp, Ink.mid))
                            Spacer(Modifier.height(8.dp))
                            BasicText(f.name.uppercase(), style = headline(big).copy(lineHeight = big * .95f))
                        }
                        if (wide) { Spacer(Modifier.width(40.dp)); Soon() }
                    }
                    if (!wide) { Spacer(Modifier.height(18.dp)); Soon() }
                }
            }
            if (yours.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                BasicText(("you play ${if (yours.size == f.artists.size) "every one of them" else "${yours.size} of them"}: " +
                    yours.take(5).joinToString(", ") + if (yours.size > 5) " and more" else "").lowercase(), style = pixel(14.sp))
            }
            // the headliners, big
            Spacer(Modifier.height(40.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
            Spacer(Modifier.height(28.dp))
            // side by side where each has room for its photo and its name whole; one under another where not (half a
            // screen: three across broke their names mid-word, 2026-11-10)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val each = (maxWidth - 36.dp * (heads.size - 1).coerceAtLeast(0)) / heads.size.coerceAtLeast(1)
                if (each >= 460.dp) Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    heads.forEach { a -> Headliner(a, a in yours, open, Modifier.weight(1f)) }
                } else Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    heads.forEach { a -> Headliner(a, a in yours, open, Modifier.fillMaxWidth()) }
                }
            }
            // the bill, set as a poster's: biggest first, shrinking down it
            if (bill.isNotEmpty()) {
                Spacer(Modifier.height(48.dp))
                BasicText("THE BILL", style = headline(18.sp, Ink.mid))
                Spacer(Modifier.height(16.dp))
                // the one under the mouse right beside the names where there's room; on a narrow page the names alone, each
                // to their page. Kept together (2026-11-10, Bronson: "feels too far away, too much black"): the names in a
                // column no wider than a poster's, in bigger type, the photo at its side, not across the page
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val beside = maxWidth >= 760.dp
                    Row(verticalAlignment = Alignment.Top) {
                        FlowRow(Modifier.weight(1f, fill = false).widthIn(max = 780.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.Bottom) {
                            bill.forEachIndexed { i, a ->
                                val size = when { i < 4 -> 52.sp; i < 10 -> 36.sp; else -> 24.sp }
                                BillName(a, size, i, a in yours, { over = a }) { open(Page.Artist(a)) }
                            }
                        }
                        if (beside) {
                            Spacer(Modifier.width(36.dp))
                            WhoIs(over ?: bill.first(), open, Modifier.width(260.dp))
                        }
                    }
                }
            }
        }
    }
}

/** An artist's page read from YouTube Music (kept): their photo, how many listen, what's said of them, their top songs. */
@Composable
private fun rememberArtist(name: String): ArtistInfo? =
    androidx.compose.runtime.produceState<ArtistInfo?>(null, name) { Artists.of(name, "").collect { if (it != null) value = it } }.value

/**
 * Their photo as it is, no dither (2026-11-10, Bronson), developing in from black as covers do ([CoverOf]); black while
 * they're looked up, their name as a cover where they've no photo.
 */
@Composable
private fun ArtistPicture(name: String, info: ArtistInfo?, side: androidx.compose.ui.unit.Dp) {
    when {
        info == null -> Box(Modifier.fillMaxSize().background(Ink.black))
        info.photo != null -> CoverOf(remember(info.photo) { faceSong(name, info.photo, photo = true) }, name, Modifier.fillMaxSize(), px = 600)
        else -> NameCover(name, side)
    }
}

/** A headliner, big: their photo, their name, how many listen, a line about them; play them, their page. */
@Composable
private fun Headliner(name: String, played: Boolean, open: (Page) -> Unit, modifier: Modifier) {
    val info = rememberArtist(name)
    val page = { open(Page.Artist(info?.name ?: name, info?.id ?: "")) }
    BoxWithConstraints(modifier) {
        val side = minOf(maxWidth * .48f, 300.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(side).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = page))) { ArtistPicture(name, info, side) }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                if (played) BasicText("you play them", style = pixel(12.sp))
                WholeWords(name.uppercase(), headline(36.sp).copy(lineHeight = 36.sp), 3)
                info?.audience?.takeIf { it.isNotEmpty() }?.let { BasicText(it.lowercase().replace("monthly audience", "a month"), style = pixel(12.sp, Ink.mid)) }
                info?.let { i -> remember(i.about) { Artists.pullQuote(i.about).first } }?.let {
                    Spacer(Modifier.height(10.dp))
                    BasicText(it.lowercase(), style = pixel(13.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 20.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    info?.top?.takeIf { it.isNotEmpty() }?.let { top -> Word("play", pixel(14.sp), touch = 8.dp) { Player.play(top) } }
                    Word("their page", pixel(14.sp, Ink.mid), touch = 8.dp, onClick = page)
                }
            }
        }
    }
}

/** A name on the bill: lit if you play them or it's under the mouse (and shown beside the bill then); rising in, in turn. */
@Composable
private fun BillName(name: String, size: androidx.compose.ui.unit.TextUnit, order: Int, yours: Boolean, onOver: () -> Unit, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    LaunchedEffect(over) { if (over) onOver() }
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(order * 35L); shown.animateTo(1f, androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo)) }
    val c by androidx.compose.animation.animateColorAsState(if (over || yours) Ink.ink else Ink.mid, androidx.compose.animation.core.tween(Motion.FADE), label = "bill")
    BasicText(name.uppercase(), style = headline(size, c).copy(lineHeight = size),
        modifier = Modifier.graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * Motion.drift.toPx() * .6f }
            .hoverable(src).pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
            .clickable(src, indication = null, onClick = felt(then = onClick)))
}

/** Beside the bill: the name under the mouse, their photo, a line about them, play, their page; one giving way to the next. */
@Composable
private fun WhoIs(name: String, open: (Page) -> Unit, modifier: Modifier) {
    androidx.compose.animation.Crossfade(name, modifier, animationSpec = androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo), label = "who") { n ->
        val info = rememberArtist(n)
        val page = { open(Page.Artist(info?.name ?: n, info?.id ?: "")) }
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = page))) {
                ArtistPicture(n, info, 300.dp)
            }
            Spacer(Modifier.height(12.dp))
            BasicText(n.uppercase(), style = headline(24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            info?.audience?.takeIf { it.isNotEmpty() }?.let { BasicText(it.lowercase().replace("monthly audience", "a month"), style = pixel(12.sp, Ink.mid)) }
            info?.let { i -> remember(i.about) { Artists.pullQuote(i.about).first } }?.let {
                Spacer(Modifier.height(8.dp))
                BasicText(it.lowercase(), style = pixel(13.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 20.sp), maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                info?.top?.takeIf { it.isNotEmpty() }?.let { top -> Word("play", pixel(14.sp), touch = 8.dp) { Player.play(top) } }
                Word("their page", pixel(14.sp, Ink.mid), touch = 8.dp, onClick = page)
            }
        }
    }
}
