package app.ink.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
    val stopped: Boolean = false, // stop pressed: the queue kept, nothing at the foot till it plays again
    val heard: Heard? = null,     // what reaches your ears, as the player read it (null: not yet, or on a speaker)
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

    /** While casting, up next is the speaker's queue (what was handed over), its song lit by the speaker's place. */
    fun showCastQueue(songs: List<Song>) { _queue.value = songs.map { Queued(it.title, it.artist, it) } }

    private fun readQueue() {
        if (casting) return
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
        lingering = false
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(ctx, ComponentName(ctx, PlayerService::class.java))
        val future = MediaController.Builder(ctx, token)
            .setListener(object : MediaController.Listener {
                // the sleep timer, as the player says it
                override fun onExtrasChanged(controller: MediaController, extras: android.os.Bundle) {
                    sleepAt = extras.getLong(PlayerService.SLEEP_AT); radio = extras.getBoolean(PlayerService.RADIO, true)
                    heard = Heard.of(extras.getString(PlayerService.HEARD)); audioSession = extras.getInt(PlayerService.AUDIO)
                Eq.bandsOf(extras.getString(PlayerService.EQ))?.let { bands.value = it }; update()
                }

                // while casting, what the notification, the lock screen, a headset, the car or the volume buttons asked
                // of the player's session (OnSpeaker): to the speaker
                override fun onCustomCommand(
                    controller: MediaController, command: androidx.media3.session.SessionCommand, args: android.os.Bundle,
                ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                    if (command.customAction == PlayerService.CAST_ASK && casting) {
                        android.util.Log.d("ink-cast", "asked from outside: ${args.getString("what")}")
                        when (args.getString("what")) {
                            "play" -> Casting.resume()
                            "pause" -> Casting.pauseThere()
                            "next" -> Casting.next()
                            "previous" -> Casting.previous()
                            "seek" -> Casting.seek(args.getLong("ms"))
                            "jump" -> args.getString("id")?.let { id -> app?.let { Casting.jumpTo(it, id) } }
                            "stop" -> Casting.bringBack(stopped = true)
                            "volume" -> Casting.setVolume(args.getFloat("level"))
                        }
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
                }
            })
            .buildAsync()
        future.addListener({
            connecting = false
            controller = runCatching { future.get() }.getOrNull()?.also {
                it.addListener(listener)
                sleepAt = it.sessionExtras.getLong(PlayerService.SLEEP_AT)
                radio = it.sessionExtras.getBoolean(PlayerService.RADIO, true)
                heard = Heard.of(it.sessionExtras.getString(PlayerService.HEARD))
                audioSession = it.sessionExtras.getInt(PlayerService.AUDIO)
                Playback.token = it.sessionExtras.parcelable(PlayerService.TOKEN)
                Eq.bandsOf(it.sessionExtras.getString(PlayerService.EQ))?.let { b -> bands.value = b }
                level(levelling(ctx))   // the player's copy brought in line with the setting
            }
            readQueue()
            update()
            // a cast still playing on a speaker since ink music was last open: shown again
            app?.let { Casting.rejoin(it) }
        }, ContextCompat.getMainExecutor(ctx))
    }

    /**
     * The screens gone (ink music left): the player let go. Not while casting: the player says the speaker's state to
     * Android only while held (let go as the phone locked, the lock screen went to another app's); it's let go
     * when the cast ends, if no screen's up by then.
     */
    fun release() {
        if (casting) { lingering = true; return }
        controller?.run { removeListener(listener); release() }
        controller = null
    }
    private var lingering = false

    private var sleepAt = 0L
    private var radio = true
    private var heard: Heard? = null

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
        // a station keeps its own sleeve whatever it's playing (OnAir)
        val song = songOf(item)
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
            // idle without an error is a stop (an error leaves it idle too, and says so in the player)
            stopped = c.playbackState == Player.STATE_IDLE && c.playerError == null,
            heard = heard?.takeIf { it.id == item.mediaId },   // never the song before's, told late
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

    /**
     * What the speaker does, told to the player's session ([OnSpeaker]): Android then shows and steers the speaker,
     * its volume buttons too. Not casting: told it's over.
     */
    fun tellPlayer() {
        val b = android.os.Bundle()
        if (casting) b.putBoolean("on", true)
        if (casting) _now.value?.let { n ->
            b.putBoolean("playing", n.playing); b.putBoolean("moving", n.moving)
            b.putLong("position", n.positionNow()); b.putLong("duration", n.duration)
            n.song?.id?.let { b.putString("id", it) }
            // a YouTube song on a DLNA receiver can't be jumped about in (the Denon takes the seek, says the new time
            // and plays on: YouTube's m4a comes in fragments): its bar not draggable
            b.putBoolean("seekable", !(Casting.on.value?.kind == "dlna" && n.song?.tube == true))
            Casting.volume.value?.let { b.putFloat("volume", it) }
        }
        controller?.sendCustomCommand(androidx.media3.session.SessionCommand(PlayerService.CAST, android.os.Bundle.EMPTY), b)
    }

    /** The phone stops for a speaker: the player told it casts, it pauses itself (OnSpeaker) and keeps its queue. */
    fun pauseHere() { casting = true; tellPlayer() }
    /**
     * The phone's queue made the speaker's when it hasn't the speaker's songs (ink music opened again on a cast), so
     * its player, paused, can be moved along with the speaker's.
     */
    fun mirror(songs: List<Song>) {
        val c = controller ?: return
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toHashSet()
        if (songs.isNotEmpty() && songs.any { it.id !in ids }) c.setMediaItems(songs.map(::item), 0, 0)
    }
    /** The speaker's song, as it says it. */
    fun showCast(n: Now) { casting = true; _now.value = n; tellPlayer() }
    /** Casting over: the phone's own player shows again. */
    fun castEnded() {
        casting = false; tellPlayer(); _now.value = null; readQueue(); update()
        // held only for the speaker: let go once what ends the cast is done with it (back to the phone plays here)
        if (lingering) android.os.Handler(android.os.Looper.getMainLooper()).post { if (lingering) { lingering = false; release() } }
    }

    fun toggle() { if (casting) return Casting.toggle(); controller?.run { if (playWhenReady) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() } } }
    fun next() { if (casting) return Casting.next(); controller?.seekToNextMediaItem() }
    /**
     * Stop: the song back to its start and the player let go (its notification goes with it); the
     * queue stays, and play starts it again. On a speaker, the same: the speaker let go, its queue here.
     */
    fun stop() {
        if (casting) return Casting.bringBack(stopped = true)
        // one ask: the player fades, lets go and goes back to the start itself (three made the fade start over)
        controller?.stop()
    }
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
    /** The phone's equalizer bands, as the player found them (null till it's said). */
    val bands = kotlinx.coroutines.flow.MutableStateFlow<Eq.Bands?>(null)

    /** The equalizer as set here (written to its file): the player told to apply it. */
    fun equalize(c: Context, s: Eq.State) {
        Eq.write(c, s)
        controller?.sendCustomCommand(androidx.media3.session.SessionCommand(PlayerService.EQ, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
    }

    /** The player's audio session (in its own process), for the phone's equalizer. */
    @Volatile var audioSession = 0
    /** The player's session as Android knows it (its volume buttons are handed to it while casting: Main). */
    var token: android.media.session.MediaSession.Token? = null
        private set

    /** The phone's own equalizer for ink music's sound (Samsung's, Android's, an equalizer app's): false if it has none. */
    fun equalizer(c: Context): Boolean = runCatching {
        c.startActivity(Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, audioSession)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, c.packageName)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** A YouTube song likely to be played next (the first on a page just opened): the player finds its stream now. */
    fun warm(s: Song?) {
        if (s == null || !s.tube || casting) return
        controller?.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlayerService.WARM, android.os.Bundle.EMPTY),
            android.os.Bundle().apply { putString(PlayerService.WARM, s.id) },
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
    fun jump(index: Int) {
        // on a speaker: the speaker's queue from there (the phone's own player had started under it)
        if (casting) { app?.let { Casting.jump(it, index) }; return }
        controller?.run { if (index in 0 until mediaItemCount) { seekTo(index, 0); play() } }
    }
    // on a speaker the queue there is fixed for now (it holds its own): moving and removing are the phone's
    fun move(from: Int, to: Int) { if (casting) return; controller?.run { if (from in 0 until mediaItemCount && to in 0 until mediaItemCount) moveMediaItem(from, to) } }
    fun remove(index: Int) { if (casting) return; controller?.run { if (index in 0 until mediaItemCount) removeMediaItem(index) } }

    /** After the song playing (nothing playing: it plays). */
    fun playNext(s: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(s), 0)
        c.addMediaItem(c.currentMediaItemIndex + 1, item(s))
    }

    /** Songs to follow [s], still playing: at the queue's end, those not in it already (radio may have topped it up). */
    fun follow(s: Song, songs: List<Song>) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId != s.id) return
        val there = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
        c.addMediaItems(songs.filter { it.id !in there }.map(::item))
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

@Suppress("DEPRECATION")
private fun android.os.Bundle.parcelable(key: String): android.media.session.MediaSession.Token? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelable(key, android.media.session.MediaSession.Token::class.java) else getParcelable(key)
