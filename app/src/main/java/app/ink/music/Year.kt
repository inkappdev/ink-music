package app.ink.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Your year, any day of it: what this phone has heard since the year began (or since ink music
 * started keeping it), summed: the minutes, the songs, artists and records heard most, the hours you
 * listen at; and from your server, the records played most there, ever (every app's plays).
 */
data class Year(
    val year: Int,
    val since: Long,                          // the first heard this year (wall clock ms), 0 if none
    val minutes: Long,
    val songs: List<Pair<History.Heard, Int>>, // the most heard, with how often
    val artists: List<Pair<String, Int>>,
    val records: List<Pair<History.Heard, Int>>, // a song off each record (for its cover), with the record's plays
    val heard: Int,                           // how many songs, how many artists
    val artistCount: Int,
    val hours: List<Long> = List(24) { 0L },  // minutes heard starting in each hour of the day
    val days: Int = 0,                        // the days anything was heard
    val listens: List<History.Heard> = emptyList(),   // this year's, for what it sounded like
) {
    companion object {
        fun of(all: List<History.Heard>, year: Int = Calendar.getInstance().get(Calendar.YEAR)): Year {
            val start = Calendar.getInstance().apply { clear(); set(year, Calendar.JANUARY, 1) }.timeInMillis
            val end = Calendar.getInstance().apply { clear(); set(year + 1, Calendar.JANUARY, 1) }.timeInMillis
            val these = all.filter { it.at in start until end }
            fun who(h: History.Heard) = h.artist.substringBefore(", ").ifEmpty { "unknown" }
            // the same song from two places (the phone, the server) is one song
            val bySong = these.groupBy { Radio.key(it.title, it.artist) }
            val byRecord = these.filter { it.album.isNotEmpty() }.groupBy { it.album.lowercase() + "/" + who(it).lowercase() }
            val byArtist = these.groupBy(::who)
            val cal = Calendar.getInstance()
            val hours = LongArray(24)
            val days = HashSet<Int>()
            these.forEach { h -> cal.timeInMillis = h.at; hours[cal.get(Calendar.HOUR_OF_DAY)] += h.ms / 60_000; days += cal.get(Calendar.DAY_OF_YEAR) }
            return Year(
                year = year,
                since = these.minOfOrNull { it.at } ?: 0L,
                minutes = these.sumOf { it.ms } / 60_000,
                songs = bySong.values.map { it.last() to it.size }.sortedByDescending { it.second }.take(5),
                artists = byArtist.map { (k, v) -> k to v.size }.filter { it.first != "unknown" }.sortedByDescending { it.second }.take(5),
                records = byRecord.values.map { it.last() to it.size }.sortedByDescending { it.second }.take(5),
                heard = bySong.size,
                artistCount = byArtist.size,
                hours = hours.toList(),
                days = days.size,
                listens = these,
            )
        }
    }
}

/** A heard song as a song (for its cover): the library's, else enough of one to name it. */
private fun History.Heard.song(): Song = Library.song(id) ?: Song(
    id = id, uri = android.net.Uri.EMPTY, title = title, artist = artist, album = album, albumId = record.ifEmpty { "h$id" },
    albumArtist = artist, duration = 0, disc = 1, track = 0, year = 0,
)

/** What a year's listening sounded like: shares of the minutes, and the format heard most. */
private class Sound(val hiRes: Float, val lossless: Float, val lossy: Float, val top: String)

/**
 * Each listen's format, as the library knows the song (your files', your server's) or as YouTube's streams are (lossy);
 * a song no longer known is left out. Shares are of the minutes heard.
 */
private fun soundOf(listens: List<History.Heard>): Sound? {
    var hi = 0L; var ll = 0L; var ly = 0L
    val words = HashMap<String, Long>()
    for (h in listens) {
        val s = Library.song(h.id)
        when {
            s != null && s.format in Heard.LOSSLESS -> { if (s.hiRes) hi += h.ms else ll += h.ms; s.quality.takeIf { it.isNotEmpty() }?.let { words[it] = (words[it] ?: 0) + h.ms } }
            // a YouTube song the library knows says "youtube" for its format: its streams
            s != null && s.format.isNotEmpty() -> { val w = if (s.tube) "youtube's streams" else s.format; ly += h.ms; words[w] = (words[w] ?: 0) + h.ms }
            h.id.startsWith("y") -> { ly += h.ms; words["youtube's streams"] = (words["youtube's streams"] ?: 0) + h.ms }
        }
    }
    val all = (hi + ll + ly).toFloat().takeIf { it > 0 } ?: return null
    return Sound(hi / all, ll / all, ly / all, words.maxByOrNull { it.value }?.key.orEmpty())
}

/** Rows of a page arriving one after another as it's swiped to (once it's the page shown). */
@Composable
private fun Modifier.coming(i: Int, shown: Boolean): Modifier {
    if (Moves.less.collectAsState().value) return this
    return this.arrive(i, shown)
}

/**
 * Your year's pages, one a screen, swiped up through: the year and its minutes; your artist; your songs; your
 * records; when you listen; how it sounded; your server's all time. "share" makes the year a poster.
 */
@Composable
fun YearPage(pages: Pages) {
    val ctx = LocalContext.current
    val year by produceState<Year?>(null) { value = withContext(Dispatchers.IO) { Year.of(History.read(ctx)) } }
    val ever by produceState<List<Server.Played>>(emptyList()) { value = Server.mostPlayed(6) }
    val scope = rememberCoroutineScope()
    val y = year
    val some = y != null && y.minutes > 0
    val count = (if (some) 6 else 1) + if (ever.isNotEmpty()) 1 else 0
    val pager = rememberPagerState { count }
    Column(Modifier.fillMaxSize().background(Ink.black).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            BasicText("${pager.currentPage + 1} of $count", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(vertical = 10.dp))
            Spacer(Modifier.weight(1f))
            if (some) Word("share", pixel(12.sp, Ink.mid), touch = 10.dp) { scope.launch { ShareCard.shareYear(ctx, y!!) } }
            CloseArrow { pages.close() }
        }
        if (y == null) return@Column
        VerticalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            val shown = pager.currentPage == i
            // clear of the mini player along the foot
            Box(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = LocalFoot.current + 12.dp)) {
                when {
                    i == 0 -> Opening(y, some, more = count > 1, shown)
                    some && i == 1 -> TopArtist(y, shown)
                    some && i == 2 -> TopSongs(y, shown)
                    some && i == 3 -> Records("YOUR RECORDS", y.records.map { (h, n) -> h.song() to n }, "", shown)
                    some && i == 4 -> Hours(y, shown)
                    some && i == 5 -> Sounded(y, shown)
                    else -> Records("ALL TIME ON YOUR SERVER", ever.map { it.face to it.plays }, ", every app", shown)
                }
            }
        }
    }
}

/** A page's head: what it is, small and dim, as every page of the year starts. */
@Composable
private fun Head(title: String) = BasicText(title, style = headline(22.sp, Ink.mid))

/**
 * The year edge to edge, the year's line under it (how far it's gone, filling in the colour of your top record), the
 * minutes counting up to what they are, then the songs, artists and days.
 */
@Composable
private fun Opening(y: Year, some: Boolean, more: Boolean, shown: Boolean) {
    val top = y.records.firstOrNull()?.first?.song()
    val accent = rememberCover(top, 320)?.accent ?: Ink.ink
    val now = remember { Calendar.getInstance() }
    val gone = (now.get(Calendar.DAY_OF_YEAR) - 1f) / now.getActualMaximum(Calendar.DAY_OF_YEAR)
    Column(Modifier.fillMaxSize()) {
        Head("YOUR YEAR")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val size = with(LocalDensity.current) { (maxWidth * .33f).toSp() }
            BasicText(y.year.toString(), style = headline(size).copy(lineHeight = size), maxLines = 1)
        }
        // the year's line: as far as it's gone
        val line = remember { Animatable(0f) }
        LaunchedEffect(shown) { if (shown) { line.snapTo(0f); line.animateTo(gone, tween(1_200, easing = Motion.expo)) } }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.line)) { Box(Modifier.fillMaxWidth(line.value).fillMaxHeight().background(accent)) }
        BasicText("${Math.round(gone * 100)}% of the year gone", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(36.dp))
        if (some) {
            // the minutes counting up as the page comes
            val counted = remember { Animatable(0f) }
            LaunchedEffect(shown) { if (shown) { counted.snapTo(0f); counted.animateTo(y.minutes.toFloat(), tween(1_400, easing = Motion.expo)) } }
            BasicText("%,d".format(counted.value.toLong()), style = headline(88.sp), modifier = Modifier.coming(0, shown))
            BasicText("minutes", style = pixel(14.sp, Ink.mid), modifier = Modifier.coming(1, shown))
            Spacer(Modifier.height(20.dp))
            BasicText("${y.heard} songs   ${y.artistCount} artists", style = pixel(13.sp), modifier = Modifier.coming(2, shown))
            // the days you listened, of the days since you began (this year, or since ink music began keeping it)
            val first = Calendar.getInstance().apply { timeInMillis = y.since }
            val span = ((now.timeInMillis - y.since) / 86_400_000L + 1).toInt().coerceAtLeast(y.days)
            val since = if (first.get(Calendar.DAY_OF_YEAR) > 7) " since " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(first.time).lowercase() else " this year"
            BasicText("listened on ${y.days} of $span days$since", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp).coming(3, shown))
        } else {
            BasicText("STARTS NOW", style = headline(44.sp))
            Spacer(Modifier.height(12.dp))
            BasicText("ink music keeps what you play on this phone, from today: come back in a while and your year is here. it stays on the phone.",
                style = pixel(12.sp, Ink.mid))
        }
        Spacer(Modifier.weight(1f))
        if (more) BasicText("swipe up", style = pixel(12.sp, Ink.mid))
    }
}

/**
 * Your artist: their photo (YouTube Music's) across the page in the dither, their name running across it in the giant
 * marquee; the next four under it, big words.
 */
@Composable
private fun TopArtist(y: Year, shown: Boolean) {
    val (name, n) = y.artists.firstOrNull() ?: return
    val info by produceState<ArtistInfo?>(null, name) { YouTubeAccount.artist(name).collect { if (it != null) value = it } }
    // no photo: their record you played most, in the same dither
    val face = info?.photo?.let { remember(it) { photoSong(name, it) } }
        ?: y.records.firstOrNull { it.first.artist.substringBefore(", ").equals(name, true) }?.first?.song()
    Column(Modifier.fillMaxSize()) {
        Head("YOUR ARTIST")
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // room left for the name and the four under it
            val side = minOf(maxWidth, maxHeight - 290.dp, 380.dp).coerceAtLeast(120.dp)
            Box(Modifier.size(side)) {
                if (face != null) Dithered(face, Modifier.fillMaxSize(), px = 720)
            }
        }
        // the name across the whole width, past the page's margins, moving
        Marquee(name, shown, Modifier.requiredWidth(LocalContext.current.resources.displayMetrics.let { (it.widthPixels / it.density).dp }), size = 96.sp)
        BasicText("$n plays, more than anyone", style = pixel(12.sp, Ink.mid), modifier = Modifier.coming(0, shown))
        Spacer(Modifier.height(16.dp))
        y.artists.drop(1).forEachIndexed { i, (a, c) ->
            Row(Modifier.padding(vertical = 5.dp).coming(i + 1, shown), verticalAlignment = Alignment.Bottom) {
                BasicText("${i + 2}", style = pixel(12.sp, Ink.mid), modifier = Modifier.width(30.dp).padding(bottom = 4.dp))
                BasicText(a.uppercase(), style = headline(24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                BasicText("$c", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            }
        }
    }
}

/** The song as it plays: the library's, else one YouTube's that this phone knows (heard there, liked). */
@Composable
private fun playable(): (History.Heard) -> Song? {
    val history by YouTubeAccount.history.collectAsState()
    val liked by YouTubeAccount.liked.collectAsState()
    val known = remember(history, liked) { (history + liked).associateBy { it.id } }
    return { h -> Library.song(h.id) ?: known[h.id] }
}

/** Your songs: the first's cover big and its name huge; the next four with their covers small; a tap plays one. */
@Composable
private fun TopSongs(y: Year, shown: Boolean) {
    val find = playable()
    val rows = y.songs
    val (first, n) = rows.firstOrNull() ?: return
    Column(Modifier.fillMaxSize()) {
        Head("YOUR SONGS")
        Spacer(Modifier.height(14.dp))
        val hero = first.song()
        Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { find(first)?.let { Playback.play(listOf(it), 0) } })) {
            BoxWithConstraints(Modifier.fillMaxWidth()) { CoverOf(hero, hero.album.ifEmpty { first.title }, Modifier.size(minOf(maxWidth, 260.dp)), px = 640) }
            Spacer(Modifier.height(12.dp))
            BasicText(first.title.uppercase(), style = headline(34.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText("${first.artist.lowercase()}   $n ${if (n == 1) "play" else "plays"}", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(18.dp))
        rows.drop(1).forEachIndexed { i, (h, c) ->
            val s = h.song()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).coming(i, shown)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { find(h)?.let { Playback.play(listOf(it), 0) } }),
                verticalAlignment = Alignment.CenterVertically) {
                CoverOf(s, s.album.ifEmpty { h.title }, Modifier.size(44.dp), px = 160)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(h.title.uppercase(), style = headline(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(h.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicText("$c", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Records with their covers: the first big, the next four two by two under it, each with its plays. */
@Composable
private fun Records(title: String, rows: List<Pair<Song, Int>>, said: String, shown: Boolean) {   // said: after "n plays"
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // sized to the page: the big cover and the four under it fit without scrolling
        val hero = minOf(maxWidth, maxHeight * .32f, 300.dp)
        val tile = minOf((maxWidth - 12.dp) / 2, ((maxHeight - hero - 120.dp) / 2 - 38.dp).coerceAtLeast(60.dp))
        Column(Modifier.fillMaxSize()) {
            Head(title)
            Spacer(Modifier.height(14.dp))
            rows.firstOrNull()?.let { (s, n) ->
                CoverOf(s, s.album, Modifier.size(hero), px = 640)
                Spacer(Modifier.height(10.dp))
                BasicText(s.album.uppercase(), style = headline(26.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText("${s.albumArtist.lowercase()}   $n ${if (n == 1) "play" else "plays"}$said", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            rows.drop(1).take(4).chunked(2).forEachIndexed { r, pair ->
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { c, (s, n) ->
                        Column(Modifier.width(tile).coming(r * 2 + c, shown)) {
                            CoverOf(s, s.album, Modifier.size(tile), px = 320)
                            BasicText(s.album.uppercase(), style = headline(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                            BasicText("$n ${if (n == 1) "play" else "plays"}", style = pixel(12.sp, Ink.mid))
                        }
                    }
                }
            }
        }
    }
}

/** What a day's hours say of you: where most of the minutes fall. */
private fun listener(hours: List<Long>): String {
    fun sum(vararg h: Int) = h.sumOf { hours[it] }
    val parts = listOf(
        "MORNINGS, MOSTLY" to sum(5, 6, 7, 8, 9, 10, 11),
        "AFTERNOONS, MOSTLY" to sum(12, 13, 14, 15, 16),
        "EVENINGS, MOSTLY" to sum(17, 18, 19, 20, 21),
        "A NIGHT LISTENER" to sum(22, 23, 0, 1, 2, 3, 4),
    )
    return parts.maxBy { it.second }.first
}

private fun hourSaid(h: Int) = when (h) { 0 -> "midnight"; 12 -> "noon"; in 1..11 -> "$h am"; else -> "${h - 12} pm" }

/**
 * When you listen: a bar of pixels for each hour of the day, as tall as the minutes heard in it (the most, lit in the
 * colour of your top song), growing up as the page comes; what that makes you, said big.
 */
@Composable
private fun Hours(y: Year, shown: Boolean) {
    val top = y.songs.firstOrNull()?.first?.song()
    val accent = rememberCover(top, 320)?.accent ?: Ink.ink
    val most = y.hours.maxOrNull()?.coerceAtLeast(1) ?: 1
    val peak = y.hours.indexOf(most)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(shown) { if (shown) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = Motion.expo)) } }
    Column(Modifier.fillMaxSize()) {
        Head("WHEN YOU LISTEN")
        Spacer(Modifier.height(14.dp))
        BasicText(listener(y.hours), style = headline(44.sp).copy(lineHeight = 44.sp), modifier = Modifier.coming(0, shown))
        BasicText("most at ${hourSaid(peak)}", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp).coming(1, shown))
        Spacer(Modifier.weight(1f))
        // the bars: whole cells of pixels, a cell 1 in 12 of the most, from the foot up
        val cells = 12
        Row(Modifier.fillMaxWidth().height(220.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            y.hours.forEachIndexed { h, m ->
                val lit = Math.round(m.toFloat() / most * cells * grow.value).let { if (m > 0) it.coerceAtLeast(1) else 0 }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom)) {
                    repeat(lit) { Box(Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f).background(if (h == peak) accent else Ink.mid)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("12 am", "6 am", "noon", "6 pm").forEach { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * How it sounded: the share of your minutes heard lossless (hi-res and CD) and lossy, the lossless share big; a line
 * cut in three in those parts (in the colour of your top record, its brightness each part's); the format heard most.
 */
@Composable
private fun Sounded(y: Year, shown: Boolean) {
    val sound = remember(y) { soundOf(y.listens) }
    val top = y.records.firstOrNull()?.first?.song()
    val accent = rememberCover(top, 320)?.accent ?: Ink.ink
    Column(Modifier.fillMaxSize()) {
        Head("HOW IT SOUNDED")
        Spacer(Modifier.height(14.dp))
        if (sound == null) { BasicText("nothing heard that ink music knows the format of", style = pixel(12.sp, Ink.mid)); return@Column }
        val lossless = sound.hiRes + sound.lossless
        val counted = remember { Animatable(0f) }
        LaunchedEffect(shown) { if (shown) { counted.snapTo(0f); counted.animateTo(lossless * 100, tween(1_200, easing = Motion.expo)) } }
        BasicText("${counted.value.toInt()}%", style = headline(120.sp).copy(lineHeight = 120.sp))
        BasicText("of it lossless", style = pixel(14.sp, Ink.mid))
        Spacer(Modifier.height(28.dp))
        // the three parts, side by side along one line
        Row(Modifier.fillMaxWidth().height(10.dp).coming(0, shown)) {
            if (sound.hiRes > 0) Box(Modifier.weight(sound.hiRes).fillMaxHeight().background(accent))
            if (sound.lossless > 0) Box(Modifier.weight(sound.lossless).fillMaxHeight().background(accent.copy(alpha = .55f)))
            if (sound.lossy > 0) Box(Modifier.weight(sound.lossy).fillMaxHeight().background(Ink.dim))
        }
        Spacer(Modifier.height(18.dp))
        @Composable fun part(i: Int, color: Color, word: String, share: Float, said: String) = Row(Modifier.padding(vertical = 5.dp).coming(i, shown), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color))
            Spacer(Modifier.width(12.dp))
            BasicText(word, style = pixel(13.sp), modifier = Modifier.width(110.dp))
            BasicText("${Math.round(share * 100)}%", style = pixel(13.sp), modifier = Modifier.width(56.dp))
            BasicText(said, style = pixel(12.sp, Ink.mid))
        }
        part(1, accent, "hi-res", sound.hiRes, "past cd: more bits, or more khz")
        part(2, accent.copy(alpha = .55f), "lossless", sound.lossless, "cd quality, as recorded")
        part(3, Ink.dim, "lossy", sound.lossy, "made smaller: streams, mp3s")
        Spacer(Modifier.height(24.dp))
        if (sound.top.isNotEmpty()) {
            BasicText("HEARD MOST AS", style = headline(16.sp, Ink.mid), modifier = Modifier.coming(4, shown))
            BasicText(sound.top.uppercase(), style = headline(34.sp), modifier = Modifier.coming(5, shown))
        }
    }
}
