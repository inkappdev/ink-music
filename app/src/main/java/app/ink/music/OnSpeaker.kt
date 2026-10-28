package app.ink.music

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player as Android sees it while a speaker plays (casting): the speaker's state, not the phone's. The phone's
 * own player stays paused under it, silent, moved along the queue with the speaker so its song and cover are the
 * speaker's, and Android is told it plays on a remote device at the speaker's volume. So the notification, the lock
 * screen, a headset, the car and the volume buttons, anywhere, steer the speaker: each ask goes to the screens (they
 * hold the speaker's connection) and none reaches the phone's player. The screens say what the speaker does
 * ([PlayerService.CAST]); told it's over, everything is the phone's again.
 *
 * Before, the screens watched the phone's player and sent on what it was asked: its own echoes looked like asks
 * (a fade out's steps read as "play"), and the volume buttons never came (Android gives them to a session playing).
 */
class OnSpeaker(p: Player, private val ask: (Bundle) -> Unit) : ForwardingPlayer(p) {
    /** What the speaker says: meant to play, actually moving, where (ms at `at`, elapsedRealtime), how long, its volume (0 to 1, null not heard). */
    private data class Said(val playing: Boolean, val moving: Boolean, val position: Long, val at: Long, val duration: Long, val volume: Float?)
    private var said: Said? = null
    private val main = Handler(Looper.getMainLooper())
    private val told = CopyOnWriteArraySet<Player.Listener>()

    init {
        // the phone's player's own events (its fade ending in a pause, a song moved to) said over with the speaker's
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (said != null) main.post { if (said != null) tell(false) } }
        })
    }

    override fun addListener(listener: Player.Listener) { super.addListener(listener); told += listener }
    override fun removeListener(listener: Player.Listener) { super.removeListener(listener); told -= listener }

    /** What the screens say the speaker does (null: casting's over). Main thread. */
    fun speaker(b: Bundle?) {
        val was = said
        said = b?.let {
            Said(it.getBoolean("playing"), it.getBoolean("moving"), it.getLong("position"), SystemClock.elapsedRealtime(),
                it.getLong("duration"), it.getFloat("volume", -1f).takeIf { v -> v >= 0f })
        }
        android.util.Log.d("ink-cast", "player: ${said?.let { "the speaker's, ${if (it.moving) "playing" else if (it.playing) "starting" else "paused"} at ${it.position / 1000}s, volume ${it.volume}" } ?: "the phone's own"}")
        // casting begins: the phone's own player paused (faded) here, not asked separately by the screens (an ask
        // that arrived after this was taken for the speaker's: reopened on a cast, ink music paused the speaker)
        if (was == null && said != null && super.getPlayWhenReady()) super.pause()
        b?.getString("id")?.let(::follow)
        tell((was == null) != (said == null) || (was?.volume == null) != (said?.volume == null))
    }

    /** The phone's player, paused, moved to the speaker's song: its title and cover what Android shows. */
    private fun follow(id: String) {
        if (super.getCurrentMediaItem()?.mediaId == id) return
        val i = (0 until super.getMediaItemCount()).firstOrNull { super.getMediaItemAt(it).mediaId == id } ?: return
        super.seekTo(i, 0)
    }

    private fun tell(changed: Boolean) {
        if (changed) {
            val c = availableCommands; told.forEach { it.onAvailableCommandsChanged(c) }
            val d = deviceInfo; told.forEach { it.onDeviceInfoChanged(d) }
        }
        val ready = playWhenReady; val state = playbackState; val playing = isPlaying; val v = deviceVolume
        told.forEach {
            it.onPlayWhenReadyChanged(ready, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            it.onPlaybackStateChanged(state)
            it.onIsPlayingChanged(playing)
            it.onDeviceVolumeChanged(v, false)
        }
    }

    private fun asked(what: String, more: Bundle.() -> Unit = {}) = ask(Bundle().apply { putString("what", what); more() })
    private fun now() = SystemClock.elapsedRealtime()

    // ---- asks: to the speaker while casting ----

    override fun play() {
        val s = said ?: return super.play()
        said = s.copy(playing = true); asked("play"); tell(false)
    }
    override fun pause() {
        val s = said ?: return super.pause()
        said = s.copy(playing = false, moving = false, position = currentPosition, at = now()); asked("pause"); tell(false)
    }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (said == null) super.setPlayWhenReady(playWhenReady) else if (playWhenReady) play() else pause()
    }
    // stop, from anywhere, as in ink music: the speaker let go, its queue back on the phone, stopped
    override fun stop() { if (said == null) super.stop() else asked("stop") }
    override fun seekToNext() { if (said == null) super.seekToNext() else asked("next") }
    override fun seekToNextMediaItem() { if (said == null) super.seekToNextMediaItem() else asked("next") }
    override fun seekToPrevious() { if (said == null) super.seekToPrevious() else asked("previous") }
    override fun seekToPreviousMediaItem() { if (said == null) super.seekToPreviousMediaItem() else asked("previous") }
    override fun seekTo(positionMs: Long) { if (said == null) super.seekTo(positionMs) else seek(positionMs) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (said == null) super.seekTo(mediaItemIndex, positionMs)
        else if (mediaItemIndex == super.getCurrentMediaItemIndex()) seek(positionMs) else jump(mediaItemIndex)
    }
    override fun seekToDefaultPosition() { if (said == null) super.seekToDefaultPosition() else seek(0) }
    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (said == null) super.seekToDefaultPosition(mediaItemIndex)
        else if (mediaItemIndex == super.getCurrentMediaItemIndex()) seek(0) else jump(mediaItemIndex)
    }
    override fun seekBack() { if (said == null) super.seekBack() else seek(currentPosition - seekBackIncrement) }
    override fun seekForward() { if (said == null) super.seekForward() else seek(currentPosition + seekForwardIncrement) }

    private fun seek(ms: Long) {
        val to = ms.coerceAtLeast(0)
        said = said?.copy(position = to, at = now())
        asked("seek") { putLong("ms", to) }; tell(false)
    }
    private fun jump(index: Int) {
        if (index !in 0 until super.getMediaItemCount()) return
        asked("jump") { putString("id", super.getMediaItemAt(index).mediaId) }
    }

    // ---- what's said: the speaker's ----

    override fun getPlayWhenReady() = said?.playing ?: super.getPlayWhenReady()
    override fun isPlaying() = said?.moving ?: super.isPlaying()
    override fun getPlaybackState() = said?.let { if (it.playing && !it.moving) Player.STATE_BUFFERING else Player.STATE_READY } ?: super.getPlaybackState()
    override fun getPlaybackSuppressionReason() = if (said != null) Player.PLAYBACK_SUPPRESSION_REASON_NONE else super.getPlaybackSuppressionReason()
    override fun getPlayerError(): PlaybackException? = if (said != null) null else super.getPlayerError()
    override fun getCurrentPosition(): Long {
        val s = said ?: return super.getCurrentPosition()
        val p = if (s.moving) s.position + now() - s.at else s.position
        return if (s.duration > 0) p.coerceAtMost(s.duration) else p
    }
    override fun getContentPosition() = if (said != null) currentPosition else super.getContentPosition()
    override fun getBufferedPosition() = if (said != null) currentPosition else super.getBufferedPosition()
    override fun getContentBufferedPosition() = if (said != null) currentPosition else super.getContentBufferedPosition()
    override fun getTotalBufferedDuration() = if (said != null) 0L else super.getTotalBufferedDuration()
    override fun getDuration() = said?.duration?.takeIf { it > 0 } ?: super.getDuration()
    override fun getContentDuration() = said?.duration?.takeIf { it > 0 } ?: super.getContentDuration()

    // ---- the speaker's volume: Android's buttons and slider, in 20 steps ----

    private val level get() = said?.volume
    override fun getDeviceInfo(): DeviceInfo = if (level == null) super.getDeviceInfo()
        else DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(PlayerService.STEPS).build()
    override fun getDeviceVolume() = level?.let { Math.round(it * PlayerService.STEPS) } ?: super.getDeviceVolume()
    override fun isDeviceMuted() = level == null && super.isDeviceMuted()
    override fun setDeviceVolume(volume: Int, flags: Int) = if (level == null) super.setDeviceVolume(volume, flags) else turn(volume)
    @Deprecated("") override fun setDeviceVolume(volume: Int) = setDeviceVolume(volume, 0)
    override fun increaseDeviceVolume(flags: Int) = if (level == null) super.increaseDeviceVolume(flags) else turn(deviceVolume + 1)
    @Deprecated("") override fun increaseDeviceVolume() = increaseDeviceVolume(0)
    override fun decreaseDeviceVolume(flags: Int) = if (level == null) super.decreaseDeviceVolume(flags) else turn(deviceVolume - 1)
    @Deprecated("") override fun decreaseDeviceVolume() = decreaseDeviceVolume(0)

    private fun turn(to: Int) {
        val v = to.coerceIn(0, PlayerService.STEPS) / PlayerService.STEPS.toFloat()
        said = said?.copy(volume = v)
        asked("volume") { putFloat("level", v) }; tell(false)
    }

    override fun getAvailableCommands(): Player.Commands {
        if (said == null) return super.getAvailableCommands()
        val b = super.getAvailableCommands().buildUpon().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        )
        if (level != null) b.addAll(
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
        return b.build()
    }
    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
}
