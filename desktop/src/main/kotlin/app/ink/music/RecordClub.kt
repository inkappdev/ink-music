package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Record club (the phone's since 2026-11-18; here 2026-11-23): one record a week to hear from start to finish: from the
 * critics' records (picked by the most, near you but not yours yet), held all week (a new one each Monday), with what a
 * critic said; how much of it you've heard, and FINISHED IT once every song has been. Kept in record-club.json.
 */
object RecordClub {
    private val file get() = File(Home.dir, "record-club.json")
    private fun kept(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    /** This week, as a number (Mondays begin them). */
    fun week(now: Long = System.currentTimeMillis()): Long = (now / 86_400_000L + 3) / 7

    /** This week's record: the one held for the week, else chosen now from `records` (and held). */
    fun of(records: List<Picks.Shown>, today: Picks.Shown?): Picks.Shown? {
        val o = kept()
        val w = week()
        val held = o.optString("w$w").ifEmpty { null }
        records.firstOrNull { Curation.key(it.pick) == held }?.let { return it }
        if (held != null && records.isEmpty()) return null
        val taste = Fresh.taste.value
        val mine = Library.songs.value.map { Curation.folded(it.artist) }.toSet()
        val before = (0..8).mapNotNull { o.optString("w${w - it}").ifEmpty { null } }.toSet()
        val pick = records.filter { it.pick.record && Curation.key(it.pick) != today?.let { t -> Curation.key(t.pick) } && Curation.key(it.pick) !in before }
            .filter { Curation.folded(it.pick.artist) !in mine }
            .maxByOrNull { it.by.size * 1.0 + taste.of(it.pick.artist) * 2 + (if (taste.of(it.pick.artist) > .7) -1.5 else 0.0) } ?: return null
        runCatching { file.writeText(o.put("w$w", Curation.key(pick.pick)).put("since$w", System.currentTimeMillis()).toString()) }
        return pick
    }

    /** How many of its songs (`titles`) you've heard since the week began. */
    fun heard(titles: List<String>, artist: String): Int {
        val since = kept().optLong("since${week()}", 0L)
        val names = titles.map { Curation.folded(it).substringBefore(" (") }.toSet()
        return History.read().filter { it.at >= since && Curation.folded(it.artist).contains(Curation.folded(artist).take(12)) }
            .map { Curation.folded(it.title).substringBefore(" (") }.filter { it in names }.distinct().size
    }
}

/** INK's record club: the week's record big beside its words, a critic's line, play from the start, how much you've heard. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RecordClubCard(today: Picks.Shown?, open: (Page) -> Unit) {
    val records by Picks.records.collectAsState()
    val d = remember(records.size, today) { RecordClub.of(records, today) } ?: return
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val songs by produceState<List<Song>?>(null, d) {
        value = withContext(Dispatchers.IO) { Picks.record(d.pick)?.let { runCatching { YouTube.album(it) }.getOrNull() } }
    }
    val heard = remember(songs) { songs?.let { RecordClub.heard(it.map { s -> s.title }, d.pick.artist) } ?: 0 }
    val all = songs?.size ?: 0
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = minOf(maxWidth * .36f, 360.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(side).clickable(remember { MutableInteractionSource() }, indication = null) {
                scope.launch { Picks.record(d.pick)?.let { open(Page.Tube(it)) } ?: run { said = "not on youtube music" } }
            }) { CoverOf(d.face, d.pick.title, Modifier.fillMaxSize(), px = 800) }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                BasicText("RECORD CLUB", style = headline(18.sp, Ink.mid))
                BasicText("this week's record, start to finish", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(14.dp))
                BasicText(d.pick.title.uppercase(), style = headline(40.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                BasicText(d.pick.artist.lowercase(), style = pixel(16.sp))
                if (d.pick.said.isNotBlank()) {
                    BasicText("“${d.pick.said}”".uppercase(), style = headline(18.sp).copy(lineHeight = 24.sp), maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 14.dp))
                    BasicText("— ${d.pick.from}", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(16.dp))
                // wrapping where the room runs out (a half-width window)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    Word("play from the start", pixel(14.sp), touch = 8.dp) {
                        val s = songs
                        if (!s.isNullOrEmpty()) Player.play(s) else scope.launch {
                            said = "finding it on youtube music"
                            val a = Picks.record(d.pick) ?: run { said = "not on youtube music"; return@launch }
                            said = null; Player.play(YouTube.album(a))
                        }
                    }
                    if (all > 0) {
                        if (heard >= all) BasicText("FINISHED IT", style = pixel(11.sp, Ink.black), modifier = Modifier.background(Ink.ink).padding(horizontal = 5.dp, vertical = 1.dp))
                        else BasicText("$heard of $all heard", style = pixel(14.sp, Ink.mid))
                    }
                }
                said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}
