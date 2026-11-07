package app.ink.music

/**
 * What core asks of the system it runs on (2026-11-07, core shared with the iPhone): each its own (jvmMain: the phone's
 * and the computer's, as before; iosMain: the iPhone's).
 */

/** Now, in ms since 1970 (the wall clock). */
expect fun nowMs(): Long

/** Lower case, accents off ("Lila Iké" is "lila ike"). */
expect fun fold(s: String): String
