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
    private val _canvasOn = MutableStateFlow(true)
    /** A song's Spotify canvas behind the player (handed over by the phone), or none. */
    val canvasOn: StateFlow<Boolean> = _canvasOn
    fun set(on: Boolean = _on.value, cover: Look? = _cover.value, canvasOn: Boolean = _canvasOn.value) {
        _on.value = on; _cover.value = cover; _canvasOn.value = canvasOn; LookFile.save()
    }
    internal fun put(on: Boolean, cover: Look?, canvasOn: Boolean) { _on.value = on; _cover.value = cover; _canvasOn.value = canvasOn }
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
        SpotifyHanded.start()
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        Looks.put(Look.entries.firstOrNull { it.name == o.optString("behind") } ?: Look.PLAIN)
        Moving.put(o.optBoolean("moving", true), Moving.coverLooks.firstOrNull { it.name == o.optString("cover") }, o.optBoolean("canvas", true))
        Marquees.put(o.optBoolean("marquee", true))
    }

    fun save() {
        runCatching {
            file.writeText(JSONObject().put("behind", Looks.on.value.name).put("moving", Moving.on.value)
                .put("cover", Moving.cover.value?.name ?: "AS_IS").put("canvas", Moving.canvasOn.value).put("marquee", Marquees.on.value).toString())
        }
    }
}

/**
 * Spotify's passes, handed over by the phone in sync (the computer can't sign in to Spotify: its sign-in is the
 * phone's web player). They last an hour; the phone hands new ones while it's open and in step. Kept in a file
 * only you can read, so a restart within the hour keeps them.
 */
object SpotifyHanded {
    private val file get() = File(Home.dir, "spotify-pass.json")
    private val _pass = MutableStateFlow<SpotifyCanvas.Pass?>(null)
    /** The passes, while within their hour. */
    val pass: StateFlow<SpotifyCanvas.Pass?> = _pass
    /** When the phone last handed passes (0: never): settings say so. */
    @Volatile var handedAt = 0L
        private set

    fun start() {
        SpotifyCanvas.keepIn(File(Home.cache, "spotify-canvas.json"))
        SpotifyCanvas.Pass.of(runCatching { file.readText() }.getOrDefault(""))?.let { handedAt = it.at; if (it.fresh) _pass.value = it }
    }

    fun take(text: String) {
        val p = SpotifyCanvas.Pass.of(text) ?: return
        if (p.bearer == _pass.value?.bearer) return
        handedAt = p.at
        _pass.value = p.takeIf { it.fresh }
        runCatching { file.writeText(text); file.setReadable(false, false); file.setReadable(true, true) }
        Log.d("spotify passes from the phone")
    }

    /** A song's canvas: as known, else asked with the phone's passes (none in hand: none). */
    suspend fun canvas(title: String, artist: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        SpotifyCanvas.known(title, artist).let { (asked, url) -> if (asked) return@withContext url }
        val p = _pass.value?.takeIf { it.fresh } ?: return@withContext null
        runCatching { SpotifyCanvas.find(title, artist, p) }
            .onFailure { e -> Log.d("canvas: $e"); if (e is SpotifyCanvas.Refused) _pass.value = null }
            .getOrNull().also { Log.d("canvas for $artist / $title: ${if (it != null) "found" else "none"}") }
    }
}
