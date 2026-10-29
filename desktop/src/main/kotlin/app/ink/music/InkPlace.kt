package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * INK's own place on the rail: what we make, all of it. This week's ink weekly on top, as its own spread each week
 * (its covers in the dither two by two, its name on them, its note, its songs as a chart beside), the weeks before as
 * their mosaics (a click puts that week on top); then ink radio, the stations we keep, in shelves by what they play
 * (Bronson, 2026-10-29: as one grid it was a long list), each with what it's playing this minute where that's known.
 */
@Composable
fun InkPage(open: (Page) -> Unit) {
    val weeks by Weekly.weeks.collectAsState()
    LaunchedEffect(Unit) { Weekly.load() }
    var chosen by remember { mutableStateOf<Int?>(null) }
    val w = weeks.firstOrNull { it.week == chosen } ?: weeks.firstOrNull()
    LaunchedEffect(Unit) { while (true) { Picks.air(); kotlinx.coroutines.delay(60_000) } }
    val air by Picks.onAir.collectAsState()
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(GAP)) {
        item(key = "week") {
            if (w == null) Section("INK WEEKLY", "reading this week's")
            else WeekSpread(w, w.week == weeks.firstOrNull()?.week)
        }
        val before = weeks.filter { it.week != w?.week }
        if (before.isNotEmpty()) item(key = "before") {
            Column {
                Section("THE WEEKS BEFORE", "every volume, the newest first")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    items(before, key = { it.week }) { b ->
                        Column(Modifier.width(170.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { chosen = b.week })) {
                            Mosaic(b.songs, Modifier.fillMaxWidth().aspectRatio(1f))
                            Spacer(Modifier.height(8.dp))
                            UnderCover(b.title, "vol ${b.week}" + Weekly.day(b.date).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty())
                        }
                    }
                }
            }
        }
        RADIO_KINDS.forEach { kind ->
            val stations = Stations.curated().filter { kindOf(it) == kind.key }.sortedByDescending { it.star }
            if (stations.isNotEmpty()) item(key = "radio-" + kind.key) {
                Column {
                    if (kind == RADIO_KINDS.first()) {
                        BasicText("INK RADIO", style = headline(44.sp))
                        Spacer(Modifier.height(18.dp))
                    }
                    Section(kind.name, kind.line)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(stations, key = { it.song.id }) { c -> StationTile(c, air.firstOrNull { it.station == c.song.id }) { Player.play(stations.dropWhile { it != c }.map { it.song }) } }
                    }
                }
            }
        }
    }
}

/** A kind of station ink radio keeps: its name, a line on it, and the genres (the first one a station's filed under) in it. */
private class RadioKind(val key: String, val name: String, val line: String, val genres: Set<String>)

private val RADIO_KINDS = listOf(
    RadioKind("djs", "REAL DJS", "public music stations: new indie, picked by people", setOf("indie", "americana", "rock", "college", "alternative", "local", "texas")),
    RadioKind("free", "FREEFORM", "community and underground stations, anything at all", setOf("eclectic", "freeform", "community", "underground")),
    RadioKind("club", "THE CLUB", "house, techno, italo, drum and bass, live from the booth", setOf("electronic", "club", "italo", "drum and bass")),
    RadioKind("world", "JAZZ, SOUL AND THE WORLD", "from paris, accra, bethlehem and london", setOf("jazz", "soul", "world")),
    RadioKind("out", "OUT THERE", "ambient, experimental, sound art", setOf("experimental", "ambient", "art")),
)

private fun kindOf(c: Stations.Curated): String {
    val first = c.genre.split(",").first().trim().lowercase()
    return RADIO_KINDS.firstOrNull { first in it.genres }?.key ?: "free"
}

/** A station: its sleeve (its name on it, so not again under it), its city, and what it's playing now, else its line. */
@Composable
private fun StationTile(c: Stations.Curated, aired: Curation.Aired?, onClick: () -> Unit) {
    val now by Player.now.collectAsState()
    val on = now?.song?.id == c.song.id
    Column(Modifier.width(200.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        StationCover(c.song, Modifier.fillMaxWidth().aspectRatio(1f), moving = on && now?.playing == true)
        Spacer(Modifier.height(8.dp))
        BasicText((if (on) "on now · " else "") + c.city.lowercase(), style = pixel(12.sp, if (on) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        if (aired != null) BasicText("playing ${aired.title} by ${aired.artist}".lowercase(), style = pixel(12.sp, Ink.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
        else BasicText(c.line.lowercase(), style = pixel(12.sp, Ink.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A week of ink weekly as a spread: its mosaic big with its name on it, its note, play; its songs as a chart beside. */
@Composable
private fun WeekSpread(w: Weekly.Week, latest: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = minOf(maxWidth * .44f, 460.dp)
        val beside = maxWidth >= 820.dp
        val head = @Composable {
            Column {
                BasicText(listOf("ink weekly", "vol ${w.week}", Weekly.day(w.date), if (latest) "this week" else "").filter { it.isNotEmpty() }.joinToString("  ·  "),
                    style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(10.dp))
                // its name on its covers, on a black tab in their corner (a sleeve's label: pale covers would swallow it)
                Box(Modifier.size(side), contentAlignment = Alignment.BottomStart) {
                    Mosaic(w.songs, Modifier.fillMaxSize())
                    BasicText(w.title.uppercase(), style = headline(44.sp).copy(lineHeight = 44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.background(Ink.black).padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 2.dp))
                }
                if (w.note.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    BasicText(w.note.lowercase(), style = pixel(13.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 21.sp), modifier = Modifier.width(side))
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    Word("play", pixel(14.sp), touch = 8.dp) { Player.play(w.songs) }
                    Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Player.play(w.songs.shuffled()) }
                    BasicText("${w.songs.size} songs  ·  ${w.songs.sumOf { it.duration } / 60_000} min", style = pixel(12.sp, Ink.mid))
                }
            }
        }
        // the songs as a chart: the place big beside each
        val chart = @Composable { m: Modifier ->
            Column(m) {
                w.songs.forEachIndexed { n, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("${n + 1}", style = headline(24.sp, Ink.mid), modifier = Modifier.width(48.dp))
                        Box(Modifier.weight(1f)) { SongRow(s, said = s.artist.lowercase()) { Player.play(w.songs, n) } }
                    }
                }
            }
        }
        if (beside) Row { head(); Spacer(Modifier.width(48.dp)); chart(Modifier.weight(1f).padding(top = 26.dp)) }
        else Column { head(); Spacer(Modifier.height(20.dp)); chart(Modifier.fillMaxWidth()) }
    }
}

/** Four of a week's covers in the dither, two by two, edge to edge: ink weekly's own cover (the phone's). */
@Composable
fun Mosaic(songs: List<Song>, modifier: Modifier) {
    BoxWithConstraints(modifier.background(Ink.black)) {
        val cell = maxWidth / 2
        val px = with(androidx.compose.ui.platform.LocalDensity.current) { cell.roundToPx() }
        val four = songs.distinctBy { it.art ?: it.albumId }.take(4)
        Column {
            for (r in 0..1) Row {
                for (c in 0..1) four.getOrNull(r * 2 + c)?.let { Dithered(it, Modifier.size(cell), px = px) } ?: Spacer(Modifier.size(cell))
            }
        }
    }
}
