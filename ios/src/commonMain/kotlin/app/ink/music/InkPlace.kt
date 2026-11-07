package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The critics' picks, kept a day (ink-picks.json): read from every feed at most twice a day, as the phone does
 * ([Curation.stale]); what was read last shown at once while the new is read.
 */
object Picks {
    private val file get() = File(Here.dir, "ink-picks.json")
    private val covers = HashMap<String, String?>()

    fun kept(): Pair<Long, List<Curation.Pick>>? = runCatching {
        val o = JSONObject(file.readText())
        val a = o.getJSONArray("picks")
        o.getLong("at") to (0 until a.length()).map { a.getJSONObject(it) }.map {
            Curation.Pick(it.getBoolean("record"), it.getString("title"), it.getString("artist"), it.optString("from"), it.optString("said"),
                it.optString("link"), it.optLong("at"), it.optString("art").ifEmpty { null })
        }
    }.getOrNull()

    /** Today's picks: the kept ones if they're fresh, else read now (and kept). */
    fun now(): List<Curation.Pick> {
        kept()?.takeIf { !Curation.stale(it.first) }?.let { return it.second }
        val all = Curation.picks()
        if (all.isNotEmpty()) Keep.write(file, JSONObject().put("at", nowMs()).put("picks", JSONArray(all.map {
            JSONObject().put("record", it.record).put("title", it.title).put("artist", it.artist).put("from", it.from).put("said", it.said)
                .put("link", it.link).put("at", it.at).put("art", it.art ?: "")
        })).toString())
        return all.ifEmpty { kept()?.second.orEmpty() }
    }

    /** A pick's cover: its own, else iTunes' or Deezer's for it (asked once a run). */
    fun cover(p: Curation.Pick): String? = p.art ?: Curation.key(p).let { k ->
        if (k in covers) covers[k] else Curation.cover(p.title, p.artist, p.record).also { covers[k] = it }
    }
}

@Composable
fun PickCover(p: Curation.Pick, size: Int, modifier: Modifier) {
    val url by produceState(p.art, p) { if (value == null) value = Here.off { Picks.cover(p) } }
    Cover(url, size, p.title, modifier)
}

/** INK: today's issue: the record of the day, the critics' records and songs, what's out this friday, what's on the air. */
@Composable
fun InkPlace(top: Dp, foot: Dp) {
    val scope = rememberCoroutineScope()
    val picks by produceState(Picks.kept()?.second) { value = Here.off { Picks.now() } }
    val friday by produceState<Curation.Week?>(null) { value = Here.off { Curation.outThisFriday() } }
    val aired by produceState(emptyList<Curation.Aired>()) { value = Here.off { Curation.onAir() } }
    var said by remember { mutableStateOf<Pair<String, String>?>(null) }
    // a pick: your server's record of it if you have it, else said so where it was pressed
    fun open(p: Curation.Pick) {
        said = Curation.key(p) to "looking on your server"
        scope.launch {
            val a = runCatching { Server.find(p.title, p.artist, p.record) }.getOrNull()
            if (a != null) { said = null; Pages.open(Page.Record(a)) } else said = Curation.key(p) to "not on your server"
        }
    }
    val all = picks.orEmpty()
    val day = remember(all) { Curation.recordOfTheDay(all) }
    val records = remember(all, day) { all.filter { it.record && (day == null || Curation.key(it) != Curation.key(day)) }.distinctBy(Curation::key).take(30) }
    val songs = remember(all) { all.filter { !it.record }.distinctBy(Curation::key).take(20) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top - 26.dp, bottom = foot)) {
        item {
            Label("issue ${Moment.issue()}   ${today().let { "${it.month.name.lowercase().take(3)} ${it.day}" }}")
            if (picks == null) BasicText("reading the critics", style = pixel(12.sp, Ink.mid))
        }
        day?.let { p ->
            item(key = "day") {
                Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { open(p) }) {
                    PickCover(p, 900, Modifier.fillMaxWidth().aspectRatio(1f))
                    BasicText("record of the day", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 12.dp))
                    BasicText(p.title.uppercase(), style = headline(34.sp), modifier = Modifier.padding(top = 2.dp))
                    BasicText("${p.artist}   ${Curation.namedBy(p, all).joinToString(", ")}".lowercase(), style = pixel(13.sp, Ink.mid))
                    if (p.said.isNotEmpty()) BasicText(p.said, style = pixel(12.sp, Ink.mid), maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    said?.takeIf { it.first == Curation.key(p) }?.let { BasicText(it.second, style = pixel(12.sp), modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
        if (records.isNotEmpty()) item(key = "records") {
            Label("the critics' records")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(records, key = { "r" + Curation.key(it) }) { p ->
                    Column(Modifier.width(140.dp).clickable(remember { MutableInteractionSource() }, indication = null) { open(p) }) {
                        PickCover(p, 400, Modifier.fillMaxWidth().aspectRatio(1f))
                        BasicText(p.title.uppercase(), style = headline(14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        BasicText(p.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val s = said?.takeIf { it.first == Curation.key(p) }?.second ?: p.from.lowercase()
                        BasicText(s, style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (songs.isNotEmpty()) {
            item { Label("songs") }
            items(songs, key = { "s" + Curation.key(it) }) { p ->
                val s = said?.takeIf { it.first == Curation.key(p) }?.second
                Named(p.title, s ?: "${p.artist}   ${p.from}") { open(p) }
            }
        }
        friday?.let { w ->
            item { Label("out ${w.day}: ${w.releases.size} records") }
            items(w.releases.take(12), key = { "f" + it.artist + it.title }) { r -> Named(r.title, r.artist) {
                open(Curation.Pick(true, r.title, r.artist, "", "", r.link, 0, null))
            } }
        }
        if (aired.isNotEmpty()) {
            item { Label("on the air") }
            items(aired, key = { "a" + it.station }) { a ->
                Named(a.title, "${a.artist}   ${a.name}") {
                    Radio.stations().firstOrNull { it.song.id == "s" + a.station }?.let { Sound.play(listOf(it.song), 0) }
                }
            }
        }
    }
}
