package app.ink.music

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On an artist's page, while they're on tour (2026-11-24): what they're playing, the songs most in their latest setlists
 * ([Setlists], setlist.fm's), a word to play that set (yours first, else YouTube Music's: [Import.match]), and setlist.fm's
 * credit, its terms' ask, opening their page there. On tour: a show of theirs ahead (OnTour), or a setlist from the last
 * two months. No key in the build: nothing asked, nothing shown.
 */
@Composable
fun TheyrePlaying(name: String, records: List<String>, ready: Boolean) {
    if (BuildConfig.SETLIST_KEY.isBlank()) return
    val ctx = LocalContext.current
    val heard by produceState<Setlists.Heard?>(null, name, ready) {
        if (!ready) return@produceState
        Setlists.key = BuildConfig.SETLIST_KEY
        Setlists.trace = { android.util.Log.d("ink-setlists", it) }
        OnTour.start(ctx)
        value = withContext(Dispatchers.IO) {
            val mbid = Socials.id(File(ctx.cacheDir, "socials"), name, records) ?: return@withContext null
            Setlists.of(File(ctx.cacheDir, "setlists"), mbid)
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
    Heading("what they're playing", "the songs most in their last ${if (h.shows == 1) "show" else "${h.shows} shows"}") {
        Word(if (finding) "finding" else "play the set", pixel(12.sp, if (finding) Ink.mid else Ink.ink), touch = 8.dp) {
            if (finding) return@Word
            finding = true
            scope.launch {
                val found = runCatching { Import.match(h.songs.map { SpotifyLibrary.Said(it.title, it.cover ?: name, "") }) }.getOrDefault(emptyList()).filterNotNull()
                if (found.isNotEmpty()) Playback.play(found.distinctBy { it.id }, 0)
                finding = false
            }
        }
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        h.songs.take(12).forEachIndexed { n, s ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("${n + 1}", style = pixel(12.sp, Ink.mid), modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(s.title.lowercase(), style = pixel(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    s.cover?.let { BasicText("${it.lowercase()}'s", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                BasicText("${s.times} of ${h.shows}", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp))
            }
        }
        // setlist.fm's credit, as its terms ask wherever its data is shown: the link its answer gave
        Word("setlists from setlist.fm", pixel(12.sp, Ink.mid), Modifier.padding(top = 8.dp, bottom = 22.dp), touch = 8.dp) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.credit)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
