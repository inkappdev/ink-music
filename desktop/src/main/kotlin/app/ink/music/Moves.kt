package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * How much moves (settings, the look, MOTION; the phone's, here since 2026-11-27): full, or less (no cascades, no words
 * fading in, pictures not developing, FREQUENCY's dots still), or auto (the default): less where the computer's own
 * setting says to reduce motion (the Mac's, Windows' animations, KDE's or GNOME's), else full. Kept in "motion".
 */
object Moves {
    const val AUTO = "auto"; const val FULL = "full"; const val LESS = "less"

    private val file = File(Home.dir, "motion")
    private val _chosen = MutableStateFlow(runCatching { file.readText().trim() }.getOrNull()?.takeIf { it == FULL || it == LESS } ?: AUTO)
    val chosen: StateFlow<String> = _chosen
    /** What the computer says, read once a run. */
    val systemSaysLess: Boolean by lazy { runCatching { system() }.getOrDefault(false) }
    private val _less = MutableStateFlow(decide())
    /** Whether ink music moves less, now. */
    val less: StateFlow<Boolean> = _less

    fun set(choice: String) {
        runCatching { if (choice == AUTO) file.delete() else file.writeText(choice) }
        _chosen.value = choice
        _less.value = decide()
    }

    private fun decide() = when (_chosen.value) { FULL -> false; LESS -> true; else -> systemSaysLess }

    private fun run(vararg cmd: String) = ProcessBuilder(*cmd).redirectErrorStream(true).start().inputStream.bufferedReader().use { it.readText() }.trim()

    private interface User32 : com.sun.jna.Library { fun SystemParametersInfoW(action: Int, param: Int, out: com.sun.jna.ptr.IntByReference, winIni: Int): Boolean }

    private fun system(): Boolean = when {
        Home.mac -> run("defaults", "read", "com.apple.universalaccess", "reduceMotion") == "1"
        // SPI_GETCLIENTAREAANIMATION: Windows' "show animations" off
        Home.windows -> com.sun.jna.ptr.IntByReference().let { r ->
            com.sun.jna.Native.load("user32", User32::class.java).SystemParametersInfoW(0x1042, 0, r, 0) && r.value == 0
        }
        else -> {
            // KDE: animation speed at its slowest-to-none (0); GNOME: animations off
            val kde = File(System.getProperty("user.home"), ".config/kdeglobals").takeIf { it.exists() }?.readLines()
                ?.firstOrNull { it.startsWith("AnimationDurationFactor=") }?.substringAfter('=')?.toDoubleOrNull()
            kde == 0.0 || runCatching { run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations") == "false" }.getOrDefault(false)
        }
    }
}
