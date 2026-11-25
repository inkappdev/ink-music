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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The song's family, under its notes on the inner sleeve (the phone's, 2026-11-24): the original where it's a cover, who
 * else covered it, what it samples and what sampled it, what it's a remix of and its remixes, as MusicBrainz keeps them
 * (Lineage, kept a month). Asked only once the notes are shown; nothing where there's none or it couldn't be asked.
 * Each a word that plays it (yours, else YouTube Music's), or opens its artist where it isn't found.
 */
@Composable
fun SongFamily(s: Song, open: (Page) -> Unit, modifier: Modifier = Modifier) {
    val of by produceState<Lineage.Of?>(null, s.title, s.artist) {
        value = withContext(Dispatchers.IO) { Lineage.of(java.io.File(Home.cache, "lineage"), s.title, s.artist) }
    }
    val l = of?.takeUnless { it.empty } ?: return
    val scope = rememberCoroutineScope()
    fun play(x: Lineage.Song) {
        scope.launch {
            val found = Import.match(listOf(SpotifyLibrary.Said(x.title, x.artist, ""))).firstOrNull()
            if (found != null) Player.play(listOf(found), 0) else open(Page.Artist(x.artist))
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
            BasicText(word, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                songs.forEach { x -> Word(said(x, title), pixel(14.sp), touch = 6.dp) { play(x) } }
            }
        }
    }
}
