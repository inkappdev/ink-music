package app.ink.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The songs you love (the phone's Loved): a server song's love is its star on the server, so the phone, the web
 * player and every other app agree, and a star made elsewhere shows here; a YouTube song's is kept on the computer
 * with its words (it's in no library), and so is a file's.
 */
object Loved {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids
    private val _tube = MutableStateFlow<List<Song>>(emptyList())
    /** The YouTube songs loved, to list and play though no search found them this run. */
    val tube: StateFlow<List<Song>> = _tube
    private var local: Set<String> = emptySet()
    /** Your liked songs on YouTube Music (signed in): loved here too. */
    private var liked: List<Song> = emptyList()
    private var server: Set<String> = emptySet()
    /** When each was loved (ms), where it's known: loved here, or a star's date on the server. */
    private var dates: Map<String, Long> = emptyMap()
    private val file = File(Home.dir, "loved.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        runCatching {
            val o = JSONObject(file.readText())
            local = o.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            server = o.optJSONArray("server")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            _tube.value = o.optJSONArray("tube")?.let { a -> (0 until a.length()).map { songOf(a.getJSONObject(it)) } }.orEmpty()
            dates = o.optJSONObject("when")?.let { w -> w.keys().asSequence().associateWith { w.getLong(it) } }.orEmpty()
        }
        _tube.value.forEach(YouTube::remember)
        publish()
    }

    /** The server's stars and their dates, as it last said (a sync). */
    fun putServer(starred: Set<String>, at: Map<String, Long>) { server = starred; dates = dates + at; publish(); keep() }

    /** When the song was loved, if known. */
    fun lovedAt(s: Song): Long? = dates[s.id]

    /** Your liked songs on YouTube Music, as last read (signed in; empty signed out). */
    fun putTube(songs: List<Song>) { liked = songs; songs.forEach(YouTube::remember); publish() }

    /** Every YouTube song loved: kept here, and liked there. */
    fun tubeSongs(): List<Song> = (_tube.value + liked).distinctBy { it.id }

    @Synchronized private fun publish() { _ids.value = local + server + _tube.value.map { it.id } + liked.map { it.id } }

    fun toggle(s: Song) {
        if (s.station) return
        val on = s.id !in _ids.value
        dates = if (on) dates + (s.id to System.currentTimeMillis()) else dates - s.id
        when {
            s.server -> { server = if (on) server + s.id else server - s.id; scope.launch { Server.star(s.id, on) } }
            // signed in, a like there (and among your liked); else kept here
            s.tube && TubeAccount.who.value != null -> {
                TubeAccount.like(s, on)
                liked = if (on) listOf(s) + liked else liked.filter { it.id != s.id }
                _tube.value = _tube.value.filter { it.id != s.id }
            }
            s.tube -> _tube.value = if (on) _tube.value + s else _tube.value.filter { it.id != s.id }
            else -> local = if (on) local + s.id else local - s.id
        }
        publish()
        keep()
        // told to a paired phone at its next call
        SyncHub.loved(s, on)
    }

    private fun keep() {
        val text = JSONObject().put("ids", JSONArray(local.toList())).put("server", JSONArray(server.toList()))
            .put("tube", JSONArray().apply { _tube.value.forEach { put(it.json()) } }).put("when", JSONObject(dates)).toString()
        scope.launch { runCatching { Home.write(file, text) } }
    }
}

/** The desktop bar's heart (waybar/heart/make-hearts.py), 7 x 6 on Silkscreen's grid. */
val HEART = listOf(".##.##.", "#######", "#######", ".#####.", "..###..", "...#...")

/** The heart's red, the desktop's: shown only a moment after loving, the one colour that isn't the art. */
private val RED = Color(0xFFE8302A)

/**
 * A song's heart (the phone's): 35% while not loved; loving it, red for two seconds, then ink (loved, the rest of the
 * time). A click loves or unloves; a step brighter under the mouse.
 */
@Composable
fun Heart(song: Song?, cell: Dp = 3.dp, modifier: Modifier = Modifier) {
    if (song?.station == true) return
    val loved by Loved.ids.collectAsState()
    val on = song != null && song.id in loved
    var lovedAt by remember { mutableLongStateOf(0L) }
    var red by remember { mutableStateOf(false) }
    LaunchedEffect(lovedAt) { if (lovedAt > 0) { red = true; delay(2_000); red = false } }
    LaunchedEffect(on) { if (!on) red = false }
    val colour by animateColorAsState(
        when { red -> RED; on -> Ink.ink; else -> Ink.dim },
        tween(if (red) Motion.FADE else Motion.ENTER, easing = Motion.expo), label = "heart",
    )
    Pixel(HEART, if (on) "unlove" else "love", colour, modifier, cell = cell) {
        val s = song ?: return@Pixel
        if (s.id !in loved) lovedAt = System.currentTimeMillis()
        Loved.toggle(s)
    }
}
