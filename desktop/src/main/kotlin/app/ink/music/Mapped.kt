package app.ink.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ink mapped on the computer (2026-11-12, Bronson: "the android ink mapped feature should also be built into the desktop
 * apps"; the phone's InkMapped.kt its model, [InkMap] in core draws it): your listening as land, a hill for each artist
 * you're near, placed by their sound, the highest named; beside it what's said of you: lately, in rotation, when.
 */
object Mapped {
    class Read(val artists: List<Pair<String, Double>>, val appetite: Double, val rotation: List<String>, val hours: String?, val record: Song?)

    fun read(): Read {
        val t = Fresh.taste.value
        val heard = History.read()
        // each artist as they're written (the latest heard), their nearness from your taste; and the ones brought in
        val names = LinkedHashMap<String, String>()
        heard.forEach { h -> h.artist.substringBefore(", ").trim().takeIf { it.isNotEmpty() }?.let { names[Taste.bare(it)] = it } }
        Fresh.brought.value.forEach { b -> b.artists.keys.forEach { names.putIfAbsent(Taste.bare(it), it) } }
        val artists = names.values.map { it to t.of(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(40)
        val now = System.currentTimeMillis()
        // in rotation: the songs most in it now (lately counting more), their artists
        val byId = heard.associateBy { it.id }
        val rotation = LoveKeeping.mostPlayed(heard.map { it.id to it.at }, now, n = 60)
            .mapNotNull { byId[it]?.artist?.substringBefore(", ")?.trim() }.distinctBy { it.lowercase() }.take(4)
        val zone = java.time.ZoneId.systemDefault()
        val hours = InkMap.hours(heard.filter { now - it.at < 60L * 86_400_000 }.map { java.time.Instant.ofEpochMilli(it.at).atZone(zone).hour })
        // the record played most this month: the map in its colour
        val record = heard.filter { now - it.at < 30L * 86_400_000 && it.album.isNotBlank() }.groupBy { it.album to it.artist.substringBefore(", ") }
            .maxByOrNull { it.value.size }?.value?.lastOrNull()?.let { Library.song(it.id) }
        return Read(artists, t.appetite, rotation, hours, record)
    }
}

@Composable
fun MappedPage(onClose: () -> Unit) {
    val taste by Fresh.taste.collectAsState()
    val read by produceState<Mapped.Read?>(null, taste) { value = withContext(Dispatchers.IO) { Mapped.read() } }
    // their tags read where they're missing (Last.fm's; a few seconds the first time), then drawn again by sound
    val tagged by GenrePicks.tagged.collectAsState()
    LaunchedEffect(read) { read?.let { r -> GenrePicks.tag(r.artists.map { it.first }) } }
    val r = read
    val accent = r?.record?.let { rememberCover(it, 240)?.accent } ?: Ink.ink
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = SIDE, end = SIDE, top = 8.dp, bottom = 48.dp)) {
            BasicText("INK MAPPED", style = headline(56.sp))
            BasicText("your listening as land: a hill for each artist you're near, the nearer the higher, kin close together",
                style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(28.dp))
            when {
                r == null -> {}
                r.artists.isEmpty() -> BasicText("play a while, and your map rises here", style = pixel(14.sp))
                else -> Row(verticalAlignment = Alignment.Top) {
                    // drawn again as tags come in (their key: how many are known)
                    val known = r.artists.count { Genres.tags(it.first) != null }
                    LandOf(r.artists, { Genres.tags(it) }, Modifier.weight(1f).widthIn(max = 760.dp).aspectRatio(1f), accent = accent, named = 5, version = known)
                    Spacer(Modifier.width(48.dp))
                    Column(Modifier.width(300.dp)) {
                        Said("lately", InkMap.mood(r.appetite).uppercase(), "${(r.appetite * 10).toInt()} in ten of what's picked for you is new to you")
                        if (r.rotation.isNotEmpty()) Said("in rotation", null, r.rotation.joinToString(", ") { it.lowercase() })
                        r.hours?.let { Said("when", null, it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Said(label: String, big: String?, line: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        BasicText(label, style = pixel(12.sp, Ink.mid))
        big?.let { BasicText(it, style = headline(24.sp)) }
        BasicText(line, style = pixel(14.sp))
    }
}
