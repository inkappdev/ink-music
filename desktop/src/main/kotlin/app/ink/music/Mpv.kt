package app.ink.music

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * libmpv's client API, the few calls the player needs, through JNA. mpv plays anything (FLAC,
 * AAC, Opus, a stream), gaplessly, with ReplayGain; on a Mac it also answers the media keys and
 * fills Now Playing (`media-controls`). The app carries its own libmpv (native/macos-arm64, put
 * there by the GitHub Action); run from the source, it's the system's.
 */
internal interface MpvApi : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(h: Pointer): Int
    fun mpv_set_option_string(h: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(h: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(h: Pointer, name: String): Pointer?
    fun mpv_free(p: Pointer)
    fun mpv_command(h: Pointer, args: Array<String?>): Int
    fun mpv_command_async(h: Pointer, userdata: Long, args: Array<String?>): Int
    fun mpv_observe_property(h: Pointer, userdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(h: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(h: Pointer)
    fun mpv_hook_add(h: Pointer, userdata: Long, name: String, priority: Int): Int
    fun mpv_hook_continue(h: Pointer, id: Long): Int
    fun mpv_terminate_destroy(h: Pointer)
    fun mpv_error_string(error: Int): String
}

internal interface Libc : Library {
    fun setlocale(category: Int, locale: String): String?
}

class Mpv(options: Map<String, String>) {
    private val api: MpvApi = load()

    init {
        // mpv won't start under a locale that writes 1,5 for 1.5 (the JVM takes the system's): numbers the C way
        if (!System.getProperty("os.name").startsWith("Windows")) runCatching {
            Native.load("c", Libc::class.java).setlocale(if (Home.mac) 4 else 1, "C")   // LC_NUMERIC: 4 on a Mac, 1 on Linux
        }
    }

    private val h: Pointer = api.mpv_create() ?: error("libmpv wouldn't start")

    init {
        // what this mpv can't take (an older one, another OS's option) it says no to; the rest stands
        options.forEach { (k, v) -> api.mpv_set_option_string(h, k, v) }
        check(api.mpv_initialize(h) >= 0) { "libmpv wouldn't initialize" }
    }

    /** A command, as mpv's own words ("loadfile", path, "append"); its result (negative: an error). */
    fun command(vararg args: String): Int = api.mpv_command(h, arrayOf(*args, null))

    /** The same, not waited for (a file to load: mpv opens it on its own thread). */
    fun commandAsync(vararg args: String) { api.mpv_command_async(h, 0, arrayOf(*args, null)) }

    fun set(name: String, value: String) = api.mpv_set_property_string(h, name, value)

    fun get(name: String): String? = api.mpv_get_property_string(h, name)?.let { p ->
        try { p.getString(0, "UTF-8") } finally { api.mpv_free(p) }
    }

    fun observe(name: String, format: Int) = api.mpv_observe_property(h, 0, name, format)

    /** Asked before each file opens ("on_load"): [Hook] comes, and the file waits till [proceed]. */
    fun hook(name: String) = api.mpv_hook_add(h, 0, name, 0)
    fun proceed(id: Long) = api.mpv_hook_continue(h, id)

    sealed interface Event
    data class Changed(val name: String, val value: Any?) : Event
    data class Ended(val reason: Int) : Event
    data object Loaded : Event
    data class Hook(val name: String, val id: Long) : Event
    data object Shutdown : Event

    /**
     * The next thing that happened (waits for it; null: nothing within `timeout` s). mpv_event is
     * { int event_id; int error; uint64 reply_userdata; void* data }, mpv_event_property
     * { const char* name; mpv_format format; void* data } (64-bit: 0, 4, 8, 16; and 0, 8, 16).
     */
    fun next(timeout: Double = -1.0): Event? {
        val e = api.mpv_wait_event(h, timeout)
        return when (e.getInt(0)) {
            SHUTDOWN -> Shutdown
            FILE_LOADED -> Loaded
            END_FILE -> Ended(e.getPointer(16)?.getInt(0) ?: 0)
            // mpv_event_hook { const char* name; uint64_t id }
            HOOK -> e.getPointer(16)?.let { p -> Hook(p.getPointer(0).getString(0, "UTF-8"), p.getLong(8)) }
            PROPERTY_CHANGE -> {
                val p = e.getPointer(16) ?: return null
                val name = p.getPointer(0).getString(0, "UTF-8")
                val data = p.getPointer(16)
                val value: Any? = if (data == null) null else when (p.getInt(8)) {
                    FLAG -> data.getInt(0) != 0
                    INT64 -> data.getLong(0)
                    DOUBLE -> data.getDouble(0)
                    STRING -> data.getPointer(0)?.getString(0, "UTF-8")
                    else -> null
                }
                Changed(name, value)
            }
            else -> null
        }
    }

    fun close() = api.mpv_terminate_destroy(h)

    companion object {
        const val STRING = 1; const val FLAG = 3; const val INT64 = 4; const val DOUBLE = 5
        private const val SHUTDOWN = 1; private const val END_FILE = 7; private const val FILE_LOADED = 8
        private const val PROPERTY_CHANGE = 22; private const val HOOK = 25
        /** Why a file ended (mpv_end_file_reason): played to its end, or stopped, or it couldn't play. */
        const val EOF = 0; const val STOP = 2; const val ERROR = 4

        private fun load(): MpvApi {
            // the app's own (its dependencies beside it, found through @loader_path), else the system's
            val bundled = System.getProperty("compose.application.resources.dir")?.let { dir ->
                listOf("libmpv.2.dylib", "libmpv.dylib", "libmpv.so.2", "libmpv-2.dll", "mpv-2.dll").map { File(dir, it) }.firstOrNull { it.exists() }
            }
            val names = listOfNotNull(bundled?.path, "mpv", "libmpv.so.2", "/opt/homebrew/lib/libmpv.dylib", "/usr/local/lib/libmpv.dylib", "libmpv-2", "mpv-2")
            var last: Throwable? = null
            for (n in names) try { return Native.load(n, MpvApi::class.java) } catch (e: UnsatisfiedLinkError) { last = e }
            throw IllegalStateException("libmpv isn't here", last)
        }
    }
}
