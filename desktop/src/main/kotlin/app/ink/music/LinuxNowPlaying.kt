package app.ink.music

import androidx.compose.ui.graphics.asSkiaBitmap
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Linux's media controls: MPRIS on the session bus, as `org.mpris.MediaPlayer2.inkmusic`. What a desktop shows of what
 * plays and steers it by (Plasma's media widget and lock screen, GNOME's, the media keys, KDE Connect, playerctl): the
 * song, its artist, record and cover, where it is in it, and play / pause / next / back / seek answered by ink's own
 * player, as the Mac's [NowPlaying] and Windows' [WinNowPlaying] are. Without it nothing on Linux saw ink music playing
 * (2026-11-23, Plasma on Fedora).
 *
 * Through GDBus (GLib's gio, on every desktop Linux) by JNA, so no D-Bus library of its own. GLib calls back on a thread
 * of ink's own running GLib's loop; what plays is said from another. No bus, no gio: no MPRIS, and the app runs on.
 */
internal object LinuxNowPlaying {
    private const val NAME = "org.mpris.MediaPlayer2.inkmusic"
    private const val PATH = "/org/mpris/MediaPlayer2"
    private const val ROOT = "org.mpris.MediaPlayer2"
    private const val PLAYER = "org.mpris.MediaPlayer2.Player"
    /** The .desktop file jpackage installs (its name is also the window's class, [linuxWindow]). */
    private const val DESKTOP = "ink-music-ink_music"

    @Suppress("FunctionName", "unused")
    private interface Gio : Library {
        fun g_main_context_new(): Pointer
        fun g_main_context_push_thread_default(ctx: Pointer)
        fun g_main_loop_new(ctx: Pointer, running: Int): Pointer
        fun g_main_loop_run(loop: Pointer)
        fun g_bus_get_sync(type: Int, cancellable: Pointer?, error: Pointer?): Pointer?
        fun g_dbus_node_info_new_for_xml(xml: String, error: Pointer?): Pointer?
        fun g_dbus_node_info_lookup_interface(info: Pointer, name: String): Pointer?
        fun g_dbus_connection_register_object(c: Pointer, path: String, iface: Pointer, vtable: Pointer, data: Pointer?, free: Pointer?, error: Pointer?): Int
        fun g_dbus_connection_call_sync(c: Pointer, bus: String, path: String, iface: String, method: String, params: Pointer?, replyType: Pointer?, flags: Int, timeout: Int, cancellable: Pointer?, error: Pointer?): Pointer?
        fun g_dbus_connection_emit_signal(c: Pointer, to: String?, path: String, iface: String, signal: String, params: Pointer?, error: Pointer?): Int
        fun g_dbus_method_invocation_return_value(inv: Pointer, value: Pointer?)
        fun g_dbus_method_invocation_return_dbus_error(inv: Pointer, name: String, message: String)
        fun g_variant_type_new(type: String): Pointer
        fun g_variant_new_string(s: String): Pointer
        fun g_variant_new_object_path(s: String): Pointer
        fun g_variant_new_int64(v: Long): Pointer
        fun g_variant_new_int32(v: Int): Pointer
        fun g_variant_new_uint32(v: Int): Pointer
        fun g_variant_new_double(v: Double): Pointer
        fun g_variant_new_boolean(v: Int): Pointer
        fun g_variant_new_strv(v: StringArray?, n: Long): Pointer
        fun g_variant_new_variant(v: Pointer): Pointer
        fun g_variant_new_dict_entry(k: Pointer, v: Pointer): Pointer
        fun g_variant_new_array(type: Pointer?, children: Array<Pointer>?, n: Long): Pointer
        fun g_variant_new_tuple(children: Array<Pointer>, n: Long): Pointer
        fun g_variant_get_child_value(v: Pointer, i: Long): Pointer
        fun g_variant_get_int64(v: Pointer): Long
        fun g_variant_get_uint32(v: Pointer): Int
        fun g_variant_get_double(v: Pointer): Double
        fun g_variant_get_boolean(v: Pointer): Int
        fun g_variant_get_string(v: Pointer, length: Pointer?): String
        fun g_variant_unref(v: Pointer)
    }

    /** GDBus's three calls into an object of ink's: a method, a property read, a property set. */
    private fun interface MethodCall : Callback {
        fun invoke(c: Pointer?, sender: String?, path: String?, iface: String?, method: String?, params: Pointer?, inv: Pointer, data: Pointer?)
    }
    private fun interface GetProperty : Callback {
        fun invoke(c: Pointer?, sender: String?, path: String?, iface: String?, name: String?, error: Pointer?, data: Pointer?): Pointer?
    }
    private fun interface SetProperty : Callback {
        fun invoke(c: Pointer?, sender: String?, path: String?, iface: String?, name: String?, value: Pointer?, error: Pointer?, data: Pointer?): Int
    }

    /** What ink says it is: MPRIS's two interfaces, read by GDBus. */
    private val XML = """
        <node>
          <interface name="$ROOT">
            <method name="Raise"/>
            <method name="Quit"/>
            <property name="CanQuit" type="b" access="read"/>
            <property name="CanRaise" type="b" access="read"/>
            <property name="HasTrackList" type="b" access="read"/>
            <property name="Identity" type="s" access="read"/>
            <property name="DesktopEntry" type="s" access="read"/>
            <property name="SupportedUriSchemes" type="as" access="read"/>
            <property name="SupportedMimeTypes" type="as" access="read"/>
          </interface>
          <interface name="$PLAYER">
            <method name="Next"/>
            <method name="Previous"/>
            <method name="Pause"/>
            <method name="PlayPause"/>
            <method name="Stop"/>
            <method name="Play"/>
            <method name="Seek"><arg direction="in" name="Offset" type="x"/></method>
            <method name="SetPosition"><arg direction="in" name="TrackId" type="o"/><arg direction="in" name="Position" type="x"/></method>
            <method name="OpenUri"><arg direction="in" name="Uri" type="s"/></method>
            <signal name="Seeked"><arg name="Position" type="x"/></signal>
            <property name="PlaybackStatus" type="s" access="read"/>
            <property name="LoopStatus" type="s" access="readwrite"/>
            <property name="Rate" type="d" access="readwrite"/>
            <property name="Shuffle" type="b" access="readwrite"/>
            <property name="Metadata" type="a{sv}" access="read"/>
            <property name="Volume" type="d" access="readwrite"/>
            <property name="Position" type="x" access="read"/>
            <property name="MinimumRate" type="d" access="read"/>
            <property name="MaximumRate" type="d" access="read"/>
            <property name="CanGoNext" type="b" access="read"/>
            <property name="CanGoPrevious" type="b" access="read"/>
            <property name="CanPlay" type="b" access="read"/>
            <property name="CanPause" type="b" access="read"/>
            <property name="CanSeek" type="b" access="read"/>
            <property name="CanControl" type="b" access="read"/>
          </interface>
        </node>
    """.trimIndent()

    private lateinit var g: Gio
    @Volatile private var bus: Pointer? = null
    private lateinit var entryType: Pointer
    private lateinit var stringType: Pointer
    private val one = Executors.newSingleThreadExecutor { Thread(it, "ink-mpris").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + one)
    /** What GLib holds on to: the callbacks and their table. */
    private val held = mutableListOf<Any>()
    /** The cover shown: its key (a record and its art), and the file it was written to (null: none). */
    @Volatile private var shownCover: String? = null
    @Volatile private var coverFile: File? = null

    /** Begun with the player: on the bus, then kept to what plays; [done] once the first song's said. */
    fun start(done: () -> Unit) {
        val ready = java.util.concurrent.CompletableFuture<Boolean>()
        Thread({
            val ok = runCatching { setUp() }.onFailure { Log.d("mpris: $it") }.getOrDefault(false)
            ready.complete(ok)
            if (!ok) return@Thread
            runCatching { loop?.let(g::g_main_loop_run) }.onFailure { Log.d("mpris: $it") }
        }, "ink-mpris-bus").apply { isDaemon = true }.start()
        scope.launch {
            if (!withContext(Dispatchers.IO) { ready.get() }) return@launch
            launch { Player.volume.collect { changed(PLAYER, "Volume") } }
            launch { Player.shuffle.collect { changed(PLAYER, "Shuffle") } }
            launch { Player.repeat.collect { changed(PLAYER, "LoopStatus") } }
            combine(Player.now, Player.onAir) { n, air -> n to air }
                .distinctUntilChanged { a, b -> a.first?.song?.id == b.first?.song?.id && a.first?.playing == b.first?.playing && a.first?.duration == b.first?.duration && a.second == b.second }
                .collect { (n, _) ->
                    runCatching { show(n) }.onFailure { Log.d("mpris: $it") }
                    if (n != null) done()
                }
        }
    }

    /** The app quitting: off the bus, so the desktop doesn't keep ink music as what plays. */
    fun stop() {
        val c = bus ?: return
        runCatching {
            changed(PLAYER, "PlaybackStatus")
            g.g_dbus_connection_call_sync(c, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ReleaseName",
                tuple(g.g_variant_new_string(NAME)), null, 0, 500, null, null)?.let(g::g_variant_unref)
        }
        bus = null
    }

    /** Where it is, said again after a seek (the desktop counts on by itself from the last time it was told). */
    fun moved() {
        val c = bus ?: return
        scope.launch { runCatching { g.g_dbus_connection_emit_signal(c, null, PATH, PLAYER, "Seeked", tuple(g.g_variant_new_int64(Player.position.value * 1000)), null) } }
    }

    private var loop: Pointer? = null

    /** On the session bus with ink's object and name; false: no bus, or another ink music has the name. */
    private fun setUp(): Boolean {
        // the covers a run before left behind (the one shown is let go at each change)
        Home.cache.listFiles { f -> f.name.startsWith("mpris-cover-") }?.forEach { it.delete() }
        g = Native.load("gio-2.0", Gio::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        entryType = g.g_variant_type_new("{sv}")
        stringType = g.g_variant_type_new("s")
        // GDBus calls ink's object back on the loop of the thread that registered it: this one's own
        val ctx = g.g_main_context_new()
        g.g_main_context_push_thread_default(ctx)
        val c = g.g_bus_get_sync(2, null, null) ?: return false.also { Log.d("mpris: no session bus") } // G_BUS_TYPE_SESSION
        val info = g.g_dbus_node_info_new_for_xml(XML, null) ?: return false
        val table = vtable()
        for (iface in listOf(ROOT, PLAYER)) {
            val i = g.g_dbus_node_info_lookup_interface(info, iface) ?: return false
            if (g.g_dbus_connection_register_object(c, PATH, i, table, null, null, null) == 0) return false.also { Log.d("mpris: $iface not registered") }
        }
        // the name last, so whoever sees it appear finds the object there; 4: DBUS_NAME_FLAG_DO_NOT_QUEUE
        val reply = g.g_dbus_connection_call_sync(c, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName",
            tuple(g.g_variant_new_string(NAME), g.g_variant_new_uint32(4)), null, 0, 2000, null, null) ?: return false
        val got = g.g_variant_get_child_value(reply, 0).let { v -> g.g_variant_get_uint32(v).also { g.g_variant_unref(v) } }
        g.g_variant_unref(reply)
        if (got != 1 && got != 4) return false.also { Log.d("mpris: $NAME taken ($got)") } // primary owner, or already
        loop = g.g_main_loop_new(ctx, 0)
        bus = c
        Log.d("mpris: on the bus as $NAME")
        return true
    }

    /** GDBus's table for ink's object: its three calls, then room GLib keeps for later ones. */
    private fun vtable(): Pointer {
        val call = MethodCall { _, _, _, _, method, params, inv, _ ->
            runCatching { answer(method ?: "", params, inv) }.onFailure {
                Log.d("mpris: $method, $it")
                g.g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.Failed", it.toString())
            }
        }
        val get = GetProperty { _, _, _, iface, name, _, _ -> runCatching { property(iface ?: "", name ?: "") }.onFailure { Log.d("mpris: $name, $it") }.getOrNull() }
        val set = SetProperty { _, _, _, _, name, value, _, _ -> if (value != null && runCatching { set(name ?: "", value) }.getOrDefault(false)) 1 else 0 }
        val table = Memory(11L * Native.POINTER_SIZE).apply { clear() }
        listOf(call, get, set).forEachIndexed { i, f -> table.setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(f)) }
        held.addAll(listOf(call, get, set, table))
        return table
    }

    /** A method asked for: what ink's own buttons do, on the window's thread (a speaker, a crossfade, the queue all heeded). */
    private fun answer(method: String, params: Pointer?, inv: Pointer) {
        val playing = Player.now.value?.playing == true
        fun ui(f: () -> Unit) = javax.swing.SwingUtilities.invokeLater { runCatching(f).onFailure { Log.d("mpris: $method, $it") } }
        fun long(i: Long) = g.g_variant_get_child_value(params!!, i).let { v -> g.g_variant_get_int64(v).also { g.g_variant_unref(v) } }
        fun text(i: Long) = g.g_variant_get_child_value(params!!, i).let { v -> g.g_variant_get_string(v, null).also { g.g_variant_unref(v) } }
        when (method) {
            "Raise" -> ui { raise() }
            "Quit" -> ui { quit() }
            "Play" -> ui { if (Player.now.value?.playing != true) Player.toggle() }
            "Pause", "Stop" -> ui { if (Player.now.value?.playing == true) Player.toggle() } // stop: paused, the queue kept
            "PlayPause" -> ui { Player.toggle() }
            "Next" -> ui { Player.next() }
            "Previous" -> ui { Player.previous() }
            "Seek" -> {
                val by = long(0) / 1000
                if (seekable()) ui {
                    val to = Player.position.value + by
                    val length = Player.now.value?.duration ?: 0
                    if (to >= length && length > 0) Player.next() else Player.seek(to.coerceAtLeast(0))
                }
            }
            "SetPosition" -> {
                val track = text(0)
                val to = long(1) / 1000
                val n = Player.now.value
                if (seekable() && n != null && track == trackId(n.song) && to in 0..n.duration) ui { Player.seek(to) }
            }
            "OpenUri" -> return g.g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.NotSupported", "ink music opens nothing by address")
            else -> return g.g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.UnknownMethod", method)
        }
        g.g_dbus_method_invocation_return_value(inv, null)
    }

    /** The window brought up: the mini player's put away, the main window back from the taskbar and in front. */
    private fun raise() {
        if (Mini.on.value) Mini.close()
        Win.state?.isMinimized = false
        Win.window?.let { it.isVisible = true; it.toFront(); it.requestFocus() }
    }

    private fun seekable() = Player.now.value.let { it != null && !it.song.station && it.duration > 0 }

    /** A property read: from what plays now (where it is, read as it's asked, never sent on its own). */
    private fun property(iface: String, name: String): Pointer? {
        val n = Player.now.value
        fun b(v: Boolean) = g.g_variant_new_boolean(if (v) 1 else 0)
        return when (name) {
            "CanQuit", "CanRaise" -> b(true)
            "HasTrackList" -> b(false)
            "Identity" -> g.g_variant_new_string("ink music")
            "DesktopEntry" -> g.g_variant_new_string(DESKTOP)
            "SupportedUriSchemes", "SupportedMimeTypes" -> g.g_variant_new_strv(null, 0)
            "PlaybackStatus" -> g.g_variant_new_string(status())
            "LoopStatus" -> g.g_variant_new_string(when (Player.repeat.value) { 1 -> "Playlist"; 2 -> "Track"; else -> "None" })
            "Rate", "MinimumRate", "MaximumRate" -> g.g_variant_new_double(1.0)
            "Shuffle" -> b(Player.shuffle.value)
            "Metadata" -> metadata(n)
            "Volume" -> g.g_variant_new_double(if (Player.muted.value) 0.0 else Player.volume.value / 100.0)
            "Position" -> g.g_variant_new_int64(if (n == null || n.song.station) 0 else Player.position.value * 1000)
            "CanGoNext", "CanGoPrevious", "CanPlay", "CanPause" -> b(n != null)
            "CanSeek" -> b(seekable())
            "CanControl" -> b(true)
            else -> null.also { Log.d("mpris: asked for $iface.$name") }
        }
    }

    /** A property set: the volume, shuffle and repeat as ink's own buttons set them; the rate stays 1. */
    private fun set(name: String, value: Pointer): Boolean {
        fun ui(f: () -> Unit) = javax.swing.SwingUtilities.invokeLater { runCatching(f) }
        when (name) {
            "Volume" -> { val v = g.g_variant_get_double(value); ui { Player.volume((v.coerceIn(0.0, 1.0) * 100).toInt()) } }
            "Shuffle" -> { val on = g.g_variant_get_boolean(value) != 0; ui { if (Player.shuffle.value != on) Player.shuffle() } }
            "LoopStatus" -> {
                val to = when (g.g_variant_get_string(value, null)) { "Playlist" -> 1; "Track" -> 2; else -> 0 }
                ui { repeat(2) { if (Player.repeat.value != to) Player.repeat() } }
            }
            "Rate" -> {}
            else -> return false
        }
        return true
    }

    private fun status() = Player.now.value.let { if (it == null) "Stopped" else if (it.playing) "Playing" else "Paused" }

    /** A song's id as D-Bus will have it: an object path (letters, digits and _ only). */
    private fun trackId(s: Song) = "/app/ink/music/track/" + s.id.map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }.joinToString("").ifEmpty { "none" }

    /** What plays as MPRIS says it: a station's what it says is on, the station as the record (the Mac's card's way). */
    private fun metadata(n: Player.Now?): Pointer {
        if (n == null) return array(entryType, emptyList())
        val s = n.song
        val air = Player.onAir.value
        val (title, artist) = if (s.station && air != null) air.title to (air.artist ?: s.title) else s.title to s.artist
        val entries = mutableListOf<Pointer>()
        fun put(key: String, v: Pointer) { entries += g.g_variant_new_dict_entry(g.g_variant_new_string(key), g.g_variant_new_variant(v)) }
        fun list(vararg v: String) = g.g_variant_new_strv(StringArray(arrayOf(*v)), v.size.toLong())
        put("mpris:trackid", g.g_variant_new_object_path(trackId(s)))
        put("xesam:title", g.g_variant_new_string(title))
        if (artist.isNotBlank()) put("xesam:artist", list(artist))
        val album = if (s.station) s.title else s.album
        if (album.isNotBlank()) put("xesam:album", g.g_variant_new_string(album))
        if (!s.station && s.albumArtist.isNotBlank()) put("xesam:albumArtist", list(s.albumArtist))
        if (!s.station && s.track > 0) put("xesam:trackNumber", g.g_variant_new_int32(s.track))
        if (!s.station && n.duration > 0) put("mpris:length", g.g_variant_new_int64(n.duration * 1000))
        coverFile?.takeIf { shownCover == keyOf(s) }?.let { put("mpris:artUrl", g.g_variant_new_string(it.toPath().toUri().toASCIIString())) }
        return array(entryType, entries)
    }

    private fun keyOf(s: Song) = s.albumId + (s.art ?: "")

    /** The desktop told what plays: the cover written out first, when it's another, then what's changed. */
    private suspend fun show(n: Player.Now?) {
        if (n != null && keyOf(n.song) != shownCover) cover(n.song)
        changed(PLAYER, "PlaybackStatus", "Metadata", "CanSeek", "CanGoNext", "CanGoPrevious", "CanPlay", "CanPause")
    }

    /**
     * The cover as a JPEG among ink's cached things, handed over as a file:// (a server's cover needs ink's sign-in and a
     * file's is inside it). A new name each time, as desktops keep a picture by its address; the one before deleted.
     */
    private suspend fun cover(s: Song) {
        val bytes = withContext(Dispatchers.IO) {
            Covers.of(s, 600)?.image?.let { org.jetbrains.skia.Image.makeFromBitmap(it.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 90)?.bytes }
        }
        val file = bytes?.let { File(Home.cache, "mpris-cover-${System.nanoTime()}.jpg").apply { withContext(Dispatchers.IO) { writeBytes(it) } } }
        val old = coverFile
        coverFile = file
        shownCover = keyOf(s)
        old?.let { scope.launch { kotlinx.coroutines.delay(5_000); it.delete() } } // the desktop may still be reading it
    }

    /** PropertiesChanged, with each property's value now. */
    private fun changed(iface: String, vararg names: String) {
        val c = bus ?: return
        val entries = names.mapNotNull { name -> property(iface, name)?.let { g.g_variant_new_dict_entry(g.g_variant_new_string(name), g.g_variant_new_variant(it)) } }
        val params = tuple(g.g_variant_new_string(iface), array(entryType, entries), array(stringType, emptyList()))
        g.g_dbus_connection_emit_signal(c, null, PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged", params, null)
    }

    /** An array of [type]'s (none: an empty one, which needs its type said). */
    private fun array(type: Pointer, v: List<Pointer>) = g.g_variant_new_array(type, v.takeIf { it.isNotEmpty() }?.toTypedArray(), v.size.toLong())

    private fun tuple(vararg v: Pointer) = g.g_variant_new_tuple(arrayOf(*v), v.size.toLong())
}
