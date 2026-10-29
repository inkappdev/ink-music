package app.ink.music

import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/**
 * Listen, the home (the phone's listen tab, with room): first and big, what you play at this hour on this kind of
 * day (one big cover, three beside it, and play this hour); then the records you've played lately, the favorites
 * you've let go quiet, what's new on your server, ink weekly, ink radio's stars, your lists. With no listening yet,
 * records from your shelves where this hour's would be.
 */
@Composable
fun ListenPage(open: (Page) -> Unit) {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val heard by Listened.now.collectAsState()
    val seed by Order.seed.collectAsState()
    val account by Server.account.collectAsState()
    val weeks by Weekly.weeks.collectAsState()
    val lists by Server.playlists.collectAsState()
    LaunchedEffect(Unit) { Weekly.load() }
    val now = remember(heard) { System.currentTimeMillis() }
    val byId = remember(albums) { albums.associateBy { it.id } }
    // this hour: the songs you play now, and their records in that order
    val hourSongs = remember(songs, heard) {
        val minute = Listening.minuteOfWeek(now)
        songs.mapNotNull { s -> heard.hour(s.id, minute)?.let { s to it } }.sortedByDescending { it.second }.map { it.first }.take(60)
    }
    val hourRecords = remember(hourSongs, byId) { hourSongs.mapNotNull { byId[it.albumId] }.distinctBy { it.id }.take(4) }
    val shelf = remember(albums, seed) { Order.of(albums, seed) { it.id }.take(4) }
    val first = hourRecords.ifEmpty { shelf }
    val lately = remember(albums, heard) { albums.mapNotNull { a -> heard.lately("r:" + a.id)?.let { a to it } }.sortedByDescending { it.second }.map { it.first }.take(16) }
    val forgotten = remember(albums, heard) { albums.mapNotNull { a -> heard.forgotten("r:" + a.id, now)?.let { a to it } }.sortedByDescending { it.second }.map { it.first }.take(16) }
    val newest by produceState(emptyList<Album>(), account, albums) { value = Server.newest(16).mapNotNull { byId[it] } }
    val stars = remember { Stations.curated().filter { it.star } }
    // your artists' mixes: who you play most, each their best-known song and YouTube Music's radio from it
    val mixes by produceState(emptyList<Mixes.Mix>(), songs.size > 0) { Mixes.of(songs).collect { value = it } }
    val mixScope = androidx.compose.runtime.rememberCoroutineScope()
    val tubeMixes by TubeAccount.mixes.collectAsState()
    LaunchedEffect(songs.isNotEmpty()) { Discover.start(songs) }
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 14.dp, bottom = 32.dp)) {
        if (first.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                BasicText(if (hourRecords.isNotEmpty()) partOfDay(now) else "FROM YOUR SHELVES", style = headline(32.sp), modifier = Modifier.weight(1f))
                val play = hourSongs.ifEmpty { first.flatMap { it.songs } }
                Word(if (hourRecords.isNotEmpty()) "play this hour" else "play these", pixel(14.sp), touch = 8.dp) { Player.play(play) }
            }
            Spacer(Modifier.height(16.dp))
            // one big, the rest beside it
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                first.forEachIndexed { i, a -> RecordTile(a, Modifier.weight(if (i == 0) 2f else 1f)) { open(Page.Record(a.id)) } }
                repeat(4 - first.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(30.dp))
        }
        // made from your listening: fresh finds, deep cuts and the rest (Discover.kt)
        item { MadeForYou() }
        // YouTube Music's own mixes for you (signed in): supermix, discover, new release, replay
        if (tubeMixes.isNotEmpty()) item {
            Label("your mixes on youtube music")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(tubeMixes, key = { it.url }) { a -> TubeTile(a, Modifier.width(150.dp)) { open(Page.Tube(a)) } }
            }
            Spacer(Modifier.height(26.dp))
        }
        if (mixes.isNotEmpty()) item {
            Label("mixes for you")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                itemsIndexed(mixes, key = { _, m -> m.name }) { _, m ->
                    Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { mixScope.launch { Mixes.play(m) } })) {
                        Dithered(m.face, Modifier.fillMaxWidth().aspectRatio(1f), px = 300)
                        Spacer(Modifier.height(8.dp))
                        UnderCover(m.name, "mix")
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
        }
        item { BecauseYouLoved(open) }
        item { NewToYou(open) }
        records("lately", lately, open)
        records("forgotten favorites", forgotten, open)
        records("new on your server", newest, open)
        weeks.firstOrNull()?.let { w ->
            item {
                Label("ink weekly · vol ${w.week}" + Weekly.day(w.date).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty())
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        BasicText(w.title.uppercase(), style = headline(26.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (w.note.isNotEmpty()) BasicText(w.note.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Word("play the week", pixel(14.sp), touch = 8.dp) { Player.play(w.songs) }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(w.songs, key = { _, s -> s.id }) { i, s ->
                        Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(w.songs, i) })) {
                            CoverOf(s, s.title, Modifier.fillMaxWidth().aspectRatio(1f), px = 300)
                            Spacer(Modifier.height(8.dp))
                            UnderCover(s.title, s.artist)
                        }
                    }
                }
                Spacer(Modifier.height(26.dp))
            }
        }
        if (stars.isNotEmpty()) item {
            Label("ink radio")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                itemsIndexed(stars, key = { _, c -> c.song.id }) { i, c ->
                    Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null,
                        onClick = felt { Player.play(stars.drop(i).map { it.song }) })) {
                        StationCover(c.song, Modifier.fillMaxWidth().aspectRatio(1f))
                        Spacer(Modifier.height(8.dp))
                        BasicText(c.city.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
        }
        if (lists.isNotEmpty()) {
            item { Label("your lists") }
            items(lists.take(8), key = { "l" + it.id }) { p -> ListRow(p) { open(Page.List(p.id, p.name)) } }
        }
    }
}

/** A shelf of records, sliding sideways. */
private fun androidx.compose.foundation.lazy.LazyListScope.records(label: String, albums: List<Album>, open: (Page) -> Unit) {
    if (albums.isEmpty()) return
    item {
        Label(label)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(albums, key = { it.id }) { a -> RecordTile(a, Modifier.width(176.dp)) { open(Page.Record(a.id)) } }
        }
        Spacer(Modifier.height(26.dp))
    }
}

/** "WEDNESDAY NIGHT": the day and its part, as listen's first shelf is named. */
private fun partOfDay(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val day = c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.ENGLISH).orEmpty()
    val part = when (c.get(Calendar.HOUR_OF_DAY)) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..20 -> "evening"; else -> "night" }
    return "$day $part".uppercase()
}

/** A list as words: its name, how many songs. */
@Composable
fun ListRow(p: Server.Playlist, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 8.dp)) {
        BasicText(p.name.uppercase(), style = headline(20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText("${p.count} ${if (p.count == 1) "song" else "songs"}" + if (p.mine) "" else "   shared with you", style = pixel(12.sp, Ink.mid))
    }
}

/** Lists: the songs you love, then your server's playlists. */
@Composable
fun ListsPage(open: (Page) -> Unit) {
    val lists by Server.playlists.collectAsState()
    val account by Server.account.collectAsState()
    val loved = lovedSongs()
    val tubeListsNow by TubeAccount.playlists.collectAsState()
    LaunchedEffect(account) { Server.readPlaylists() }
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 14.dp, bottom = 32.dp)) {
        if (loved.isNotEmpty()) item { ListRow(Server.Playlist(LOVED, "loved", loved.size, true)) { open(Page.List(LOVED, "loved")) } }
        if (lists.isEmpty()) item {
            BasicText(if (account == null) "your server's playlists show here once you're signed in to it" else "no playlists on your server yet",
                style = pixel(12.sp, Ink.mid))
        }
        items(lists, key = { it.id }) { p -> ListRow(p) { open(Page.List(p.id, p.name)) } }
        // yours on YouTube Music (signed in from the phone)
        val tubeLists = tubeListsNow
        if (tubeLists.isNotEmpty()) {
            item { Label("on youtube music") }
            items(tubeLists, key = { it.url }) { t ->
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { open(Page.Tube(t)) }).padding(vertical = 8.dp)) {
                    BasicText(t.title.uppercase(), style = headline(20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(t.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A playlist: its name, its songs in its order, played from there. */
@Composable
fun ListPage(id: String, name: String, open: (Page) -> Unit, onClose: () -> Unit) {
    val loved = lovedSongs()
    val songs by produceState<List<Song>?>(null, id, loved) { value = if (id == LOVED) loved else Server.playlist(id) }
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 14.dp, bottom = 32.dp)) {
            item {
                BasicText(name.uppercase(), style = headline(56.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val s = songs
                BasicText(s?.let { "${it.size} ${if (it.size == 1) "song" else "songs"} · ${clock(it.sumOf { x -> x.duration })}" } ?: "reading", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(14.dp))
                if (!s.isNullOrEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Word("play", pixel(14.sp), touch = 8.dp) { Player.play(s) }
                    Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Player.play(s.shuffled()) }
                }
                Spacer(Modifier.height(14.dp))
            }
            val s = songs.orEmpty()
            itemsIndexed(s, key = { i, x -> "$i" + x.id }) { i, x -> SongRow(x, said = "${x.artist}  ·  ${x.album}".lowercase(), open = open) { Player.play(s, i) } }
        }
    }
}

/** The loved list's id among the lists (none of the server's look like it). */
const val LOVED = "~loved"

/** The songs you love, the latest loved first: your library's and the YouTube songs kept with their words. */
@Composable
fun lovedSongs(): List<Song> {
    val ids by Loved.ids.collectAsState()
    val songs by Library.songs.collectAsState()
    val tube by Loved.tube.collectAsState()
    val liked by TubeAccount.liked.collectAsState()
    return remember(ids, songs, tube, liked) {
        (songs.filter { it.id in ids } + tube.filter { it.id in ids } + liked.filter { it.id in ids }).distinctBy { it.id }
            .sortedByDescending { Loved.lovedAt(it) ?: 0L }
    }
}

/**
 * Mixes for you (the phone's, the way it auto-DJs): the artists you play most (else the ones you have most of), each
 * their best-known song on YouTube Music and its radio behind it, the queue's end bringing more as radio does.
 */
object Mixes {
    data class Mix(val name: String, val face: Song, val hit: Song)

    /** Up to eight, each as its artist page is read (kept on disk: at once after the first time). */
    fun of(songs: List<Song>): kotlinx.coroutines.flow.Flow<List<Mix>> = kotlinx.coroutines.flow.flow {
        val since = System.currentTimeMillis() - 90L * 86_400_000
        val played = History.read().filter { it.at >= since }.groupingBy { it.artist.substringBefore(", ").lowercase() }.eachCount()
        val names = songs.filter { !it.station }.groupBy { it.albumArtist.ifBlank { it.artist.substringBefore(", ") } }
            .filterKeys { it.isNotBlank() && it != "unknown" && !it.equals("various artists", true) }
            .entries.sortedByDescending { (name, list) -> (played[name.lowercase()] ?: 0) * 1000 + list.size }.map { it.key }.take(8)
        val found = ArrayList<Mix>()
        for (name in names) {
            val info = kotlinx.coroutines.withTimeoutOrNull(8_000) { Artists.of(name, "").firstOrNull { it != null } } ?: continue
            val hit = info.top.firstOrNull { it.tube } ?: continue
            val face = info.photo?.let { faceSong(name, it, photo = true) } ?: hit
            found += Mix(info.name, face, hit)
            emit(found.toList())
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    /** Their song at once, the mix's songs behind it once they're read (a speaker takes the whole queue, so it waits). */
    suspend fun play(m: Mix) {
        if (Casting.on.value != null) {
            val more = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.related(m.hit.id) }
            return Player.play(listOf(m.hit) + more)
        }
        Player.play(listOf(m.hit))
        val more = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.related(m.hit.id) }
        Player.follow(m.hit, more)
    }
}
