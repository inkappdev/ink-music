package app.ink.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What the easter eggs ([Eggs]) know of your listening, read with it: the songs at their 100th play today, the last few heard, your first. */
object EggsNow {
    val golden = MutableStateFlow<Set<String>>(emptySet())
    val recent = MutableStateFlow<List<String>>(emptyList())
    val first = MutableStateFlow<History.Heard?>(null)

    fun take(all: List<History.Heard>) {
        golden.value = all.groupBy { it.id }.filterValues { Eggs.golden(it.map { h -> h.at }) }.keys
        recent.value = all.takeLast(4).map { it.id }
        first.value = all.minByOrNull { it.at }
    }

    /** The line under a song in the player, when an egg's due: a promise kept, on repeat, its 100th time. */
    @Composable
    fun line(s: Song): String? {
        val recent by recent.collectAsState()
        val golden by golden.collectAsState()
        val hidden by Private.on.collectAsState()
        return when {
            hidden && Eggs.shy(s.artist) -> "we said we wouldn't tell"
            Eggs.onRepeat(recent, s.id) -> "on repeat, huh"
            s.id in golden -> "your 100th time"
            else -> null
        }
    }
}

/** A heart's gold, the day of a song's 100th play. */
val GOLD = Color(0xFFE8B830)

/** 1-bit mode: every cover in the dither, its own colour, for the run (the Konami code; again turns it off). */
object OneBit {
    val on = MutableStateFlow(false)
    private val keys = ArrayDeque<String>()

    /** A key pressed; true when it finished the code (and 1-bit mode turned over). */
    fun key(name: String): Boolean {
        keys.addLast(name); while (keys.size > Eggs.KONAMI.size) keys.removeFirst()
        if (keys.toList() != Eggs.KONAMI) return false
        keys.clear(); on.value = !on.value
        return true
    }
}

/**
 * The heart after "made by ink": a click and it beats (lub-dub, a few times); seven clicks running and small hearts
 * float up from it and fade.
 */
@Composable
fun InkHeart(colour: Color) {
    val beat = remember { Animatable(1f) }
    var clicks by remember { mutableIntStateOf(0) }
    var lastClick by remember { mutableLongStateOf(0L) }
    val floating = remember { mutableStateListOf<Long>() }
    var beating by remember { mutableLongStateOf(0L) }
    LaunchedEffect(beating) {
        if (beating == 0L) return@LaunchedEffect
        repeat(4) {
            beat.animateTo(1.35f, tween(90)); beat.animateTo(1f, tween(110))
            beat.animateTo(1.2f, tween(80)); beat.animateTo(1f, tween(140))
            delay(420)
        }
    }
    // the hearts going up: each its own start, gone after two seconds
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(floating.size) { while (floating.isNotEmpty()) { tick = System.currentTimeMillis(); floating.removeAll { tick - it > 2_000 }; delay(16) } }
    Canvas(Modifier.size(10.5.dp, 9.dp).clickable(remember { MutableInteractionSource() }, indication = null) {
        val t = System.currentTimeMillis()
        clicks = if (t - lastClick < 600) clicks + 1 else 1
        lastClick = t
        beating = t
        if (clicks >= 7) { clicks = 0; repeat(9) { i -> floating += t + i * 110L } }
    }) {
        val cell = size.width / 7
        val k = beat.value
        heart(Offset(size.width * (1 - k) / 2, size.height * (1 - k) / 2), cell * k, colour)
        floating.forEach { start ->
            val p = ((tick - start) / 2_000f).coerceIn(0f, 1f)
            if (p <= 0f) return@forEach
            val sway = kotlin.math.sin((start % 1000) / 1000f * 6.28f + p * 9f) * size.width * .9f
            heart(Offset(sway, -p * size.height * 22f), cell * (.6f + (start % 3) * .2f), colour.copy(alpha = 1f - p))
        }
    }
}

/** ink's pixel heart (the love button's), drawn at `at`, `cell` a dot. */
fun DrawScope.heart(at: Offset, cell: Float, colour: Color) {
    HEART.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> if (ch == '#') drawRect(colour, Offset(at.x + c * cell, at.y + r * cell), Size(cell, cell)) } }
}
