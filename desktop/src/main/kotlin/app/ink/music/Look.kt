package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * The look (settings, the look; the phone's, 2026-10-30): the cover's own effect, still or moving; one background
 * behind the player, the cover in an effect; whether the big names move. On the computer the background is plain at
 * first, as it always was: the cover's print in the dither beside it; any other, the whole window behind the player.
 */
enum class Look(val word: String, val alpha: Float) {
    BLUR("blur", .3f),
    FLUTED("fluted glass", .45f),
    PIXELS("pixels", .4f),
    DITHER("dither", .4f),
    // the cover itself, held to half over the black so the words over it read
    AS_IS("as it is", .5f),
    PLAIN("plain", 0f),
}

/** The background behind the player. */
object Looks {
    private val _on = MutableStateFlow(Look.PLAIN)
    val on: StateFlow<Look> = _on
    fun set(look: Look) { _on.value = look; LookFile.save() }
    internal fun put(look: Look) { _on.value = look }
}

/** Moving covers (Apple Music's motion artwork, [MotionCover]) and the cover's effect. */
object Moving {
    /** The effects a cover takes (blur would hide it); null is as it is. */
    val coverLooks = listOf(Look.DITHER, Look.PIXELS, Look.FLUTED)
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on
    private val _cover = MutableStateFlow<Look?>(null)
    val cover: StateFlow<Look?> = _cover
    fun set(on: Boolean = _on.value, cover: Look? = _cover.value) { _on.value = on; _cover.value = cover; LookFile.save() }
    internal fun put(on: Boolean, cover: Look?) { _on.value = on; _cover.value = cover }
}

/** Whether the big names (the player's artist along the foot, ink weekly's, a long one on an artist's page) move. */
object Marquees {
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on
    fun set(on: Boolean) { _on.value = on; LookFile.save() }
    internal fun put(on: Boolean) { _on.value = on }
}

/** The three kept together (look.json). */
internal object LookFile {
    private val file get() = File(Home.dir, "look.json")

    fun start() {
        MotionCover.dir = Home.cache
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        Looks.put(Look.entries.firstOrNull { it.name == o.optString("behind") } ?: Look.PLAIN)
        Moving.put(o.optBoolean("moving", true), Moving.coverLooks.firstOrNull { it.name == o.optString("cover") })
        Marquees.put(o.optBoolean("marquee", true))
    }

    fun save() {
        runCatching {
            file.writeText(JSONObject().put("behind", Looks.on.value.name).put("moving", Moving.on.value)
                .put("cover", Moving.cover.value?.name ?: "AS_IS").put("marquee", Marquees.on.value).toString())
        }
    }
}
