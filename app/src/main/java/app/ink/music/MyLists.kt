package app.ink.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Playlists of your own, as they're kept (2026-11-24, #4 and #6: only smart lists and the server's before, and adding a
 * song to one took too long): any song in them (your files, your server's, YouTube Music's), each once. The computer's
 * HereLists' file, the same shape: a list a line of [{id, name, songs}], ids "here:" and a number, so the two could be
 * kept in step ([ListsSync], since 2026-11-24); `used` beside them, when a song last went in (add to a playlist offers the
 * latest first), and `at`, when the list last changed (its name, its songs, their order: the latest change wins in a sync).
 * Written here with nothing of Android's, so it's tried on its own (ListsFileTest).
 */
object ListsFile {
    data class Mine<S>(val id: String, val name: String, val songs: List<S>, val used: Long = 0, val at: Long = 0)

    const val PREFIX = "here:"

    fun <S : Any> read(text: String, songOf: (JSONObject) -> S?): List<Mine<S>> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val s = o.optJSONArray("songs")
            val songs = if (s == null) emptyList() else (0 until s.length()).mapNotNull { k -> s.optJSONObject(k)?.let { runCatching { songOf(it) }.getOrNull() } }
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            Mine(id, o.optString("name").ifBlank { "playlist" }, songs, o.optLong("used"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    fun <S> write(lists: List<Mine<S>>, json: (S) -> JSONObject): String = JSONArray(lists.map { m ->
        JSONObject().put("id", m.id).put("name", m.name).put("songs", JSONArray(m.songs.map(json))).put("used", m.used).put("at", m.at)
    }).toString()

    /** A new list at the end, with these songs (each once); its id a moment no other list has. */
    fun <S> make(lists: List<Mine<S>>, name: String, songs: List<S>, now: Long, key: (S) -> String): Pair<List<Mine<S>>, Mine<S>> {
        var at = now
        while (lists.any { it.id == PREFIX + at }) at++
        val m = Mine(PREFIX + at, name.trim().ifEmpty { "playlist" }, songs.distinctBy(key), now, now)
        return (lists + m) to m
    }

    /** These songs onto the end of a list, the ones it hasn't already. */
    fun <S> add(lists: List<Mine<S>>, id: String, songs: List<S>, now: Long, key: (S) -> String) = lists.map { m ->
        if (m.id != id) m else m.copy(songs = (m.songs + songs).distinctBy(key), used = now)
    }

    /** The song at `index` out of a list. */
    fun <S> removeAt(lists: List<Mine<S>>, id: String, index: Int) = lists.map { m ->
        if (m.id != id) m else m.copy(songs = m.songs.filterIndexed { i, _ -> i != index })
    }

    /** A list's song at `from` carried to `to`. */
    fun <S> move(lists: List<Mine<S>>, id: String, from: Int, to: Int) = lists.map { m ->
        if (m.id != id || from !in m.songs.indices || to !in m.songs.indices || from == to) m
        else m.copy(songs = m.songs.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** A new name (a blank one leaves it as it was). */
    fun <S> rename(lists: List<Mine<S>>, id: String, name: String): List<Mine<S>> {
        val n = name.trim().ifEmpty { return lists }
        return lists.map { m -> if (m.id != id) m else m.copy(name = n) }
    }

    fun <S> delete(lists: List<Mine<S>>, id: String) = lists.filter { it.id != id }

    /**
     * After a change: the lists it touched stamped with `now` (one new, renamed, its songs or their order changed), and
     * the ids of those it deleted.
     */
    fun <S> stamped(old: List<Mine<S>>, new: List<Mine<S>>, now: Long, key: (S) -> String): Pair<List<Mine<S>>, Set<String>> {
        val (changed, gone) = ListsSync.touched(old, new, { it.id }) { a, b -> a.name == b.name && a.songs.map(key) == b.songs.map(key) }
        return new.map { if (it.id in changed) it.copy(at = now) else it } to gone
    }

    /** A list as it goes to the other device, its songs as `wire` tells them. */
    fun <S> wired(m: Mine<S>, wire: (S) -> JSONObject) = ListsSync.Wired(m.id, m.name, m.songs.map(wire), m.at)

    /**
     * The other's lists merged with these: each list the one changed last (one of theirs made into songs here by
     * `song`, each once), the deletions either knows. Null when nothing here changed.
     */
    fun <S : Any> merged(mine: List<Mine<S>>, myGone: Map<String, Long>, part: JSONObject, wire: (S) -> JSONObject,
                   song: (JSONObject) -> S?, key: (S) -> String): Pair<List<Mine<S>>, Map<String, Long>>? {
        val (theirs, theirGone) = ListsSync.read(part)
        val m = ListsSync.merge(mine.map { wired(it, wire) }, myGone, theirs, theirGone)
        if (!m.changed) return null
        val byId = mine.associateBy { it.id }
        val lists = m.lists.map { k ->
            val l = k.list
            if (!k.theirs) byId.getValue(l.id)
            else Mine(l.id, l.name, l.songs.mapNotNull { runCatching { song(it) }.getOrNull() }.distinctBy(key), byId[l.id]?.used ?: l.at, l.at)
        }
        return lists to m.gone
    }

    /** The lists a song was last put in first (one never added to after, as it was made). */
    fun <S> latest(lists: List<Mine<S>>) = lists.sortedByDescending { it.used }
}

/**
 * Your playlists on this phone, read and written by the screens (files/lists.json; the player could read it too, as a
 * file, never prefs). A station or an episode isn't a song to keep in one.
 */
object MyLists {
    val all = MutableStateFlow<List<ListsFile.Mine<Song>>>(emptyList())
    private var file: File? = null

    @Synchronized fun start(c: android.content.Context) {
        if (file != null) return
        file = File(c.filesDir, "lists.json").also { f ->
            all.value = runCatching { ListsFile.read(f.readText(), ::songOf) }.getOrDefault(emptyList())
            all.value.forEach { m -> m.songs.filter { it.tube }.forEach(YouTubeMusic::remember) }
        }
    }

    /** A change made here: the lists it touched stamped now, a deleted one's id marked (lists-gone.json), for the sync. */
    @Synchronized private fun keep(v: List<ListsFile.Mine<Song>>) {
        val now = System.currentTimeMillis()
        val (stamped, gone) = ListsFile.stamped(all.value, v, now) { it.id }
        file?.parentFile?.let { ListsSync.Gone(it).add(gone, now) }
        write(stamped)
    }

    private fun write(v: List<ListsFile.Mine<Song>>) {
        all.value = v
        file?.let { f -> runCatching { Keep.write(f, ListsFile.write(v) { it.json() }) } }
    }

    /** These lists as the computer's told them ([ListsSync.part]). */
    fun syncPart(c: android.content.Context, wire: (Song) -> JSONObject): JSONObject {
        start(c)
        return ListsSync.part(all.value.map { ListsFile.wired(it, wire) }, ListsSync.Gone(c.filesDir).read())
    }

    /**
     * The computer's lists taken in: each the one changed last, as it was. A song of theirs is the same one here where
     * it can be (`song`: a server's or YouTube's by id, a file by its name and artist), else kept as it was told, so
     * the list goes back whole: a file only the computer has shows here and doesn't play.
     */
    @Synchronized fun takeSync(c: android.content.Context, part: JSONObject, song: (JSONObject) -> Song?) {
        start(c)
        val gone = ListsSync.Gone(c.filesDir)
        val (v, g) = ListsFile.merged(all.value, gone.read(), part, { SyncPhone.wire(it) }, { song(it) ?: away(it) }) { it.id } ?: return
        gone.write(g)
        v.forEach { m -> m.songs.filter { it.tube }.forEach(YouTubeMusic::remember) }
        write(v)
    }

    /** A song in a list from the computer that isn't here (its own file): kept by what it's called, with nowhere to play from. */
    private fun away(o: JSONObject): Song? {
        val id = o.optString("id").ifEmpty { return null }
        val title = o.optString("title"); val artist = o.optString("artist")
        return Song(id = id, uri = android.net.Uri.EMPTY, title = title, artist = artist, album = o.optString("album"),
            albumId = o.optString("albumId").ifEmpty { id }, albumArtist = o.optString("albumArtist").ifEmpty { artist },
            duration = o.optLong("duration"), disc = 0, track = 0, year = 0, art = o.optString("art").ifEmpty { null })
    }

    fun fits(s: Song) = !s.station && !s.episode
    fun of(id: String) = all.value.firstOrNull { it.id == id }

    fun make(name: String, songs: List<Song>): ListsFile.Mine<Song> {
        val (v, m) = ListsFile.make(all.value, name, songs.filter(::fits), System.currentTimeMillis()) { it.id }
        keep(v); return m
    }
    fun add(id: String, songs: List<Song>) = keep(ListsFile.add(all.value, id, songs.filter(::fits), System.currentTimeMillis()) { it.id })
    fun remove(id: String, index: Int) = keep(ListsFile.removeAt(all.value, id, index))
    fun move(id: String, from: Int, to: Int) = keep(ListsFile.move(all.value, id, from, to))
    fun rename(id: String, name: String) = keep(ListsFile.rename(all.value, id, name))
    fun delete(id: String) = keep(ListsFile.delete(all.value, id))
}

/**
 * A whole record into the queue (#4): play next keeps it in its order, each put after the one before; nothing playing,
 * it simply plays.
 */
object Whole {
    fun next(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (Playback.queue.value.isEmpty()) Playback.play(songs, 0) else songs.asReversed().forEach { Playback.playNext(it) }
    }
    fun queue(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (Playback.queue.value.isEmpty()) Playback.play(songs, 0) else songs.forEach { Playback.add(it) }
    }
}

/** In lists: your own playlists (a tap opens one), then "new playlist": named there, it opens. */
@Composable
fun MineRows(pages: Pages) {
    val mine by MyLists.all.collectAsState()
    mine.forEach { m -> ListRow(m.name, "${m.songs.size} ${if (m.songs.size == 1) "song" else "songs"}") { pages.open(Page.Mine(m.id)) } }
    NewMine(pages)
}

/** "new playlist", and once tapped its name typed in place: made on the phone and opened. */
@Composable
fun NewMine(pages: Pages) {
    var naming by remember { mutableStateOf(false) }
    if (naming) Box(Modifier.padding(vertical = 8.dp)) {
        NewPlaylist(null, focused = true, make = { val m = MyLists.make(it, emptyList()); pages.open(Page.Mine(m.id)); true }) { naming = false }
    }
    else Word("new playlist", pixel(13.sp, Ink.mid), Modifier.padding(vertical = 8.dp), touch = 8.dp) { naming = true }
}

private val ROW = 58.dp   // carried rows all one height, so the one carried knows the place it's over

/**
 * One of your playlists: its name big, play, shuffle, mix, covers, offline; rename, order, delete (asks once more). Its
 * songs as anywhere, held for more (remove among it); "order" turns them into rows to hold and carry, as up next's.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MinePage(id: String, pages: Pages) {
    val lists by MyLists.all.collectAsState()
    val m = lists.firstOrNull { it.id == id } ?: return
    val songs = m.songs
    val covers by ListCovers.on.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }
    var sure by remember { mutableStateOf(false) }
    LaunchedEffect(sure) { if (sure) { delay(3_000); sure = false } }
    var find by remember { mutableStateOf("") }
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> ListPageBody(frost, top, list, head = { Column(Modifier.cascade(0)) {
            if (renaming) {
                var name by remember { mutableStateOf(m.name) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                fun done() { MyLists.rename(id, name); renaming = false }
                BasicTextField(name, { name = it }, singleLine = true, textStyle = headline(36.sp), cursorBrush = SolidColor(Ink.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { done() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
                Word("done", pixel(14.sp), Modifier.padding(top = 6.dp), touch = 8.dp) { done() }
            } else BasicText(m.name.uppercase(), style = headline(if (Device.tv) 30.sp else 44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            BasicText(
                listOfNotNull("${songs.size} ${if (songs.size == 1) "song" else "songs"}", songs.sumOf { it.duration }.takeIf { it > 0 }?.let { "${it / 60_000} min" },
                    "on this ${Device.word}").joinToString("   "),
                style = pixel(12.sp, Ink.mid),
            )
            Spacer(Modifier.height(16.dp))
            if (songs.isNotEmpty()) FlowRow {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(songs, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(songs.shuffled(), 0) }
                MixWord(m.name, songs, Modifier.padding(end = 26.dp))
                CoversWord(Modifier.padding(end = 26.dp))
                KeepOffline(songs)
            }
            MixProgress(m.name)
            FlowRow(Modifier.padding(top = 10.dp)) {
                Word("rename", pixel(12.sp, Ink.mid), Modifier.padding(end = 22.dp), touch = 8.dp) { renaming = true }
                if (songs.size > 1) Word(if (ordering) "done ordering" else "order", pixel(12.sp, if (ordering) Ink.ink else Ink.mid), Modifier.padding(end = 22.dp), touch = 8.dp) { ordering = !ordering }
                // deleting asks once more: the word says so, lit, for a moment
                Word(if (sure) "delete it" else "delete", pixel(12.sp, if (sure) Ink.ink else Ink.mid), touch = 8.dp) {
                    if (!sure) sure = true else { pages.back(); MyLists.delete(id) }
                }
            }
            if (songs.isEmpty()) BasicText("hold any song, then add to playlist, and it's in here", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp))
            if (ordering) BasicText("hold a song and carry it to its place", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(14.dp))
        }}) {
        if (ordering) {
            // no keys here either: a row is its place, the carried one drawn where the finger has it
            item(key = "carry") { Carried(id, songs) }
        } else {
            // found in it: each song keeps its own place in the list (taking one out is by its place)
            val shown = songs.withIndex().filter { matches(it.value, find) }
            if (songs.size > 8) item(key = "find") { Column(Modifier.padding(bottom = 10.dp)) { ListFind(find, { find = it }); Narrowed(shown.size, songs.size, find) } }
            itemsIndexed(shown, key = { _, (_, s) -> s.id }) { k, (i, s) -> Box(Modifier.cascade(k + 1)) {
                SongRow(s, pages = pages, cover = covers, onRemove = { MyLists.remove(id, i) }) { Playback.play(shown.map { it.value }, k) }
            }}
        }
    } }
}

/**
 * A list's songs to put in order: hold one and it lifts, carried the others step aside to where it would go (a tick a
 * place), let go it settles there. As up next's rows, all in one column so the carried one stays over the rest.
 */
@Composable
private fun Carried(id: String, songs: List<Song>) {
    val haptic = LocalHapticFeedback.current
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    var lifted by remember { mutableStateOf<Int?>(null) }
    var drag by remember { mutableFloatStateOf(0f) }
    val target = lifted?.let { (it + (drag / rowPx).roundToInt()).coerceIn(0, (songs.size - 1).coerceAtLeast(0)) }
    var lastTarget by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(target) {
        if (target != null && lastTarget != null && target != lastTarget) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        lastTarget = target
    }
    Column {
        songs.forEachIndexed { i, s ->
            val l = lifted
            val shift = when {
                l == null || target == null || i == l -> 0f
                i in (l + 1)..target -> -rowPx
                i in target until l -> rowPx
                else -> 0f
            }
            val eased by animateFloatAsState(shift, tween(Motion.ENTER, easing = Motion.expo), label = "make room")
            val tone = if (l != null && i != l) Ink.dim else Ink.ink
            Row(
                Modifier.fillMaxWidth().height(ROW).zIndex(if (i == l) 1f else 0f)
                    .graphicsLayer { translationY = if (i == l) drag else eased }
                    .pointerInput(i, songs.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); lifted = i; drag = 0f },
                            onDragEnd = {
                                val to = (i + kotlin.math.round(drag / rowPx).toInt()).coerceIn(0, (songs.size - 1).coerceAtLeast(0))
                                if (to != i) MyLists.move(id, i, to)
                                lifted = null; drag = 0f
                            },
                            onDragCancel = { lifted = null; drag = 0f },
                        ) { c, d -> c.consume(); drag += d.y }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("${i + 1}", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(s.title.uppercase(), style = headline(18.sp, tone), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(s.artist.lowercase(), style = pixel(12.sp, if (tone == Ink.ink) Ink.mid else Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Your records saved on YouTube Music, among the albums tab's after your own (#4: "an option to show your youtube music
 * library in your ink library"; settings, your music, youtube music). Off till chosen; the full build's only.
 */
object TubeInLibrary {
    val on = MutableStateFlow(false)
    fun start(c: android.content.Context) { on.value = YouTubeAccount.available && c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean("tube-in-library", false) }
    fun set(c: android.content.Context, v: Boolean) { on.value = v; c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("tube-in-library", v).apply() }
}

/** Under a record's play (or a YouTube list's): the whole of it next, at the queue's end, or into a playlist; a word the moment after. */
@Composable
fun WholeWords(songs: List<Song>, pages: Pages) {
    var said by remember(songs) { mutableStateOf<String?>(null) }
    LaunchedEffect(said) { if (said != null) { delay(1_400); said = null } }
    Row(Modifier.padding(top = 10.dp)) {
        said?.let { BasicText(it, style = pixel(13.sp), modifier = Modifier.padding(vertical = 8.dp)) } ?: run {
            Word("play next", pixel(13.sp, Ink.mid), Modifier.padding(end = 22.dp), touch = 8.dp) { Whole.next(songs); said = "playing next" }
            Word("add to queue", pixel(13.sp, Ink.mid), Modifier.padding(end = 22.dp), touch = 8.dp) { Whole.queue(songs); said = "queued" }
            Word("add to playlist", pixel(13.sp, Ink.mid), touch = 8.dp) { pages.open(Page.AddToMany(songs)) }
        }
    }
}
