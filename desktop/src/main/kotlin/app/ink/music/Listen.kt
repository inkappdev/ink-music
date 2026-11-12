package app.ink.music

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
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
    val hourRecords = remember(hourSongs, byId) { hourSongs.mapNotNull { byId[it.albumId] }.distinctBy { it.id }.take(21) }
    val shelf = remember(albums, seed) { Order.of(albums, seed) { it.id }.take(21) }
    // what you play at this hour, topped up from your shelves (one record played at this hour once showed a shelf of
    // one, 2026-10-30); as many as the width has room for (below)
    val first = remember(hourRecords, shelf) { (hourRecords + shelf).distinctBy { it.id }.take(21) }
    val lately = remember(albums, heard) { albums.mapNotNull { a -> heard.lately("r:" + a.id)?.let { a to it } }.sortedByDescending { it.second }.map { it.first }.take(16) }
    val forgotten = remember(albums, heard) { albums.mapNotNull { a -> heard.forgotten("r:" + a.id, now)?.let { a to it } }.sortedByDescending { it.second }.map { it.first }.take(16) }
    val newest by produceState(emptyList<Album>(), account, albums) { value = Server.newest(16).mapNotNull { byId[it] } }
    val stars = remember { Stations.curated().filter { it.star } }
    // your artists' mixes: who you play most, each their best-known song and YouTube Music's radio from it
    val mixes by produceState(emptyList<Mixes.Mix>(), songs.size > 0) { Mixes.of(songs).collect { value = it } }
    val mixScope = androidx.compose.runtime.rememberCoroutineScope()
    val tubeMixes by TubeAccount.mixes.collectAsState()
    LaunchedEffect(songs.isNotEmpty()) { Discover.start(songs) }
    val list = pageList()
    LaunchedEffect(Unit) { Picks.start(); Picks.startFriday(); Picks.startElectronic(); Updates.check() }
    val update by Updates.offer.collectAsState()
    val shut by Server.shut.collectAsState()
    val pickedRecords by Picks.records.collectAsState()
    val pickedSongs by Picks.songs.collectAsState()
    val friday by Picks.friday.collectAsState()
    val electronic by Picks.electronic.collectAsState()
    val brought by Fresh.brought.collectAsState()
    var knowMeShut by remember { mutableStateOf(java.io.File(Home.dir, "know-me-shut").exists()) }
    val fromYours = remember(pickedRecords, pickedSongs, friday, electronic, heard, songs, brought) { Picks.yours(heard, songs, brought) }
    val taste by Fresh.taste.collectAsState()
    val visit by Fresh.visit.collectAsState()
    val sinceAt by Fresh.since.collectAsState()
    val order = remember(visit) { Fresh.order(LISTEN_SHELVES) }
    val pickedForYou = remember(pickedRecords, pickedSongs, fromYours, taste) {
        val above = fromYours.map { Curation.key(it.pick) }.toSet()
        Fresh.rank((pickedRecords + pickedSongs).filter { Curation.key(it.pick) !in above }, taste).take(16)
    }
    val continued = continueOffer()
    val firstHeard by EggsNow.first.collectAsState()
    val made by Discover.mixes.collectAsState()
    val because by Discover.because.collectAsState()
    val newTo by Discover.newToYou.collectAsState()
    // the shows you follow: their newest episodes not yet heard (read again from their feeds each hour)
    val episodes by Pods.fresh.collectAsState()
    LaunchedEffect(Unit) { Pods.refresh() }
    fun openPick(p: Picks.Shown) = mixScope.launch {
        val a = if (p.pick.record) Picks.record(p.pick) else null
        a?.let { open(Page.Tube(it)) } ?: Picks.song(p.pick.title, p.pick.artist)?.let { Player.play(listOf(it)) }
    }
    val sinceLines = remember(fromYours, pickedForYou, newest, episodes, sinceAt) {
        val at = sinceAt ?: return@remember emptyList<NewLine>()
        fun new(key: String) = Fresh.seen.first(key).let { it == null || it > at }
        val out = ArrayList<NewLine>()
        fromYours.filter { new(Fresh.key(it.pick)) }.take(2).forEach { y -> out += NewLine(Fresh.key(y.pick), y.pick.title, "${y.pick.artist.lowercase()}, new") { openPick(y) } }
        pickedForYou.firstOrNull { !it.stretch && new(Fresh.key(it.item.pick)) }?.let { p ->
            out += NewLine(Fresh.key(p.item.pick), p.item.pick.title, p.item.pick.artist.lowercase() + (Fresh.reason(p.item, false, taste)?.let { ", $it" } ?: "")) { openPick(p.item) }
        }
        newest.filter { new("server:${it.id}") }.let { n -> n.firstOrNull()?.let { a ->
            out += NewLine("server:${a.id}", a.title, "${a.artist.lowercase()}, on your server" + if (n.size > 1) ", and ${n.size - 1} more" else "") { open(Page.Record(a.id)) }
        } }
        episodes.filter { new("ep:${it.id}") }.let { n -> n.firstOrNull()?.let { e ->
            out += NewLine("ep:${e.id}", e.title, "${e.album.lowercase()}, " + if (n.size > 1) "and ${n.size - 1} more episodes" else "new episode") { Player.play(listOf(e)) }
        } }
        // what's said is seen: not new the next time
        newest.forEach { Fresh.seen.shown("server:${it.id}") }; episodes.forEach { Fresh.seen.shown("ep:${it.id}") }
        out.take(4)
    }
    // one rhythm down the page: the same room between every shelf, each with a head of its own (2026-10-29)
    LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(GAP)) { cascading {
        // macOS keeping it off the network: why nothing from the server shows
        if (shut) item(key = "shut") { NetworkShut() }
        // a newer ink music, first of all (Updates)
        update?.let { u -> item(key = "update") { UpdateCard(u) } }
        // what the phone was playing, to carry on with here
        continued?.let { o -> item(key = "continue") { ContinueHere(o) } }
        // a year in (or two...): your first song here, on the day (an easter egg)
        firstHeard?.takeIf { Eggs.anniversary(it.at) > 0 }?.let { f -> item(key = "anniversary") { Anniversary(f) } }
        // new from your artists: out this week or picked lately, by artists you play (where ink weekly was: that's INK's)
        // knowing nothing of you yet (little played here, nothing brought in): your listening elsewhere asked for
        if (!Local.on && brought.isEmpty() && heard.plays.size < 40 && !knowMeShut) item(key = "know-me") { KnowMe { knowMeShut = true } }
        // since you were here: back after a while, what's new since then, four lines at most (Fresh.since)
        if (sinceAt != null && sinceLines.isNotEmpty()) item(key = "since") { SinceYouWereHere(sinceAt!!, sinceLines) }
        // the shelves in the moment's order (Fresh.order: the time of day, the kind of day, what you open and what you
        // pass over; held for the visit), as the phone's listen (2026-11-11)
        val shelf = LinkedHashMap<String, () -> Unit>()
        shelf["fromYours"] = {
            if (fromYours.isNotEmpty()) item { NewFromYours(fromYours, open) }
        }
        shelf["forYou"] = {
            // picked for you: the critics' records and songs in your order, seven near and three a stretch (Fresh), once
            // there's a taste to order them by
            if (!taste.empty && pickedForYou.isNotEmpty()) item(key = "for-you") { PickedForYou(pickedForYou, open) }
        }
        shelf["videos"] = {
            // their new music videos (MusicVideos.kt), shown once some are found
            item(key = "videos") { NewVideos(open) }
        }
        shelf["liveSets"] = {
            // live DJ sets from four channels that film them (LiveSets.kt)
            item(key = "live-sets") { LiveSetsShelf(open) }
        }
        shelf["yours"] = {
            if (first.isNotEmpty()) item {
                Column {
                val play = hourSongs.ifEmpty { first.flatMap { it.songs } }
                Section(Eggs.greeting() ?: if (hourRecords.isNotEmpty()) partOfDay(now) else "FROM YOUR SHELVES",
                    if (hourRecords.isNotEmpty()) "what you play at this hour" else "a few of yours, a new few each day") {
                    Word(if (hourRecords.isNotEmpty()) "play this hour" else "play these", pixel(14.sp), touch = 8.dp) { Player.play(play) }
                }
                // one big, the rest beside it in two rows: covers about the size of every other shelf's, a wider window more
                // of them, not bigger ones (sized by the window's share, a full screen's big one was ~590 dp and the shelf
                // the whole first screen: "some of the albums feel huge", 2026-11-08). The big one two covers and the gap.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val gap = 20.dp
                    val cols = ((maxWidth + gap) / (160.dp + gap)).toInt().coerceAtLeast(5)
                    val tile = (maxWidth - gap * (cols - 1)) / cols
                    val beside = cols - 2
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                        first.firstOrNull()?.let { a -> RecordTile(a, Modifier.width(tile * 2 + gap)) { open(Page.Record(a.id)) } }
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            first.drop(1).take(beside * 2).chunked(beside).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    row.forEach { a -> RecordTile(a, Modifier.width(tile)) { open(Page.Record(a.id)) } }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        shelf["madeForYou"] = {
            // made from your listening: fresh finds, deep cuts and the rest (Discover.kt)
            if (made.isNotEmpty()) item { MadeForYou() }
        }
        shelf["tubeMixes"] = {
            // YouTube Music's own mixes for you (signed in): supermix, discover, new release, replay
            if (tubeMixes.isNotEmpty()) item {
                Column {
                Section("YOUR MIXES", "youtube music's, made for you")
                Shelf {
                    items(tubeMixes, key = { it.url }) { a -> TubeTile(a, Modifier.width(170.dp)) { open(Page.Tube(a)) } }
                }
                }
            }
        }
        shelf["episodes"] = {
            if (episodes.isNotEmpty()) item {
                Column {
                Section("NEW EPISODES", "from the shows you follow") { Word("podcasts", pixel(14.sp, Ink.mid), touch = 8.dp) { Nav.go("podcasts") } }
                Shelf {
                    items(episodes.take(16), key = { it.id }) { e ->
                        Column(Modifier.width(170.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(listOf(e)) })) {
                            CoverOf(e, e.album, Modifier.fillMaxWidth().aspectRatio(1f), px = 340)
                            Spacer(Modifier.height(8.dp))
                            UnderCover(e.title, e.album)
                        }
                    }
                }
                }
            }
        }
        shelf["mixes"] = {
            if (mixes.isNotEmpty()) item {
                Column {
                Section("YOUR ARTISTS' MIXES", "who you play most, and more like them")
                Shelf {
                    itemsIndexed(mixes, key = { _, m -> m.name }) { _, m ->
                        Column(Modifier.width(170.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { mixScope.launch { Mixes.play(m) } })) {
                            Covered(m.face, Modifier.fillMaxWidth().aspectRatio(1f), px = 340)
                            Spacer(Modifier.height(8.dp))
                            UnderCover(m.name, "mix")
                        }
                    }
                }
                }
            }
        }
        shelf["because"] = {
            if (because != null) item { BecauseYouLoved(open) }
        }
        shelf["newToYou"] = {
            if (newTo.isNotEmpty()) item { NewToYou(open) }
        }
        shelf["jumpBack"] = {
            records("lately", "the records you've played lately", lately, open)
        }
        shelf["forgotten"] = {
            records("forgotten favorites", "ones you loved, quiet for a while", forgotten, open)
        }
        shelf["newest"] = {
            records("new on your server", "the newest there", newest, open)
        }
        shelf["inkRadio"] = {
            if (stars.isNotEmpty()) item {
                Column {
                Section("INK RADIO", "the stations we keep") { Word("all of them", pixel(14.sp, Ink.mid), touch = 8.dp) { Nav.go("ink") } }
                Shelf {
                    itemsIndexed(stars, key = { _, c -> c.song.id }) { i, c ->
                        Column(Modifier.width(170.dp).clickable(remember { MutableInteractionSource() }, indication = null,
                            onClick = felt { Player.play(stars.drop(i).map { it.song }) })) {
                            StationCover(c.song, Modifier.fillMaxWidth().aspectRatio(1f))
                            Spacer(Modifier.height(8.dp))
                            BasicText(c.city.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                }
            }
        }
        shelf["lists"] = {
            if (lists.isNotEmpty()) item {
                Column {
                    Section("YOUR LISTS", "on your server")
                    lists.take(8).forEach { p -> ListRow(p) { open(Page.List(p.id, p.name)) } }
                }
            }
        }
        for (k in order) shelf[k]?.invoke()
    }}
}

/** A line of what's new since you were here: what it is, a word on it, where it goes. */
class NewLine(val key: String, val title: String, val said: String, val go: () -> Unit)

/** Since you were here: the time said ("since this morning"), what's new under it, each a line to open. */
@Composable
private fun SinceYouWereHere(since: Long, lines: List<NewLine>) {
    Column(Modifier.widthIn(max = 640.dp)) {
        BasicText(Moment.since(since).uppercase(), style = headline(24.sp))
        Spacer(Modifier.height(8.dp))
        lines.forEach { l ->
            val src = remember { MutableInteractionSource() }
            val over by src.collectIsHoveredAsState()
            Row(Modifier.fillMaxWidth().hoverable(src).clickable(src, indication = null, onClick = felt { Fresh.tapped(l.key); l.go() }).padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top) {
                BasicText("+", style = pixel(14.sp), modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(l.title.uppercase(), style = headline(17.sp, if (over) brighter(Ink.ink) else Ink.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(l.said, style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Listen's shelves in their usual order (Fresh.order moves them for the moment); the phone's keys, where shared. */
val LISTEN_SHELVES = listOf("fromYours", "forYou", "videos", "liveSets", "yours", "madeForYou", "tubeMixes", "episodes", "mixes", "because",
    "newToYou", "jumpBack", "forgotten", "newest", "inkRadio", "lists")

/**
 * Know me (2026-11-11): your listening on Last.fm or ListenBrainz, brought in by name, so INK, listen and the mixes
 * know you from today; "not now" puts it away for good (settings has it too).
 */
@Composable
private fun KnowMe(shut: () -> Unit) {
    var where by remember { mutableStateOf("last.fm") }
    var name by remember { mutableStateOf("") }
    var bringing by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    fun go() {
        if (bringing || name.isBlank()) return
        bringing = true; said = null
        Fresh.bringIn(name, brainz = where == "listenbrainz") { b -> bringing = false; if (b == null) said = "$where has no listening under that name" }
    }
    Column(Modifier.widthIn(max = 560.dp)) {
        Section("DO YOU SCROBBLE?", "your listening on last.fm or listenbrainz, brought in by your name, so ink music knows you from today. nothing's sent, no password")
        Row {
            for (w in listOf("last.fm", "listenbrainz"))
                Word(w, pixel(14.sp, if (where == w) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { where = w }
        }
        Spacer(Modifier.height(10.dp))
        Field("your $where name", name, { name = it }, "", onEnter = ::go)
        said?.let { BasicText(it, style = pixel(12.sp, Ink.ink)); Spacer(Modifier.height(8.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Word(if (bringing) "BRINGING IT IN" else "BRING IT IN", headline(28.sp, if (name.isBlank() || bringing) Ink.dim else Ink.ink), Modifier.padding(end = 28.dp), touch = 8.dp) { go() }
            Word("not now", pixel(13.sp, Ink.mid), touch = 8.dp) { runCatching { java.io.File(Home.dir, "know-me-shut").writeText("") }; shut() }
        }
    }
}

/** New from your artists: each pick by someone you play, its cover, why it's here (out friday, or who picked it, and how often you've played them). */
@Composable
private fun NewFromYours(list: List<Picks.Shown>, open: (Page) -> Unit) {
    Column {
        Section("NEW FROM YOUR ARTISTS", "out this week or picked by critics, by artists you play")
        Shelf {
            items(list, key = { it.pick.from + it.pick.artist + it.pick.title }) { s ->
                val art by produceState(s.art, s) { value = Picks.artOf(s, s.pick.record) }
                Box(Modifier.width(176.dp)) { PickTile(remember(s, art) { s.copy(pick = s.pick.copy(art = art), art = art) }, open) }
            }
        }
    }
}

/** Picked for you: the critics' picks in your order, each saying why (a stretch, you love them, from your last.fm). */
@Composable
private fun PickedForYou(list: List<ForYou.Placed<Picks.Shown>>, open: (Page) -> Unit) {
    val taste by Fresh.taste.collectAsState()
    Column {
        Section("PICKED FOR YOU", "the critics' favorites, nearest you first, with a few stretches")
        Shelf {
            items(list, key = { "fy" + Curation.key(it.item.pick) }) { p ->
                Box(Modifier.width(176.dp)) { PickTile(p.item, open, why = Fresh.reason(p.item, p.stretch, taste)) }
            }
        }
    }
}

/** A shelf of records, sliding sideways. */
private fun androidx.compose.foundation.lazy.LazyListScope.records(label: String, line: String, albums: List<Album>, open: (Page) -> Unit) {
    if (albums.isEmpty()) return
    item {
        Column {
            Section(label.uppercase(), line)
            Shelf {
                items(albums, key = { it.id }) { a -> RecordTile(a, Modifier.width(176.dp)) { open(Page.Record(a.id)) } }
            }
        }
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
fun ListRow(p: Server.Playlist, said: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 8.dp)) {
        BasicText(p.name.uppercase(), style = headline(20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(said ?: ("${p.count} ${if (p.count == 1) "song" else "songs"}" + if (p.mine) "" else "   shared with you"), style = pixel(12.sp, Ink.mid))
    }
}

/** Lists: the songs you love, then your server's playlists. */
@Composable
fun ListsPage(open: (Page) -> Unit) {
    val lists by Server.playlists.collectAsState()
    val account by Server.account.collectAsState()
    val loved = lovedSongs()
    val keptIds by Offline.kept.collectAsState()
    val tubeListsNow by TubeAccount.playlists.collectAsState()
    val hereLists by HereLists.all.collectAsState()
    LaunchedEffect(account) { Server.readPlaylists() }
    val gridded by ListsGrid.on.collectAsState()
    if (gridded) return ListsTiles(open)
    val list = pageList()
    // a right click anywhere: a new list, empty or from files (2026-11-12, a tester: the way it's done everywhere)
    var menuAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    fun fresh(): String { var n = 1; while (hereLists.any { it.name == if (n == 1) "new list" else "new list $n" }) n++; return if (n == 1) "new list" else "new list $n" }
    Box(Modifier.fillMaxSize().rightClick { menuAt = it }) {
    menuAt?.let { at ->
        MenuAt(at, Ink.ink, listOf(listOf(
            "new list" to { val m = HereLists.make(fresh(), emptyList()); open(Page.List(m.id, m.name)) },
            "new list from files" to { chooseFiles { files -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val songs = Dropped.songsOf(files)
                if (songs.isNotEmpty()) { val m = HereLists.make(fresh(), songs); javax.swing.SwingUtilities.invokeLater { open(Page.List(m.id, m.name)) } }
            } } },
        ))) { menuAt = null }
    }
    LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 14.dp, bottom = 32.dp)) { cascading {
        item { ListsView() }
        if (loved.isNotEmpty()) item { ListRow(Server.Playlist(LOVED, "loved", loved.size, true)) { open(Page.List(LOVED, "loved")) } }
        // what's on the computer to play with no signal (your server's, YouTube's)
        if (keptIds.isNotEmpty()) item { ListRow(Server.Playlist(KEPT, "kept on this computer", keptIds.size, true)) { open(Page.List(KEPT, "kept on this computer")) } }
        // lists that fill themselves from rules (SmartLists), and a new one
        item { SmartRows(open) }
        // lists kept here, on this computer (HereLists): no account needed
        item { Label("here, on this computer") }
        items(hereLists, key = { it.id }) { m -> ListRow(Server.Playlist(m.id, m.name, m.songs.size, true)) { open(Page.List(m.id, m.name)) } }
        item { NewList { name -> val m = HereLists.make(name, emptyList()); open(Page.List(m.id, m.name)) } }
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
    }}
    }
}

/** Lists as a list or a grid: two words, the one chosen lit. */
@Composable
private fun ListsView() {
    val grid by ListsGrid.on.collectAsState()
    Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Word("list", pixel(14.sp, if (!grid) Ink.ink else Ink.dim), touch = 8.dp) { ListsGrid.set(false) }
        Word("grid", pixel(14.sp, if (grid) Ink.ink else Ink.dim), touch = 8.dp) { ListsGrid.set(true) }
    }
}

/** A list as a tile: its art square, its name and what it holds under it. */
@Composable
private fun ListTile(name: String, said: String, onClick: () -> Unit, art: @Composable () -> Unit) {
    Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Ink.black)) { art() }
        Spacer(Modifier.height(8.dp))
        UnderCover(name, said)
    }
}

/** A list's art from its songs: four of its records' covers, two by two; fewer records, the first; none, its name. */
@Composable
private fun Mosaic(songs: List<Song>, name: String) {
    val four = remember(songs) { songs.distinctBy { it.albumId }.take(4) }
    when {
        four.size == 4 -> Column(Modifier.fillMaxSize()) {
            four.chunked(2).forEach { row -> Row(Modifier.weight(1f)) { row.forEach { s -> CoverOf(s, s.album, Modifier.weight(1f).aspectRatio(1f), px = 320) } } }
        }
        four.isNotEmpty() -> CoverOf(four[0], four[0].album, Modifier.fillMaxSize(), px = 480)
        else -> Lettered(name)
    }
}

/**
 * Lists as a grid (the phone's, 2026-10-30): loved and kept as mosaics of their records; your server's playlists
 * their own art (Navidrome's mosaic); YouTube's lists theirs (YouTube's own made ink's, dithered).
 */
@Composable
private fun ListsTiles(open: (Page) -> Unit) {
    val lists by Server.playlists.collectAsState()
    val loved = lovedSongs()
    val keptIds by Offline.kept.collectAsState()
    val kept by produceState(emptyList<Song>(), keptIds) { value = Offline.songs() }
    val tubeLists by TubeAccount.playlists.collectAsState()
    val grid = pageGrid()
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        androidx.compose.foundation.lazy.grid.GridCells.Adaptive(176.dp), Modifier.fillMaxSize().feather(grid), grid,
        contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 14.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "view") { ListsView() }
        if (loved.isNotEmpty()) item(key = "loved") {
            ListTile("loved", "${loved.size} ${if (loved.size == 1) "song" else "songs"}", { open(Page.List(LOVED, "loved")) }) { Mosaic(loved, "loved") }
        }
        item(key = "year") {
            val minutes by produceState(0L) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Year.of(History.read()).minutes.toLong() } }
            ListTile("your year", if (minutes > 0) "%,d minutes so far".format(minutes) else "starts now", { open(Page.Year) }) { YearArt(minutes) }
        }
        if (keptIds.isNotEmpty()) item(key = "kept") {
            ListTile("kept on this computer", "${keptIds.size} ${if (keptIds.size == 1) "song" else "songs"}", { open(Page.List(KEPT, "kept on this computer")) }) { Mosaic(kept, "kept") }
        }
        gridItems(lists, key = { it.id }) { p ->
            ListTile(p.name, "${p.count} ${if (p.count == 1) "song" else "songs"}", { open(Page.List(p.id, p.name)) }) {
                // the server's own art for it (Navidrome's mosaic of its songs), by a song-shaped stand-in
                val face = remember(p.id, p.art) { p.art?.let { Song("npl" + p.id, "", p.name, "", p.name, "npl" + p.id, "", 0, 1, 0, 0, art = it) } }
                if (face != null) CoverOf(face, p.name, Modifier.fillMaxSize(), px = 480) else Lettered(p.name)
            }
        }
        gridItems(tubeLists, key = { it.url }) { t ->
            ListTile(t.title, listOf("youtube music", t.artist.lowercase()).filter { it.isNotEmpty() }.joinToString("  ·  "), { open(Page.Tube(t)) }) {
                if (t.art == null) Lettered(t.title)
                else if (t.branded) Covered(face(t), Modifier.fillMaxSize(), px = 400)
                else CoverOf(face(t), t.title, Modifier.fillMaxSize(), px = 480)
            }
        }
    }
}

/** A playlist: its name, its songs in its order, played from there. */
@Composable
fun ListPage(id: String, name: String, open: (Page) -> Unit, onClose: () -> Unit) {
    val covers by ListCovers.on.collectAsState()
    val loved = lovedSongs()
    val keptNow by Offline.kept.collectAsState()
    val here by HereLists.all.collectAsState()
    val songs by produceState<List<Song>?>(null, id, loved, keptNow, here) {
        value = when {
            id == LOVED -> loved
            id == KEPT -> Offline.songs()
            id.startsWith(HereLists.PREFIX) -> HereLists.of(id)?.songs.orEmpty()
            else -> Server.playlist(id)
        }
    }
    val list = pageList()
    // a list of yours: files dropped on the window go into it (Dropped), and files can be added from a folder
    val mine = id.startsWith(HereLists.PREFIX)
    androidx.compose.runtime.DisposableEffect(id) { if (mine) Dropped.into = id; onDispose { if (Dropped.into == id) Dropped.into = null } }
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 14.dp, bottom = 32.dp)) { cascading {
            item {
                // loved: ink's red heart beside its name, beating, as the phone's (2026-11-10)
                if (id == LOVED) Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(name.uppercase(), style = headline(56.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(18.dp))
                    Heartbeat { Sprite(HEART, RED, 6.dp) }
                } else BasicText(name.uppercase(), style = headline(56.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val s = songs
                // kept on this computer: how much room they take too (and each one's right click lets it go)
                val room = remember(keptNow) { if (id == KEPT) " · ${Offline.bytes() / 1_048_576} mb" else "" }
                BasicText(s?.let { "${it.size} ${if (it.size == 1) "song" else "songs"} · ${clock(it.sumOf { x -> x.duration })}$room" } ?: "reading", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(14.dp))
                if (!s.isNullOrEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Word("play", pixel(14.sp), touch = 8.dp) { Player.play(s) }
                    Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Player.play(s.shuffled()) }
                    MixWord(name, s)
                    CoversWord()
                    KeepOffline(s)
                }
                // the list written out: as words, as a spreadsheet, or every song's link (2026-11-11)
                if (!s.isNullOrEmpty()) {
                    var copied by remember { mutableStateOf(false) }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        Word("save as text", pixel(13.sp, Ink.mid), touch = 8.dp) { ListOut.save(name, "txt", ListOut.text(s)) }
                        Word("save as csv", pixel(13.sp, Ink.mid), touch = 8.dp) { ListOut.save(name, "csv", ListOut.csv(s)) }
                        Word(if (copied) "links copied" else "copy links", pixel(13.sp, Ink.mid), touch = 8.dp) { ListOut.copy(ListOut.links(s)); copied = true }
                        // a list here: let it go (a second click, as remove all's)
                        if (id.startsWith(HereLists.PREFIX)) {
                            var sure by remember { mutableStateOf(false) }
                            LaunchedEffect(sure) { if (sure) { kotlinx.coroutines.delay(3_000); sure = false } }
                            Word(if (sure) "delete it" else "delete list", pixel(13.sp, if (sure) Ink.ink else Ink.dim), touch = 8.dp) {
                                if (!sure) sure = true else { HereLists.delete(id); onClose() }
                            }
                        }
                    }
                }
                // yours: songs from the computer into it, chosen or dropped (2026-11-12, a tester)
                if (mine) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    Word("add files", pixel(13.sp), touch = 8.dp) { chooseFiles { files -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { HereLists.add(id, Dropped.songsOf(files)) } } }
                    BasicText("or drop them here", style = pixel(12.sp, Ink.mid))
                }
                MixProgress(name)
                Spacer(Modifier.height(14.dp))
            }
            val s = songs.orEmpty()
            itemsIndexed(s, key = { i, x -> "$i" + x.id }) { i, x ->
                SongRow(x, said = "${x.artist}  ·  ${x.album}".lowercase(), open = open, cover = covers,
                    onRemove = when {
                        id == KEPT -> ({ Offline.remove(listOf(x.id)) })
                        id.startsWith(HereLists.PREFIX) -> ({ HereLists.remove(id, i) })
                        else -> null
                    }, order = s) { Player.play(s, i) }
            }
        }}
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
            .entries.sortedByDescending { (name, list) -> (played[name.lowercase()] ?: 0) * 1000 + list.size }.map { it.key }
            // whom you follow first, then whom you play most
            .let { mine -> (Follows.all.value.map { it.name } + mine).distinctBy { it.lowercase() } }.take(8)
        val found = ArrayList<Mix>()
        val t0 = System.nanoTime()
        for (name in names) {
            val t = System.nanoTime()
            val info = kotlinx.coroutines.withTimeoutOrNull(8_000) { Artists.of(name, "").firstOrNull { it != null } }
            if (Log.on) Log.d("mixes: $name ${(System.nanoTime() - t) / 1_000_000} ms (${(System.nanoTime() - t0) / 1_000_000} ms in)${if (info == null) ", none" else ""}")
            info ?: continue
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

/** A year of ink music (or more), on the day: the first song you played here, to play again. */
@Composable
private fun Anniversary(f: History.Heard) {
    val years = Eggs.anniversary(f.at)
    val song = remember(f) { Library.song(f.id) ?: Library.songs.value.firstOrNull { it.title.equals(f.title, true) && it.artist.equals(f.artist, true) } }
    val day = remember(f) { java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(f.at)).lowercase() }
    Column {
        Section(if (years == 1) "A YEAR OF INK MUSIC" else "$years YEARS OF INK MUSIC", "the first song you played here, $day")
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverOf(song, f.album.ifEmpty { f.title }, Modifier.size(96.dp), px = 300)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                BasicText(f.title.uppercase(), style = headline(28.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(f.artist.lowercase(), style = pixel(13.sp, Ink.mid))
            }
            song?.let { s -> Word("PLAY IT AGAIN", headline(18.sp), touch = 8.dp) { Player.play(listOf(s)) } }
        }
    }
}

/** The list of what's kept on the computer ([Offline]), as a list's id. */
const val KEPT = "ink:kept"

/**
 * Songs kept on the computer, to play with no signal: "keep offline"; while they come, "keeping 4 of 12" filling in;
 * all here, "offline" (a click then "remove it", a second lets them go). Nothing for songs that can't be kept.
 */
@Composable
fun KeepOffline(songs: List<Song>) {
    val keepable = remember(songs) { songs.filter { Offline.keepable(it) } }
    if (keepable.isEmpty()) return
    val kept by Offline.kept.collectAsState()
    val keeping by Offline.keeping.collectAsState()
    val here = keepable.count { it.id in kept }
    val coming = keepable.filter { it.id in keeping }
    var sure by remember { mutableStateOf(false) }
    LaunchedEffect(sure) { if (sure) { kotlinx.coroutines.delay(3_000); sure = false } }
    when {
        coming.isNotEmpty() -> {
            val done = (here + coming.sumOf { (keeping[it.id] ?: 0f).toDouble() }.toFloat()) / keepable.size
            Box {
                val text = "keeping $here of ${keepable.size}"
                BasicText(text, style = pixel(14.sp, Ink.dim))
                BasicText(text, style = pixel(14.sp), modifier = Modifier.drawWithContent { clipRect(right = size.width * done) { this@drawWithContent.drawContent() } })
            }
        }
        here == keepable.size -> Word(if (sure) "remove it" else "offline", pixel(14.sp), touch = 8.dp) {
            if (!sure) sure = true else { sure = false; Offline.remove(keepable.map { it.id }) }
        }
        else -> Word("keep offline", pixel(14.sp, Ink.mid), touch = 8.dp) { Offline.keep(keepable) }
    }
}

/**
 * A list with no picture of its own: its name set big in ink's headline on black, from the foot up, a hairline round
 * it (the grey name cover read as a missing picture).
 */
@Composable
private fun Lettered(name: String) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Ink.black).border(1.dp, Ink.line)) {
        val size = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth * .15f).toSp() }
        BasicText(name.uppercase(), style = headline(size).copy(lineHeight = size * 1.02f), maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
}

/**
 * Your year as a tile: the year big (the minutes under the tile), and along the top edge the line of how far the year
 * has gone, filling as the progress line does.
 */
@Composable
private fun YearArt(minutes: Long) {
    val now = remember { java.time.LocalDate.now() }
    val gone = (now.dayOfYear - 1f) / now.lengthOfYear()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Ink.black).border(1.dp, Ink.line)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.line)) { Box(Modifier.fillMaxWidth(gone).fillMaxSize().background(Ink.ink)) }
        val size = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth * .3f).toSp() }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            BasicText("${now.year}", style = headline(size).copy(lineHeight = size), maxLines = 1)
        }
    }
}

/** A subtle heartbeat, the phone's: a quick double thump every second and a half, its content gently swelling. */
@Composable
private fun Heartbeat(content: @Composable () -> Unit) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "beat")
    val scale by t.animateFloat(1f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.keyframes { durationMillis = 1500; 1f at 0; 1.07f at 120; 1f at 250; 1.04f at 360; 1f at 520 }), label = "beat")
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { content() }
}
