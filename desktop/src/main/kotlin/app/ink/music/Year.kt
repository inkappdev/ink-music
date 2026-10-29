package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Your year, any day of it (the phone's Year): what this computer has heard since the year began (or since ink music
 * started keeping it), summed: the minutes, the songs, artists and records heard most; and from your server, the
 * records played most there, ever (every app's plays, so it's never empty).
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
            // the same song from two places (the computer, the server) is one song
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
    id = id, path = "", title = title, artist = artist, album = album, albumId = record.ifEmpty { "h$id" },
    albumArtist = artist, duration = 0, disc = 1, track = 0, year = 0,
)

/**
 * Your year on one page (the phone's swiped pages laid side by side, the window's wide): the year and its minutes,
 * then top songs, top artists and top records across, then your server's all time.
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = SIDE, end = SIDE, top = 8.dp, bottom = 40.dp)) {
            BasicText("YOUR YEAR", style = headline(28.sp, Ink.mid))
            BasicText(y.year.toString(), style = headline(120.sp))
            Spacer(Modifier.height(10.dp))
            if (some) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    Big("%,d".format(y.minutes), "minutes")
                    Big("${y.heard}", "songs")
                    Big("${y.artistCount}", "artists")
                }
                // kept since ink music began keeping it, if that was after the year began
                val first = Calendar.getInstance().apply { timeInMillis = y.since }
                if (first.get(Calendar.DAY_OF_YEAR) > 7)
                    BasicText("since " + java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault()).format(first.time).lowercase(),
                        style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(44.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    Ranked("TOP SONGS", y.songs.map { (h, n) -> Triple(h.title, h.artist, n) }, Modifier.weight(1f)) { i -> Player.play(y.songs.map { it.first.song() }, i) }
                    Ranked("TOP ARTISTS", y.artists.map { (a, n) -> Triple(a, "", n) }, Modifier.weight(1f), firstHuge = true) { i -> open(Page.Artist(y.artists[i].first)) }
                }
                Spacer(Modifier.height(44.dp))
                Records("TOP RECORDS", y.records.map { (h, n) -> h.song() to n }, "", open)
            } else {
                BasicText("STARTS NOW", style = headline(44.sp))
                Spacer(Modifier.height(12.dp))
                BasicText("ink music keeps what you play on this ${if (Home.mac) "mac" else "computer"}, from today: come back in a while and your year is here. it stays on the ${if (Home.mac) "mac" else "computer"}.",
                    style = pixel(12.sp, Ink.mid))
            }
            if (ever.isNotEmpty()) {
                Spacer(Modifier.height(44.dp))
                Records("ALL TIME ON YOUR SERVER", ever.map { it.face to it.plays }, ", every app", open)
            }
        }
    }
}

@Composable
private fun Big(n: String, said: String) {
    Column {
        BasicText(n, style = headline(72.sp))
        BasicText(said, style = pixel(14.sp, Ink.mid))
    }
}

/** A top five as words: the first big when `firstHuge`, each with how often; a click plays it (or opens them). */
@Composable
private fun Ranked(title: String, rows: List<Triple<String, String, Int>>, modifier: Modifier, firstHuge: Boolean = false, onClick: (Int) -> Unit) {
    Column(modifier) {
        BasicText(title, style = headline(28.sp, Ink.mid))
        Spacer(Modifier.height(14.dp))
        rows.forEachIndexed { i, (name, by, n) ->
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                BasicText("${i + 1}", style = pixel(12.sp, Ink.mid), modifier = Modifier.width(30.dp).padding(bottom = 4.dp))
                Column(Modifier.weight(1f)) {
                    Word(name.uppercase(), headline(if (firstHuge && i == 0) 56.sp else 26.sp), onClick = { onClick(i) })
                    if (by.isNotEmpty()) BasicText(by.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicText("$n", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            }
        }
    }
}

/** Records with their covers in a row, the first bigger, each with its plays; a click opens one of yours. */
@Composable
private fun Records(title: String, rows: List<Pair<Song, Int>>, said: String, open: (Page) -> Unit) {   // said: after "n plays"
    Column(Modifier.fillMaxWidth()) {
        BasicText(title, style = headline(28.sp, Ink.mid))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            rows.take(5).forEachIndexed { i, (s, n) ->
                Column(Modifier.weight(if (i == 0) 2f else 1f).let { m -> if (Library.albums.value.any { it.id == s.albumId }) m.clickable(
                    androidx.compose.foundation.interaction.MutableInteractionSource(), indication = null) { open(Page.Record(s.albumId)) } else m }) {
                    CoverOf(s, s.album, Modifier.fillMaxWidth().aspectRatio(1f))
                    BasicText(s.album.uppercase(), style = headline(if (i == 0) 24.sp else 14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    BasicText("${s.albumArtist.lowercase()}   $n ${if (n == 1) "play" else "plays"}${if (i == 0) said else ""}", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat((5 - rows.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
    }
}
