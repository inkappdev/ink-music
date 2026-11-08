package app.ink.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSProcessInfo

/**
 * Pictures of the screens, for building them from the PC (tools/ios-shot.sh): launched with `-inkShot listen,play,player`,
 * each step shown in turn and saved as shot-<step>.png in the app's folder, to be copied off. "play" starts your liked
 * songs, silent. Never otherwise: without the argument the app is drawn as it is.
 */
@Composable
fun Shooting(content: @Composable () -> Unit) {
    val asked = remember {
        NSProcessInfo.processInfo.arguments.map { it.toString() }.let { a -> a.indexOf("-inkShot").takeIf { it >= 0 }?.let { a.getOrNull(it + 1) } }
    }
    if (asked == null) { content(); return }
    val layer = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) { content() }
    LaunchedEffect(Unit) {
        Sound.quiet()
        delay(5_000)
        for (step in asked.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                step == "play" -> {
                    val liked = Here.off { Tube.readLibrary()?.liked.orEmpty() }
                    if (liked.isNotEmpty()) Sound.play(liked, 0)
                }
                step == "player" -> Pages.player = true
                step == "back" -> { Pages.player = false; Pages.close() }
                step in PLACES -> { Pages.player = false; Pages.stack.clear(); Pages.place = step }
                step == "find" -> { Pages.player = false; Pages.stack.clear(); Pages.open(Page.Find) }
                step == "settings" -> { Pages.player = false; Pages.stack.clear(); Pages.open(Page.Settings) }
                step.startsWith("wait") -> { delay(step.removePrefix("wait").toLongOrNull() ?: 3_000); continue }
            }
            delay(4_000)
            val png = runCatching { Image.makeFromBitmap(layer.toImageBitmap().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes }
                .onFailure { println("SHOT $step: $it") }.getOrNull() ?: continue
            File(Here.dir, "shot-$step.png").writeBytes(png)
            println("SHOT $step")
        }
        println("SHOT done")
    }
}
