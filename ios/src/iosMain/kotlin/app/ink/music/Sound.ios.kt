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

    private fun start() {
        if (started) return
        started = true
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(0.5, 600), null) { _ -> tell() }
        val c = MPRemoteCommandCenter.sharedCommandCenter()
        c.playCommand.addTargetWithHandler { _ -> player.play(); tell(); MPRemoteCommandHandlerStatusSuccess }
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
        val url = Server.stream(s)?.let { NSURL.URLWithString(it) } ?: return
        val item = AVPlayerItem(uRL = url)
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { _ ->
            if (index + 1 < queue.size) { index++; load() } else { player.pause(); tell() }
        }
        player.replaceCurrentItemWithPlayerItem(item)
        player.play()
        tell()
        cover(s)
    }

    actual fun toggle() { if (player.rate > 0f) player.pause() else player.play(); tell() }
    actual fun next() { if (index + 1 < queue.size) { index++; load() } }
    actual fun previous() {
        if (position() > 3_000 || index == 0) seek(0) else { index--; load() }
    }
    actual fun seek(ms: Long) { player.seekToTime(CMTimeMakeWithSeconds(ms / 1000.0, 600)); tell() }

    private fun position(): Long = CMTimeGetSeconds(player.currentTime()).takeIf { !it.isNaN() }?.times(1000)?.toLong() ?: 0L

    /** The screens and the lock screen told where it is. */
    private fun tell() {
        val s = queue.getOrNull(index) ?: return
        val playing = player.rate > 0f
        val pos = position()
        _now.value = Now(s, playing, pos, s.duration, index, queue.size)
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to s.title, MPMediaItemPropertyArtist to s.artist, MPMediaItemPropertyAlbumTitle to s.album,
            MPMediaItemPropertyPlaybackDuration to s.duration / 1000.0, MPNowPlayingInfoPropertyElapsedPlaybackTime to pos / 1000.0,
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
