package app.ink.music

import kotlinx.coroutines.flow.StateFlow

/** What plays: the song, whether it's moving, where it is (ms), how long it is. */
data class Now(val song: Song, val playing: Boolean, val position: Long, val duration: Long, val index: Int, val count: Int,
               val shuffle: Boolean = false, val repeat: Int = REPEAT_OFF) {
    companion object { const val REPEAT_OFF = 0; const val REPEAT_ALL = 1; const val REPEAT_ONE = 2 }
}

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
    /** The rest of the queue shuffled (or put back in its order). */
    fun shuffle()
    /** Repeat: off, the whole queue, this song, in turn. */
    fun repeat()
    /** Stopped: nothing playing, the queue let go. */
    fun stop()
    /** A song put after the one playing. */
    fun playNext(s: Song)
    /** A song put at the queue's end. */
    fun add(s: Song)
    /** Silent from now on (pictures of the screens taken while it plays). */
    fun quiet()
}
