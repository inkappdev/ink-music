package app.ink.music

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * Windows: back, play or pause, and next under ink music's preview in the taskbar (2026-11-12, a tester: "hover over the
 * application's icon in the Windows taskbar ... Play/Stop, Next, and Previous controls"). ITaskbarList3's thumbnail
 * buttons, by hand through JNA as [WinFolder] does the folder picker; their clicks come to the window as WM_COMMAND, read
 * by a window procedure put in front of AWT's (every other message handed on to it). Anything failing, no buttons: the
 * app goes on as before.
 */
internal object WinThumbBar {
    private interface Ole32 : Library {
        fun CoInitializeEx(reserved: Pointer?, model: Int): Int
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, out: PointerByReference): Int
    }

    private interface User32 : Library {
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, proc: Callback): Pointer?
        fun CallWindowProcW(prev: Pointer, hwnd: Pointer, msg: Int, w: Long, l: Long): Long
        fun LoadImageW(instance: Pointer?, name: WString, type: Int, cx: Int, cy: Int, load: Int): Pointer?
    }

    /** The window procedure's shape (WNDPROC). */
    private interface WndProc : Callback { fun callback(hwnd: Pointer, msg: Int, w: Long, l: Long): Long }

    // from the Windows SDK's ShObjIdl_core.h: ITaskbarList3, after IUnknown (0 to 2), ITaskbarList (HrInit 3, AddTab 4,
    // DeleteTab 5, ActivateTab 6, SetActiveAlt 7) and ITaskbarList2 (MarkFullscreenWindow 8): SetProgressValue 9,
    // SetProgressState 10, RegisterTab 11, UnregisterTab 12, SetTabOrder 13, SetTabActive 14, ThumbBarAddButtons 15,
    // ThumbBarUpdateButtons 16. A wrong place calls the wrong method: check there before changing one
    private const val CLSID_TASKBAR_LIST = "56fdf344-fd6d-11d0-958a-006097c9a090"
    private const val IID_TASKBAR_LIST3 = "ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf"
    private const val HR_INIT = 3
    private const val ADD_BUTTONS = 15
    private const val UPDATE_BUTTONS = 16

    // THUMBBUTTON on 64-bit: dwMask 0, iId 4, iBitmap 8, (4 to align) hIcon 16, szTip[260] 24, dwFlags 544; 552 in all
    private const val BUTTON_SIZE = 552L
    private const val THB_ICON = 0x2
    private const val THB_TOOLTIP = 0x4
    private const val THB_FLAGS = 0x8
    private const val WM_COMMAND = 0x0111
    private const val THBN_CLICKED = 0x1800
    private const val GWLP_WNDPROC = -4
    private const val IMAGE_ICON = 1
    private const val LR_LOADFROMFILE = 0x10

    private const val BACK_ID = 1
    private const val PLAY_ID = 2
    private const val NEXT_ID = 3

    private val ole by lazy { Native.load("ole32", Ole32::class.java) }
    private val user by lazy { Native.load("user32", User32::class.java) }
    // one thread for the taskbar object (COM's single-threaded apartment): made, given its buttons and told of play/pause there
    private val com = Executors.newSingleThreadExecutor { r -> Thread(r, "ink-thumbbar").apply { isDaemon = true } }
    @Volatile private var bar: Pointer? = null
    @Volatile private var prev: Pointer? = null
    // kept, or the collector takes the procedure Windows is calling
    private var proc: WndProc? = null
    private var icons = emptyMap<String, Pointer>()

    /** The buttons put on the window's taskbar preview, a moment after it's shown (its taskbar button made by then). */
    fun start(window: java.awt.Window) {
        if (!Home.windows) return
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
        com.execute {
            runCatching {
                Thread.sleep(1_500)
                ole.CoInitializeEx(null, 2)
                val out = PointerByReference()
                check(ole.CoCreateInstance(guid(CLSID_TASKBAR_LIST), null, 1, guid(IID_TASKBAR_LIST3), out), "TaskbarList")
                val b = out.value
                check(call(b, HR_INIT), "HrInit")
                icons = mapOf("back" to icon("back", BACK), "play" to icon("play", PLAY), "pause" to icon("pause", PAUSE), "next" to icon("next", NEXT))
                // the clicks: a window procedure in front of AWT's
                val p = object : WndProc {
                    override fun callback(hwnd: Pointer, msg: Int, w: Long, l: Long): Long {
                        if (msg == WM_COMMAND && ((w shr 16) and 0xFFFF).toInt() == THBN_CLICKED) {
                            when ((w and 0xFFFF).toInt()) {
                                BACK_ID -> javax.swing.SwingUtilities.invokeLater { Player.previous() }
                                PLAY_ID -> javax.swing.SwingUtilities.invokeLater { Player.toggle() }
                                NEXT_ID -> javax.swing.SwingUtilities.invokeLater { Player.next() }
                            }
                            return 0
                        }
                        return user.CallWindowProcW(prev ?: return 0, hwnd, msg, w, l)
                    }
                }
                proc = p
                prev = user.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, p)
                check(call(b, ADD_BUTTONS, hwnd, 3, buttons(Player.now.value?.playing == true)), "ThumbBarAddButtons")
                bar = b
                // play and pause as it changes
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    Player.now.map { it?.playing == true }.distinctUntilChanged().collect { on ->
                        com.execute { runCatching { call(b, UPDATE_BUTTONS, hwnd, 3, buttons(on)) } }
                    }
                }
            }.onFailure { Log.d("thumb bar: $it") }
        }
    }

    /** The three buttons, play or pause as it is. */
    private fun buttons(playing: Boolean): Memory {
        val m = Memory(BUTTON_SIZE * 3).apply { clear() }
        listOf(Triple(BACK_ID, "back", "Previous"), Triple(PLAY_ID, if (playing) "pause" else "play", if (playing) "Pause" else "Play"),
            Triple(NEXT_ID, "next", "Next")).forEachIndexed { i, (id, ic, tip) ->
            val at = i * BUTTON_SIZE
            m.setInt(at, THB_ICON or THB_TOOLTIP or THB_FLAGS)
            m.setInt(at + 4, id)
            m.setPointer(at + 16, icons[ic])
            m.setWideString(at + 24, tip)
            m.setInt(at + 544, 0)
        }
        return m
    }

    /** A control's pixels as a 16 px icon, ink on clear (a PNG inside an .ico, which Windows reads from Vista on). */
    private fun icon(name: String, rows: List<String>): Pointer {
        val size = 16
        val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val cell = minOf((size - 4) / rows.size, (size - 4) / rows.maxOf { it.length }).coerceAtLeast(1)
        val ox = (size - cell * rows.maxOf { it.length }) / 2; val oy = (size - cell * rows.size) / 2
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, c ->
            if (c == '#') for (dy in 0 until cell) for (dx in 0 until cell) img.setRGB(ox + x * cell + dx, oy + y * cell + dy, 0xFFF1F0EC.toInt())
        } }
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        val ico = java.nio.ByteBuffer.allocate(6 + 16 + png.size).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(1); putShort(1)
            put(size.toByte()); put(size.toByte()); put(0); put(0); putShort(1); putShort(32); putInt(png.size); putInt(22)
            put(png)
        }.array()
        val f = File(Home.cache, "thumbbar-$name.ico").apply { parentFile.mkdirs(); writeBytes(ico) }
        return user.LoadImageW(null, WString(f.path), IMAGE_ICON, size, size, LR_LOADFROMFILE) ?: error("icon $name")
    }

    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int =
        Function.getFunction(obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)).invokeInt(arrayOf(obj, *args))

    private fun check(hr: Int, what: String) { if (hr < 0) error("$what: 0x%08x".format(hr)) }

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
