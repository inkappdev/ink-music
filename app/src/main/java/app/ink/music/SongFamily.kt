package app.ink.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A song's family under its options, held open (2026-11-24): the original where it's a cover, who else covered it, what
 * it samples and what sampled it, what it's a remix of and its remixes, as MusicBrainz keeps them (Lineage, kept a
 * month). Asked only once the song's held; nothing shown where there's none or it couldn't be asked. Each a word that
 * plays it (yours, else YouTube Music's), or opens its artist where it isn't found.
 */
@Composable
fun SongFamily(s: Song, pages: Pages, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val of by produceState<Lineage.Of?>(null, s.title, s.artist) {
        value = withContext(Dispatchers.IO) { Lineage.of(java.io.File(ctx.cacheDir, "lineage"), s.title, s.artist) }
    }
    val l = of?.takeUnless { it.empty } ?: return
    val scope = rememberCoroutineScope()
    fun play(x: Lineage.Song) {
        scope.launch {
            val found = Import.match(listOf(SpotifyLibrary.Said(x.title, x.artist, ""))).firstOrNull()
            if (found != null) Playback.play(listOf(found), 0) else pages.open(Page.Artist(x.artist))
        }
    }
    // the song's own artist goes without saying (their remixes); a cover's title is the song's own
    fun said(x: Lineage.Song, title: Boolean) = when {
        !title -> x.artist
        x.artist.equals(s.artist, ignoreCase = true) -> x.title
        else -> "${x.title} by ${x.artist}"
    }.lowercase()
    Column(modifier.padding(top = 14.dp)) {
        listOf(
            Triple("the original", listOfNotNull(l.original), true), Triple("covered by", l.covers, false),
            Triple("samples", l.samples, true), Triple("sampled in", l.sampledIn, true),
            Triple("remix of", l.remixOf, true), Triple("remixes", l.remixes, true),
        ).filter { it.second.isNotEmpty() }.forEach { (word, songs, title) ->
            BasicText(word, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                songs.forEach { x -> Word(said(x, title), pixel(13.sp), touch = 6.dp) { play(x) } }
            }
        }
    }
}
