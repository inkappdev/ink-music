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
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.volume
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
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    actual val songs: StateFlow<List<Song>> = _songs
    private var queue: List<Song>
        get() = _songs.value
        set(v) { _songs.value = v }
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
    private var shuffled = false
    private var repeating = Now.REPEAT_OFF
    private var ordered: List<Song>? = null

    actual fun shuffle() {
        val cur = queue.getOrNull(index)
        if (!shuffled) {
            ordered = queue
            queue = queue.take(index + 1) + queue.drop(index + 1).shuffled()
        } else {
            val back = ordered ?: queue
            queue = back
            index = cur?.let { c -> back.indexOfFirst { it.id == c.id } }?.takeIf { it >= 0 } ?: index
            ordered = null
        }
        shuffled = !shuffled
        tell()
    }

    actual fun playNext(s: Song) {
        if (queue.isEmpty()) { play(listOf(s), 0); return }
        queue = queue.take(index + 1) + s + queue.drop(index + 1)
        ordered = ordered?.let { it + s }
        tell()
    }

    actual fun add(s: Song) {
        if (queue.isEmpty()) { play(listOf(s), 0); return }
        queue = queue + s; ordered = ordered?.let { it + s }
        tell()
    }

    actual fun move(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val q = queue.toMutableList(); val s = q.removeAt(from); q.add(to, s)
        queue = q
        index = when {
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        tell()
    }

    actual fun remove(i: Int) {
        if (i !in queue.indices) return
        val gone = queue[i]
        queue = queue.toMutableList().also { it.removeAt(i) }
        ordered = ordered?.let { o -> o.toMutableList().also { l -> l.indexOfFirst { it.id == gone.id }.takeIf { it >= 0 }?.let(l::removeAt) } }
        when {
            queue.isEmpty() -> stop()
            i < index -> { index--; tell() }
            i == index -> { index = index.coerceAtMost(queue.size - 1); load() }
            else -> tell()
        }
    }

    private val _radio = MutableStateFlow(Store.get("radio") != "off")
    actual val radio: StateFlow<Boolean> = _radio
    actual fun radio(on: Boolean) { _radio.value = on; Store.set("radio", if (on) "on" else "off"); if (on) topUp() }
    private var toppingUp = false

    /**
     * Near the queue's end with radio on: more like it, YouTube's radio from the last song (found on YouTube if it's
     * your server's), the ones already queued left out. Not for stations or episodes.
     */
    private fun topUp() {
        if (!_radio.value || toppingUp || queue.isEmpty() || queue.size - index > 3) return
        val seed = queue.last().takeIf { it.kind != "station" && it.kind != "episode" } ?: return
        toppingUp = true
        scope.launch {
            val more = Here.off {
                runCatching {
                    val id = if (seed.kind == "youtube") seed.id else Tube.api.searchSongs("${seed.artist} ${seed.title}", 1).firstOrNull()?.videoId
                    id?.let { Tube.api.queue("RDAMVM$it", 30, it) }.orEmpty().map { Tube.song(it) }
                }.getOrDefault(emptyList())
            }
            toppingUp = false
            if (!_radio.value || queue.isEmpty()) return@launch
            val have = queue.mapTo(HashSet()) { it.id }
            val fresh = more.filter { it.id !in have }
            if (fresh.isNotEmpty()) { queue = queue + fresh; ordered = ordered?.let { it + fresh }; tell(); ahead() }
        }
    }

    actual fun jump(i: Int) { if (i in queue.indices && i != index) { index = i; load() } }

    actual fun repeat() { repeating = (repeating + 1) % 3; tell() }

    actual fun stop() {
        player.pause()
        done()
        player.replaceCurrentItemWithPlayerItem(null)
        queue = emptyList(); index = 0; ordered = null
        _now.value = null
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
    }
    actual fun pause() { player.pause(); tell() }
    actual fun quiet() { player.volume = 0f }

    private fun start() {
        if (started) return
        started = true
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        // playing as soon as there's a little: YouTube's and the server's links fill faster than it plays
        player.automaticallyWaitsToMinimizeStalling = false
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
        shuffled = false; ordered = null
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
            _now.value = Now(s, false, 0, s.duration, index, queue.size, shuffled, repeating)
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
            when {
                repeating == Now.REPEAT_ONE -> { done(); heardOf = s; heardAt = nowMs(); heardMs = 0; seek(0); player.play() }
                index + 1 < queue.size -> { index++; load() }
                repeating == Now.REPEAT_ALL && queue.isNotEmpty() -> { index = 0; load() }
                else -> { player.pause(); done(); tell() }
            }
        }
        player.replaceCurrentItemWithPlayerItem(item)
        // an episode carries on where it was left
        if (s.kind == "episode") Podcasts.place(s.id).takeIf { it > 0 }?.let { player.seekToTime(CMTimeMakeWithSeconds(it / 1000.0, 600)) }
        player.play()
        tell()
        cover(s)
        ahead()
        topUp()
    }

    /** The next song's YouTube link found while this one plays, so it starts at once. */
    private fun ahead() {
        val n = queue.getOrNull(index + 1)?.takeIf { it.kind == "youtube" } ?: return
        Here.work.launch { runCatching { Tube.stream(n.id) } }
    }

    actual fun toggle() { if (player.rate > 0f) player.pause() else { player.play(); resumed = nowMs() }; tell() }
    actual fun next() { if (index + 1 < queue.size) { index++; load() } else if (repeating == Now.REPEAT_ALL && queue.isNotEmpty()) { index = 0; load() } }
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
            if (s.kind == "youtube") Here.work.launch { Tube.played(s.id) }
        }
        if (s.kind == "episode") { val p = position(); Here.work.launch { if (Podcasts.place(s.id) != -1L) Podcasts.setPlace(s.id, p) } }
    }

    /** The screens and the lock screen told where it is. */
    private fun tell() {
        val s = queue.getOrNull(index) ?: return
        val playing = player.rate > 0f
        val pos = position()
        val len = length(s)
        _now.value = Now(s, playing, pos, len, index, queue.size, shuffled, repeating)
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
