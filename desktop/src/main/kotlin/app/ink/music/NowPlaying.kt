package app.ink.music

import androidx.compose.ui.graphics.asSkiaBitmap
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The Mac's Now Playing (the menu bar's and Control Center's card, the lock screen, the media keys): the song, its
 * artist, record and cover, where it is in it, and its buttons, through MediaPlayer's MPNowPlayingInfoCenter and
 * MPRemoteCommandCenter by the Objective-C runtime (JNA). mpv's own (`media-controls`) is fed only when mpv is its
 * own app: as a library it showed "ink music", a dash and no times (Bronson, 2026-10-29).
 */
object NowPlaying {
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_autoreleasePoolPush(): Pointer
        fun objc_autoreleasePoolPop(pool: Pointer)
    }

    /** A CGSize, as a block is handed it by value. */
    @Structure.FieldOrder("width", "height")
    open class Size : Structure() {
        @JvmField var width = 0.0
        @JvmField var height = 0.0
        class ByValue : Size(), Structure.ByValue
    }

    /** A block's invoke: the artwork's (a size asked for, the picture) and a command's (its event, handled or not). */
    private fun interface Art : Callback { fun invoke(block: Pointer, size: Size.ByValue): Pointer? }
    private fun interface Command : Callback { fun invoke(block: Pointer, event: Pointer): Long }

    private lateinit var objc: ObjC
    private lateinit var send: Function
    private val one = Executors.newSingleThreadExecutor { Thread(it, "ink-nowplaying").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + one)
    /** Kept from the collector: native code holds them (blocks, their callbacks, the cover). */
    private val held = mutableListOf<Any>()
    @Volatile private var image: Pointer? = null
    private var artwork: Pointer? = null
    /** The one block the artwork asks through: the cover now. */
    private var artBlock: Pointer? = null
    private var shownCover: String? = null

    private fun cls(name: String) = objc.objc_getClass(name)!!
    private fun sel(name: String) = objc.sel_registerName(name)
    private fun msg(target: Pointer?, selector: String, vararg args: Any?): Pointer? = send.invokePointer(arrayOf(target, sel(selector), *args))
    private fun str(s: String) = msg(cls("NSString"), "stringWithUTF8String:", s)
    private fun num(d: Double) = msg(cls("NSNumber"), "numberWithDouble:", d)

    /** A global block (nothing captured, never copied or freed) whose invoke is `f`. */
    private fun block(f: Callback): Pointer {
        val descriptor = Memory(16).apply { setLong(0, 0); setLong(8, 32) }
        val b = Memory(32)
        b.setPointer(0, NativeLibrary.getInstance("System").getGlobalVariableAddress("_NSConcreteGlobalBlock"))
        b.setInt(8, 1 shl 28) // BLOCK_IS_GLOBAL
        b.setInt(12, 0)
        b.setPointer(16, com.sun.jna.CallbackReference.getFunctionPointer(f))
        b.setPointer(24, descriptor)
        held.addAll(listOf(f, descriptor, b))
        return b
    }

    /**
     * Native calls gone wrong end the app, not an exception: a mark is left while it's set up and the first song shown,
     * so after a crash there the app opens with mpv's own instead (the mark deleted, it's tried again).
     */
    private val trying = java.io.File(Home.dir, "now-playing-trying")
    /** Whether ink's own is used (else mpv's `media-controls`). */
    val ours: Boolean = Home.mac && !trying.exists().also { if (it) { Log.d("now playing: crashed last time, mpv's instead"); trying.delete() } }

    /** Begun with the player, on a Mac only: the buttons answered, the card kept to what plays. */
    fun start() {
        if (!ours) return
        runCatching { trying.writeText("") }
        scope.launch {
            runCatching {
                objc = Native.load("objc", ObjC::class.java)
                send = Function.getFunction("objc", "objc_msgSend")
                NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
                NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit")
                commands()
            }.onFailure { Log.d("now playing: $it"); return@launch }
            combine(Player.now, Player.onAir) { n, air -> n to air }
                .distinctUntilChanged { a, b -> a.first?.song?.id == b.first?.song?.id && a.first?.playing == b.first?.playing && a.first?.duration == b.first?.duration && a.second == b.second }
                .collect { (n, _) -> runCatching { show(n) }.onFailure { Log.d("now playing: $it") }; if (n != null && image != null) trying.delete() }
        }
    }

    /** Where it is, said again after a seek (the card counts on by itself from the last time it was told). */
    fun moved() { if (ours) scope.launch { runCatching { show(Player.now.value) } } }

    private fun commands() {
        val pool = objc.objc_autoreleasePoolPush()
        val center = msg(cls("MPRemoteCommandCenter"), "sharedCommandCenter")
        fun on(command: String, f: (Pointer) -> Unit) {
            val c = msg(center, command)
            msg(c, "setEnabled:", true)
            msg(c, "addTargetWithHandler:", block(Command { _, event -> runCatching { f(event) }; 0L }))  // MPRemoteCommandHandlerStatusSuccess
        }
        on("togglePlayPauseCommand") { Player.toggle() }
        on("playCommand") { if (Player.now.value?.playing != true) Player.toggle() }
        on("pauseCommand") { if (Player.now.value?.playing == true) Player.toggle() }
        on("nextTrackCommand") { Player.next() }
        on("previousTrackCommand") { Player.previous() }
        on("changePlaybackPositionCommand") { e ->
            val at = send.invokeDouble(arrayOf(e, sel("positionTime")))
            Player.seek((at * 1000).toLong())
        }
        // the picture the card asks for, at whatever size: the cover as it is
        artBlock = block(Art { _, _ -> image })
        objc.objc_autoreleasePoolPop(pool)
    }

    private suspend fun show(n: Player.Now?) {
        val pool = objc.objc_autoreleasePoolPush()
        try {
            val center = msg(cls("MPNowPlayingInfoCenter"), "defaultCenter")
            if (n == null) {
                msg(center, "setNowPlayingInfo:", null as Pointer?)
                msg(center, "setPlaybackState:", 3L) // stopped
                return
            }
            val s = n.song
            // a station: what it says it's playing, the station as the record
            val air = Player.onAir.value
            val (title, artist) = if (s.station && air != null) air.title to (air.artist ?: s.title) else s.title to s.artist
            val info = msg(cls("NSMutableDictionary"), "dictionary")
            fun put(key: String, value: Pointer?) { if (value != null) msg(info, "setObject:forKey:", value, str(key)) }
            put("title", str(title))
            put("artist", str(artist))
            put("albumTitle", str(if (s.station) s.title else s.album))
            if (!s.station && n.duration > 0) put("playbackDuration", num(n.duration / 1000.0))
            put("MPNowPlayingInfoPropertyElapsedPlaybackTime", num(Player.position.value / 1000.0))
            put("MPNowPlayingInfoPropertyPlaybackRate", num(if (n.playing) 1.0 else 0.0))
            put("MPNowPlayingInfoPropertyMediaType", num(1.0)) // audio
            cover(s)?.let { put("artwork", it) }
            msg(center, "setNowPlayingInfo:", info)
            msg(center, "setPlaybackState:", if (n.playing) 1L else 2L)
        } finally {
            objc.objc_autoreleasePoolPop(pool)
        }
    }

    /** The song's cover as the card's artwork (an NSImage behind a block), made again only when the cover changes. */
    private suspend fun cover(s: Song): Pointer? {
        val key = s.albumId + (s.art ?: "")
        if (key == shownCover) return artwork
        shownCover = key
        val bytes = withContext(Dispatchers.IO) {
            Covers.of(s, 600)?.image?.let { org.jetbrains.skia.Image.makeFromBitmap(it.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 90)?.bytes }
        }
        val old = image
        image = bytes?.let { b ->
            val m = Memory(b.size.toLong()).apply { write(0, b, 0, b.size) }
            val data = msg(cls("NSData"), "dataWithBytes:length:", m, b.size.toLong())
            msg(msg(cls("NSImage"), "alloc"), "initWithData:", data)
        }
        old?.let { msg(it, "release") }
        artwork?.let { msg(it, "release") }
        artwork = image?.let {
            val size = Size.ByValue().apply { width = 600.0; height = 600.0 }
            msg(msg(cls("MPMediaItemArtwork"), "alloc"), "initWithBoundsSize:requestHandler:", size, artBlock)
        }
        return artwork
    }
}
