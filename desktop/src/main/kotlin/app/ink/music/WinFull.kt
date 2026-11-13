package app.ink.music

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Windows' full screen as a browser makes it: the window's frame taken off (no caption, no sizing border, so Windows'
 * minimise, maximise and close go too), laid over the whole monitor it's on, and put back exactly as it was after,
 * zoomed again if it was. Not Java's full screen, which is exclusive on Windows and minimized the moment another window
 * took the focus; and not the window's state sized to the screen (2.1.17's first try, 2026-11-12), which a zoomed window
 * undid as Windows put it back to its own size, and which kept the frame and its buttons: Bronson, "doesn't actually
 * make the player go fullscreen". Everything by user32 straight to the window, so AWT and Compose only hear of it.
 */
internal object WinFull {
    private interface User32 : Library {
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetWindowRect(hwnd: Pointer, rect: Rect): Boolean
        fun IsZoomed(hwnd: Pointer): Boolean
        fun SendMessageW(hwnd: Pointer, msg: Int, w: Long, l: Long): Long
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfo): Boolean
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class Rect : Structure() {
        @JvmField var left = 0; @JvmField var top = 0; @JvmField var right = 0; @JvmField var bottom = 0
    }

    /** MONITORINFO, its two RECTs laid flat: the monitor's whole rectangle, then its work area. */
    @Structure.FieldOrder("cbSize", "ml", "mt", "mr", "mb", "wl", "wt", "wr", "wb", "flags")
    class MonitorInfo : Structure() {
        @JvmField var cbSize = 40
        @JvmField var ml = 0; @JvmField var mt = 0; @JvmField var mr = 0; @JvmField var mb = 0
        @JvmField var wl = 0; @JvmField var wt = 0; @JvmField var wr = 0; @JvmField var wb = 0
        @JvmField var flags = 0
    }

    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20
    private const val WS_CAPTION = 0x00C00000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val WS_EX_FRAMES = 0x00000001L or 0x00000100L or 0x00000200L or 0x00020000L // dialog, window, client, static edges
    private const val WM_SYSCOMMAND = 0x0112
    private const val SC_RESTORE = 0xF120L
    private const val SC_MAXIMIZE = 0xF030L
    private const val MONITOR_DEFAULTTONEAREST = 2
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    private val user by lazy { Native.load("user32", User32::class.java) }

    /** What the window was before, to put back. */
    private class Was(val style: Long, val ex: Long, val rect: Rect, val zoomed: Boolean)
    private var was: Was? = null

    /** Over the whole monitor; false if it couldn't be (the caller does what it did before). */
    fun enter(window: java.awt.Window): Boolean = runCatching {
        if (was != null) return true
        val hwnd = Native.getWindowPointer(window) ?: return false
        val zoomed = user.IsZoomed(hwnd)
        // a zoomed window first put back to its own size, so that's the size it comes back to
        if (zoomed) user.SendMessageW(hwnd, WM_SYSCOMMAND, SC_RESTORE, 0)
        val style = user.GetWindowLongPtrW(hwnd, GWL_STYLE)
        val ex = user.GetWindowLongPtrW(hwnd, GWL_EXSTYLE)
        val rect = Rect().also { user.GetWindowRect(hwnd, it) }
        val mon = user.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: return false
        val m = MonitorInfo()
        if (!user.GetMonitorInfoW(mon, m)) return false
        was = Was(style, ex, rect, zoomed)
        user.SetWindowLongPtrW(hwnd, GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME).inv())
        user.SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex and WS_EX_FRAMES.inv())
        user.SetWindowPos(hwnd, null, m.ml, m.mt, m.mr - m.ml, m.mb - m.mt, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
        true
    }.onFailure { Log.d("full screen: $it") }.getOrDefault(false)

    /** Back as it was: its frame, its place and size, zoomed if it had been. */
    fun leave(window: java.awt.Window): Boolean = runCatching {
        val w = was ?: return false
        was = null
        val hwnd = Native.getWindowPointer(window) ?: return false
        user.SetWindowLongPtrW(hwnd, GWL_STYLE, w.style)
        user.SetWindowLongPtrW(hwnd, GWL_EXSTYLE, w.ex)
        val r = w.rect
        user.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
        if (w.zoomed) user.SendMessageW(hwnd, WM_SYSCOMMAND, SC_MAXIMIZE, 0)
        true
    }.onFailure { Log.d("full screen: $it") }.getOrDefault(false)
}
