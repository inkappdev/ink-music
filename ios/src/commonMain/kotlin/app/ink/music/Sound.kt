package app.ink.music

import kotlinx.coroutines.flow.StateFlow

/** What plays: the song, whether it's moving, where it is (ms), how long it is. */
data class Now(val song: Song, val playing: Boolean, val position: Long, val duration: Long, val index: Int, val count: Int)

/** The iPhone's player (AVFoundation): a queue of songs, the lock screen and Control Center kept to it. */
expect object Sound {
    val now: StateFlow<Now?>
    fun play(songs: List<Song>, index: Int)
    fun toggle()
    fun next()
    fun previous()
    fun seek(ms: Long)
    /** What's queued and where (for "continue here" on the other devices). */
    fun queue(): List<Song>
    /** When play was last pressed here (sync: a stop asked for before it passed over). */
    val resumedAt: Long
    fun pause()
}
