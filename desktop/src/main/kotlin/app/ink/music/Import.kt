package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bringing a Spotify playlist in (the phone's since 2026-10-27; here 2026-11-23): your Spotify playlists listed with the
 * passes the phone hands this computer (it can't sign in to Spotify itself), or a file chosen (Exportify's CSV, Spotify's
 * own download); its songs matched, yours first, else YouTube Music's; then made here, on YouTube Music or your server.
 */
object Import {
    /** Each song as one that plays, in order (null where none was found), eight asked at a time; [found] told as it goes. */
    suspend fun match(songs: List<SpotifyLibrary.Said>, found: (Int) -> Unit = {}): List<Song?> = coroutineScope {
        val mine = Library.songs.value.associateBy { Radio.key(it.title, it.artist) }
        var done = 0
        songs.chunked(8).flatMap { batch ->
            batch.map { s ->
                async(Dispatchers.IO) {
                    (mine[Radio.key(s.title, s.artist)] ?: YouTube.find(s.title, s.artist)?.takeIf { SpotifyLibrary.sameArtist(it.artist, s.artist) })
                        .also { synchronized(this@Import) { done++; found(done) } }
                }
            }.awaitAll()
        }
    }
}

/**
 * A copy of a Spotify playlist kept in step with it (the phone's since 2026-11-23): with Spotify passes from the phone,
 * once a day the playlist's songs are read again and the ones added since found and put at the end of your copy (here, on
 * YouTube Music, or your server). Only added. Each link keeps the songs it has seen, in in-step.json.
 */
object InStep {
    data class Link(val spotify: String, val name: String, val where: String, val target: String, val known: Set<String>, val at: Long)

    val links = MutableStateFlow<List<Link>>(emptyList())
    private const val DAY = 24 * 3600 * 1000L
    private val file get() = File(Home.dir, "in-step.json")
    @Volatile private var running = false

    init {
        links.value = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val k = o.getJSONArray("known")
                Link(o.getString("spotify"), o.optString("name"), o.getString("where"), o.getString("target"), (0 until k.length()).map { k.getString(it) }.toSet(), o.optLong("at"))
            }
        }.getOrDefault(emptyList())
    }

    private fun save() = runCatching {
        file.writeText(JSONArray(links.value.map { l ->
            JSONObject().put("spotify", l.spotify).put("name", l.name).put("where", l.where).put("target", l.target).put("known", JSONArray(l.known.toList())).put("at", l.at)
        }).toString())
    }

    fun key(title: String, artist: String) = Search.fold(artist.substringBefore(", ") + " / " + title)

    fun link(spotify: String, name: String, where: String, target: String, songs: List<SpotifyLibrary.Said>) {
        links.value = links.value.filterNot { it.where == where && it.target == target } +
            Link(spotify, name, where, target, songs.map { key(it.title, it.artist) }.toSet(), System.currentTimeMillis())
        save()
    }

    fun unlink(where: String, target: String) { links.value = links.value.filterNot { it.where == where && it.target == target }; save() }

    /** Your Spotify playlist of the same name as a copy here (one made before keeping in step), with the passes there are. */
    suspend fun sameNamed(name: String): SpotifyLibrary.Theirs? = withContext(Dispatchers.IO) {
        val pass = SpotifyHanded.pass.value ?: return@withContext null
        runCatching { SpotifyLibrary.playlists(pass) }.getOrNull()?.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
    }

    suspend fun linkExisting(theirs: SpotifyLibrary.Theirs, where: String, target: String): Boolean = withContext(Dispatchers.IO) {
        val pass = SpotifyHanded.pass.value ?: return@withContext false
        val songs = runCatching { SpotifyLibrary.songs(theirs.id, pass) }.getOrNull() ?: return@withContext false
        link(theirs.id, theirs.name, where, target, songs); true
    }

    /** Every copy brought up to date, once a day each, with passes from the phone. */
    suspend fun catchUp() = withContext(Dispatchers.IO) {
        val pass = SpotifyHanded.pass.value ?: return@withContext
        if (running) return@withContext
        val due = links.value.filter { System.currentTimeMillis() - it.at > DAY }
        if (due.isEmpty()) return@withContext
        running = true
        try {
            for (l in due) {
                val songs = runCatching { SpotifyLibrary.songs(l.spotify, pass) }.getOrNull() ?: continue
                val fresh = songs.filter { key(it.title, it.artist) !in l.known }
                if (fresh.isNotEmpty()) {
                    val found = Import.match(fresh).filterNotNull()
                    when (l.where) {
                        "here" -> HereLists.add(l.target, found)
                        "youtube" -> found.forEach { TubeAccount.addTo(TubeAlbum("${InnerTube.BASE}/playlist?list=${l.target}", l.name, "", null), it) }
                        "server" -> found.forEach { Server.addTo(l.target, it) }
                    }
                    Log.d("in step: ${l.name}, ${fresh.size} new on spotify, ${found.size} added to ${l.where}")
                }
                links.value = links.value.map { if (it.where == l.where && it.target == l.target) it.copy(known = it.known + songs.map { s -> key(s.title, s.artist) }, at = System.currentTimeMillis()) else it }
                save()
            }
        } finally { running = false }
    }
}

/** Whether a copy is kept in step with Spotify: "in step with spotify" and stop; else, one of your playlists of its name, "keep in step". */
@Composable
fun InStepLine(where: String, target: String, name: String) {
    val scope = rememberCoroutineScope()
    val links by InStep.links.collectAsState()
    val link = links.firstOrNull { it.where == where && it.target == target }
    val pass by SpotifyHanded.pass.collectAsState()
    var said by remember(target) { mutableStateOf<String?>(null) }
    val theirs by produceState<SpotifyLibrary.Theirs?>(null, target, link, pass) { if (link == null && pass != null) value = InStep.sameNamed(name) }
    when {
        link != null -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("in step with spotify: its new songs come here too", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.width(16.dp))
            Word("stop", pixel(12.sp, Ink.mid), touch = 8.dp) { InStep.unlink(where, target) }
        }
        theirs != null -> Word("keep in step with spotify", pixel(13.sp, Ink.mid), Modifier.padding(top = 4.dp), touch = 8.dp) {
            val t = theirs ?: return@Word
            said = "reading it on spotify"
            scope.launch { said = if (InStep.linkExisting(t, where, target)) null else "spotify didn't say. open ink music on your phone, then try again" }
        }
    }
    said?.let { BasicText(it, style = pixel(12.sp, Ink.mid)) }
}

/** The import page: your Spotify playlists (with the phone's passes) or a file; one chosen, its songs found; made here, there or on your server. */
@Composable
fun ImportPage(open: (Page) -> Unit) {
    val scope = rememberCoroutineScope()
    val pass by SpotifyHanded.pass.collectAsState()
    var theirs by remember { mutableStateOf<List<SpotifyLibrary.Theirs>?>(null) }
    var chosen by remember { mutableStateOf<SpotifyLibrary.Listed?>(null) }
    var fromSpotify by remember { mutableStateOf<String?>(null) }
    var lists by remember { mutableStateOf<List<SpotifyLibrary.Listed>?>(null) }
    var reading by remember { mutableStateOf<Int?>(null) }
    var progress by remember { mutableStateOf(0) }
    var matched by remember { mutableStateOf<List<Song?>?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pass) { val p = pass ?: return@LaunchedEffect; if (theirs == null) theirs = withContext(Dispatchers.IO) { runCatching { SpotifyLibrary.playlists(p) }.getOrNull() } }
    LaunchedEffect(chosen) { val c = chosen ?: return@LaunchedEffect; progress = 0; matched = null; matched = Import.match(c.songs) { progress = it } }
    fun pick(t: SpotifyLibrary.Theirs) = scope.launch {
        val p = pass ?: return@launch
        reading = 0; said = null
        val songs = withContext(Dispatchers.IO) { runCatching { SpotifyLibrary.songs(t.id, p) { reading = it } }.getOrNull() }
        reading = null
        if (songs.isNullOrEmpty()) said = "spotify didn't give that one's songs" else { chosen = SpotifyLibrary.Listed(t.name, songs); fromSpotify = t.id }
    }
    fun file() {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "A playlist from Spotify", java.awt.FileDialog.LOAD)
        d.isVisible = true
        val f = d.file?.let { File(d.directory, it) } ?: return
        val read = runCatching { SpotifyLibrary.parse(f.readText(), f.nameWithoutExtension.replace('_', ' ')) }.getOrDefault(emptyList())
        lists = read; chosen = read.singleOrNull(); matched = null; fromSpotify = null
        said = if (read.isEmpty()) "no playlist in that file: an exportify csv, or spotify's Playlist1.json" else null
    }
    val c = chosen
    val m = matched
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 24.dp, bottom = 40.dp)) {
        item {
            BasicText((c?.name ?: "FROM SPOTIFY").uppercase(), style = headline(44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            when {
                c == null && reading != null -> BasicText("reading it: $reading songs", style = pixel(12.sp, Ink.mid))
                c == null -> {
                    BasicText(if (pass != null) "your spotify playlists, from your phone's sign-in. pick one, or a file" else
                        "your spotify playlists come from your phone's sign-in: open ink music there while it's paired. or a file: an exportify.app csv, or spotify's own download (Playlist1.json)",
                        style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(bottom = 12.dp))
                    Row { Word("choose a file", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { file() }; Word("exportify.app", pixel(14.sp, Ink.mid), touch = 8.dp) { browse("https://exportify.app") } }
                }
                m == null -> BasicText("finding $progress of ${c.songs.size}", style = pixel(12.sp, Ink.mid))
                else -> {
                    val found = m.filterNotNull()
                    BasicText("${found.size} of ${c.songs.size} found   ${found.count { !it.tube }} yours", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(14.dp))
                    Word("play it", pixel(14.sp), touch = 8.dp) { Player.play(found) }
                    MakeIt(c.name, found, open, fromSpotify, c.songs)
                }
            }
            said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(14.dp))
        }
        if (c == null && reading == null) theirs?.let { all ->
            items(all, key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { pick(t) }).padding(vertical = 9.dp)) {
                    BasicText(t.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (t.by.isNotBlank()) BasicText(t.by.lowercase(), style = pixel(12.sp, Ink.mid))
                }
            }
        }
        if (c == null) lists?.takeIf { it.size > 1 }?.let { all ->
            items(all) { l ->
                Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { chosen = l }).padding(vertical = 9.dp)) {
                    BasicText(l.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    BasicText("${l.songs.size} songs", style = pixel(12.sp, Ink.mid))
                }
            }
        }
        if (c != null && m != null) itemsIndexed(c.songs) { i, s ->
            val song = m.getOrNull(i)
            if (song != null) SongRow(song) { Player.play(m.filterNotNull(), m.subList(0, i).count { it != null }) }
            else Column(Modifier.padding(vertical = 8.dp)) {
                BasicText(s.title.uppercase(), style = headline(18.sp, Ink.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText("${s.artist.lowercase()}   not found", style = pixel(12.sp, Ink.dim))
            }
        }
    }
}

/** Where an import's songs are made into a playlist: here, on YouTube Music, on your server; kept in step when it came from Spotify. */
@Composable
private fun MakeIt(name: String, found: List<Song>, open: (Page) -> Unit, spotify: String?, songs: List<SpotifyLibrary.Said>) {
    val scope = rememberCoroutineScope()
    var making by remember(name, found) { mutableStateOf<String?>(null) }
    val made = remember(name, found) { mutableStateMapOf<String, Pair<String, Page>>() }
    val who by TubeAccount.who.collectAsState()
    val account by Server.account.collectAsState()
    @Composable
    fun Place(key: String, where: String, make: suspend () -> Pair<String, Page>?) {
        Spacer(Modifier.height(10.dp))
        val done = made[key]
        when {
            done != null -> Column {
                Row {
                    BasicText("made $where", style = pixel(14.sp), modifier = Modifier.padding(end = 26.dp, top = 8.dp))
                    Word("open it", pixel(14.sp), touch = 8.dp) { open(done.second) }
                }
                if (spotify != null) InStepLine(key, done.first, name)
            }
            making == key -> BasicText("making it $where", style = pixel(14.sp, Ink.dim), modifier = Modifier.padding(top = 8.dp))
            else -> Word("make it $where", pixel(14.sp, Ink.mid), touch = 8.dp) {
                if (making != null) return@Word
                making = key
                scope.launch {
                    make()?.let { r -> made[key] = r; if (spotify != null) InStep.link(spotify, name, key, r.first, songs) }
                    making = null
                }
            }
        }
    }
    Place("here", "here") { val l = HereLists.make(name, found); l.id to Page.List(l.id, l.name) }
    if (who != null) Place("youtube", "on youtube music") {
        if (!TubeAccount.makePlaylist(name, found)) null
        else TubeAccount.playlists.value.firstOrNull()?.let { t -> TubeAccount.listId(t) to Page.Tube(t) }
    }
    if (account != null) Place("server", "on your server") {
        val before = Server.playlists.value.filter { it.name == name }.map { it.id }.toSet()
        if (!Server.makePlaylist(name, found)) null
        else Server.playlists.value.firstOrNull { it.name == name && it.id !in before }?.let { it.id to Page.List(it.id, it.name) }
    }
}
