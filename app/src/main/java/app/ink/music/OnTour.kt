package app.ink.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Your artists on tour, near you (2026-11-23): the forty you play most (your history, else the library's biggest), their
 * shows from every source there is ([Tours]: Spotify's artist pages while you're signed in there, Ticketmaster's with the
 * build's key), read once a day and kept in files/tours.json; the city chosen once in settings (listening, on tour), kept
 * in files/tour-city.json. INK shows the ones near it.
 */
object OnTour {
    val place = MutableStateFlow<Tours.Place?>(null)
    val shows = MutableStateFlow<List<Tours.Show>>(emptyList())
    /** Reading the shows now. */
    val reading = MutableStateFlow(false)
    private const val DAY = 24 * 3600 * 1000L
    @Volatile private var started = false

    private fun placeFile(c: Context) = File(c.filesDir, "tour-city.json")
    private fun showsFile(c: Context) = File(c.filesDir, "tours.json")

    fun start(c: Context) {
        if (started) return
        started = true
        Tours.ticketmasterKey = BuildConfig.TICKETMASTER_KEY
        Tours.trace = { android.util.Log.i("ink-tours", it) }
        place.value = runCatching { Tours.Place.of(org.json.JSONObject(placeFile(c).readText())) }.getOrNull()
        shows.value = runCatching { Tours.read(showsFile(c).readText()) }.getOrDefault(emptyList())
    }

    /** Whether there's any way to ask: a key in the build, or a Spotify sign-in. */
    fun askable(): Boolean = Tours.ticketmasterKey.isNotBlank() || Spotify.signedIn()

    fun choose(c: Context, p: Tours.Place?) {
        place.value = p
        runCatching { if (p == null) placeFile(c).delete() else placeFile(c).writeText(p.json().toString()) }
    }

    /** The shows read again if they're a day old (or never read), the city chosen and a way to ask there. */
    suspend fun freshen(c: Context, now: Boolean = false) {
        start(c)
        if (place.value == null || !askable()) return
        val f = showsFile(c)
        if (!now && f.exists() && System.currentTimeMillis() - f.lastModified() < DAY) return
        // one read at a time (the shelf and settings can both ask)
        if (!reading.compareAndSet(false, true)) return
        try {
            val artists = withContext(Dispatchers.IO) { yours(c) }
            val pass = runCatching { Spotify.passFor(c) }.getOrNull()
            val (read, failed) = withContext(Dispatchers.IO) { read(artists, pass) }
            // an artist Spotify wouldn't answer for keeps the shows it had (2026-11-23, a review: passes running out
            // partway had the read's first artists replace everyone's)
            val kept = shows.value.filter { s -> Search.fold(s.artist) in failed }
            val found = withContext(Dispatchers.IO) { placed(c, Tours.merge(read, kept)) }
            // nothing at all from anywhere (offline, turned away): the kept ones stay, asked again next time
            if (found.isEmpty() && shows.value.isNotEmpty()) return
            shows.value = found
            runCatching { f.writeText(Tours.write(found)) }
            android.util.Log.i("ink-tours", "${found.size} shows for ${artists.size} artists (spotify ${pass != null}, ticketmaster ${Tours.ticketmasterKey.isNotBlank()})")
        } finally { reading.value = false }
    }

    /** Shows with only a city put on the map by it, the cities looked up kept in files/tour-places.json. */
    private fun placed(c: Context, shows: List<Tours.Show>): List<Tours.Show> {
        val f = File(c.filesDir, "tour-places.json")
        val known = runCatching { org.json.JSONObject(f.readText()).let { o -> o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap() } }.getOrDefault(HashMap())
        val out = Tours.placed(shows, known)
        runCatching { f.writeText(org.json.JSONObject(known as Map<*, *>).toString()) }
        android.util.Log.i("ink-tours", "${out.count { it.lat != null }} of ${out.size} shows placed, ${known.size} cities known")
        return out
    }

    /** The artists you play most (your history's last year), the library's biggest after them; forty at most. */
    private fun yours(c: Context): List<String> {
        val since = System.currentTimeMillis() - 365 * DAY
        val heard = History.read(c).filter { it.at > since }
            .groupingBy { it.artist.substringBefore(", ").trim() }.eachCount().filterKeys { it.isNotBlank() }
            .entries.sortedByDescending { it.value }.map { it.key }
        val library = Library.songs.value.groupingBy { it.albumArtist.ifBlank { it.artist }.substringBefore(", ").trim() }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        return (heard + library).filter { it.isNotBlank() && !it.equals("various artists", true) && !it.startsWith("[") }
            .distinctBy { Search.fold(it) }.take(40)
    }

    /** Every artist's shows from each source, four artists at a time, put together; and the artists Spotify failed for (folded). */
    private suspend fun read(artists: List<String>, pass: SpotifyCanvas.Pass?): Pair<List<Tours.Show>, Set<String>> = coroutineScope {
        val gate = Semaphore(4)
        val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val each = artists.map { a ->
            async {
                gate.withPermit {
                    val theirs = pass?.let { p -> runCatching { Tours.spotify(a, p) }.onFailure { failed += Search.fold(a); android.util.Log.w("ink-tours", "spotify $a: $it") }.getOrNull() }.orEmpty()
                    val tm = Tours.ticketmaster(a)
                    theirs to tm
                }
            }
        }.awaitAll()
        Tours.merge(each.flatMap { it.first }, each.flatMap { it.second }) to failed
    }

    /** The shows near the city chosen, from today on. */
    fun near(all: List<Tours.Show>, p: Tours.Place): List<Tours.Show> {
        val today = java.time.LocalDate.now().toString()
        return Tours.near(all, p).filter { it.day >= today }
    }
}

/**
 * INK's shelf of your artists playing near you: each show a ticket stub in a hairline (the day big, who, where), a tap on
 * it opens its tickets' page. No city yet: the city asked for right here (2026-11-23, Bronson couldn't find it in
 * settings, listening), once there's a way to read shows; a city with none near: nothing.
 */
@Composable
fun OnTourShelf() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { OnTour.freshen(ctx) }
    val place by OnTour.place.collectAsState()
    val all by OnTour.shows.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    val p = place
    if (p == null || choosing) {
        if (!OnTour.askable()) return
        Heading("on tour near you", "your artists' shows. your city?")
        CityPicker { choosing = false }
        Spacer(Modifier.height(8.dp))
        return
    }
    val near = remember(all, p) { OnTour.near(all, p) }
    if (near.isEmpty()) return
    Heading("on tour near you", "your artists, around ${p.name.substringBefore(",").lowercase()}") {
        Word("change city", pixel(12.sp, Ink.mid), touch = 8.dp) { choosing = true }
    }
    Shelf(near) { s -> TourStub(s) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
    Spacer(Modifier.height(8.dp))
}

/** A city typed, the cities of that name found under it, one tapped: chosen, and the shows read for it at once. */
@Composable
internal fun CityPicker(onChosen: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    val found by androidx.compose.runtime.produceState(emptyList<Tours.Place>(), typed) {
        kotlinx.coroutines.delay(350)
        value = withContext(Dispatchers.IO) { Tours.cities(typed) }
    }
    // the keyboard put away once the matches come (typing stopped a moment): it had covered them, 2026-11-23; a tap on the
    // field brings it back
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(found) { if (found.isNotEmpty()) keyboard?.hide() }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Field("city", typed, { typed = it }, "your city", androidx.compose.ui.text.input.KeyboardType.Text)
        found.forEach { c ->
            Word(c.name.lowercase(), pixel(14.sp), Modifier.padding(bottom = 6.dp), touch = 6.dp) {
                OnTour.choose(ctx, c); typed = ""; onChosen(); scope.launch { OnTour.freshen(ctx, now = true) }
            }
        }
    }
}

/** A show as a ticket stub: the weekday and the date big, the artist, the venue and the city; "tickets" at its foot. */
@Composable
private fun TourStub(s: Tours.Show, onClick: () -> Unit) {
    val day = remember(s.day) { runCatching { java.time.LocalDate.parse(s.day) }.getOrNull() }
    Column(Modifier.width(170.dp).aspectRatio(.82f).border(1.dp, Ink.line)
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(14.dp)) {
        BasicText(day?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)?.lowercase() ?: "", style = pixel(12.sp, Ink.mid))
        BasicText(day?.let { java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH).format(it).uppercase() } ?: s.day,
            style = headline(30.sp), maxLines = 1)
        Spacer(Modifier.height(10.dp))
        BasicText(s.artist.uppercase(), style = headline(16.sp).copy(lineHeight = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        BasicText(s.venue.lowercase(), style = pixel(12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(s.city.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        BasicText("tickets", style = pixel(12.sp, Ink.mid))
    }
}
