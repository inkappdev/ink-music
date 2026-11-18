package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** BLEND's way in, on INK under TUNER: a hairline tile, its name and what it does. */
@Composable
fun BlendDoor(pages: Pages) {
    if (!YouTubeMusic.available || LastFm.key.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).border(1.dp, Ink.line)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { pages.open(Page.FriendBlend) }).padding(16.dp),
    ) {
        BasicText("BLEND", style = headline(22.sp))
        BasicText("mix your taste with a friend's", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * A blend with a friend (2026-11-18; [FriendBlend]): their Last.fm name (the last kept), BLEND; how in tune you are, then the
 * songs in turn, each saying whose side it's from; play all, or from any one.
 */
@Composable
fun BlendPage(pages: Pages) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("blend", android.content.Context.MODE_PRIVATE) }
    var friend by remember { mutableStateOf(prefs.getString("friend", "").orEmpty()) }
    var said by remember { mutableStateOf<String?>(null) }
    var tune by remember { mutableStateOf<Int?>(null) }
    var picks by remember { mutableStateOf<List<Pair<FriendBlend.Pick, Song>>>(emptyList()) }
    var who by remember { mutableStateOf("") }
    fun blend() {
        val name = friend.trim()
        if (name.isEmpty()) return
        prefs.edit().putString("friend", name).apply()
        said = "reading $name's listening"; picks = emptyList(); tune = null
        scope.launch {
            val theirs = FriendBlend.read(name)
            if (theirs == null) { said = "last.fm has no listening for $name"; return@launch }
            val taste = Fresh.taste.value
            // your favorites: what you've played most these six months
            val mine = withContext(Dispatchers.IO) {
                val since = System.currentTimeMillis() - 182L * 86_400_000
                History.read(ctx).filter { it.at >= since }.groupingBy { it.artist.substringBefore(", ") to it.title }.eachCount()
                    .entries.sortedByDescending { it.value }.take(150).map { it.key }
            }
            val mix = FriendBlend.mix(theirs, taste::of, mine)
            tune = FriendBlend.inTune(theirs, taste::of)
            said = "finding ${mix.size} songs"
            val found = Import.match(mix.map { Import.Said(it.title, it.artist, "") }) { said = "finding songs: $it of ${mix.size}" }
            picks = mix.zip(found).mapNotNull { (p, s) -> s?.let { p to it } }
            who = theirs.user
            said = null
        }
    }
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText("BLEND", style = headline(40.sp))
            BasicText("your taste and a friend's, mixed. their last.fm name is all it needs", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(18.dp))
            Field("their last.fm name", friend, { friend = it }, "a friend's last.fm name", KeyboardType.Text)
            Spacer(Modifier.height(14.dp))
            Word("BLEND", headline(32.sp), touch = 8.dp) { blend() }
            said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp)) }
            tune?.let { t ->
                Spacer(Modifier.height(22.dp))
                BasicText("$t% IN TUNE", style = headline(30.sp))
                BasicText("with ${who.lowercase()}, by how many of their top artists are near you", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
            }
            if (picks.isNotEmpty()) Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Word("play all", pixel(14.sp), touch = 8.dp) { Playback.play(picks.map { it.second }, 0) }
            }
        }
        itemsIndexed(picks, key = { _, p -> p.second.id }) { i, (p, s) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Playback.play(picks.map { it.second }, i) })) {
                BasicText(s.title.uppercase(), style = headline(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val side = when (p.side) { "both" -> "you both play them"; "theirs" -> "from ${who.lowercase()}"; else -> "from you" }
                BasicText("${s.artist.lowercase()}  ·  $side", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
}
