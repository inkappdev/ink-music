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
    /** The queue, told as it changes (up next). */
    val songs: StateFlow<List<Song>>
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
    /** The queue's song at `from` moved to `to`, the one playing kept playing. */
    fun move(from: Int, to: Int)
    /** The queue's song at `i` taken out (the one playing: on to the next). */
    fun remove(i: Int)
    /** The queue's song at `i` played. */
    fun jump(i: Int)
    /** Radio: on, the queue's end brings more like it (YouTube's radio from its last songs); kept. */
    val radio: StateFlow<Boolean>
    fun radio(on: Boolean)
    /** Silent from now on (pictures of the screens taken while it plays). */
    fun quiet()
}
