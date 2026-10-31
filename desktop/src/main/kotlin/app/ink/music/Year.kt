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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Your year, any day of it (the phone's Year): what this computer has heard since the year began (or since ink music
 * started keeping it), summed: the minutes, the songs, artists and records heard most, the hours you listen at; and
 * from your server, the records played most there, ever (every app's plays, so it's never empty).
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
    val listens: List<History.Heard> = emptyList(),
) {
    companion object {
        fun of(all: List<History.Heard>, year: Int = Calendar.getInstance().get(Calendar.YEAR)): Year {
            val start = Calendar.getInstance().apply { clear(); set(year, Calendar.JANUARY, 1) }.timeInMillis
            val end = Calendar.getInstance().apply { clear(); set(year + 1, Calendar.JANUARY, 1) }.timeInMillis
            val these = all.filter { it.at in start until end }
            fun who(h: History.Heard) = h.artist.substringBefore(", ").ifEmpty { "unknown" }
            // the same song from two places (the computer, the server) is one song
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
    id = id, path = "", title = title, artist = artist, album = album, albumId = record.ifEmpty { "h$id" },
    albumArtist = artist, duration = 0, disc = 1, track = 0, year = 0,
)

/** What a year's listening sounded like: shares of the minutes, and the format heard most. */
private class Sound(val hiRes: Float, val lossless: Float, val lossy: Float, val top: String)

private fun soundOf(listens: List<History.Heard>): Sound? {
    var hi = 0L; var ll = 0L; var ly = 0L
    val words = HashMap<String, Long>()
    for (h in listens) {
        val s = Library.song(h.id)
        when {
            s != null && s.format in Heard.LOSSLESS -> { if (s.hiRes) hi += h.ms else ll += h.ms; s.quality.takeIf { it.isNotEmpty() }?.let { words[it] = (words[it] ?: 0) + h.ms } }
            s != null && s.format.isNotEmpty() -> { val w = if (s.tube) "youtube's streams" else s.format; ly += h.ms; words[w] = (words[w] ?: 0) + h.ms }
            h.id.startsWith("y") -> { ly += h.ms; words["youtube's streams"] = (words["youtube's streams"] ?: 0) + h.ms }
        }
    }
    val all = (hi + ll + ly).toFloat().takeIf { it > 0 } ?: return null
    return Sound(hi / all, ll / all, ly / all, words.maxByOrNull { it.value }?.key.orEmpty())
}

/** A section's rows arriving one after another as the page opens. */
@Composable
private fun Modifier.coming(i: Int): Modifier {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(60L * i); t.animateTo(1f, tween(Motion.ENTER, easing = Motion.expo)) }
    return this.graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 8.dp.toPx() }
}

@Composable
private fun Head(title: String) = BasicText(title, style = headline(22.sp, Ink.mid))

/**
 * Your year on one page (the phone's seven, laid out across a wide window): the year over its line and the minutes;
 * your artist; your songs and records side by side; when you listen and how it sounded side by side; your server's
 * all time.
 */
@Composable
fun YearPage(open: (Page) -> Unit, onClose: () -> Unit) {
    val year by produceState<Year?>(null) { value = withContext(Dispatchers.IO) { Year.of(History.read()) } }
    val ever by produceState<List<Server.Played>>(emptyList()) { value = Server.mostPlayed(6) }
    val y = year
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        if (y == null) return@Column
        val some = y.minutes > 0
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = SIDE, end = SIDE, top = 8.dp, bottom = 48.dp)) {
            Opening(y, some)
            if (some) {
                Spacer(Modifier.height(64.dp))
                TopArtist(y, open)
                Spacer(Modifier.height(64.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                    TopSongs(y, Modifier.weight(1f))
                    Records("YOUR RECORDS", y.records.map { (h, n) -> h.song() to n }, "", open, Modifier.weight(1f))
                }
                Spacer(Modifier.height(64.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                    Hours(y, Modifier.weight(1f))
                    Sounded(y, Modifier.weight(1f))
                }
            }
            if (ever.isNotEmpty()) {
                Spacer(Modifier.height(64.dp))
                Records("ALL TIME ON YOUR SERVER", ever.map { it.face to it.plays }, ", every app", open, Modifier.fillMaxWidth(.5f))
            }
        }
    }
}

/** The year big over its line (how far it's gone, in your top record's colour); the minutes counting up beside. */
@Composable
private fun Opening(y: Year, some: Boolean) {
    val top = y.records.firstOrNull()?.first?.song()
    val accent = rememberCover(top, 320)?.accent ?: Ink.ink
    val now = remember { Calendar.getInstance() }
    val gone = (now.get(Calendar.DAY_OF_YEAR) - 1f) / now.getActualMaximum(Calendar.DAY_OF_YEAR)
    val computer = if (Home.mac) "mac" else "computer"
    Head("YOUR YEAR")
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.width(560.dp)) {
            BasicText(y.year.toString(), style = headline(180.sp).copy(lineHeight = 180.sp), maxLines = 1)
            val line = remember { Animatable(0f) }
            LaunchedEffect(Unit) { line.animateTo(gone, tween(1_200, easing = Motion.expo)) }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.line)) { Box(Modifier.fillMaxWidth(line.value).fillMaxHeight().background(accent)) }
            BasicText("${Math.round(gone * 100)}% of the year gone", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.width(64.dp))
        if (some) Column(Modifier.padding(bottom = 26.dp)) {
            val counted = remember { Animatable(0f) }
            LaunchedEffect(Unit) { counted.animateTo(y.minutes.toFloat(), tween(1_400, easing = Motion.expo)) }
            BasicText("%,d".format(counted.value.toLong()), style = headline(96.sp).copy(lineHeight = 96.sp))
            BasicText("minutes", style = pixel(14.sp, Ink.mid))
            Spacer(Modifier.height(14.dp))
            BasicText("${y.heard} songs   ${y.artistCount} artists", style = pixel(13.sp), modifier = Modifier.coming(1))
            val first = Calendar.getInstance().apply { timeInMillis = y.since }
            val span = ((now.timeInMillis - y.since) / 86_400_000L + 1).toInt().coerceAtLeast(y.days)
            val since = if (first.get(Calendar.DAY_OF_YEAR) > 7) " since " + java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault()).format(first.time).lowercase() else " this year"
            BasicText("listened on ${y.days} of $span days$since", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp).coming(2))
        } else Column(Modifier.padding(bottom = 26.dp)) {
            BasicText("STARTS NOW", style = headline(44.sp))
            Spacer(Modifier.height(12.dp))
            BasicText("ink music keeps what you play on this $computer, from today: come back in a while and your year is here. it stays on the $computer.",
                style = pixel(12.sp, Ink.mid))
        }
    }
}

/** Your artist: their photo in the dither, their name running across the page in the giant marquee, the next four beside. */
@Composable
private fun TopArtist(y: Year, open: (Page) -> Unit) {
    val (name, n) = y.artists.firstOrNull() ?: return
    val info by produceState<ArtistInfo?>(null, name) { Artists.of(name).collect { if (it != null) value = it } }
    val face = info?.photo?.let { remember(it) { faceSong(name, it, photo = true) } }
        ?: y.records.firstOrNull { it.first.artist.substringBefore(", ").equals(name, true) }?.first?.song()
    Head("YOUR ARTIST")
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.size(360.dp).clickable(remember { MutableInteractionSource() }, indication = null) { open(Page.Artist(name)) }) {
            if (face != null) Dithered(face, Modifier.fillMaxSize(), px = 720)
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            BasicText("$n plays, more than anyone", style = pixel(12.sp, Ink.mid), modifier = Modifier.coming(0))
            Spacer(Modifier.height(14.dp))
            y.artists.drop(1).forEachIndexed { i, (a, c) ->
                Row(Modifier.padding(vertical = 6.dp).coming(i + 1), verticalAlignment = Alignment.Bottom) {
                    BasicText("${i + 2}", style = pixel(12.sp, Ink.mid), modifier = Modifier.width(30.dp).padding(bottom = 4.dp))
                    Word(a.uppercase(), headline(28.sp), Modifier.weight(1f, fill = false)) { open(Page.Artist(a)) }
                    BasicText("$c", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                }
            }
        }
    }
    Marquee(name, true, Modifier.fillMaxWidth(), size = 120.sp)
}

/** Your songs: the first's cover big and its name huge; the next four with covers small; a click plays one. */
@Composable
private fun TopSongs(y: Year, modifier: Modifier) {
    val (first, n) = y.songs.firstOrNull() ?: return
    val all = remember(y) { y.songs.map { it.first.song() } }
    Column(modifier) {
        Head("YOUR SONGS")
        Spacer(Modifier.height(16.dp))
        Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { Player.play(all, 0) }) {
            CoverOf(all[0], all[0].album.ifEmpty { first.title }, Modifier.size(260.dp), px = 640)
            Spacer(Modifier.height(12.dp))
            BasicText(first.title.uppercase(), style = headline(34.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText("${first.artist.lowercase()}   $n ${if (n == 1) "play" else "plays"}", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(18.dp))
        y.songs.drop(1).forEachIndexed { i, (h, c) ->
            val s = all[i + 1]
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).coming(i).clickable(remember { MutableInteractionSource() }, indication = null) { Player.play(all, i + 1) },
                verticalAlignment = Alignment.CenterVertically) {
                CoverOf(s, s.album.ifEmpty { h.title }, Modifier.size(44.dp), px = 160)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(h.title.uppercase(), style = headline(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(h.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicText("$c", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Records with their covers: the first big, the next four two by two beside it; a click opens one of yours. */
@Composable
private fun Records(title: String, rows: List<Pair<Song, Int>>, said: String, open: (Page) -> Unit, modifier: Modifier) {   // said: after "n plays"
    fun Modifier.opens(s: Song) = if (Library.albums.value.any { it.id == s.albumId }) clickable(MutableInteractionSource(), indication = null) { open(Page.Record(s.albumId)) } else this
    Column(modifier) {
        Head(title)
        Spacer(Modifier.height(16.dp))
        rows.firstOrNull()?.let { (s, n) ->
            Column(Modifier.opens(s)) {
                CoverOf(s, s.album, Modifier.size(260.dp), px = 640)
                Spacer(Modifier.height(12.dp))
                BasicText(s.album.uppercase(), style = headline(30.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText("${s.albumArtist.lowercase()}   $n ${if (n == 1) "play" else "plays"}$said", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(18.dp))
        rows.drop(1).take(4).chunked(2).forEachIndexed { r, pair ->
            Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEachIndexed { c, (s, n) ->
                    Column(Modifier.width(150.dp).coming(r * 2 + c).opens(s)) {
                        CoverOf(s, s.album, Modifier.size(150.dp), px = 320)
                        BasicText(s.album.uppercase(), style = headline(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        BasicText("$n ${if (n == 1) "play" else "plays"}", style = pixel(12.sp, Ink.mid))
                    }
                }
            }
        }
    }
}

private fun listener(hours: List<Long>): String {
    fun sum(vararg h: Int) = h.sumOf { hours[it] }
    return listOf(
        "MORNINGS, MOSTLY" to sum(5, 6, 7, 8, 9, 10, 11),
        "AFTERNOONS, MOSTLY" to sum(12, 13, 14, 15, 16),
        "EVENINGS, MOSTLY" to sum(17, 18, 19, 20, 21),
        "A NIGHT LISTENER" to sum(22, 23, 0, 1, 2, 3, 4),
    ).maxBy { it.second }.first
}

private fun hourSaid(h: Int) = when (h) { 0 -> "midnight"; 12 -> "noon"; in 1..11 -> "$h am"; else -> "${h - 12} pm" }

/** When you listen: a bar of pixels each hour, as tall as its minutes (the most in your top song's colour), growing up. */
@Composable
private fun Hours(y: Year, modifier: Modifier) {
    val accent = rememberCover(y.songs.firstOrNull()?.first?.song(), 320)?.accent ?: Ink.ink
    val most = y.hours.maxOrNull()?.coerceAtLeast(1) ?: 1
    val peak = y.hours.indexOf(most)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(300); grow.animateTo(1f, tween(900, easing = Motion.expo)) }
    Column(modifier) {
        Head("WHEN YOU LISTEN")
        Spacer(Modifier.height(16.dp))
        BasicText(listener(y.hours), style = headline(48.sp).copy(lineHeight = 48.sp))
        BasicText("most at ${hourSaid(peak)}", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(28.dp))
        val cells = 12
        Row(Modifier.fillMaxWidth().height(240.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            y.hours.forEachIndexed { h, m ->
                val lit = Math.round(m.toFloat() / most * cells * grow.value).let { if (m > 0) it.coerceAtLeast(1) else 0 }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                    repeat(lit) { Box(Modifier.fillMaxWidth().aspectRatio(1f).background(if (h == peak) accent else Ink.mid)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("12 am", "6 am", "noon", "6 pm").forEach { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f)) }
        }
    }
}

/** How it sounded: the lossless share big, a line in three parts (hi-res, lossless, lossy), the format heard most. */
@Composable
private fun Sounded(y: Year, modifier: Modifier) {
    val sound = remember(y) { soundOf(y.listens) }
    val accent = rememberCover(y.records.firstOrNull()?.first?.song(), 320)?.accent ?: Ink.ink
    Column(modifier) {
        Head("HOW IT SOUNDED")
        Spacer(Modifier.height(16.dp))
        if (sound == null) { BasicText("nothing heard that ink music knows the format of", style = pixel(12.sp, Ink.mid)); return@Column }
        val lossless = sound.hiRes + sound.lossless
        val counted = remember { Animatable(0f) }
        LaunchedEffect(Unit) { counted.animateTo(lossless * 100, tween(1_200, easing = Motion.expo)) }
        BasicText("${counted.value.toInt()}%", style = headline(120.sp).copy(lineHeight = 120.sp))
        BasicText("of it lossless", style = pixel(14.sp, Ink.mid))
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            if (sound.hiRes > 0) Box(Modifier.weight(sound.hiRes).fillMaxHeight().background(accent))
            if (sound.lossless > 0) Box(Modifier.weight(sound.lossless).fillMaxHeight().background(accent.copy(alpha = .55f)))
            if (sound.lossy > 0) Box(Modifier.weight(sound.lossy).fillMaxHeight().background(Ink.dim))
        }
        Spacer(Modifier.height(18.dp))
        @Composable fun part(i: Int, color: Color, word: String, share: Float, said: String) = Row(Modifier.padding(vertical = 5.dp).coming(i), verticalAlignment = Alignment.CenterVertically) {
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
            BasicText("HEARD MOST AS", style = headline(16.sp, Ink.mid))
            BasicText(sound.top.uppercase(), style = headline(34.sp))
        }
    }
}
