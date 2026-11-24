package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import java.io.File

/**
 * On an artist's page, while they're on tour (the phone's since 2026-11-24): what they're playing, the songs most in their
 * latest setlists ([Setlists], setlist.fm's), a word to play that set (yours first, else YouTube Music's: [Import.match]),
 * and setlist.fm's credit, its terms' ask, opening their page there. On tour: a show of theirs ahead (OnTour), or a
 * setlist from the last two months. No key in the build: nothing asked, nothing shown.
 */
@Composable
fun TheyrePlaying(name: String, records: List<String>, wide: Boolean) {
    if (Setlists.key.isBlank()) return
    val heard by produceState<Setlists.Heard?>(null, name) {
        Setlists.trace = { Log.d("setlists: $it") }
        OnTour.start()
        value = withContext(Dispatchers.IO) {
            val mbid = Socials.id(File(Home.cache, "socials"), name, records) ?: return@withContext null
            Setlists.of(File(Home.cache, "setlists"), mbid)
        }
    }
    val shows by OnTour.shows.collectAsState()
    val h = heard ?: return
    val touring = remember(h, shows, name) {
        val today = java.time.LocalDate.now()
        shows.any { Search.fold(it.artist) == Search.fold(name) && it.day >= today.toString() } ||
            h.latest >= today.minusDays(60).toString()
    }
    if (!touring || h.songs.isEmpty()) return
    val scope = rememberCoroutineScope()
    var finding by remember(name) { mutableStateOf(false) }
    val row = @Composable { n: Int, s: Setlists.Played ->
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("${n + 1}", style = pixel(12.sp, Ink.mid), modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                BasicText(s.title.lowercase(), style = pixel(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                s.cover?.let { BasicText("${it.lowercase()}'s", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            BasicText("${s.times} of ${h.shows}", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 16.dp))
        }
    }
    Column {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Ink.line))
        Label("what they're playing, the songs most in their last ${if (h.shows == 1) "show" else "${h.shows} shows"}")
        val songs = h.songs.take(12)
        if (wide && songs.size > 3) {
            val half = (songs.size + 1) / 2
            Row(horizontalArrangement = Arrangement.spacedBy(60.dp)) {
                listOf(songs.take(half), songs.drop(half)).forEachIndexed { c, part ->
                    Column(Modifier.weight(1f)) { part.forEachIndexed { n, s -> row(c * half + n, s) } }
                }
            }
        } else Column(Modifier.widthIn(max = 560.dp)) { songs.forEachIndexed { n, s -> row(n, s) } }
        Row(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Word(if (finding) "finding" else "play the set", pixel(14.sp, if (finding) Ink.mid else Ink.ink), Modifier.padding(end = 26.dp), touch = 8.dp) {
                if (finding) return@Word
                finding = true
                scope.launch {
                    val found = runCatching { Import.match(h.songs.map { SpotifyLibrary.Said(it.title, it.cover ?: name, "") }) }.getOrDefault(emptyList()).filterNotNull()
                    if (found.isNotEmpty()) Player.play(found.distinctBy { it.id }, 0)
                    finding = false
                }
            }
            // setlist.fm's credit, as its terms ask wherever its data is shown: the link its answer gave
            Word("setlists from setlist.fm", pixel(12.sp, Ink.mid), touch = 6.dp) { browse(h.credit) }
        }
    }
}
