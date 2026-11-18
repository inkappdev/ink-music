package app.ink.music

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Record club (2026-11-18, Bronson: "work through the rest"): one record a week to hear from start to finish, the slow
 * counterweight to TUNER: from the critics' records (picked by the most, near you but not yours yet), held all week (a
 * new one each Monday), with what a critic said; how much of it you've heard, and FINISHED IT once every song has been.
 */
object RecordClub {
    private fun prefs(c: Context) = c.getSharedPreferences("record-club", Context.MODE_PRIVATE)
    /** This week, as a number (Mondays begin them). */
    fun week(now: Long = System.currentTimeMillis()): Long = (now / 86_400_000L + 3) / 7

    /** This week's record: the one held for the week, else chosen now from `records` (and held). */
    fun of(c: Context, records: List<Picks.Shown>, today: Picks.Shown?): Picks.Shown? {
        val p = prefs(c)
        val w = week()
        val held = p.getString("w$w", null)
        records.firstOrNull { Curation.key(it.pick) == held }?.let { return it }
        if (held != null && records.isEmpty()) return null
        val taste = Fresh.taste.value
        val mine = Library.songs.value.map { Curation.folded(it.artist) }.toSet()
        val before = (0..8).mapNotNull { p.getString("w${w - it}", null) }.toSet()
        val pick = records.filter { it.pick.record && Curation.key(it.pick) != today?.let { t -> Curation.key(t.pick) } && Curation.key(it.pick) !in before }
            .filter { Curation.folded(it.pick.artist) !in mine }
            .maxByOrNull { it.by.size * 1.0 + taste.of(it.pick.artist) * 2 + (if (taste.of(it.pick.artist) > .7) -1.5 else 0.0) } ?: return null
        p.edit().putString("w$w", Curation.key(pick.pick)).putLong("since$w", System.currentTimeMillis()).apply()
        return pick
    }

    /** How many of its songs (`titles`) you've heard since the week began. */
    fun heard(c: Context, titles: List<String>, artist: String): Int {
        val since = prefs(c).getLong("since${week()}", 0L)
        val names = titles.map { Curation.folded(it).substringBefore(" (") }.toSet()
        return History.read(c).filter { it.at >= since && Curation.folded(it.artist).contains(Curation.folded(artist).take(12)) }
            .map { Curation.folded(it.title).substringBefore(" (") }.filter { it in names }.distinct().size
    }
}

/** INK's record club card (under TUNER): the week's record, its cover, a critic's words, play from the start, how much you've heard. */
@Composable
fun RecordClubCard(records: List<Picks.Shown>, today: Picks.Shown?, pages: Pages) {
    if (!YouTubeMusic.available) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val d = remember(records.size, today) { RecordClub.of(ctx, records, today) } ?: return
    var said by remember { mutableStateOf<String?>(null) }
    // its songs, from YouTube Music, for how much you've heard
    val songs by produceState<List<Song>?>(null, d) {
        value = withContext(Dispatchers.IO) { Picks.record(d.pick)?.let { runCatching { YouTubeMusic.album(it) }.getOrNull() } }
    }
    val heard = remember(songs, Listen.visit) { songs?.let { RecordClub.heard(ctx, it.map { s -> s.title }, d.pick.artist) } ?: 0 }
    val all = songs?.size ?: 0
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 22.dp)) {
        BasicText("RECORD CLUB", style = headline(22.sp))
        BasicText("this week's record, start to finish", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(12.dp))
        val art by produceState(d.art, d) { value = Picks.coverFor(d.pick.title, d.pick.artist, true) ?: value }
        val face = remember(d, art) { if (art == d.art) d.face else d.copy(art = art).face }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(Modifier.size(maxWidth * .62f).clickable(remember { MutableInteractionSource() }, indication = null) {
                scope.launch { Picks.record(d.pick)?.let { pages.open(Page.Tube(it)) } ?: run { said = "not on youtube music" } }
            }) { CoverOf(face, d.pick.title, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.height(12.dp))
        BasicText(d.pick.title.uppercase(), style = headline(26.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(d.pick.artist.lowercase(), style = pixel(14.sp))
        if (d.pick.said.isNotBlank()) {
            BasicText("“${d.pick.said}”".uppercase(), style = headline(15.sp).copy(lineHeight = 20.sp), maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            BasicText("— ${d.pick.from}", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Word("play from the start", pixel(14.sp), touch = 8.dp) {
                val s = songs
                if (!s.isNullOrEmpty()) Playback.play(s, 0) else scope.launch {
                    said = "finding it on youtube music"
                    val a = Picks.record(d.pick) ?: run { said = "not on youtube music"; return@launch }
                    said = null; Playback.play(YouTubeMusic.album(a), 0)
                }
            }
            if (all > 0) {
                if (heard >= all) BasicText("FINISHED IT", style = pixel(11.sp, Ink.black), modifier = Modifier.background(Ink.ink).padding(horizontal = 5.dp, vertical = 1.dp))
                else BasicText("$heard of $all heard", style = pixel(13.sp, Ink.mid))
            }
        }
        said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp)) }
    }
}
