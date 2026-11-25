package app.ink.music

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Just your music (settings, your music; 2026-11-24, issue 8: "I want to use only my navidrome server"): the phone's
 * files and your server, your lists, loves, history and scrobbles kept; YouTube Music, INK's picks and the editors'
 * lists, new releases from outside your library, TUNER, radio's and podcasts' suggestions never started, and YouTube's
 * hosts never asked ([HeldBack]). Off till chosen. A file (both processes read it: the player's radio keeps to your
 * server's similar songs). The screens read it as ink music opens, so it takes the whole way the next time it opens
 * (the computer's [Local] is the same); the player reads it again each time it looks.
 */
object JustYours {
    private const val NAME = "just-yours"
    /** YouTube's own hosts, held back while it's on. */
    private val TUBE = setOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtube-nocookie.com", "youtubei.googleapis.com")

    /** This run's, read as the screens (or the player) started. */
    @Volatile var on = false
        private set

    /** As chosen in settings, the next run's. */
    val wanted = MutableStateFlow(false)

    private fun file(c: Context) = File(c.applicationContext.filesDir, NAME)

    fun start(c: Context) {
        on = file(c).exists()
        wanted.value = on
        HeldBack.hosts = if (on) TUBE else emptySet()
    }

    /** The player's: read again (the screens may have chosen since), YouTube held back or let be with it. */
    fun read(c: Context): Boolean {
        val now = file(c).exists()
        if (now != on) { on = now; HeldBack.hosts = if (now) TUBE else emptySet() }
        return now
    }

    fun choose(c: Context, to: Boolean) {
        wanted.value = to
        runCatching { if (to) file(c).writeText("") else file(c).delete() }
        // the places that are all online put away as it's turned on (each can be shown again)
        if (to) Places.hideOnline(c)
    }

    /** Opened again at once, so it takes the whole way now (the player plays on in its own process). */
    fun restart(c: Context) {
        val app = c.applicationContext
        val again = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        again.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(again)
        Runtime.getRuntime().exit(0)
    }
}

/**
 * The places shown (settings, your music; issue 8: "I don't need the ink, podcasts and radio tabs"): each hidden one
 * gone from the tabs, the swipe and the places' menu. One of listen, albums, artists and songs always stays. Kept in
 * the screens' prefs ("places-hidden"), a place a line.
 */
object Places {
    val ALL = listOf("listen", "ink", "albums", "artists", "songs", "radio", "podcasts", "lists")
    private val YOURS = setOf("listen", "albums", "artists", "songs")
    private val ONLINE = setOf("ink", "radio", "podcasts")
    val hidden = MutableStateFlow<Set<String>>(emptySet())
    private var prefs: android.content.SharedPreferences? = null

    fun start(c: Context) {
        prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE).also { p ->
            hidden.value = p.getString("places-hidden", "").orEmpty().lines().map { it.trim() }.filter { it in ALL }.toSet()
        }
    }

    /** A place shown or hidden; never the last of listen, albums, artists and songs. */
    fun show(p: String, on: Boolean) {
        val now = if (on) hidden.value - p else hidden.value + p
        if (YOURS.all { it in now }) return
        put(now)
    }

    fun hideOnline(c: Context) {
        if (prefs == null) start(c)
        put(hidden.value + ONLINE)
    }

    private fun put(now: Set<String>) {
        hidden.value = now
        prefs?.edit()?.putString("places-hidden", now.joinToString("\n"))?.apply()
    }
}

/**
 * Settings' two rows for it (your music): JUST YOUR MUSIC, everything or just yours (taken the whole way the next time
 * ink music opens, or at once by opening it again); PLACES, each place lit while it's shown, a tap hides or shows it.
 */
@androidx.compose.runtime.Composable
fun JustYoursRows(opened: Opened) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val wanted by JustYours.wanted.collectAsState()
    SettingRow("JUST YOUR MUSIC", if (wanted) "on" else "off", opened,
        "your files and your server, your lists, loves and history. no youtube music, no picks, no radio or podcasts suggested") {
        Pick(listOf("everything" to !wanted, "just your music" to wanted)) { JustYours.choose(ctx, it == 1) }
        if (wanted != JustYours.on) {
            BasicText("all of it from the next time ink music opens", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp))
            Word("open it again now", pixel(14.sp), Modifier.padding(top = 6.dp), touch = 8.dp) { JustYours.restart(ctx) }
        }
    }
    val hidden by Places.hidden.collectAsState()
    SettingRow("PLACES", if (hidden.isEmpty()) "all" else "${Places.ALL.count { it !in hidden }} of ${Places.ALL.size}", opened,
        "the places along the top and in the places' menu; lit is shown, a tap hides or shows one" +
            if (wanted) ". just your music put ink, radio and podcasts away" else "") {
        Pick(Places.ALL.map { it to (it !in hidden) }) { i -> Places.show(Places.ALL[i], Places.ALL[i] in hidden) }
    }
}
