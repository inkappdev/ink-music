package app.ink.music

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * How much moves (settings, "MOTION"): full, or less (no cascades, no words carried between pages, covers not
 * developing, nothing softened behind the bars: what a slower phone strains at), or as the phone says (the
 * default): less when Android's own animations are off, or the phone is a small one (little memory).
 */
object Moves {
    const val AUTO = "as the phone says"; const val FULL = "full"; const val LESS = "less"

    private val _less = MutableStateFlow(false)
    /** Whether ink music moves less, now. */
    val less: StateFlow<Boolean> = _less
    private val _chosen = MutableStateFlow(AUTO)
    val chosen: StateFlow<String> = _chosen

    private fun file(c: Context) = File(c.filesDir, "motion")

    fun start(c: Context) {
        _chosen.value = runCatching { file(c).readText().trim() }.getOrNull()?.takeIf { it == FULL || it == LESS } ?: AUTO
        _less.value = decide(c)
    }

    fun set(c: Context, choice: String) {
        if (choice == AUTO) file(c).delete() else runCatching { file(c).writeText(choice) }
        _chosen.value = choice
        _less.value = decide(c)
    }

    /** What the phone says: its animations off, or a phone with little memory (Android's own word for it, or under 3 GB). */
    fun phoneSaysLess(c: Context): Boolean {
        val off = runCatching { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
        val am = c.getSystemService(ActivityManager::class.java)
        val small = am?.isLowRamDevice == true || ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }.totalMem in 1 until 3L * 1024 * 1024 * 1024
        return off || small
    }

    private fun decide(c: Context) = when (_chosen.value) { FULL -> false; LESS -> true; else -> phoneSaysLess(c) }
}
