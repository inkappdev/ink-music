package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What listen shows from what you've heard (heard.jsonl, here and on the paired devices). */
object Listening {
    data class Shelves(val recent: List<Song>, val repeat: List<Song>, val forgotten: List<Song>)

    /** A line heard, as a song to play here: YouTube's and your server's (the phone's own files can't be). */
    private fun song(o: JSONObject, liked: Map<String, Song>): Song? {
        val id = o.optString("id")
        return when {
            id.startsWith("y") -> liked[id.drop(1)] ?: Song(id.drop(1), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optString("record").removePrefix("y"), "https://i.ytimg.com/vi/${id.drop(1)}/hqdefault.jpg", 0, 0, 1, kind = "youtube")
            id.startsWith("n") && !id.startsWith("na") -> o.optString("record").removePrefix("na").let { rec ->
                Song(id.drop(1), o.optString("title"), o.optString("artist"), o.optString("album"), rec, rec.ifEmpty { null }, 0, 0, 1)
            }
            else -> null
        }
    }

    /** Blocking. Jump back in: the latest, each once; on repeat: the month's most heard; forgotten favorites: much heard, not lately. */
    fun shelves(): Shelves {
        val all = Heard.all()
        val liked = Tube.library.value?.liked.orEmpty().associateBy { it.id }
        val now = nowMs()
        val day = 86_400_000L
        val recent = all.asReversed().distinctBy { it.optString("id") }.mapNotNull { song(it, liked) }.take(20)
        fun counted(lines: List<JSONObject>) = lines.groupBy { it.optString("id") }.mapValues { it.value.size }
        val month = counted(all.filter { now - it.optLong("at") < 30 * day })
        val repeat = month.filterValues { it >= 3 }.entries.sortedByDescending { it.value }
            .mapNotNull { (id, _) -> all.lastOrNull { it.optString("id") == id }?.let { song(it, liked) } }.take(20)
        val lately = all.filter { now - it.optLong("at") < 60 * day }.mapTo(HashSet()) { it.optString("id") }
        val forgotten = counted(all).filter { (id, n) -> n >= 4 && id !in lately }.entries.sortedByDescending { it.value }
            .mapNotNull { (id, _) -> all.lastOrNull { it.optString("id") == id }?.let { song(it, liked) } }.take(20)
        return Shelves(recent, repeat, forgotten)
    }

    fun greeting(): String = Eggs.greeting() ?: when (localNow().hour) {
        in 5..11 -> "GOOD MORNING"
        in 12..16 -> "GOOD AFTERNOON"
        in 17..21 -> "GOOD EVENING"
        else -> "LATE NIGHT"
    }

    /** "saturday 7 november", as the phone says the day. */
    fun day(): String = today().let { d ->
        "${d.dayOfWeek.name.lowercase()} ${d.day} ${d.month.name.lowercase()}"
    }
}

/**
 * listen, as the phone's: the greeting and the day; what plays on your computer (continue here); then the shelves,
 * each a row of covers: jump back in, your mixes on youtube music, on repeat, new episodes, ink radio, forgotten
 * favorites, liked on youtube music, your playlists, youtube music's for you, new on your server, from your records.
 */
@Composable
fun ListenPlace(top: Dp, foot: Dp) {
    val offer by Together.theirs.collectAsState()
    val changed by Together.changed.collectAsState()
    val now by Sound.now.collectAsState()
    val who by Tube.who.collectAsState()
    val tube by produceState<Tube.Library?>(Tube.library.value, who, changed) { value = Here.off { Tube.readLibrary() } }
    val heard by produceState<Listening.Shelves?>(null, changed, tube) { value = Here.off { Listening.shelves() } }
    val server by produceState<List<Pair<String, List<Album>>>>(emptyList(), changed) {
        val seen = HashSet<String>()
        value = listOf("new on your server" to "newest", "from your records" to "random")
            .map { (name, type) -> name to runCatching { Server.shelf(type, 24) }.getOrDefault(emptyList()).filter { seen.add(it.id) } }
            .filter { it.second.isNotEmpty() }
    }
    val episodes by produceState<List<Pair<Podcasts.Show, Podcasts.Episode>>>(emptyList(), changed) { value = Here.off { Podcasts.fresh(n = 10) } }
    val stations = remember { Radio.stations().filter { it.star } }
    val opened = remember { nowMs() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top - 14.dp, bottom = foot)) {
        item {
            Column(Modifier.cascade(0, opened).padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 10.dp)) {
                BasicText(Listening.greeting(), style = headline(40.sp), maxLines = 2)
                BasicText(Listening.day(), style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
            }
        }
        offer?.let { o -> item(key = "continue") { Box(Modifier.cascade(1, opened)) { ContinueHere(o) } } }
        val shelves = ArrayList<Pair<String, @Composable () -> Unit>>()
        heard?.recent?.takeIf { it.isNotEmpty() }?.let { r ->
            shelves += "recent" to { ShelfLabel("jump back in"); ShelfIndexed(r) { i, s -> Tile(s.cover, s.title, s.artist) { Sound.play(r, i) } } }
        }
        tube?.mixes?.takeIf { it.isNotEmpty() }?.let { m ->
            shelves += "mixes" to { ShelfLabel("your mixes on youtube music"); Shelf(m) { a -> Tile(a.cover, a.title, a.artist.ifEmpty { "mix" }, dither = true) { Pages.open(Page.Record(a)) } } }
        }
        heard?.repeat?.takeIf { it.isNotEmpty() }?.let { r ->
            shelves += "repeat" to { ShelfLabel("on repeat"); ShelfIndexed(r) { i, s -> Tile(s.cover, s.title, s.artist) { Sound.play(r, i) } } }
        }
        if (episodes.isNotEmpty()) shelves += "episodes" to {
            ShelfLabel("new episodes")
            Shelf(episodes) { (show, e) -> Tile(show.art, e.title, show.title) { Sound.play(listOf(episodeSong(show, e)), 0) } }
        }
        if (stations.isNotEmpty()) shelves += "radio" to {
            Row(verticalAlignment = Alignment.Bottom) {
                ShelfLabel("ink radio")
                Spacer(Modifier.weight(1f))
                Word("all", pixel(12.sp, Ink.mid), Modifier.padding(end = 16.dp), touch = 8.dp) { Pages.place = "radio" }
            }
            ShelfIndexed(stations) { i, st ->
                val on = now?.song?.id == st.song.id
                StationTile(st.song, on, now?.playing == true, if (on) "on now" else st.city) { Sound.play(stations.map { it.song }, i) }
            }
        }
        heard?.forgotten?.takeIf { it.isNotEmpty() }?.let { f ->
            shelves += "forgotten" to { ShelfLabel("forgotten favorites"); ShelfIndexed(f) { i, s -> Tile(s.cover, s.title, s.artist) { Sound.play(f, i) } } }
        }
        tube?.liked?.takeIf { it.isNotEmpty() }?.let { l ->
            shelves += "liked" to {
                Row(verticalAlignment = Alignment.Bottom) {
                    ShelfLabel("liked on youtube music")
                    Spacer(Modifier.weight(1f))
                    Word("shuffle all ${l.size}", pixel(12.sp, Ink.mid), Modifier.padding(end = 16.dp), touch = 8.dp) { Sound.play(l.shuffled(), 0) }
                }
                ShelfIndexed(l.take(30)) { i, s -> Tile(s.cover, s.title, s.artist) { Sound.play(l, i) } }
            }
        }
        tube?.lists?.takeIf { it.isNotEmpty() }?.let { ls ->
            shelves += "lists" to { ShelfLabel("your playlists"); Shelf(ls) { a -> Tile(a.cover, a.title, "playlist", dither = true) { Pages.open(Page.Record(a)) } } }
        }
        tube?.home?.forEach { (name, records) ->
            shelves += "home:$name" to { ShelfLabel(name.lowercase()); Shelf(records) { a -> Tile(a.cover, a.title, a.artist) { Pages.open(Page.Record(a)) } } }
        }
        server.forEach { (name, albums) ->
            shelves += "server:$name" to { ShelfLabel(name); Shelf(albums) { a -> Tile(a.cover, a.title, a.artist) { Pages.open(Page.Record(a)) } } }
        }
        shelves.forEachIndexed { i, (k, body) -> item(key = k) { Column(Modifier.cascade(2 + i, opened)) { body() } } }
    }
}

/** A shelf's name, as the phone's: small, in pixels, at 70%, at the page's margin. */
@Composable
fun ShelfLabel(text: String) {
    BasicText(text, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
}

/** A shelf: a row of tiles, sliding sideways, the page's margins at its ends. */
@Composable
fun <T> Shelf(items: List<T>, tile: @Composable (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(items) { tile(it) } }
    Spacer(Modifier.height(10.dp))
}

@Composable
fun <T> ShelfIndexed(items: List<T>, tile: @Composable (Int, T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { itemsIndexed(items) { i, it -> tile(i, it) } }
    Spacer(Modifier.height(10.dp))
}

/** A tile: a cover, a name big, a word small; numbered on a chart; a mix's or playlist's picture in the dither. */
@Composable
fun Tile(cover: String?, name: String, said: String, number: Int? = null, dither: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        if (dither && cover != null) Dithered(cover, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        else Cover(cover, 400, name, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            number?.let { BasicText("$it", style = pixel(12.sp, Ink.mid), modifier = Modifier.width(26.dp).padding(top = 2.dp)) }
            UnderCover(name, said, pixel(12.sp, Ink.mid))
        }
    }
}

/** A name and a word under a cover, always two lines of room for the name, so a row's covers line up. */
@Composable
fun UnderCover(title: String, said: String, saidStyle: TextStyle) {
    Box {
        Column(Modifier.alpha(0f)) {
            BasicText("I\nI", style = headline(15.sp))
            BasicText("i", style = saidStyle)
        }
        Column {
            BasicText(title.uppercase(), style = headline(15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(said.lowercase(), style = saidStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A station as a tile: its sleeve (its rings moving while it plays), its name, where or what's on. */
@Composable
fun StationTile(station: Song, on: Boolean, moving: Boolean, said: String, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        StationCover(station, Modifier.fillMaxWidth().aspectRatio(1f), moving = on && moving)
        Spacer(Modifier.height(8.dp))
        BasicText(station.title.uppercase(), style = headline(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(said.lowercase(), style = pixel(12.sp, if (on) Ink.ink else Ink.mid), maxLines = 1)
    }
}

/** What the computer was playing: its cover, "on <computer>", the song; continue here (where it was), or not now. */
@Composable
private fun ContinueHere(o: Together.Offer) {
    val s = o.songs[o.index]
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Together.take(o) })) {
            Cover(s.cover, 200, s.album.ifEmpty { s.title }, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText((if (o.playing) "playing on " else "on ") + o.from, style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(s.title.uppercase(), style = headline(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Word("CONTINUE HERE", headline(14.sp), Modifier.padding(end = 18.dp), touch = 8.dp) { Together.take(o) }
                Word("not now", pixel(12.sp, Ink.mid), touch = 8.dp) { Together.theirs.value = null }
            }
        }
    }
}
