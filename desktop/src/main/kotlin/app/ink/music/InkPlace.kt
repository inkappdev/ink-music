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
 * ink weekly as INK's front cover (Bronson, 2026-10-29: "really make that top ink weekly design shine"): its four covers
 * in the dither, big; the week's name huge beside them, its note, play the week; the week's artists running across the
 * page in the giant marquee, as the player's do; its songs as a chart in columns; the volumes before, small, a click
 * putting one on the cover.
 */
@Composable
fun InkWeekly() {
    val weeks by Weekly.weeks.collectAsState()
    LaunchedEffect(Unit) { Weekly.load() }
    var chosen by remember { mutableStateOf<Int?>(null) }
    val w = weeks.firstOrNull { it.week == chosen } ?: weeks.firstOrNull() ?: return Section("INK WEEKLY", "reading this week's")
    val latest = w.week == weeks.firstOrNull()?.week
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room = maxWidth
        val side = minOf(room * .46f, 540.dp)
        val wide = room >= 820.dp
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.size(if (wide) side else room)) { Mosaic(w.songs, Modifier.fillMaxSize()) }
                if (wide) {
                    Spacer(Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) { WeekWords(w, latest, big = true) { chosen = null } }
                }
            }
            if (!wide) { Spacer(Modifier.height(18.dp)); WeekWords(w, latest, big = false) { chosen = null } }
            Spacer(Modifier.height(26.dp))
            // who's on it, running off both edges (still under less motion)
            val names = remember(w) { w.songs.map { it.artist.substringBefore(",").trim() }.distinct().joinToString("  ·  ") }
            Marquee(names, true, Modifier.fillMaxWidth(), size = 84.sp)
            Spacer(Modifier.height(22.dp))
            // the songs as a chart: three columns wide, the place big beside each
            val across = if (room >= 1100.dp) 3 else if (wide) 2 else 1
            val per = (w.songs.size + across - 1) / across
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                w.songs.chunked(per.coerceAtLeast(1)).forEachIndexed { c, col ->
                    Column(Modifier.weight(1f)) {
                        col.forEachIndexed { r, s ->
                            val n = c * per + r
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText("${n + 1}".padStart(2, '0'), style = headline(20.sp, Ink.mid), modifier = Modifier.width(40.dp))
                                CoverOf(s, s.album.ifEmpty { s.title }, Modifier.size(44.dp), px = 120)
                                Spacer(Modifier.width(12.dp))
                                Box(Modifier.weight(1f)) { SongRow(s, said = s.artist.lowercase()) { Player.play(w.songs, n) } }
                            }
                        }
                    }
                }
                repeat(across - w.songs.chunked(per.coerceAtLeast(1)).size) { Spacer(Modifier.weight(1f)) }
            }
            // the volumes before, small
            val before = weeks.filter { it.week != w.week }
            if (before.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                Label(if (latest) "the volumes before" else "every volume")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(before, key = { it.week }) { b ->
                        Column(Modifier.width(120.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { chosen = b.week })) {
                            Mosaic(b.songs, Modifier.fillMaxWidth().aspectRatio(1f))
                            Spacer(Modifier.height(6.dp))
                            UnderCover(b.title, "vol ${b.week}")
                        }
                    }
                }
            }
        }
    }
}

/** A week's words: ink weekly, its volume and day; its name huge; its note; play the week, shuffle, how long. */
@Composable
private fun WeekWords(w: Weekly.Week, latest: Boolean, big: Boolean, onLatest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("INK WEEKLY", style = headline(18.sp))
        Spacer(Modifier.width(14.dp))
        BasicText(listOf("vol ${w.week}", Weekly.day(w.date), if (latest) "this week" else "").filter { it.isNotEmpty() }.joinToString("  ·  "),
            style = pixel(12.sp, Ink.mid))
        if (!latest) { Spacer(Modifier.width(18.dp)); Word("this week's", pixel(12.sp), touch = 6.dp, onClick = onLatest) }
    }
    Spacer(Modifier.height(10.dp))
    BasicText(w.title.uppercase(), style = headline(if (big) 88.sp else 56.sp).copy(lineHeight = if (big) 84.sp else 54.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
    if (w.note.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        BasicText(w.note.lowercase(), style = pixel(14.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 23.sp), maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Word("PLAY THE WEEK", headline(24.sp), touch = 8.dp) { Player.play(w.songs) }
        Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Player.play(w.songs.shuffled()) }
        BasicText("${w.songs.size} songs  ·  ${w.songs.sumOf { it.duration } / 60_000} min", style = pixel(12.sp, Ink.mid))
    }
}

/** Ink radio on INK: the stations we keep, a kind chosen in a row of words (all, the stars first), each what it's playing now. */
@Composable
fun InkRadio(air: List<Curation.Aired>) {
    var kind by remember { mutableStateOf<String?>(null) }
    val stations = remember(kind) { Stations.curated().filter { kind == null || kindOf(it) == kind }.sortedByDescending { it.star } }
    Column {
        Section("INK RADIO", "the stations we keep: real djs, picked by people")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            item { Word("all", pixel(13.sp, if (kind == null) Ink.ink else Ink.dim), touch = 8.dp) { kind = null } }
            items(RADIO_KINDS, key = { it.key }) { k -> Word(k.name.lowercase(), pixel(13.sp, if (kind == k.key) Ink.ink else Ink.dim), touch = 8.dp) { kind = k.key } }
        }
        androidx.compose.runtime.key(kind) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(stations, key = { it.song.id }) { c -> StationTile(c, air.firstOrNull { it.station == c.song.id }) { Player.play(stations.dropWhile { it != c }.map { it.song }) } }
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
