package app.ink.music

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
 * Your artists on tour, near you (the phone's since 2026-11-23): the forty you play most, their shows from Spotify's artist
 * pages (with the passes the phone hands over while it's open and in step: the computer can't sign in to Spotify itself)
 * and Ticketmaster's (with the build's key), read once a day and kept; the city chosen once in settings (listening).
 * INK shows the ones near it.
 */
object OnTour {
    val place = MutableStateFlow<Tours.Place?>(null)
    val shows = MutableStateFlow<List<Tours.Show>>(emptyList())
    val reading = MutableStateFlow(false)
    private const val DAY = 24 * 3600 * 1000L
    @Volatile private var started = false

    private val placeFile get() = File(Home.dir, "tour-city.json")
    private val showsFile get() = File(Home.dir, "tours.json")
    private val placesFile get() = File(Home.dir, "tour-places.json")

    fun start() {
        if (started) return
        started = true
        Tours.trace = { Log.d("tours: $it") }
        place.value = runCatching { Tours.Place.of(org.json.JSONObject(placeFile.readText())) }.getOrNull()
        shows.value = runCatching { Tours.read(showsFile.readText()) }.getOrDefault(emptyList())
    }

    fun choose(p: Tours.Place?) {
        place.value = p
        runCatching { if (p == null) placeFile.delete() else placeFile.writeText(p.json().toString()) }
    }

    /** Whether there's a way to ask now: the build's Ticketmaster key, or Spotify passes from the phone. */
    fun askable(): Boolean = Tours.ticketmasterKey.isNotBlank() || SpotifyHanded.pass.value != null

    /** The shows read again if they're a day old (or never read), the city chosen and a way to ask. */
    suspend fun freshen(now: Boolean = false) {
        start()
        if (place.value == null || !askable() || reading.value) return
        if (!now && showsFile.exists() && System.currentTimeMillis() - showsFile.lastModified() < DAY) return
        reading.value = true
        try {
            val found = withContext(Dispatchers.IO) { placed(read(yours(), SpotifyHanded.pass.value)) }
            if (found.isEmpty() && shows.value.isNotEmpty()) return
            shows.value = found
            runCatching { showsFile.writeText(Tours.write(found)) }
            Log.d("tours: ${found.size} shows")
        } finally { reading.value = false }
    }

    private fun yours(): List<String> {
        val since = System.currentTimeMillis() - 365 * DAY
        val heard = History.read().filter { it.at > since }.groupingBy { it.artist.substringBefore(", ").trim() }.eachCount()
            .filterKeys { it.isNotBlank() }.entries.sortedByDescending { it.value }.map { it.key }
        val library = Library.songs.value.groupingBy { it.albumArtist.ifBlank { it.artist }.substringBefore(", ").trim() }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        return (heard + library).filter { it.isNotBlank() && !it.equals("various artists", true) && !it.startsWith("[") }
            .distinctBy { Search.fold(it) }.take(40)
    }

    private suspend fun read(artists: List<String>, pass: SpotifyCanvas.Pass?): List<Tours.Show> = coroutineScope {
        val gate = Semaphore(4)
        val each = artists.map { a ->
            async { gate.withPermit { (pass?.let { p -> runCatching { Tours.spotify(a, p) }.getOrNull() }.orEmpty()) to Tours.ticketmaster(a) } }
        }.awaitAll()
        Tours.merge(each.flatMap { it.first }, each.flatMap { it.second })
    }

    private fun placed(shows: List<Tours.Show>): List<Tours.Show> {
        val known = runCatching { org.json.JSONObject(placesFile.readText()).let { o -> o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap() } }.getOrDefault(HashMap())
        val out = Tours.placed(shows, known)
        runCatching { placesFile.writeText(org.json.JSONObject(known as Map<*, *>).toString()) }
        return out
    }

    fun near(all: List<Tours.Show>, p: Tours.Place): List<Tours.Show> {
        val today = java.time.LocalDate.now().toString()
        return Tours.near(all, p).filter { it.day >= today }
    }
}

/** INK's shelf of your artists playing near you: each show a ticket stub; a click opens its tickets' page. */
@Composable
fun OnTourShelf() {
    LaunchedEffect(Unit) { OnTour.freshen() }
    val place by OnTour.place.collectAsState()
    val all by OnTour.shows.collectAsState()
    val p = place ?: return
    val near = remember(all, p) { OnTour.near(all, p) }
    if (near.isEmpty()) return
    Column {
        Section("ON TOUR NEAR YOU", "your artists, around ${p.name.substringBefore(",").lowercase()}")
        Shelf(near.size, 190.dp, gap = 16.dp) { each ->
            items(near, key = { it.artist + it.day + it.city }) { s -> TourStub(s, Modifier.width(each)) { browse(s.url) } }
        }
    }
}

@Composable
private fun TourStub(s: Tours.Show, modifier: Modifier, onClick: () -> Unit) {
    val day = remember(s.day) { runCatching { java.time.LocalDate.parse(s.day) }.getOrNull() }
    Column(modifier.aspectRatio(.82f).border(1.dp, Ink.line)
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(14.dp)) {
        BasicText(day?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)?.lowercase() ?: "", style = pixel(12.sp, Ink.mid))
        BasicText(day?.let { java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH).format(it).uppercase() } ?: s.day, style = headline(30.sp), maxLines = 1)
        Spacer(Modifier.height(10.dp))
        BasicText(s.artist.uppercase(), style = headline(16.sp).copy(lineHeight = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        BasicText(s.venue.lowercase(), style = pixel(12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(s.city.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        BasicText("tickets", style = pixel(12.sp, Ink.mid))
    }
}
