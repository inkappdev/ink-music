package app.ink.music

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What's playing, as the screens show it. */
data class Now(
    val song: Song?,          // the library's song (null: not one of the phone's, or not read yet)
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,       // ms, 0 not known yet
    val position: Long,       // ms at `at`
    val at: Long,             // elapsedRealtime of that position
    val playing: Boolean,     // meant to play (also while it buffers)
    val moving: Boolean,      // the position is actually advancing
    val index: Int,
    val count: Int,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val sleepAt: Long = 0L,   // the sleep timer's end (wall clock ms), PlayerService.SLEEP_END_OF_SONG, or 0
    val device: String = "",  // the speaker it plays on (Casting), or "" for the phone
    val quality: String = "", // what reaches the speaker: "flac 24/96", "mp3 320"
    val radio: Boolean = true, // the queue's end brings more like it
) {
    fun positionNow(): Long =
        if (!moving) position else (position + SystemClock.elapsedRealtime() - at).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)
}

/** A song in the queue, as the queue page lists it. */
data class Queued(val title: String, val artist: String, val song: Song?)

/** The screens' hold on the player: a controller to [PlayerService], while a screen is up. */
object Playback {
    private var controller: MediaController? = null
    private var connecting = false
    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> = _now
    private val _queue = MutableStateFlow<List<Queued>>(emptyList())
    val queue: StateFlow<List<Queued>> = _queue

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // the queue read again only when it changed (a long one is a thousand items)
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) readQueue()
            update()
        }
    }

    private fun readQueue() {
        val c = controller ?: return
        _queue.value = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            val song = songOf(item)
            Queued(item.mediaMetadata.title?.toString() ?: song?.title.orEmpty(), item.mediaMetadata.artist?.toString() ?: song?.artist.orEmpty(), song)
        }
    }

    private var app: Context? = null
    /** Playing on a speaker: what's shown and steered is the speaker's ([Casting]). */
    private var casting = false

    fun connect(ctx: Context) {
        app = ctx.applicationContext
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(ctx, ComponentName(ctx, PlayerService::class.java))
        val future = MediaController.Builder(ctx, token)
            .setListener(object : MediaController.Listener {
                // the sleep timer, as the player says it
                override fun onExtrasChanged(controller: MediaController, extras: android.os.Bundle) {
                    sleepAt = extras.getLong(PlayerService.SLEEP_AT); radio = extras.getBoolean(PlayerService.RADIO, true); update()
                }
            })
            .buildAsync()
        future.addListener({
            connecting = false
            controller = runCatching { future.get() }.getOrNull()?.also {
                it.addListener(listener)
                sleepAt = it.sessionExtras.getLong(PlayerService.SLEEP_AT)
                radio = it.sessionExtras.getBoolean(PlayerService.RADIO, true)
                level(levelling(ctx))   // the player's copy brought in line with the setting
            }
            readQueue()
            update()
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun release() {
        controller?.run { removeListener(listener); release() }
        controller = null
    }

    private var sleepAt = 0L
    private var radio = true

    /**
     * The library read (again): the queue's songs looked up anew. Connected before the library had
     * loaded, what plays had no song (so no cover), and paused, no player event would come to fix it.
     */
    fun refresh() { readQueue(); update() }

    private fun update() {
        if (casting) return
        val c = controller ?: return
        val item = c.currentMediaItem ?: return run { _now.value = null }
        val md = c.mediaMetadata
        // a station playing a song it gave a cover for: the song's cover, kept by its link
        val song = songOf(item).let { s ->
            val art = CoverProvider.linkOf(md.artworkUri)
            if (s != null && s.station && art != null) s.copy(art = art, albumId = "art:$art") else s
        }
        _now.value = Now(
            song = song,
            title = md.title?.toString() ?: song?.title.orEmpty(),
            artist = md.artist?.toString() ?: song?.artist.orEmpty(),
            album = md.albumTitle?.toString() ?: song?.album.orEmpty(),
            duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: song?.duration ?: 0L,
            position = c.currentPosition.coerceAtLeast(0),
            at = SystemClock.elapsedRealtime(),
            playing = c.playWhenReady,
            moving = c.isPlaying,
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            sleepAt = sleepAt,
            radio = radio,
        )
    }

    /** The library's song for a queue item (a queue kept before ids carried their source: a bare number is a file). */
    private fun songOf(id: String) = Library.song(id) ?: id.takeIf { it.all(Char::isDigit) }?.let { Library.song("l$it") }

    /**
     * A queue item's song; a YouTube one not seen this run (a queue kept from before) made again
     * from the item's own words and cover.
     */
    private fun songOf(item: MediaItem): Song? = songOf(item.mediaId) ?: item.mediaId.takeIf { it.startsWith("y") }?.let { id ->
        val md = item.mediaMetadata
        Song(id, android.net.Uri.parse("ytm://" + id.removePrefix("y")), md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(),
            md.albumTitle?.toString().orEmpty(), id, md.albumArtist?.toString().orEmpty(), 0, 1, 0, 0,
            art = md.artworkUri?.toString(), format = "youtube").also(YouTubeMusic::remember)
    }

    /** Plays `songs` from `start` (at `at` ms), as a queue; on the speaker while one is chosen. */
    fun play(songs: List<Song>, start: Int, at: Long = 0L, go: Boolean = true) {
        if (songs.isEmpty()) return
        val speaker = Casting.on.value
        if (speaker != null) { app?.let { Casting.play(it, speaker, songs, start, at) }; return }
        val c = controller ?: return
        c.setMediaItems(songs.map(::item), start.coerceIn(songs.indices), at)
        c.prepare()
        if (go) c.play()
    }

    /** The phone stops for a speaker (it keeps its queue, paused). */
    fun pauseHere() { controller?.pause(); casting = true }
    /** The speaker's song, as it says it. */
    fun showCast(n: Now) { casting = true; _now.value = n }
    /** Casting over: the phone's own player shows again. */
    fun castEnded() { casting = false; _now.value = null; update() }

    fun toggle() { if (casting) return Casting.toggle(); controller?.run { if (playWhenReady) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() } } }
    fun next() { if (casting) return Casting.next(); controller?.seekToNextMediaItem() }
    /** Back: to the song's start, or the song before when it's only just begun (as every player). */
    fun previous() { if (casting) return Casting.previous(); controller?.seekToPrevious() }
    fun seek(ms: Long) { if (casting) return Casting.seek(ms); controller?.seekTo(ms) }

    /** The phone's own queue and where in it, for handing to a speaker. */
    fun handover(): Triple<List<Song>, Int, Long>? {
        val c = controller ?: return null
        val songs = (0 until c.mediaItemCount).mapNotNull { songOf(c.getMediaItemAt(it)) }
        if (songs.isEmpty()) return null
        val current = songOf(c.currentMediaItem ?: return null)
        return Triple(songs, songs.indexOf(current).coerceAtLeast(0), c.currentPosition)
    }
    fun shuffle() { controller?.run { shuffleModeEnabled = !shuffleModeEnabled } }
    /** Off, then the whole queue again, then this song again. */
    fun repeat() {
        controller?.run {
            repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        }
    }
    /** The sleep timer: in `ms`, at the song's end (PlayerService.SLEEP_END_OF_SONG), or off (0). */
    fun sleep(ms: Long) {
        controller?.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlayerService.SLEEP, android.os.Bundle.EMPTY),
            android.os.Bundle().apply { putLong(PlayerService.SLEEP_MS, ms) },
        )
    }
    /** Radio on or off: when the queue ends, more like it. */
    fun radio(on: Boolean) {
        radio = on; update()
        controller?.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlayerService.RADIO, android.os.Bundle.EMPTY),
            android.os.Bundle().apply { putBoolean(PlayerService.RADIO, on) },
        )
    }

    /** A radio from this song: it plays, and songs like it follow. */
    fun startRadio(s: Song) {
        if (!radio) radio(true)
        play(listOf(s), 0)
    }

    /** Volume levelling on or off: kept here (the setting), told to the player (which keeps its own). */
    fun level(on: Boolean) {
        app?.getSharedPreferences("settings", Context.MODE_PRIVATE)?.edit()?.putBoolean("levelling", on)?.apply()
        controller?.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlayerService.LEVEL, android.os.Bundle.EMPTY),
            android.os.Bundle().apply { putBoolean(PlayerService.LEVEL, on) },
        )
    }

    fun levelling(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("levelling", true)

    /** The queue's `index`th song, from its start. */
    fun jump(index: Int) { controller?.run { if (index in 0 until mediaItemCount) { seekTo(index, 0); play() } } }
    fun move(from: Int, to: Int) { controller?.run { if (from in 0 until mediaItemCount && to in 0 until mediaItemCount) moveMediaItem(from, to) } }
    fun remove(index: Int) { controller?.run { if (index in 0 until mediaItemCount) removeMediaItem(index) } }

    /** After the song playing (nothing playing: it plays). */
    fun playNext(s: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(s), 0)
        c.addMediaItem(c.currentMediaItemIndex + 1, item(s))
    }

    /** At the queue's end (nothing playing: it plays). */
    fun add(s: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(s), 0)
        c.addMediaItem(item(s))
    }

    fun item(s: Song) = MediaItem.Builder()
        .setMediaId(s.id)
        .setUri(s.uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(s.uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
                .setAlbumArtist(s.albumArtist).setTrackNumber(s.track).setIsPlayable(true).setExtras(Levelling.extras(s))
                // a cover for the notification, the lock screen and the car: YouTube's link, else ink music's own
                .setArtworkUri(if (s.tube) s.art?.let(android.net.Uri::parse) else CoverProvider.of(s.id)).build(),
        )
        .build()
}
