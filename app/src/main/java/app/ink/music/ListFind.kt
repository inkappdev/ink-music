package app.ink.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Finding a song in a list (2026-11-23, asked on r/INKmusic: "search for songs/artists inside playlists"): "find in it"
 * under a list's play and shuffle opens a field; the list shows only the songs whose title, artist or record hold every
 * word typed; "clear" brings them all back.
 */
@Composable
fun ListFind(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(query.isNotEmpty()) }
    Column(modifier) {
        if (!open) Word("find in it", pixel(13.sp, Ink.mid), touch = 8.dp) { open = true }
        else {
            Field("find in it", query, onQuery, "a song, an artist, a record", KeyboardType.Text)
            Row {
                Word("clear", pixel(12.sp, Ink.mid), touch = 6.dp) { onQuery(""); open = false }
            }
        }
    }
}

/** The songs holding every word of `query` in their title, artist or record (folded: no accents, any case); all for none. */
fun matching(songs: List<Song>, query: String): List<Song> = songs.filter { matches(it, query) }

/** Whether a song holds every word of `query` (a list that needs each song's own place keeps it so). */
fun matches(s: Song, query: String): Boolean {
    val words = Search.fold(query).split(' ').filter { it.isNotEmpty() }
    return words.isEmpty() || Search.fold("${s.title} ${s.artist} ${s.album}").let { hay -> words.all { it in hay } }
}

/** How many of a list `matching` left, said under the field while it narrows ("3 of 210"). */
@Composable
fun Narrowed(shown: Int, of: Int, query: String) {
    if (query.isNotBlank()) BasicText(if (shown == 0) "none of $of" else "$shown of $of", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
}
