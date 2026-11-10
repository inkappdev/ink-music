package app.ink.music

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * What stopped ink music, written down where it can be sent on: crash.log among its own files (%APPDATA%\ink music on
 * Windows). Till now an error while the window drew left only Compose's own small "Error" box with a word or two in it
 * ("Check failed.", a PC, 2026-11-09) and nothing kept, so what had gone wrong had to be worked back from those two
 * words. The box still shows, as it did; the whole of it is kept first: which ink music, which system, the screens and
 * their scales, and where in the code.
 */
@OptIn(ExperimentalComposeUiApi::class)
object Crash : WindowExceptionHandlerFactory {
    val file = File(Home.dir, "crash.log")

    /** Before the first window: whatever no one catches, on any thread, kept too (then said as Java says it). */
    fun start() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            keep(e)
            before?.uncaughtException(t, e) ?: e.printStackTrace()
        }
    }

    override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler {
        val shown = DefaultWindowExceptionHandlerFactory.exceptionHandler(window)
        return WindowExceptionHandler { e -> keep(e); shown.onException(e) }
    }

    private var last: Throwable? = null

    /** The newest only (one error is often thrown on through several hands: written once). */
    @Synchronized
    fun keep(e: Throwable) {
        if (e === last) return
        last = e
        runCatching {
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
            file.writeText(
                "ink music ${System.getProperty("jpackage.app-version") ?: "from the source"}, ${java.time.OffsetDateTime.now()}\n" +
                    "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}), " +
                    "Java ${System.getProperty("java.version")}, ${java.util.Locale.getDefault()}\n" +
                    "screens: ${screens()}\nsize: ${Size.chosen.value ?: "fit"}\n\n$trace",
            )
        }
    }

    /** Each screen's size in its own points and its scale: "1080x1920 at 0,0 x1.5". */
    private fun screens() = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.joinToString("; ") { d ->
            val c = d.defaultConfiguration
            "${c.bounds.width}x${c.bounds.height} at ${c.bounds.x},${c.bounds.y} x${c.defaultTransform.scaleX}"
        }
    }.getOrDefault("?")
}
