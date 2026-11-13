package app.ink.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Smart playlists on the computer (SmartLists in core, as the phone's): kept in smart-lists.json; each filled as it's
 * opened from the songs you have (the library's, YouTube's liked and loved, the kept), as the songs page's pool.
 */
object Smart {
    private val file = File(Home.dir, "smart-lists.json")
    val lists = MutableStateFlow(runCatching { SmartLists.load(file.readText()) }.getOrDefault(emptyList()))
    private fun keep() { runCatching { Home.write(file, SmartLists.save(lists.value)) } }
    fun put(l: SmartLists.SmartList) { lists.value = if (lists.value.any { it.id == l.id }) lists.value.map { if (it.id == l.id) l else it } else lists.value + l; keep() }
    fun remove(id: String) { lists.value = lists.value.filter { it.id != id }; keep() }

    /** What's known of a computer song, for the rules. */
    fun facts(heard: Listening, loved: Set<String>, kept: Set<String>) = object : SmartLists.Facts<Song> {
        override fun id(s: Song) = s.id
        override fun loved(s: Song) = s.id in loved
        override fun plays(s: Song) = heard.plays[s.id] ?: 0
        override fun last(s: Song) = heard.last[s.id] ?: 0L
        override fun added(s: Song) = s.modified
        override fun bpm(s: Song) = Tempos.kept(s.id)?.bpm ?: 0.0
        override fun length(s: Song) = s.duration
        override fun from(s: Song) = when (SongsFrom.of(s)) { "youtube" -> "youtube"; "server" -> "your server"; else -> "this computer" }
        override fun kept(s: Song) = s.id in kept
        override fun genre(s: Song, g: String) = Genres.fits(s.artist, g)
    }
}

/** The songs a smart playlist picks from: as the songs page's (the library's, YouTube's liked and loved, the kept). */
@Composable
private fun smartPool(): List<Song> {
    val songs by Library.songs.collectAsState()
    val liked by TubeAccount.liked.collectAsState()
    val lovedTube by Loved.tube.collectAsState()
    val keptIds by Offline.kept.collectAsState()
    val keptSongs = remember(keptIds, songs) { Offline.songs() }
    return remember(songs, liked, lovedTube, keptSongs) {
        val have = songs.mapTo(HashSet()) { it.id }
        songs + (liked + lovedTube + keptSongs.filter { it.tube }).filter { it.id !in have }.distinctBy { it.id }
    }
}

/** A smart playlist's songs, picked now. */
@Composable
private fun smartSongs(l: SmartLists.SmartList?): List<Song> {
    val pool = smartPool()
    val heard by Listened.now.collectAsState()
    val loved by Loved.ids.collectAsState()
    val kept by Offline.kept.collectAsState()
    val tagged by GenrePicks.tagged.collectAsState()
    return produceState(emptyList<Song>(), l, pool, heard, loved, kept, tagged) {
        value = if (l == null) emptyList() else withContext(Dispatchers.Default) {
            SmartLists.pick(pool, l, Smart.facts(heard, loved, kept.toSet()), System.currentTimeMillis())
        }
    }.value
}

/** In lists: each smart playlist (its name, what its rules say) and a new one. */
@Composable
fun SmartRows(open: (Page) -> Unit) {
    val all by Smart.lists.collectAsState()
    Column {
        all.forEach { l ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Word(l.name.uppercase(), headline(20.sp), touch = 4.dp) { open(Page.Smart(l.id)) }
                BasicText("smart · ${SmartLists.said(l)}", style = pixel(12.sp, Ink.mid))
            }
        }
        Word("new smart playlist", pixel(13.sp, Ink.mid), Modifier.padding(vertical = 8.dp), touch = 8.dp) { open(Page.SmartEdit(null)) }
    }
}

/** A smart playlist's page: its name, what its rules say, play, shuffle, mix, change it; its songs, picked as it opens. */
@Composable
fun SmartPage(id: String, open: (Page) -> Unit, onClose: () -> Unit) {
    val all by Smart.lists.collectAsState()
    val l = all.firstOrNull { it.id == id }
    val songs = smartSongs(l)
    if (l == null) return Close(onClose)
    val list = pageList()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp)) {
            item {
                Column {
                    BasicText(l.name.uppercase(), style = headline(56.sp), maxLines = 2)
                    Spacer(Modifier.height(6.dp))
                    BasicText("smart · ${SmartLists.said(l)} · ${songs.size} ${if (songs.size == 1) "song" else "songs"}", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(14.dp))
                    if (songs.isNotEmpty()) PlayShuffle(songs)
                    HeadWords {
                        if (songs.isNotEmpty()) MixWord(l.name, songs)
                        Word("change", pixel(14.sp, Ink.mid), touch = 8.dp) { open(Page.SmartEdit(l.id)) }
                    }
                    if (songs.isEmpty()) BasicText("nothing meets all of these yet" + if (SmartLists.Kind.TEMPO in l.rules) ": a tempo is known once a song's been mixed" else "",
                        style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp))
                    Spacer(Modifier.height(12.dp))
                }
            }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, said = "${s.artist}  ·  ${s.album}".lowercase(), open = open, order = songs) { Player.play(songs, i) } }
        }
    }
}

/**
 * A smart playlist made or changed: its name, the starters (a new one), then each kind of rule with its choices as
 * words (one clicked sets it, clicked again lets it go), the order and how many, how many songs that finds now; save,
 * or delete.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SmartEditPage(id: String?, open: (Page) -> Unit, onClose: () -> Unit) {
    val was = Smart.lists.collectAsState().value.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(was?.name ?: "") }
    var rules by remember { mutableStateOf(was?.rules ?: emptyMap()) }
    var order by remember { mutableStateOf(was?.order ?: SmartLists.Order.SHUFFLED) }
    var most by remember { mutableStateOf(was?.most ?: 50) }
    val newId = remember { java.util.UUID.randomUUID().toString() }
    val draft = SmartLists.SmartList(was?.id ?: newId, name.ifBlank { "smart playlist" }, rules, order, most)
    val found = smartSongs(draft)
    val pool = smartPool()
    val tagged by GenrePicks.tagged.collectAsState()
    val genres = remember(pool, tagged) { Genres.names.map { g -> g to pool.count { Genres.fits(it.artist, g) } }.filter { it.second > 0 }.sortedByDescending { it.second }.take(12) }
    @Composable
    fun Choice(word: String, lit: Boolean, onClick: () -> Unit) = Word(word, pixel(14.sp, if (lit) Ink.ink else Ink.dim), touch = 8.dp, onClick = onClick)
    val list = pageList()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp)) {
            item {
                Column(Modifier.widthIn(max = READ)) {
                    BasicText(if (was == null) "NEW SMART PLAYLIST" else "CHANGE IT", style = headline(56.sp), maxLines = 2)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("name > ", style = pixel(14.sp, Ink.dim))
                        Box(Modifier.weight(1f)) {
                            if (name.isEmpty()) BasicText("gym, sunday, late", style = pixel(14.sp, Ink.dim))
                            BasicTextField(name, { name = it }, singleLine = true, textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                                modifier = Modifier.fillMaxWidth().typing())
                        }
                    }
                    if (was == null) {
                        Spacer(Modifier.height(18.dp))
                        BasicText("START FROM", style = headline(16.sp, Ink.mid))
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SmartLists.STARTERS.forEach { st -> Choice(st.name, rules == st.rules) { rules = st.rules; order = st.order; if (name.isBlank()) name = st.name } }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    BasicText("EVERY ONE OF THESE", style = headline(16.sp, Ink.mid))
                    SmartLists.Kind.entries.forEach { k ->
                        val choices = if (k == SmartLists.Kind.GENRE) genres.map { it.first }
                            else k.choices.map { if (it == "this phone") "this computer" else it }
                        if (choices.isEmpty()) return@forEach
                        Spacer(Modifier.height(10.dp))
                        BasicText(k.word, style = pixel(12.sp, Ink.mid))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            choices.forEach { c ->
                                // "this computer" kept as the rule's own word, which reads it as either device's
                                val value = if (c == "this computer") "this phone" else c
                                Choice(c, rules[k] == value) { rules = if (rules[k] == value) rules - k else rules + (k to value) }
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    BasicText("IN WHAT ORDER", style = headline(16.sp, Ink.mid))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { SmartLists.Order.entries.forEach { o -> Choice(o.word, order == o) { order = o } } }
                    Spacer(Modifier.height(10.dp))
                    BasicText("HOW MANY", style = headline(16.sp, Ink.mid))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { listOf(25, 50, 100, 0).forEach { n -> Choice(if (n == 0) "all" else "$n", most == n) { most = n } } }
                    Spacer(Modifier.height(22.dp))
                    BasicText(if (rules.isEmpty()) "pick at least one" else "finds ${found.size} ${if (found.size == 1) "song" else "songs"} now", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (rules.isNotEmpty()) Word("SAVE", headline(22.sp), Modifier.padding(end = 28.dp), touch = 8.dp) {
                            Smart.put(draft); Nav.back(); if (was == null) open(Page.Smart(draft.id))
                        }
                        if (was != null) Word("delete", pixel(14.sp, Ink.mid), touch = 8.dp) { Smart.remove(was.id); Nav.close() }
                    }
                }
            }
        }
    }
}
