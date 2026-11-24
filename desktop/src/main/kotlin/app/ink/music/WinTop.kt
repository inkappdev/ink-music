package app.ink.music

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Windows: the mini player kept over every other window. Java asks for "topmost" once, as the window's made, and Windows
 * can let that go: a tester (2026-11-24) found the mini player stopped showing over Firefox or Photoshop once they were
 * up or maximised. So it's asked again, without taking the focus, whenever the mini player loses it and every few
 * seconds while it's up. Anything failing, nothing happens: the window is as Java left it.
 */
internal object WinTop {
    private interface User32 : Library {
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    }

    private val user by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }
    // HWND_TOPMOST is (HWND)-1
    private val TOPMOST = Pointer(-1L)
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_NOOWNERZORDER = 0x0200

    /** The window put back above every other, where it is, at its size, the focus left where it was. */
    fun keep(window: java.awt.Window) {
        if (!Home.windows || !window.isShowing) return
        runCatching {
            val hwnd = Native.getWindowPointer(window) ?: return
            user?.SetWindowPos(hwnd, TOPMOST, 0, 0, 0, 0, SWP_NOSIZE or SWP_NOMOVE or SWP_NOACTIVATE or SWP_NOOWNERZORDER)
        }.onFailure { Log.d("topmost: $it") }
    }
}
