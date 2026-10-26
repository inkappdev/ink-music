package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Your year, any day of it: what this phone has heard since the year began (or since ink music
 * started keeping it), summed: the minutes, the songs, artists and records heard most; and from
 * your server, the records played most there, ever (every app's plays, so it's never empty).
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
            return Year(
                year = year,
                since = these.minOfOrNull { it.at } ?: 0L,
                minutes = these.sumOf { it.ms } / 60_000,
                songs = bySong.values.map { it.last() to it.size }.sortedByDescending { it.second }.take(5),
                artists = byArtist.map { (k, v) -> k to v.size }.filter { it.first != "unknown" }.sortedByDescending { it.second }.take(5),
                records = byRecord.values.map { it.last() to it.size }.sortedByDescending { it.second }.take(5),
                heard = bySong.size,
                artistCount = byArtist.size,
            )
        }
    }
}

/** A heard song as a song (for its cover): the library's, else enough of one to name it. */
private fun History.Heard.song(): Song = Library.song(id) ?: Song(
    id = id, uri = android.net.Uri.EMPTY, title = title, artist = artist, album = album, albumId = record.ifEmpty { "h$id" },
    albumArtist = artist, duration = 0, disc = 1, track = 0, year = 0,
)

/**
 * Your year's pages, one a screen, swiped up through: the minutes; the songs; the artists; the
 * records; your server's all time. "share" makes the year a poster.
 */
@Composable
fun YearPage(pages: Pages) {
    val ctx = LocalContext.current
    val year by produceState<Year?>(null) { value = withContext(Dispatchers.IO) { Year.of(History.read(ctx)) } }
    val ever by produceState<List<Server.Played>>(emptyList()) { value = Server.mostPlayed(6) }
    val scope = rememberCoroutineScope()
    val y = year
    val some = y != null && y.minutes > 0
    val count = (if (some) 4 else 1) + if (ever.isNotEmpty()) 1 else 0
    val pager = rememberPagerState { count }
    Column(Modifier.fillMaxSize().background(Ink.black).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            BasicText("${pager.currentPage + 1} of $count", style = pixel(11.sp, Ink.mid), modifier = Modifier.padding(vertical = 10.dp))
            Spacer(Modifier.weight(1f))
            if (some) Word("share", pixel(11.sp, Ink.mid), touch = 10.dp) { scope.launch { ShareCard.shareYear(ctx, y!!) } }
            Word("close", pixel(11.sp, Ink.mid), touch = 10.dp) { pages.back() }
        }
        if (y == null) return@Column
        VerticalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            // clear of the mini player along the foot
            Box(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = LocalFoot.current + 12.dp)) {
                when {
                    i == 0 -> Opening(y, some, more = count > 1)
                    some && i == 1 -> Ranked("TOP SONGS", y.songs.map { (h, n) -> Triple(h.title, h.artist, n) })
                    some && i == 2 -> Ranked("TOP ARTISTS", y.artists.map { (a, n) -> Triple(a, "", n) }, firstHuge = true)
                    some && i == 3 -> Records("TOP RECORDS", y.records.map { (h, n) -> h.song() to n }, "")
                    else -> Records("ALL TIME ON YOUR SERVER", ever.map { it.face to it.plays }, ", every app")
                }
            }
        }
    }
}

@Composable
private fun Opening(y: Year, some: Boolean, more: Boolean) {
    Column(Modifier.fillMaxSize()) {
        BasicText("YOUR YEAR", style = headline(28.sp, Ink.mid))
        BasicText(y.year.toString(), style = headline(120.sp))
        Spacer(Modifier.height(28.dp))
        if (some) {
            BasicText("%,d".format(y.minutes), style = headline(72.sp))
            BasicText("minutes", style = pixel(14.sp, Ink.mid))
            Spacer(Modifier.height(18.dp))
            BasicText("${y.heard} songs   ${y.artistCount} artists", style = pixel(13.sp, Ink.mid))
            // kept since ink music began keeping it, if that was after the year began
            val first = Calendar.getInstance().apply { timeInMillis = y.since }
            if (first.get(Calendar.DAY_OF_YEAR) > 7)
                BasicText("since " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(first.time).lowercase(),
                    style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
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

/** A top five as words: the first big when `firstHuge`, each with how often. */
@Composable
private fun Ranked(title: String, rows: List<Triple<String, String, Int>>, firstHuge: Boolean = false) {
    Column(Modifier.fillMaxSize()) {
        BasicText(title, style = headline(28.sp, Ink.mid))
        Spacer(Modifier.height(22.dp))
        rows.forEachIndexed { i, (name, by, n) ->
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                BasicText("${i + 1}", style = pixel(12.sp, Ink.mid), modifier = Modifier.width(30.dp).padding(bottom = 4.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(name.uppercase(), style = headline(if (firstHuge && i == 0) 56.sp else 26.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (by.isNotEmpty()) BasicText(by.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicText("$n", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            }
        }
    }
}

/** Records with their covers: the first big, the rest in a row under it, each with its plays. */
@Composable
private fun Records(title: String, rows: List<Pair<Song, Int>>, said: String) {   // said: after "n plays"
    Column(Modifier.fillMaxSize()) {
        BasicText(title, style = headline(28.sp, Ink.mid))
        Spacer(Modifier.height(18.dp))
        rows.firstOrNull()?.let { (s, n) ->
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                CoverOf(s, s.album, Modifier.size(minOf(maxWidth, 300.dp)))
            }
            Spacer(Modifier.height(12.dp))
            BasicText(s.album.uppercase(), style = headline(30.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText("${s.albumArtist.lowercase()}   $n ${if (n == 1) "play" else "plays"}$said", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.drop(1).take(4).forEach { (s, n) ->
                Column(Modifier.weight(1f)) {
                    CoverOf(s, s.album, Modifier.fillMaxWidth().aspectRatio(1f))
                    BasicText(s.album.uppercase(), style = headline(12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    BasicText("$n", style = pixel(12.sp, Ink.mid))
                }
            }
            repeat((4 - (rows.size - 1)).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
    }
}
