@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage

/**
 * The player: one AVPlayer, the queue kept here (the next song put in as one ends), the sound session for playing
 * in the background and through the lock, the lock screen's and Control Center's card and buttons kept to it.
 */
actual object Sound {
    private val _now = MutableStateFlow<Now?>(null)
    actual val now: StateFlow<Now?> = _now
    private val player = AVPlayer()
    private var queue: List<Song> = emptyList()
    private var index = 0
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var endObserver: Any? = null
    private var art: MPMediaItemArtwork? = null
    private var artFor: String? = null
    private var resumed = 0L
    actual val resumedAt: Long get() = resumed
    // the listen being counted: which, when it began, how long it's been heard so far
    private var heardOf: Song? = null
    private var heardAt = 0L
    private var heardMs = 0L
    private var tickAt = 0L
    private var placeAt = 0L

    actual fun queue(): List<Song> = queue
    actual fun pause() { player.pause(); tell() }

    private fun start() {
        if (started) return
        started = true
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(0.5, 600), null) { _ -> count(); tell() }
        val c = MPRemoteCommandCenter.sharedCommandCenter()
        c.playCommand.addTargetWithHandler { _ -> player.play(); resumed = nowMs(); tell(); MPRemoteCommandHandlerStatusSuccess }
        c.pauseCommand.addTargetWithHandler { _ -> player.pause(); tell(); MPRemoteCommandHandlerStatusSuccess }
        c.togglePlayPauseCommand.addTargetWithHandler { _ -> toggle(); MPRemoteCommandHandlerStatusSuccess }
        c.nextTrackCommand.addTargetWithHandler { _ -> next(); MPRemoteCommandHandlerStatusSuccess }
        c.previousTrackCommand.addTargetWithHandler { _ -> previous(); MPRemoteCommandHandlerStatusSuccess }
        c.changePlaybackPositionCommand.addTargetWithHandler { e ->
            (e as? MPChangePlaybackPositionCommandEvent)?.let { seek((it.positionTime * 1000).toLong()) }
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    actual fun play(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return
        start()
        queue = songs; this.index = index.coerceIn(0, songs.size - 1)
        load()
    }

    private fun load() {
        val s = queue.getOrNull(index) ?: return
        done()
        heardOf = s; heardAt = nowMs(); heardMs = 0; tickAt = 0
        resumed = nowMs()
        if (s.kind == "youtube") {
            // YouTube's link found as it starts (off the main thread: the player, its challenges, its tokens)
            val at = index
            _now.value = Now(s, false, 0, s.duration, index, queue.size)
            scope.launch {
                val link = Here.off { Tube.stream(s.id) }
                if (index != at || queue.getOrNull(index)?.id != s.id) return@launch
                if (link == null) { println("ink-youtube ${s.id}: no stream, on to the next"); if (index + 1 < queue.size) { index++; load() }; return@launch }
                start(s, NSURL.URLWithString(link) ?: return@launch)
            }
            return
        }
        val url = Server.stream(s)?.let { NSURL.URLWithString(it) } ?: return
        start(s, url)
    }

    private fun start(s: Song, url: NSURL) {
        val item = AVPlayerItem(uRL = url)
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { _ ->
            if (s.kind == "episode") Podcasts.setPlace(s.id, -1)
            if (index + 1 < queue.size) { index++; load() } else { player.pause(); done(); tell() }
        }
        player.replaceCurrentItemWithPlayerItem(item)
        // an episode carries on where it was left
        if (s.kind == "episode") Podcasts.place(s.id).takeIf { it > 0 }?.let { player.seekToTime(CMTimeMakeWithSeconds(it / 1000.0, 600)) }
        player.play()
        tell()
        cover(s)
    }

    actual fun toggle() { if (player.rate > 0f) player.pause() else { player.play(); resumed = nowMs() }; tell() }
    actual fun next() { if (index + 1 < queue.size) { index++; load() } }
    actual fun previous() {
        if (position() > 3_000 || index == 0) seek(0) else { index--; load() }
    }
    actual fun seek(ms: Long) { player.seekToTime(CMTimeMakeWithSeconds(ms / 1000.0, 600)); tell() }

    private fun position(): Long = CMTimeGetSeconds(player.currentTime()).takeIf { !it.isNaN() }?.times(1000)?.toLong() ?: 0L

    /** How long the song's known to be: its own, else the stream's once it says (an episode's feed may not). */
    private fun length(s: Song): Long = if (s.kind == "station") 0L else s.duration.takeIf { it > 0 }
        ?: player.currentItem?.duration?.let { CMTimeGetSeconds(it) }?.takeIf { !it.isNaN() && it > 0 }?.times(1000)?.toLong() ?: 0L

    /** Every half second while it plays: the listen counted on, an episode's place kept now and then. */
    private fun count() {
        val t = nowMs()
        if (player.rate > 0f && tickAt > 0) heardMs += (t - tickAt).coerceIn(0, 2_000)
        tickAt = t
        val s = heardOf ?: return
        if (s.kind == "episode" && player.rate > 0f && t - placeAt > 10_000) { placeAt = t; val p = position(); Here.work.launch { Podcasts.setPlace(s.id, p) } }
    }

    /** The song before is done with: a listen if it was heard long enough, the server told. */
    private fun done() {
        val s = heardOf ?: return
        heardOf = null
        if (heardMs >= Heard.ENOUGH) {
            Heard.add(s, heardAt, heardMs)
            if (s.kind == "server") scope.launch { Server.scrobble(s.id) }
        }
        if (s.kind == "episode") { val p = position(); Here.work.launch { if (Podcasts.place(s.id) != -1L) Podcasts.setPlace(s.id, p) } }
    }

    /** The screens and the lock screen told where it is. */
    private fun tell() {
        val s = queue.getOrNull(index) ?: return
        val playing = player.rate > 0f
        val pos = position()
        val len = length(s)
        _now.value = Now(s, playing, pos, len, index, queue.size)
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to s.title, MPMediaItemPropertyArtist to s.artist, MPMediaItemPropertyAlbumTitle to s.album,
            MPMediaItemPropertyPlaybackDuration to len / 1000.0, MPNowPlayingInfoPropertyElapsedPlaybackTime to pos / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to if (playing) 1.0 else 0.0,
        )
        if (artFor == s.cover) art?.let { info[MPMediaItemPropertyArtwork] = it }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    /** The song's cover on the lock screen, read once a record. */
    private fun cover(s: Song) {
        val id = s.cover ?: return
        if (artFor == id) return
        scope.launch {
            val bytes = Images.bytes(id, 600) ?: return@launch
            val img = UIImage.imageWithData(bytes.toNSData()) ?: return@launch
            art = MPMediaItemArtwork(boundsSize = img.size) { _ -> img }
            artFor = id
            tell()
        }
    }
}
