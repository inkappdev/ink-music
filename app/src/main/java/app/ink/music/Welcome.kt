package app.ink.music

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The first open: ink music before anything is asked. The look first (INK MUSIC over a slow drift
 * of covers); then where your music is (this phone, your server, YouTube
 * Music, radio: the phone's files asked for only if you choose them); then a few artists you love
 * (the play build: the kinds of radio you like), which the listen page grows from.
 */
@Composable
fun Welcome(onAllowed: () -> Unit, onDone: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    fun finish() {
        Seeds.welcome()
        if (android.os.Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
    }
    Box(Modifier.fillMaxSize().background(Ink.black)) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                ContentTransform(
                    slideInHorizontally(tween(Motion.ENTER, easing = Motion.expo)) { it / 5 } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
                    slideOutHorizontally(tween(Motion.EXIT, easing = Motion.expo)) { -it / 10 } + fadeOut(tween(Motion.EXIT)),
                )
            },
            label = "welcome",
        ) { s ->
            when (s) {
                0 -> Hello { step = 1 }
                1 -> Sources(onAllowed) { step = 2 }
                else -> if (YouTubeMusic.available) ArtistPicker("PICK A FEW ARTISTS YOU LOVE", skip = true, onDone = { finish() })
                    else GenrePicker { finish() }
            }
        }
    }
}

/** INK MUSIC over covers drifting by, the line about it, and the one word to go on. */
@Composable
private fun Hello(onNext: () -> Unit) {
    // real records' covers (the full build): the well-loved artists' best-known songs, found
    // side by side while the welcome is up, each shown as it comes
    val covers = remember { mutableStateListOf<Song>() }
    LaunchedEffect(Unit) {
        if (!YouTubeMusic.available) return@LaunchedEffect
        Seeds.offered.take(24).chunked(6).forEach { batch ->
            batch.map { name -> async(Dispatchers.IO) { YouTubeMusic.hit(name) } }.awaitAll()
                .filterNotNull().filter { it.art != null }.forEach {
                    // let in one at a time, not six at once
                    if (covers.none { c -> c.art == it.art }) { covers.add(it); delay(Motion.STAGGER.toLong()) }
                }
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (covers.isNotEmpty()) Drift(covers, Modifier.fillMaxSize().alpha(.6f))
        // no covers to show (the play build, or offline): the kinds of music, drifting by as words
        else if (!YouTubeMusic.available) WordDrift(Stations.genres.map { it.first }, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().windowInsetsPadding(bars).navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp)) {
            Spacer(Modifier.weight(1f))
            BasicText("INK", style = headline(96.sp))
            BasicText("MUSIC", style = pixel(40.sp), modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(22.dp))
            BasicText("designed around the music you love", style = pixel(13.sp, Ink.mid))
            Spacer(Modifier.height(30.dp))
            Word("BEGIN", headline(56.sp), touch = 12.dp, onClick = onNext)
        }
    }
}

/**
 * A cover in the dither (the player's own drawing of it, at the size it's shown), black till it's
 * ready, then developing: from black to the whole of it, the brightest dots first, on ink's curve.
 */
@Composable
private fun Looked(song: Song, side: Dp) {
    val ctx = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val frames by produceState<List<androidx.compose.ui.graphics.ImageBitmap>?>(null, song.id) {
        value = withContext(Dispatchers.Default) {
            val c = Covers.of(ctx, song) ?: return@withContext null
            Looks.develop(c.image.asAndroidBitmap(), c.accent, with(density) { side.toPx() }, density.density)?.map { it.asImageBitmap() }
        }
    }
    val f = frames
    if (f == null) { Box(Modifier.size(side)); return }
    val shown = remember(f) { Animatable(0f) }
    LaunchedEffect(f) { shown.animateTo(1f, tween(Motion.ENTER, easing = Motion.expo)) }
    androidx.compose.foundation.Image(f[((shown.value * f.size).toInt()).coerceIn(0, f.size - 1)], null, Modifier.size(side),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
}

/** Where the covers go as they come, spread over the three rows (row * 8 + place), not filling the top row first. */
private val SLOTS = intArrayOf(9, 2, 21, 13, 5, 18, 11, 0, 23, 15, 7, 19, 3, 12, 17, 6, 22, 1, 10, 16, 4, 20, 14, 8)

/** Three rows of covers sliding by, each its own way and pace, wider than the screen. */
@Composable
private fun Drift(covers: List<Song>, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "drift")
    val run by t.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Reverse), label = "run")
    Column(modifier.clipToBounds().padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // each slot's cover, once one has come for it
        val at = arrayOfNulls<Song>(SLOTS.size).also { slot -> covers.take(SLOTS.size).forEachIndexed { i, s -> slot[SLOTS[i]] = s } }
        for (row in 0 until 3) {
            val side = 150.dp
            val way = if (row % 2 == 0) -1 else 1
            Row(Modifier.requiredWidth(side * 8 + 12.dp * 7).offset(x = (way * run * 220 - 110 * way).dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (place in 0 until 8) {
                    val s = at[row * 8 + place]
                    if (s == null) Box(Modifier.size(side)) else key(s.id) { Looked(s, side) }
                }
            }
        }
    }
}

/** Words in rows, huge and faint, sliding by each its own way (the welcome without covers). */
@Composable
private fun WordDrift(words: List<String>, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "words")
    val run by t.animateFloat(0f, 1f, infiniteRepeatable(tween(50_000, easing = LinearEasing), RepeatMode.Reverse), label = "run")
    Column(modifier.clipToBounds().padding(top = 30.dp)) {
        words.chunked(3).take(4).forEachIndexed { row, list ->
            val way = if (row % 2 == 0) -1 else 1
            BasicText(list.joinToString("  ").uppercase(), style = headline(88.sp, Ink.dim), maxLines = 1, softWrap = false,
                modifier = Modifier.requiredWidth(1400.dp).offset(x = (way * run * 300 - 150 * way).dp))
        }
    }
}

/** Where your music is: each a word, lit once it's there. */
@Composable
private fun Sources(onAllowed: () -> Unit, onNext: () -> Unit) {
    val ctx = LocalContext.current
    var phone by remember { mutableStateOf(Library.allowed(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        phone = Library.allowed(ctx); if (phone) onAllowed()
    }
    val account by Server.account.collectAsState()
    var server by remember { mutableStateOf(false) }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(bars).navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
    ) {
        item {
            BasicText("WHERE'S YOUR MUSIC?", style = headline(40.sp))
            Spacer(Modifier.height(8.dp))
            BasicText("all of these, or any. you can change it later in settings", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(26.dp))
            Source("this phone", if (phone) "its music files, read" else "the music files on it", phone) {
                if (!phone) ask.launch(Manifest.permission.READ_MEDIA_AUDIO)
            }
            Source("your server", account?.let { "signed in at ${it.base.substringAfter("://")}" } ?: "navidrome or any subsonic server", account != null) {
                server = !server
            }
            if (server && account == null) Box(Modifier.padding(top = 4.dp, bottom = 18.dp)) { SignIn() }
            if (YouTubeMusic.available) Source("youtube music", "search and play anything, no account", true) {}
            Source("radio", "thousands of stations, every kind of music", true) {}
            Spacer(Modifier.height(30.dp))
            Word("NEXT", headline(56.sp), touch = 12.dp, onClick = onNext)
        }
    }
}

/** A source: its name big, lit when it's there (selection is brightness), a line on it under. */
@Composable
private fun Source(name: String, said: String, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 12.dp)) {
        BasicText(name.uppercase(), style = headline(30.sp, if (on) Ink.ink else Ink.mid))
        BasicText(said, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Artists to pick, as faces: the library's most-heard first, then well-loved ones; "find" for
 * anyone else. Picked are lit, the rest dimmed. Done keeps them ([Seeds]); the first open may skip.
 */
@Composable
fun ArtistPicker(title: String, skip: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val songs by Library.songs.collectAsState()
    val picked = remember { mutableStateListOf<String>().apply { addAll(Seeds.artists.value.map { it.name }) } }
    val found = remember { mutableStateListOf<String>() }
    // the library's own, by how many songs, before the well-loved ones
    val mine = remember(songs) {
        songs.flatMap { it.artist.split(", ") }.filter { it != "unknown" }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(9).map { it.key }
    }
    // the order holds still as you pick (what you'd picked before, then the library's, then the well-loved);
    // only an artist found by name comes in at the top
    val first = remember(mine) { (Seeds.artists.value.map { it.name } + mine + Seeds.offered).distinctBy { it.lowercase() } }
    val offered = (found + first).distinctBy { it.lowercase() }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun find() {
        val q = query.trim(); if (q.isEmpty()) return
        scope.launch {
            val a = withContext(Dispatchers.IO) { YouTubeMusic.artist(q) } ?: return@launch
            if (a.name !in found) found.add(0, a.name)
            if (a.name !in picked) picked.add(a.name)
            query = ""
        }
    }
    Box(Modifier.fillMaxSize().background(Ink.black)) {
        LazyVerticalGrid(
            GridCells.Fixed(3),
            Modifier.fillMaxSize().windowInsetsPadding(bars),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(3) }) {
                Column {
                    BasicText(title, style = headline(36.sp))
                    Spacer(Modifier.height(8.dp))
                    BasicText("your listen page grows from them: their mixes, their records", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("find > ", style = pixel(14.sp, Ink.dim))
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { find() }),
                            modifier = Modifier.weight(1f))
                        if (query.isNotBlank()) Word("add", pixel(14.sp), touch = 8.dp) { find() }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            items(offered, key = { it.lowercase() }) { name ->
                val on = name in picked
                Face(name, on) { if (on) picked.remove(name) else picked.add(name) }
            }
        }
        // done, over the foot, on black
        Row(
            // above the mini player when there is one
            Modifier.align(Alignment.BottomCenter).padding(bottom = LocalFoot.current).fillMaxWidth().background(Ink.black)
                .then(if (LocalFoot.current == 0.dp) Modifier.navigationBarsPadding() else Modifier)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(if (picked.isEmpty()) "pick a few" else "${picked.size} picked", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
            if (skip) Word("skip", pixel(13.sp, Ink.mid), Modifier.padding(end = 24.dp), touch = 10.dp, onClick = onDone)
            Word("DONE", headline(40.sp, if (picked.isEmpty()) Ink.dim else Ink.ink), touch = 10.dp) {
                if (picked.isEmpty() && !skip) return@Word
                scope.launch {
                    val faces = withContext(Dispatchers.IO) { picked.map { n -> Seeds.Artist(n, YouTubeMusic.artist(n)?.face, Seeds.artists.value.firstOrNull { it.name == n }?.hit) } }
                    Seeds.setArtists(faces)
                    Listen.load(ctx)
                    onDone()
                }
            }
        }
    }
}

/** An artist's face (their photo, found by name; ink's sleeve with the name till then), their name under it. */
@Composable
private fun Face(name: String, on: Boolean, onClick: () -> Unit) {
    val face by produceState<String?>(null, name) { value = withContext(Dispatchers.IO) { YouTubeMusic.artist(name)?.face } }
    val shown = face?.let { faceSong(name, it) }
    Column(Modifier.alpha(if (on) 1f else .45f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        CoverOf(shown, name, Modifier.fillMaxWidth().aspectRatio(1f))
        BasicText(name.lowercase(), style = pixel(12.sp, if (on) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/** The play build's last step: the kinds of radio you like, as words, lit when picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenrePicker(onDone: () -> Unit) {
    val picked = remember { mutableStateListOf<String>().apply { addAll(Seeds.genres.value) } }
    Column(Modifier.fillMaxSize().windowInsetsPadding(bars).navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp)) {
        BasicText("WHAT DO YOU LIKE?", style = headline(40.sp))
        Spacer(Modifier.height(8.dp))
        BasicText("radio for these comes first", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Stations.genres.forEach { (word, _) ->
                val on = word in picked
                Word(word.uppercase(), headline(30.sp, if (on) Ink.ink else Ink.dim), touch = 6.dp) { if (on) picked.remove(word) else picked.add(word) }
            }
        }
        Spacer(Modifier.weight(1f))
        Word("DONE", headline(56.sp), touch = 12.dp) { Seeds.setGenres(picked.toList()); onDone() }
    }
}

