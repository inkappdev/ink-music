package app.ink.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** ink's look, the phone's and the computer's (Theme.kt there): black, ink, its two steps down, hairlines. */
object Ink {
    val black = Color.Black
    val ink = Color(0xFFF1F0EC)
    val mid = ink.copy(alpha = .70f)
    val dim = ink.copy(alpha = .35f)
    val line = ink.copy(alpha = .12f)
    val plate = Color(0xFF141414)
}

// Two fonts, never a third: Archivo Black UPPERCASE, Silkscreen lowercase (the app's own files, in its bundle)
val Archivo by lazy { FontFamily(androidx.compose.ui.text.platform.Font("archivo_black", fontBytes("archivo_black"))) }
val Pixel by lazy { FontFamily(androidx.compose.ui.text.platform.Font("silkscreen", fontBytes("silkscreen"))) }

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

fun headline(size: TextUnit, color: Color = Ink.ink) = TextStyle(
    fontFamily = Archivo, fontSize = size, color = color, letterSpacing = (-0.02).em, lineHeight = 0.95.em, lineHeightStyle = tight,
)

fun pixel(size: TextUnit, color: Color = Ink.ink) = TextStyle(
    fontFamily = Pixel, fontSize = if (size.value < 12f) 12.sp else size, color = color, lineHeight = 1.4.em,
)

/** m:ss */
fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
