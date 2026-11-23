package app.ink.music

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * ink music's icon, one of three (2026-11-23): the words, an ink drop in pixels, the drop in the dither. Each is an
 * alias of the screens in the manifest with its own icon; the one chosen is turned on and the others off (the home
 * screen's icon changes with it, as a launcher allows: some take a moment, some put it back where it was).
 */
object AppIcon {
    val CHOICES = listOf("words" to ".Main", "drop" to ".MainDrop", "dithered drop" to ".MainDither")

    private fun component(c: Context, alias: String) = ComponentName(c.packageName, "app.ink.music" + alias)

    /** The one on now (the words if somehow none is). */
    fun now(c: Context): Int = CHOICES.indexOfFirst { (_, alias) ->
        when (c.packageManager.getComponentEnabledSetting(component(c, alias))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias == ".Main"
            else -> false
        }
    }.coerceAtLeast(0)

    /** The chosen one on, then the rest off (on first, so there's never a moment with none). */
    fun choose(c: Context, i: Int) {
        val pm = c.packageManager
        pm.setComponentEnabledSetting(component(c, CHOICES[i].second), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        CHOICES.forEachIndexed { j, (_, alias) ->
            if (j != i) pm.setComponentEnabledSetting(component(c, alias), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
