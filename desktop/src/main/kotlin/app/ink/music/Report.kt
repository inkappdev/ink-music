package app.ink.music

import java.io.File

/**
 * What to paste when something's wrong (2026-11-16): the version and system, how ink music is set, whether YouTube
 * Music answered as you, a crash if there was one, and the end of [Log]'s file. Copied, never sent: it goes where you
 * paste it. No names, sign-ins, cookies or passwords: the log is scrubbed as it's written, a crash's trace here (the
 * top, the system's own words, isn't: a Java version looks like an internet address to the scrub).
 */
object Report {
    fun text(): String = buildString {
        appendLine("ink music ${Keys.version.ifEmpty { "from the source" }}${if (Keys.build.isNotEmpty()) " (${Keys.build})" else ""}")
        appendLine("${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}), " +
            "Java ${System.getProperty("java.version")}, ${java.util.Locale.getDefault()}")
        appendLine("transitions ${Crossfade.mode.value}, shuffle ${if (Player.shuffle.value) "on" else "off"}, repeat ${Player.repeat.value}")
        val tube = when {
            TubeAccount.who.value == null -> "not signed in"
            TubeAccount.stale.value -> "answered as signed out"
            else -> "signed in"
        }
        appendLine("youtube music: $tube, ${TubeAccount.playlists.value.size} lists, ${TubeAccount.liked.value.size} liked" +
            (TubeAccount.problem.value?.let { "; last read: $it" } ?: ""))
        appendLine("phones paired ${SyncHub.peers.value.size}, songs here ${Library.songs.value.count { !it.server }}, " +
            "a server ${if (Server.account.value != null) "yes" else "no"}")
        Crash.file.takeIf { it.exists() }?.let { f ->
            appendLine()
            appendLine("--- the last crash (${java.time.Instant.ofEpochMilli(f.lastModified())}) ---")
            runCatching { f.readLines().take(30) }.getOrDefault(emptyList()).forEach { appendLine(Log.scrub(it)) }
        }
        appendLine()
        appendLine("--- the end of ink.log ---")
        Log.tail(150).forEach(::appendLine)
    }
}
