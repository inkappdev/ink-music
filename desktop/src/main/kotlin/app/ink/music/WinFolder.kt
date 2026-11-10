package app.ink.music

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * Windows' own folder picker (the Common Item Dialog, folders only), in place of Swing's look-alike: that one drew its
 * names in pale grey on a white list, hard to read (2026-11-10). This is the one every Windows app has: Quick access,
 * the network, search, light or dark as Windows is. By hand through JNA, as [WinNowPlaying] does WinRT's.
 */
internal object WinFolder {
    private interface Ole32 : Library {
        fun CoInitializeEx(reserved: Pointer?, model: Int): Int
        fun CoUninitialize()
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, out: PointerByReference): Int
        fun CoTaskMemFree(p: Pointer?)
    }

    private interface Shell32 : Library {
        fun SHCreateItemFromParsingName(path: WString, bindContext: Pointer?, iid: Pointer, out: PointerByReference): Int
    }

    // the classes and interfaces by name, and each method by its place in its interface, read from the Windows SDK's
    // ShObjIdl_core.h (IUnknown 0 to 2; IModalWindow's Show 3; then IFileDialog's own). A wrong place calls the wrong
    // method and the app goes down, so check there before changing one
    private const val CLSID_FILE_OPEN_DIALOG = "dc1c5a9c-e88a-4dde-a5a1-60f82a20aef7"
    private const val IID_FILE_OPEN_DIALOG = "d57c7288-d4ad-4768-be02-9d969532d960"
    private const val IID_SHELL_ITEM = "43826d1e-e718-42ee-bc55-a1e261c37bfe"
    private const val SHOW = 3
    private const val SET_OPTIONS = 9
    private const val GET_OPTIONS = 10
    private const val SET_FOLDER = 12
    private const val SET_TITLE = 17
    private const val GET_RESULT = 20
    /** IShellItem's GetDisplayName. */
    private const val GET_DISPLAY_NAME = 5

    private const val PICK_FOLDERS = 0x20
    private const val FORCE_FILESYSTEM = 0x40
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()
    private const val INPROC_SERVER = 1
    /** The single-threaded apartment, which the dialog needs: a thread of its own, so the UI thread's left alone. */
    private const val APARTMENT_THREADED = 2
    /** HRESULT_FROM_WIN32(ERROR_CANCELLED): the picker closed without a folder. */
    private const val CANCELLED = 0x800704C7.toInt()

    private val ole by lazy { Native.load("ole32", Ole32::class.java) }
    private val shell by lazy { Native.load("shell32", Shell32::class.java) }

    /**
     * The picker over ink music's window, opened from `from`. It runs on a thread of its own (its own message loop, so
     * the window keeps drawing behind it); [picked] comes on the UI thread with the folder, not at all if it's
     * cancelled. Should Windows' fail, [otherwise] (Swing's) is asked instead.
     */
    fun choose(title: String, from: File?, otherwise: () -> File?, picked: (File) -> Unit) {
        val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: java.awt.Frame.getFrames().firstOrNull { it.isDisplayable && it.title == "ink music" }
        val hwnd = owner?.let { runCatching { Native.getWindowPointer(it) }.getOrNull() }
        Thread({
            runCatching { show(title, from, hwnd) }
                .onSuccess { f -> if (f != null) javax.swing.SwingUtilities.invokeLater { picked(f) } }
                .onFailure { e ->
                    Log.d("folder picker: $e")
                    javax.swing.SwingUtilities.invokeLater { otherwise()?.let(picked) }
                }
        }, "ink-folder-picker").apply { isDaemon = true }.start()
    }

    private fun show(title: String, from: File?, owner: Pointer?): File? {
        val began = ole.CoInitializeEx(null, APARTMENT_THREADED)
        try {
            val out = PointerByReference()
            check(ole.CoCreateInstance(guid(CLSID_FILE_OPEN_DIALOG), null, INPROC_SERVER, guid(IID_FILE_OPEN_DIALOG), out), "FileOpenDialog")
            val dialog = out.value
            try {
                val options = IntByReference()
                check(call(dialog, GET_OPTIONS, options), "GetOptions")
                check(call(dialog, SET_OPTIONS, options.value or PICK_FOLDERS or FORCE_FILESYSTEM), "SetOptions")
                call(dialog, SET_TITLE, WString(title))
                if (from != null && from.isDirectory) {
                    val start = PointerByReference()
                    if (shell.SHCreateItemFromParsingName(WString(from.path), null, guid(IID_SHELL_ITEM), start) >= 0) {
                        call(dialog, SET_FOLDER, start.value)
                        release(start.value)
                    }
                }
                val shown = call(dialog, SHOW, owner)
                if (shown == CANCELLED) return null
                check(shown, "Show")
                val item = PointerByReference()
                check(call(dialog, GET_RESULT, item), "GetResult")
                try {
                    val path = PointerByReference()
                    check(call(item.value, GET_DISPLAY_NAME, SIGDN_FILESYSPATH, path), "GetDisplayName")
                    try { return File(path.value.getWideString(0)) } finally { ole.CoTaskMemFree(path.value) }
                } finally { release(item.value) }
            } finally { release(dialog) }
        } finally { if (began >= 0) ole.CoUninitialize() }
    }

    /** A method of a COM object by its place in the object's table. */
    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int =
        Function.getFunction(obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)).invokeInt(arrayOf(obj, *args))

    private fun release(obj: Pointer?) { if (obj != null) call(obj, 2) }

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
}
