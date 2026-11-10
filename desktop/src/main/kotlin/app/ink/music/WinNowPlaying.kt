package app.ink.music

import androidx.compose.ui.graphics.asSkiaBitmap
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Windows' media controls (the card over the volume flyout and on the lock screen, the media keys, the taskbar's): the
 * song, its artist, record and cover, playing or paused, and back / play / next answered by ink's own player, through
 * Windows' SystemMediaTransportControls by hand (WinRT's COM, through JNA), as the Mac's [NowPlaying] is.
 *
 * mpv has its own (`media-controls`), but it only knows what's in the file's tags: a YouTube song showed as its id
 * ("58pWeliush0"), no artist and no cover (2026-11-09). Its next and back also went round ink's player (a speaker,
 * a crossfade). This one is the main window's: a click on the card brings ink music forward.
 */
internal object WinNowPlaying {
    private interface Combase : Library {
        fun RoInitialize(type: Int): Int
        fun WindowsCreateString(s: WString, length: Int, out: PointerByReference): Int
        fun WindowsDeleteString(h: Pointer?): Int
        fun RoGetActivationFactory(classId: Pointer, iid: Pointer, out: PointerByReference): Int
    }

    private fun interface QueryInterface : Callback { fun invoke(self: Pointer, iid: Pointer, out: Pointer): Int }
    private fun interface Count : Callback { fun invoke(self: Pointer): Int }
    private fun interface Pressed : Callback { fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int }

    private lateinit var com: Combase
    private val one = Executors.newSingleThreadExecutor { Thread(it, "ink-media-controls").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + one)
    /** What native code holds on to: the button handler, its table and callbacks. */
    private val held = mutableListOf<Any>()
    private var smtc: Pointer? = null
    private var updater: Pointer? = null
    private var shownCover: String? = null
    private var coverFile: File? = null

    private const val PLAYING = 3
    private const val PAUSED = 4
    private const val STOPPED = 2

    // the interfaces asked for by name, and each method below by its place in its interface: both read from the
    // Windows SDK's headers (10.0.26100, winrt\windows.media.h, windows.storage.h, windows.storage.streams.h,
    // AsyncInfo.h). A wrong place calls the wrong method and the app goes down, so check there before changing one
    private const val IID_INTEROP = "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a"
    private const val IID_SMTC = "99fa3ff4-1742-42a6-902e-087d41f965ec"
    private const val IID_MUSIC2 = "00368462-97d3-44b9-b00f-008afcefaf18"
    private const val IID_STREAM_REF_STATICS = "857309dc-3fbf-4e7d-986f-ef3b1a07a964"
    private const val IID_STORAGE_FILE_STATICS = "5984c710-daf2-43c8-8bb4-a4d3eacfd03f"
    private const val IID_ASYNC_INFO = "00000036-0000-0000-c000-000000000046"
    private const val IID_UNKNOWN = "00000000-0000-0000-c000-000000000046"
    private const val IID_AGILE = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"
    /** TypedEventHandler<SystemMediaTransportControls, SystemMediaTransportControlsButtonPressedEventArgs>. */
    private const val IID_PRESSED = "0557e996-7b23-5bae-aa81-ea0d671143a4"

    /** Begun with the player: once the main window's there, the card is ink's, kept to what plays. */
    fun start(done: () -> Unit) {
        scope.launch {
            val window = withContext(Dispatchers.IO) { mainWindow() } ?: return@launch Log.d("media controls: no window")
            runCatching { setUp(window) }.onFailure { Log.d("media controls: $it"); return@launch }
            combine(Player.now, Player.onAir) { n, air -> n to air }
                .distinctUntilChanged { a, b -> a.first?.song?.id == b.first?.song?.id && a.first?.playing == b.first?.playing && a.second == b.second }
                .collect { (n, _) -> runCatching { show(n) }.onFailure { Log.d("media controls: $it") }; if (n != null) done() }
        }
    }

    /** The app quitting: the card let go, so Windows doesn't keep ink music as what plays. */
    fun stop() {
        val s = smtc ?: return
        runCatching { call(s, 7, STOPPED); call(s, 11, 0) }
    }

    /** ink music's main window, once it's on the screen (up to half a minute). */
    private suspend fun mainWindow(): java.awt.Window? {
        repeat(120) {
            java.awt.Frame.getFrames().firstOrNull { it.isDisplayable && it.title == "ink music" }?.let { return it }
            delay(250)
        }
        return null
    }

    private fun setUp(window: java.awt.Window) {
        // the covers a run before left behind (the one shown is let go at each change, the last at the quit)
        Home.cache.listFiles { f -> f.name.startsWith("media-cover-") }?.forEach { it.delete() }
        com = Native.load("combase", Combase::class.java)
        com.RoInitialize(1) // the multithreaded apartment: this thread's, and the button presses' from Windows' own
        val interop = factory("Windows.Media.SystemMediaTransportControls", IID_INTEROP)
        val out = PointerByReference()
        check(call(interop, 6, Native.getWindowPointer(window), guid(IID_SMTC), out), "GetForWindow")
        release(interop)
        val s = out.value
        smtc = s
        call(s, 11, 1)   // IsEnabled
        call(s, 13, 1)   // IsPlayEnabled
        call(s, 17, 1)   // IsPauseEnabled
        call(s, 25, 1)   // IsPreviousEnabled
        call(s, 27, 1)   // IsNextEnabled
        val u = PointerByReference()
        check(call(s, 8, u), "DisplayUpdater")
        updater = u.value
        call(updater!!, 7, 1) // MediaPlaybackType.Music
        check(call(s, 32, handler(), LongByReference()), "ButtonPressed")
    }

    /** A COM object of ink's own for the button presses: Windows calls its Invoke with the button pressed. */
    private fun handler(): Pointer {
        val wanted = listOf(IID_UNKNOWN, IID_AGILE, IID_PRESSED).map { guid(it).getByteArray(0, 16).toList() }
        val self = Memory(Native.POINTER_SIZE.toLong())
        val qi = QueryInterface { me, iid, out ->
            if (iid.getByteArray(0, 16).toList() in wanted) { out.setPointer(0, me); 0 }
            else { out.setPointer(0, null); Log.d("media controls: asked for ${iidOf(iid)}"); 0x80004002.toInt() } // E_NOINTERFACE
        }
        val ref = Count { 1 }   // never freed: it lives as long as the app
        val pressed = Pressed { _, _, args ->
            runCatching {
                val b = IntByReference()
                val hr = if (args == null) -1 else call(args, 6, b)
                Log.d("media controls: pressed, ${if (hr >= 0) "button ${b.value}" else "no button (0x%08x)".format(hr)}")
                if (hr >= 0) javax.swing.SwingUtilities.invokeLater { press(b.value) }
            }.onFailure { Log.d("media controls: pressed, $it") }
            0
        }
        for (c in listOf(qi, ref, pressed)) Native.setCallbackThreadInitializer(c, CallbackThreadInitializer(true, false, "ink-media-controls"))
        val table = Memory(4L * Native.POINTER_SIZE)
        listOf(qi, ref, ref, pressed).forEachIndexed { i, c -> table.setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(c)) }
        self.setPointer(0, table)
        held.addAll(listOf(qi, ref, pressed, table, self))
        return self
    }

    /** A button on the card, or a media key: what ink's own buttons do (a speaker, a crossfade, the queue all heeded). */
    private fun press(button: Int) {
        val playing = Player.now.value?.playing == true
        when (button) {
            0 -> if (!playing) Player.toggle()          // Play
            1 -> if (playing) Player.toggle()           // Pause
            2 -> if (playing) Player.toggle()           // Stop: paused, the queue kept
            6 -> Player.next()
            7 -> Player.previous()
        }
    }

    private suspend fun show(n: Player.Now?) {
        val s = smtc ?: return
        val u = updater ?: return
        if (n == null) {
            call(u, 16)            // ClearAll
            call(u, 7, 1)
            call(u, 17)            // Update
            call(s, 7, STOPPED)
            return
        }
        val song = n.song
        // a station: what it says it's playing, the station as the record (the Mac's card's way)
        val air = Player.onAir.value
        val (title, artist) = if (song.station && air != null) air.title to (air.artist ?: song.title) else song.title to song.artist
        val music = PointerByReference()
        check(call(u, 12, music), "MusicProperties")
        val m = music.value
        withString(title) { call(m, 7, it) }
        withString(artist) { call(m, 11, it) }
        withString(if (song.station) song.title else song.albumArtist.ifBlank { artist }) { call(m, 9, it) }
        // the record, where Windows has the second set of music properties (Windows 10 and later)
        query(m, IID_MUSIC2)?.let { m2 -> withString(if (song.station) "" else song.album) { call(m2, 7, it) }; release(m2) }
        release(m)
        val key = song.albumId + (song.art ?: "")
        if (key != shownCover) {
            shownCover = key
            cover(u, song)
        }
        call(u, 17)                // Update
        call(s, 7, if (n.playing) PLAYING else PAUSED)
    }

    /**
     * The cover onto the card: written out as a JPEG among ink's cached things and handed to Windows as a file (a
     * YouTube cover's address would do as it is, but a server's needs ink's sign-in and a file's own is inside it).
     */
    private suspend fun cover(u: Pointer, song: Song) {
        val bytes = withContext(Dispatchers.IO) {
            Covers.of(song, 600)?.image?.let { org.jetbrains.skia.Image.makeFromBitmap(it.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 90)?.bytes }
        }
        if (bytes == null) { call(u, 11, null as Pointer?); return }
        // a new name each time: Windows keeps a file's picture by its name
        val file = File(Home.cache, "media-cover-${System.nanoTime()}.jpg")
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        coverFile?.delete()
        coverFile = file
        val statics = factory("Windows.Storage.StorageFile", IID_STORAGE_FILE_STATICS)
        val op = PointerByReference()
        val got = withString(file.absolutePath) { call(statics, 6, it, op) }  // GetFileFromPathAsync
        release(statics)
        if (got < 0) return
        val storage = finish(op.value) ?: return
        val refs = factory("Windows.Storage.Streams.RandomAccessStreamReference", IID_STREAM_REF_STATICS)
        val ref = PointerByReference()
        if (call(refs, 6, storage, ref) >= 0) { call(u, 11, ref.value); release(ref.value) }  // CreateFromFile, Thumbnail
        release(refs)
        release(storage)
    }

    /** An IAsyncOperation waited out (it's quick: a file's found), its result, or null. */
    private suspend fun finish(op: Pointer): Pointer? {
        val info = query(op, IID_ASYNC_INFO) ?: return null.also { release(op) }
        val status = IntByReference()
        var tries = 0
        while (call(info, 7, status) >= 0 && status.value == 0 && tries++ < 300) delay(10)
        release(info)
        val result = PointerByReference()
        val ok = status.value == 1 && call(op, 8, result) >= 0  // Completed, GetResults
        release(op)
        return if (ok) result.value else null
    }

    // ---- WinRT's COM by hand ----

    /** A method of a COM object by its place in the object's table (0 to 2 IUnknown's, 3 to 5 IInspectable's). */
    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int =
        Function.getFunction(obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)).invokeInt(arrayOf(obj, *args))

    private fun release(obj: Pointer?) { if (obj != null) call(obj, 2) }

    private fun query(obj: Pointer, iid: String): Pointer? {
        val out = PointerByReference()
        return if (call(obj, 0, guid(iid), out) >= 0) out.value else null
    }

    private fun factory(cls: String, iid: String): Pointer {
        val out = PointerByReference()
        check(withString(cls) { com.RoGetActivationFactory(it!!, guid(iid), out) }, "factory $cls")
        return out.value
    }

    /** A WinRT string (an HSTRING) for the length of `f`. */
    private inline fun withString(s: String, f: (Pointer?) -> Int): Int {
        if (s.isEmpty()) return f(null)
        val h = PointerByReference()
        com.WindowsCreateString(WString(s), s.length, h)
        try { return f(h.value) } finally { com.WindowsDeleteString(h.value) }
    }

    private fun check(hr: Int, what: String) { if (hr < 0) error("$what: 0x%08x".format(hr)) }

    /** A GUID as Windows has it in memory: the first three parts little-endian, the last two as bytes. */
    private fun guid(s: String): Memory {
        val h = s.replace("-", "")
        return Memory(16).apply {
            setInt(0, h.substring(0, 8).toLong(16).toInt())
            setShort(4, h.substring(8, 12).toInt(16).toShort())
            setShort(6, h.substring(12, 16).toInt(16).toShort())
            for (i in 0 until 8) setByte(8L + i, h.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
        }
    }

    private fun iidOf(p: Pointer) = "%08x-%04x-%04x-".format(p.getInt(0), p.getShort(4), p.getShort(6)) +
        (0 until 8).joinToString("") { "%02x".format(p.getByte(8L + it)) }.let { it.substring(0, 4) + "-" + it.substring(4) }
}
