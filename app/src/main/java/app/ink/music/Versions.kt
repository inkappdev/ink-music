package app.ink.music

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import org.json.JSONObject
import java.io.File

/**
 * A song's YouTube version, chosen by hand (after Meld's "change YouTube version"; Bronson, 2026-11-01): when the
 * YouTube song ink music plays is the wrong one (a live take, a cover, the music video), the right one's link pasted
 * in, and kept. The song stays the song (its loves, its place in lists, its words); only what's streamed for it is the
 * chosen one, and a song found by its words (a pick, an import, a mix) finds the chosen one too. Kept as a file both
 * processes read: the player streams it, the screens choose it.
 */
object Versions {
    private fun file(c: Context) = File(c.filesDir, "youtube/versions.json")
    private fun all(c: Context): JSONObject = runCatching { JSONObject(file(c).readText()) }.getOrDefault(JSONObject())
    private fun words(title: String, artist: String) = "w:" + title.trim().lowercase() + "|" + artist.substringBefore(',').trim().lowercase()

    /** The video chosen for a YouTube song ("y" and its id), or null: its own. */
    fun of(c: Context, id: String): String? = all(c).optString(id).takeIf { it.isNotEmpty() }

    /** The video chosen for a song by these words, or null. */
    fun byWords(c: Context, title: String, artist: String): String? = all(c).optString(words(title, artist)).takeIf { it.isNotEmpty() }

    /** The song's version chosen (a video id), or let go of (null: its own again). */
    fun set(c: Context, s: Song, video: String?) {
        val o = all(c)
        if (video == null) { o.remove(s.id); o.remove(words(s.title, s.artist)) }
        else { o.put(s.id, video); o.put(words(s.title, s.artist), video) }
        runCatching { file(c).parentFile?.mkdirs(); file(c).writeText(o.toString()) }
    }

    /** A video's id from a link as it's pasted (youtube.com, music.youtube.com, youtu.be, shorts), or the id itself. */
    fun idOf(link: String): String? {
        val t = link.trim()
        Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})""").find(t)?.let { return it.groupValues[1] }
        return t.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
    }
}

/** The page: the song, a line to paste the right one's link on, USE THIS; and back to its own, if one's chosen. */
@Composable
fun VersionPage(s: Song, pages: Pages) {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf("") }
    val chosen = remember { Versions.of(ctx, s.id) }
    val id = Versions.idOf(link)
    fun done(video: String?) {
        Versions.set(ctx, s, video)
        // a copy kept offline is the old version: let go, so the chosen one plays (and can be kept again)
        if (s.id in Offline.kept.value) Offline.remove(ctx, listOf(s.id))
        // playing now: taken up again where it is, so the chosen one's what's heard at once
        if (Playback.now.value?.song?.id == s.id) Playback.handover()?.let { (queue, index, at) -> Playback.play(queue, index, at, Playback.now.value?.playing == true) }
        pages.back()
    }
    FrostedPage(onClose = { pages.close() }) { frost, top ->
        Column(
            Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
        ) {
            BasicText("YOUTUBE VERSION", style = headline(36.sp), maxLines = 2)
            Spacer(Modifier.height(8.dp))
            BasicText("${s.title} · ${s.artist}".lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 2)
            Spacer(Modifier.height(18.dp))
            BasicText("the wrong one playing (a live take, a cover, the video)? find the right one on youtube or youtube music, " +
                "share > copy link, and paste it here. it's kept: this song plays that one from now on", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(22.dp))
            Field("its link", link, { link = it }, "music.youtube.com/watch?v=…", KeyboardType.Uri)
            if (link.isNotBlank() && id == null) BasicText("that's not a youtube link", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(10.dp))
            Word("USE THIS", headline(40.sp, if (id != null) Ink.ink else Ink.dim), touch = 10.dp) { if (id != null) done(id) }
            if (chosen != null) {
                Spacer(Modifier.height(28.dp))
                BasicText("chosen by hand now: $chosen", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(6.dp))
                Word("back to its own", pixel(14.sp), touch = 8.dp) { done(null) }
            }
        }
    }
}
