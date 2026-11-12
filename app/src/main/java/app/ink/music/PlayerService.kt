package app.ink.music

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The player, in its own process (`:player`): ExoPlayer behind a media session, so the
 * notification, the lock screen, headphones and Bluetooth buttons, other apps' controls (ink's
 * home among them) and audio focus all come with it. The screens talk to it as a controller; Android
 * Auto browses it as a library ([Auto]).
 */
class PlayerService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var onSpeaker: OnSpeaker? = null
    // the screens' controller telling us what a speaker does (its asks go back to it; gone, casting's over here)
    private var caster: MediaSession.ControllerInfo? = null
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var live: Live
    private var clock: History.Clock? = null
    private var onAir: OnAir? = null

    /** A future answered by a coroutine (the car's asks read the library, which may still be loading). */
    private fun <T> later(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        work.launch { runCatching { block() }.onSuccess { f.set(it) }.onFailure { f.setException(it) } }
        return f
    }

    /**
     * Items as they come to be played: a screen's carry their address in the request (Media3 keeps
     * it from crossing over); the car's are ids, made into songs from the library.
     */
    private suspend fun resolve(items: List<MediaItem>): MutableList<MediaItem> = items.mapNotNull { item ->
        item.requestMetadata.mediaUri?.let { return@mapNotNull item.buildUpon().setUri(it).build() }
        val song = Auto.play(this, item.mediaId)?.let { (songs, at) -> songs[at] }
        song?.let(Playback::item) ?: item.takeIf { it.localConfiguration != null }
    }.toMutableList()
    private var levelling: Levelling? = null
    private val prefs by lazy { getSharedPreferences("queue", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    // while it plays, where it is is kept every 15 s (and on every pause and stop)
    private val keepPlace = object : Runnable {
        override fun run() {
            session?.player?.let { if (it.isPlaying) { place(it); heard(it); episodePlace(it) } }
            main.postDelayed(this, 15_000)
        }
    }
    // ink music's own way to a DAC: what the DAC says (its clock, any gap) told to the signal path every few seconds
    private val dacSays = object : Runnable {
        override fun run() {
            if ((UsbDac.held?.now != null || Exclusive.rate > 0) && session?.player?.isPlaying == true) tell()
            main.postDelayed(this, 3_000)
        }
    }
    // a server song heard far enough (half of it, or four minutes, as Last.fm counts) is told to
    // the server once; "playing now" as it starts
    private val net = oneThread("ink-net")
    /** The next YouTube song's stream found while this one plays, so a skip doesn't wait for it. */
    private val ahead = oneThread("ink-ahead")
    // the jumps past a YouTube video's talking set for the song playing (SponsorBlock), let go as the next begins
    private val jumps = ArrayList<androidx.media3.exoplayer.PlayerMessage>()

    /**
     * A YouTube song beginning: where its video isn't music (SponsorBlock's marks: the talking, a skit, the credits),
     * jumped past as each comes, the player's own message at that moment (no clock watched). Settings, sound: on unless
     * said (a file, as the screens set it: files/skip-talking-off). A mark it's already inside: jumped at once.
     */
    private fun skipTalking(p: Player) {
        jumps.forEach { it.cancel() }; jumps.clear()
        val x = exo ?: return
        val item = p.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
        if (uri?.scheme != "ytm" || java.io.File(filesDir, "skip-talking-off").exists()) return
        val video = uri.host ?: return
        net.execute {
            val marks = SponsorBlock.notMusic(video)
            if (marks.isEmpty()) return@execute
            main.post {
                if (x.currentMediaItem?.mediaId != item.mediaId) return@post
                val index = x.currentMediaItemIndex
                for (m in marks) {
                    val now = x.currentPosition
                    if (now in m) { x.seekTo(m.last); continue }
                    if (m.first < now) continue
                    jumps += x.createMessage { _, _ ->
                        if (x.currentMediaItem?.mediaId == item.mediaId && x.currentPosition < m.last) x.seekTo(m.last)
                    }.setLooper(Looper.getMainLooper()).setPosition(index, m.first).setDeleteAfterDelivery(true).send()
                }
                android.util.Log.i("ink-youtube", "$video: ${marks.size} not music, skipped as they come")
            }
        }
    }
    @Volatile private var warmWanted: String? = null
    private var told: String? = null
    private var nowSaid: String? = null
    private var tubeTold: String? = null

    private fun heard(p: Player) {
        // private listening: nothing told, anywhere
        if (Private.on(this)) return
        val item = p.currentMediaItem ?: return
        val id = item.mediaId
        // a YouTube song 30 s in: told to YouTube Music, signed in (its history, its mixes)
        if (id.startsWith("y") && tubeTold != id && p.currentPosition >= History.ENOUGH) {
            tubeTold = id
            net.execute { YouTubeAccount.played(this, id) }
        }
        // a station's song, a podcast's episode: not a listen
        if (told == id || id.startsWith("s") || id.startsWith("e")) return
        val length = p.duration.takeIf { it > 0 } ?: return
        if (p.currentPosition >= minOf(length / 2, 240_000L)) {
            told = id
            if (id.startsWith("n")) net.execute { Server.scrobble(this, id, done = true) }
            // and to ListenBrainz, if you've said to (every song but a station's), as it started
            val said = said(item, length)
            val at = startedAt.takeIf { it > 0 } ?: (System.currentTimeMillis() - p.currentPosition)   // a queue put back: no start seen
            net.execute { ListenBrainz.listened(this, said, at); LastFm.listened(this, said, at) }
        }
    }

    /** A podcast's episode playing: where it is, kept (every 15 s, at a pause), so it starts there next time. */
    private fun episodePlace(p: Player) {
        val id = p.currentMediaItem?.mediaId?.takeIf { it.startsWith("e") } ?: return
        val at = p.currentPosition
        net.execute { keepPlace(id, at) }
    }

    /** Where an episode was left; the last 20 s reached, heard through. */
    private fun keepPlace(id: String, at: Long) {
        val length = Podcasts.episode(id)?.second?.length ?: 0L
        if (length > 0 && at >= length - 20_000) Podcasts.setPlace(id, -1)
        else if (at > 5_000) Podcasts.setPlace(id, at)
    }

    /** An episode's pauses skipped if that's chosen; never a song's (a song's quiet is its own). */
    private fun silence() {
        val p = exo ?: return
        val on = p.currentMediaItem?.mediaId?.startsWith("e") == true && Podcasts.skipSilence
        if (p.skipSilenceEnabled != on) p.skipSilenceEnabled = on
    }

    /** An episode begun: on from where you left it, at your speed; anything else at the speed it was made. */
    private fun episodeBegins(p: Player) {
        val item = p.currentMediaItem
        val episode = item?.mediaId?.startsWith("e") == true
        val speed = if (episode) Podcasts.speed else 1f
        // a beat-matched crossfade holds the new song at the old one's tempo for a while: left be
        if (p.playbackParameters.speed != speed && crossfade?.owns != true) p.setPlaybackSpeed(speed)
        silence()
        if (episode && p.currentPosition < 3_000) Podcasts.place(item!!.mediaId).takeIf { it > 5_000 }?.let { p.seekTo(it) }
    }

    /** When the song playing began (wall clock), for its listen. */
    private var startedAt = 0L

    private fun said(item: MediaItem, length: Long): ListenBrainz.Said {
        val md = item.mediaMetadata
        return ListenBrainz.Said(md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), md.albumTitle?.toString().orEmpty(),
            length, md.trackNumber ?: 0, item.mediaId.takeIf { it.startsWith("y") }?.removePrefix("y"))
    }

    // the sleep timer: when it ends (wall clock ms), or SLEEP_END_OF_SONG; told to the screens
    // through the session's extras
    private var sleepAt = 0L
    private val sleep = Runnable { session?.player?.let(::fadeOut) }

    private fun setSleep(p: Player, ms: Long) {
        main.removeCallbacks(sleep)
        exo?.pauseAtEndOfMediaItems = ms == SLEEP_END_OF_SONG
        sleepAt = when {
            ms == SLEEP_END_OF_SONG -> SLEEP_END_OF_SONG
            ms > 0 -> System.currentTimeMillis() + ms
            else -> 0L
        }
        if (ms > 0) main.postDelayed(sleep, ms)
        tell()
    }

    /** What the screens are told through the session's extras: sleep's end, radio, and what's heard. */
    /** ink music's own equalizer on the player's sound ([Eq]): ten bands through DynamicsProcessing, made again for a new audio session. */
    private var eq: android.media.audiofx.DynamicsProcessing? = null
    private var eqSession = 0
    private val bands = Eq.Bands(Eq.TEN, -Eq.RANGE, Eq.RANGE)

    /** The equalizer as set (the file the screens write): on with its levels, the whole turned down by its biggest boost (no clipping), or off. */
    private fun equalize() {
        val id = exo?.audioSessionId ?: return
        if (id == 0) return
        if (eq == null || eqSession != id) {
            runCatching { eq?.release() }
            eq = runCatching {
                val config = android.media.audiofx.DynamicsProcessing.Config.Builder(
                    android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, Eq.TEN.size, false, 0, false, 0, false,
                ).build()
                android.media.audiofx.DynamicsProcessing(0, id, config)
            }.onFailure { android.util.Log.d("ink-eq", "none: $it") }.getOrNull()
            eqSession = id
        }
        // exact to a usb dac: even volume rests too (both change the bits)
        val exact = Exact.on(this)
        levelling?.let { l -> val want = prefs.getBoolean("levelling", true) && !exact; if (l.on != want) { l.on = want; l.apply() } }
        // exact with ink music's own way to the DAC: the equalizer goes with the samples (UsbDac.eq), Android's rests
        Eq.read(this).let { st -> UsbDac.eq = if (exact && st.on && st.levels.any { it != 0 }) Eq.TEN.indices.map { st.levels.getOrElse(it) { 0 }.coerceIn(-Eq.RANGE, Eq.RANGE) / 100f }.toFloatArray() else null }
        val e = eq ?: return
        val st = Eq.read(this).let { if (exact) it.copy(on = false) else it }
        runCatching {
            if (st.on) {
                val levels = Eq.TEN.indices.map { st.levels.getOrElse(it) { 0 }.coerceIn(-Eq.RANGE, Eq.RANGE) }
                for (ch in 0 until 2) {
                    val pre = e.getPreEqByChannelIndex(ch)
                    Eq.TEN.forEachIndexed { i, hz ->
                        // each band reaches up to halfway (on a log scale) to the next one's centre
                        val top = Eq.TEN.getOrNull(i + 1)?.let { kotlin.math.sqrt(hz.toDouble() * it).toFloat() } ?: 20_000f
                        pre.getBand(i).apply { isEnabled = true; cutoffFrequency = top; gain = levels[i] / 100f }
                    }
                    pre.isEnabled = true
                    e.setPreEqByChannelIndex(ch, pre)
                }
                e.setInputGainAllChannelsTo(-(levels.maxOrNull() ?: 0).coerceAtLeast(0) / 100f)
            }
            e.enabled = st.on
        }.onFailure { android.util.Log.d("ink-eq", "apply: $it") }
    }

    /** The audio session opened to the phone's effects (its equalizer, an equalizer app), or closed. */
    private var effectsOpen = 0
    private fun effects(open: Boolean) {
        val id = exo?.audioSessionId ?: return
        if (effectsOpen != 0 && (effectsOpen != id || !open)) sendBroadcast(Intent(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, effectsOpen).putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName))
        effectsOpen = 0
        if (open && id != 0) {
            sendBroadcast(Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, id).putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC))
            effectsOpen = id
        }
    }

    private fun tell() {
        val heard = nowHeard()?.json().orEmpty()
        val audio = exo?.audioSessionId ?: 0
        session?.setSessionExtras(Bundle().apply {
            putLong(SLEEP_AT, sleepAt); putBoolean(RADIO, radio); putString(HEARD, heard); putInt(AUDIO, audio)
            putString(EQ, Eq.bandsJson(bands))
            putString(MIX, crossfade?.said()?.json().orEmpty())
            // for the screens to hand Android: their volume buttons to this session (a speaker's, while casting)
            session?.platformToken?.let { putParcelable(TOKEN, it) }
        })
    }

    /** The song playing as it reaches your ears: its format once the player has read it, how it came, its gain. */
    private fun nowHeard(): Heard? {
        val p = session?.player ?: return null
        val item = p.currentMediaItem ?: return null
        val f = formats[item.mediaId] ?: return null
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        val c = chosen[uri]
        val id = item.mediaId
        val out = Exact.output(this)
        val dac = UsbDac.held?.let { h -> h.now?.let { h to it } }
        val from = when {
            id.startsWith("s") -> "station"
            id.startsWith("y") -> "youtube"
            id.startsWith("n") -> "server"
            else -> "phone"
        }
        val made = when (from) {
            "server" -> when (c?.quality) { Quality.HIGH -> "mp3 320"; Quality.SAVER -> "opus 96"; else -> "" }
            "youtube" -> if (c?.quality == Quality.SAVER) "smallest" else "best"
            else -> ""
        }
        val kbps = when {
            from == "youtube" -> YouTubeMusic.kbps(id, c?.quality == Quality.SAVER)
            made == "mp3 320" -> 320
            made == "opus 96" -> 96
            else -> (f.averageBitrate.takeIf { it > 0 } ?: f.bitrate).takeIf { it > 0 }?.div(1000) ?: 0
        }
        return Heard(
            id = id, codec = Heard.codecOf(f.sampleMimeType), bits = Heard.bitsOf(f), rate = f.sampleRate.coerceAtLeast(0),
            channels = f.channelCount.coerceAtLeast(0), kbps = kbps, from = from, made = made,
            quality = if (from == "server" || from == "youtube") c?.quality ?: -1 else -1, metered = c?.metered == true,
            gain = levelling?.takeIf { it.on }?.db,
            decoder = decoder, outBits = dac?.second?.alt?.bits ?: outBits, outRate = dac?.second?.rate ?: outRate,
            device = dac?.first?.name?.lowercase() ?: out.first, kind = if (dac != null) "usb" else out.second, mixer = Exact.mixer(this),
            eq = Eq.read(this).takeIf { it.on && (!Exact.on(this) || (dac != null && UsbDac.eq != null)) }?.label.orEmpty(),
            exact = exactSaid,
            slot = dac?.second?.alt?.bits ?: 0, clock = dac?.second?.clock() ?: 0.0,
            dropouts = dac?.second?.underruns() ?: Exclusive.underrunsNow.takeIf { Exclusive.rate > 0 } ?: 0, exclusive = Exclusive.rate,
        )
    }

    // the signal path's facts, as the player reads them (Heard)
    private var decoder = ""
    private var outBits = 0
    private var outRate = 0
    private var crossfade: Crossfade? = null
    private var usbOutput: UsbOutputProvider? = null
    private var routedToDac: Boolean? = null

    /**
     * Where the samples go changed (exact switched, a DAC allowed or plugged in): the song taken up again where it is,
     * so Media3 opens its output anew. It kept the one it had, Android's, while the DAC was taken from Android: the
     * song went on out of the phone's speaker (2026-11-01). A moment's gap, only when the way changes.
     */
    private fun reroute() {
        val p = exo ?: return
        val now = Exact.on(this) && UsbDac.hold(this) != null
        if (routedToDac == now) return
        routedToDac = now
        if (p.playbackState == Player.STATE_IDLE || p.currentMediaItem == null) return
        val at = p.currentPosition; val index = p.currentMediaItemIndex; val go = p.playWhenReady
        p.stop(); p.prepare(); p.seekTo(index, at); p.playWhenReady = go
    }
    private val usbSeen = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            if (i.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED) UsbDac.release()
            android.util.Log.i("ink-usb", "seen: ${i.action}")
            usbOutput?.changed()
            reroute()
        }
    }
    /** Exact's word for a format: ink music's own way to the DAC where it holds one, else Android's bit-perfect path asked. */
    private fun exactFor(format: androidx.media3.common.Format): String {
        if (Exact.on(this) && UsbDac.hold(this)?.altFor(format.sampleRate, format.channelCount.coerceAtLeast(1)) != null)
            return "yes ${Heard.bitsOf(format).takeIf { it > 0 } ?: 16}/${Heard.khz(format.sampleRate)}"
        return Exact.request(this, format)
    }
    private var exactSaid = ""

    // radio: on, the queue's last songs bring more like them (Radio); asked once per seed
    private var radio = true
    /** The songs being added were asked to play next (the screens mark them): read once by [Shuffle]. */
    private var playNext = false
    private var radioFrom: String? = null
    /** Songs radio added (this run): not seeds for the next round while there are songs you chose. */
    private val fromRadio = HashSet<String>()

    /** One song or none left (repeat off): ten more like the last, added at the end. */
    private fun topUp(p: Player) {
        if (!radio || p.repeatMode != Player.REPEAT_MODE_OFF || p.mediaItemCount == 0) return
        // casting, the speaker plays the list in order (shuffle is the phone's own): its end is the list's end. Going
        // by the shuffled order, the speaker ran out while the phone thought songs were left (2026-10-28)
        val inOrder = onSpeaker?.casting == true
        fun after(i: Int) = if (inOrder) (i + 1).takeIf { it < p.mediaItemCount } ?: C.INDEX_UNSET
            else p.currentTimeline.getNextWindowIndex(i, p.repeatMode, p.shuffleModeEnabled)
        val next = if (inOrder) after(p.currentMediaItemIndex) else p.nextMediaItemIndex
        val after = if (next == C.INDEX_UNSET) C.INDEX_UNSET else after(next)
        if (after != C.INDEX_UNSET) return
        val last = p.getMediaItemAt(if (next != C.INDEX_UNSET) next else p.currentMediaItemIndex)
        // a station plays on forever: nothing to follow it
        // nor a podcast's episode: no songs like it to bring
        if (radioFrom == last.mediaId || last.mediaId.startsWith("s") || last.mediaId.startsWith("e")) return
        radioFrom = last.mediaId
        fun seed(i: androidx.media3.common.MediaItem) =
            Radio.Seed(i.mediaId, i.mediaMetadata.title?.toString().orEmpty(), i.mediaMetadata.artist?.toString().orEmpty())
        val queued = HashSet<String>()
        for (i in 0 until p.mediaItemCount) p.getMediaItemAt(i).let {
            queued += it.mediaId
            queued += Radio.key(it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.artist?.toString().orEmpty())
        }
        // the last song, and the last two before it you chose (radio's own passed over): where the vibe is
        val at = (0 until p.mediaItemCount).indexOfLast { p.getMediaItemAt(it).mediaId == last.mediaId }
        val chosen = (at - 1 downTo 0).map { p.getMediaItemAt(it) }
            .filter { it.mediaId !in fromRadio && !it.mediaId.startsWith("s") && it.mediaId != last.mediaId }
            .distinctBy { it.mediaId }.take(2)
        val seeds = listOf(seed(last)) + chosen.map(::seed)
        val firstId = p.getMediaItemAt(0).mediaId
        net.execute {
            // a mix's queue (its first song the mix's): radio keeps to its tempo
            val tempo = Mixes.keeping(this, firstId)
            val more = Radio.after(this, seeds, queued, tempo)
            android.util.Log.d("ink-radio", "after ${seeds.joinToString(" + ") { it.title + " - " + it.artist }}: ${more.size} (${more.joinToString { it.title + " - " + it.artist }})")
            if (more.isEmpty()) return@execute
            main.post {
                if (!radio) return@post
                val ended = p.playbackState == Player.STATE_ENDED
                fromRadio += more.map { it.id }
                p.addMediaItems(more.map { Playback.item(it) })
                // it ran out while asking: on into the first of them
                if (ended) { p.seekTo(p.mediaItemCount - more.size, 0); p.prepare(); p.play() }
            }
        }
    }

    /** Sleep's end: the music eases out over 6 s, pauses, and its volume is put back for next time. */
    /** The player itself (the session talks to it through [Soft]). */
    private var exo: ExoPlayer? = null

    /**
     * Pause, play and stop, eased (a setting, on by default; off, a file says so: both processes read it): the sound
     * down to nothing over a quarter second before a pause or a stop, and up from nothing over a third after a play,
     * from wherever they're asked (the app, the notification, a headset, the car). Even volume's level is where it
     * rises to and falls from.
     */
    private inner class Soft(p: ExoPlayer) : androidx.media3.common.ForwardingPlayer(p) {
        private var fading = 0   // the fade in hand; a newer one ends it
        // fading out to a pause: told as paused already, so a screen hears "paused" at once, not "playing" with each
        // step of the fade (casting took that for the phone asked to play, paused it again, and the fade began anew:
        // the phone went on, ever quieter, for many seconds under the speaker)
        private var leaving = false
        // fading out to a stop: told as stopped already (the mini player went, came back with the fade's steps, went)
        private var ending = false

        private fun on() = !java.io.File(filesDir, "soft-off").exists()

        private fun fade(from: Float, to: Float, ms: Long, then: () -> Unit = {}) {
            val mine = ++fading
            leaving = false; ending = false
            val start = android.os.SystemClock.uptimeMillis()
            main.post(object : Runnable {
                override fun run() {
                    if (mine != fading) return
                    val t = ((android.os.SystemClock.uptimeMillis() - start) / ms.toFloat()).coerceIn(0f, 1f)
                    // an equal-power curve: no dip or bump in loudness on the way
                    val k = kotlin.math.sin(t * Math.PI / 2).toFloat()
                    volume = from + (to - from) * k
                    if (t < 1f) main.postDelayed(this, 16) else then()
                }
            })
        }

        private fun level() = levelling?.level ?: 1f

        override fun getPlayWhenReady() = !leaving && !ending && super.getPlayWhenReady()
        override fun getPlaybackState() = if (ending) Player.STATE_IDLE else super.getPlaybackState()

        override fun pause() {
            if (leaving) return   // already on its way
            // (a stop's fade cut short by this pause: it's over, so the player isn't left saying it's ending forever)
            if (!on() || !isPlaying) { fading++; leaving = false; ending = false; return super.pause() }
            fade(volume, 0f, 250) { leaving = false; super.pause(); volume = level() }
            leaving = true
        }

        /** Stop: faded out, let go, and back to the song's start (rewound once quiet: before, its first moment was heard). */
        override fun stop() {
            // stop asked again while one's fading (or one cut short, which left `ending` on and every stop after
            // ignored: the TV's stop did nothing, 2026-11-04): stopped now
            if (ending) { fading++; ending = false; leaving = false; super.stop(); super.seekTo(currentMediaItemIndex, 0); volume = level(); return }
            prefs.edit().putBoolean("stopped", true).apply()
            if (!on() || !isPlaying) { fading++; leaving = false; super.stop(); return super.seekTo(currentMediaItemIndex, 0) }
            // stopped now in truth: no longer told so ahead of time
            fade(volume, 0f, 250) { ending = false; super.stop(); super.seekTo(currentMediaItemIndex, 0); volume = level() }
            ending = true
        }

        override fun play() {
            prefs.edit().putBoolean("stopped", false).apply()
            if (!on()) { fading++; leaving = false; ending = false; return super.play() }
            // still fading out to a pause: that pause called off, the sound brought back
            if (isPlaying) { fade(volume, level(), 200); return }
            volume = 0f
            super.play()
            fade(0f, level(), 350)
        }
    }

    private fun fadeOut(p: Player) {
        val steps = 30
        var i = 0
        main.post(object : Runnable {
            override fun run() {
                i++
                val level = levelling?.level ?: 1f
                p.volume = level * (1f - i.toFloat() / steps).coerceAtLeast(0f)
                if (i < steps) main.postDelayed(this, 200)
                else { p.pause(); p.volume = level; setSleep(p, 0) }
            }
        })
    }

    /** Each stream's quality as chosen when the song began (Quality), when, and on mobile data or not: an hour, then chosen anew. */
    private data class Chosen(val quality: Int, val at: Long, val metered: Boolean)
    private val chosen = java.util.concurrent.ConcurrentHashMap<String, Chosen>()
    // Preferred remote source for each original media URI; on failure, try YouTube, then the original source.
    private data class Preferred(
        val original: Uri,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val fromAddon: Boolean,
        val streamUrl: String,
    )
    private val preferred = java.util.concurrent.ConcurrentHashMap<String, Preferred>()
    private val sourceMisses = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun mediaFor(uri: Uri): MediaItem? {
        val player = exo ?: return null
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            if (item.localConfiguration?.uri == uri || item.requestMetadata.mediaUri == uri) return item
        }
        return null
    }

    private suspend fun youtubeMatch(title: String, artist: String, durationMs: Long): Song? {
        if (!YouTubeMusic.available || title.isBlank()) return null
        val found = YouTubeMusic.search("$title $artist", all = true)
        fun norm(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val wantedTitle = norm(title)
        val wantedArtist = norm(artist.substringBefore(',').trim())
        return found.songs.mapNotNull { song ->
            if (norm(song.title) != wantedTitle) return@mapNotNull null
            val foundArtist = norm(song.artist)
            if (wantedArtist.isNotEmpty() && foundArtist != wantedArtist &&
                !foundArtist.contains(wantedArtist) && !wantedArtist.contains(foundArtist)) return@mapNotNull null
            val difference = if (durationMs > 0 && song.duration > 0) kotlin.math.abs(song.duration - durationMs) else 0L
            if (difference > 15_000) return@mapNotNull null
            song to difference
        }.minByOrNull { it.second }?.first
    }

    // YouTube songs found again after their stream failed, and how often (onPlayerError)
    private val retried = HashMap<String, Int>()

    /**
     * A song stopped by the network (2026-11-09: the S26 just reinstalled, its screen locked and its network not up yet:
     * a YouTube song tried three times in six seconds, then sat in its error for good): once the phone's online again,
     * the song tried anew from where it stopped, its tries counted afresh; played on if it was meant to play. Once.
     */
    private var waiting: android.net.ConnectivityManager.NetworkCallback? = null
    /** The songs tried anew once online already (once each: a song that fails online too isn't the network's). */
    private val triedOnline = HashSet<String>()
    private fun againWhenOnline() {
        if (waiting != null) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        // only while the phone's really offline: online, the failure's the song's (Android tells a callback the network
        // there is at once, and it would be tried again and again)
        val online = runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }.getOrDefault(true)
        val id = session?.player?.currentMediaItem?.mediaId ?: return
        if (online || id in triedOnline) return
        triedOnline += id
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                main.post {
                    val cb = waiting ?: return@post
                    waiting = null
                    runCatching { cm.unregisterNetworkCallback(cb) }
                    val player = session?.player ?: return@post
                    if (player.playerError == null) return@post
                    player.currentMediaItem?.localConfiguration?.uri?.takeIf { it.scheme == "ytm" }?.let { retried.remove("y" + it.host) }
                    android.util.Log.d("ink-youtube", "online again: ${player.currentMediaItem?.mediaId} tried anew")
                    player.prepare()
                }
            }
        }
        waiting = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onFailure { waiting = null }
    }
    /** Each song's stream format as the player read it (by media id): a song's comes in a little before it plays. */
    private val formats = java.util.concurrent.ConcurrentHashMap<String, androidx.media3.common.Format>()

    override fun onCreate() {
        super.onCreate()
        // this process's WebView keeps its own data (YouTube's poToken runs one here, and the
        // screens' process may run another: two processes sharing one data folder crash)
        runCatching { android.webkit.WebView.setDataDirectorySuffix("player") }
        YouTubeMusic.attach(this)
        // the first song's way made ready now: YouTube Music's page, the player's code, the solver (it had cost that song 1-2 s)
        ahead.execute { YouTubeMusic.warmUp(this) }
        Pods.start(this)
        // stopped (the player's "stop"), the notification goes; Media3 kept it after a stop by default
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        Stations.curated(this)   // ink radio's stations, known by id (the car, the queue kept)
        // a YouTube Music song plays from "ytm://<id>": its stream found as it starts (the full build only);
        // a Navidrome song's stream as the quality asks for the network it starts on (Quality)
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            androidx.media3.datasource.ResolvingDataSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(this)) { spec ->
                // chosen once a song: a seek opens it again partway, and must get the same stream
                fun quality(): Int {
                    val key = spec.uri.toString()
                    val now = android.os.SystemClock.elapsedRealtime()
                    chosen[key]?.takeIf { now - it.at < 3_600_000L }?.let { return it.quality }
                    if (chosen.size > 500) chosen.clear()
                    val metered = Quality.metered(this)
                    return Quality.now(this).also { chosen[key] = Chosen(it, now, metered) }
                }
                // Addon is preferred for every song, no matter which page or library it came from.
                // If the addon has no match, the full build tries YouTube before the original source.
                val original = spec.uri
                val preferredSpec = if (original.scheme != "addon" && AddonMusic.baseUrl(this).isNotBlank()) {
                    runCatching {
                        val item = mediaFor(original)
                        if (item == null || item.mediaId.startsWith("s") || item.mediaId.startsWith("e")) null
                        else {
                            val key = original.toString()
                            val now = android.os.SystemClock.elapsedRealtime()
                            val cached = preferred[key]
                            when {
                                cached != null -> spec.buildUpon().setUri(Uri.parse(cached.streamUrl))
                                    .setHttpRequestHeaders(spec.httpRequestHeaders + if (cached.fromAddon) AddonMusic.signedHeaders(this, cached.streamUrl) else emptyMap()).build()
                                sourceMisses[key]?.let { it > now } == true -> null
                                else -> {
                                    val md = item.mediaMetadata
                                    val title = md.title?.toString().orEmpty()
                                    val artist = md.artist?.toString().orEmpty()
                                    val album = md.albumTitle?.toString().orEmpty()
                                    val duration = Library.song(item.mediaId)?.duration ?: 0L
                                    val addonUrl = AddonMusic.findStream(this, title, artist, album, duration / 1000)
                                    if (addonUrl != null) {
                                        preferred[key] = Preferred(original, title, artist, album, duration, true, addonUrl)
                                        android.util.Log.i("ink-addon", "playing $title from addon")
                                        spec.buildUpon().setUri(Uri.parse(addonUrl))
                                            .setHttpRequestHeaders(spec.httpRequestHeaders + AddonMusic.signedHeaders(this, addonUrl)).build()
                                    } else if (original.scheme != "ytm" && YouTubeMusic.available) {
                                        val yt = kotlinx.coroutines.runBlocking(Dispatchers.IO) { youtubeMatch(title, artist, duration) }
                                        val ytUrl = yt?.let { YouTubeMusic.stream(it.id, false, small = quality() == Quality.SAVER)?.first }
                                        if (ytUrl != null) {
                                            preferred[key] = Preferred(original, title, artist, album, duration, false, ytUrl)
                                            android.util.Log.i("ink-addon", "addon miss; playing $title from YouTube Music")
                                            spec.withUri(Uri.parse(ytUrl))
                                        } else {
                                            sourceMisses[key] = now + 300_000L
                                            null
                                        }
                                    } else {
                                        sourceMisses[key] = now + 300_000L
                                        null
                                    }
                                }
                            }
                        }
                    }.onFailure { android.util.Log.w("ink-addon", "source selection failed: ${it.message}") }.getOrNull()
                } else null
                when {
                    // A BitChord addon search result resolves directly through its stored addon id.
                    original.scheme == "addon" -> {
                        val url = AddonMusic.stream(this, original.toString().removePrefix("addon://").substringBefore('/'))
                        spec.buildUpon().setUri(Uri.parse(url))
                            .setHttpRequestHeaders(spec.httpRequestHeaders + AddonMusic.signedHeaders(this, url)).build()
                    }
                    preferredSpec != null -> preferredSpec
                    // a YouTube song kept on the phone plays from there, no signal needed (Offline)
                    spec.uri.scheme == "ytm" && Offline.file(this, "y" + spec.uri.host) != null ->
                        spec.withUri(Uri.fromFile(Offline.file(this, "y" + spec.uri.host)!!)).also { android.util.Log.d("ink-offline", "kept: y${spec.uri.host}") }
                    spec.uri.scheme == "ytm" -> spec.withUri(Uri.parse(
                        YouTubeMusic.stream("y" + spec.uri.host, false, small = quality() == Quality.SAVER)?.first
                            ?: throw java.io.IOException("no stream")))
                    // a server song kept on the phone plays from there, no signal needed (Offline)
                    spec.uri.path?.endsWith("/rest/stream") == true -> spec.uri.getQueryParameter("id")?.let { Offline.file(this, "n$it") }
                        ?.let { android.util.Log.d("ink-offline", "kept: ${it.name}"); spec.withUri(Uri.fromFile(it)) }
                        ?: spec.withUri(Quality.server(spec.uri, quality()))
                    // a podcast's episode kept on the phone plays from there (Offline: kept by its id, known by its link)
                    spec.uri.scheme?.startsWith("http") == true && Podcasts.idOf(spec.uri.toString())?.let { Offline.file(this, it) } != null ->
                        Podcasts.idOf(spec.uri.toString())?.let { Offline.file(this, it) }
                            ?.let { android.util.Log.d("ink-offline", "kept: ${it.name}"); spec.withUri(Uri.fromFile(it)) } ?: spec
                    else -> spec
                }
            },
        )
        // 24 bit kept 24 bit (2026-11-01): the decoders give
        // float, the song's whole depth, and float goes to Android (Media3's default made every song 16 bit). Media3
        // can't change speed on float itself, so an episode's speed is Android's audio track's (1x for music: untouched)
        // the crossfade made in the player's own sound (Blend; Crossfade cuts the songs and hands it their ends)
        val blend = Blend().also { it.dumpDir = getExternalFilesDir("blends") }
        // and where the samples go: a USB DAC ink music holds itself (exact on: UsbDac), else Android's audio
        val usb = UsbOutputProvider(this, androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider.Builder(this).build()).also { usbOutput = it }
        // a DAC plugged in, out, or allowed: Media3 asks again where each format goes (one gone, let go of first)
        androidx.core.content.ContextCompat.registerReceiver(this, usbSeen, android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbDac.ALLOWED)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean) =
                // every song at 48 kHz while transitions are on (exact off), before the output sees it (OneRate): a change
                // of rate stopped the sound a moment, and after a jump threw the output's clock out
                OneRate(androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioOutputProvider(usb)
                    .setAudioProcessors(arrayOf(blend, Tap(blend)))
                    .build()) { Crossfade.on(this@PlayerService) && !Exact.inUse(this@PlayerService) }
            // the decoder asked for float only for a file deeper than 16 bit: a 16 bit or lossy one stays 16 bit (as
            // exact: the same numbers), and goes Media3's 16 bit way, where its processors are (an episode's silence
            // skipping; they're never on float)
            override fun buildAudioRenderers(
                context: android.content.Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean, audioSink: androidx.media3.exoplayer.audio.AudioSink, eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener, out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                out += object : androidx.media3.exoplayer.audio.MediaCodecAudioRenderer(
                    context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink,
                ) {
                    // each jump, where it landed (ink-seek: what scrubbing does to a blend, 2026-11-10)
                    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
                        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
                        android.util.Log.d("ink-seek", "jump to ${positionUs / 1000} ms (joining $joining)")
                    }
                    override fun getMediaFormat(format: androidx.media3.common.Format, codecMimeType: String, codecMaxInputSize: Int, codecOperatingRate: Float) =
                        super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate).also { mf ->
                            // and with transitions on (no exact to a DAC), a deeper file 16 bit too: Media3's processors are
                            // never on float, and the blend is one of them (24 bit songs never crossfaded, 2026-11-07); every
                            // song's 48 kHz while transitions are on anyway
                            val blending = Crossfade.on(this@PlayerService) && !Exact.inUse(this@PlayerService)
                            if ((Heard.bitsOf(format) <= 16 || blending) && mf.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)) mf.removeKey(android.media.MediaFormat.KEY_PCM_ENCODING)
                        }
                }
            }
        }.setEnableAudioFloatOutput(true).setEnableAudioOutputPlaybackParameters(true)
            // Android's own decoders first: Samsung's flac decoder, asked for float, says float and gives 16 bit (read
            // as float it was loud crackle, and Media3 counted half the time; a KA11, 2026-11-01)
            .setMediaCodecSelector { mime, secure, tunneling ->
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                    .sortedBy { if (mime.startsWith("audio/") && !it.name.startsWith("c2.android.")) 1 else 0 }
            }
        // a playing song's end can be moved where it plays (Crossfade cuts the song begun on, and one skipped to, for a
        // blend: 2026-11-11, "a song doesn't get a tick until the 3rd song"): clipped in its period, and every song
        // wrapped for clipping from the start (a song without a clipping can't be given one in place: it would restart)
        sources.setEnableClippingInMediaPeriod(true)
        val clippable = object : androidx.media3.exoplayer.source.MediaSource.Factory by sources {
            override fun createMediaSource(mediaItem: MediaItem) = sources.createMediaSource(
                if (mediaItem.clippingConfiguration != MediaItem.ClippingConfiguration.UNSET || !Crossfade.cuttable(mediaItem.mediaId)) mediaItem
                else mediaItem.buildUpon().setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setRelativeToDefaultPosition(true).build()).build())
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(clippable)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)   // headphones out: pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // The resolving data source needs the timeline from the very first prepare (including a restored queue).
        exo = player
        // what's added with shuffle on comes next or last, not anywhere
        player.setShuffleOrder(Shuffle(IntArray(0), next = { playNext.also { playNext = false } }))
        // the queue as it was left: there, paused, ready to go on
        // the queue as it was left; stopped (stop pressed) it stays stopped, the mini player away till something's chosen
        // (it came back paused whenever ink music started again)
        crossfade = Crossfade(this, player, sources, { levelling }, { onSpeaker?.casting == true }, blend, { playNext = true },
            { order -> player.setShuffleOrder(Shuffle(order, next = { playNext.also { playNext = false } })) }).also { it.onChange = { main.post { levelling?.apply(); tell() } }; it.start() }
        // the song left playing cut where it hands over, as one chosen is (a restart's song never blended, 2026-11-08)
        kept()?.let { (items, index, at) -> player.setMediaItems(crossfade?.precut(items, index, at.coerceAtLeast(0)) ?: items, index, at); if (!prefs.getBoolean("stopped", false)) player.prepare() }
        player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        levelling = Levelling(player, this, blend).also { it.measured = { id -> crossfade?.lufs(id) }; it.peakOf = { id -> crossfade?.peak(id) };  it.on = prefs.getBoolean("levelling", true) && !Exact.on(this); it.onApply = { tell() } }
        // a song ending goes into the next (settings, sound: crossfade)
        // what reaches your ears (Heard): each song's format as the player reads it, by the song it's for
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            // jumps, and the sound's clock caught out (ink-seek: what scrubbing near a song's end does to a blend)
            override fun onPositionDiscontinuity(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                android.util.Log.d("ink-seek", "discontinuity ($reason): ${old.mediaItem?.mediaId} ${old.positionMs} ms -> ${new.mediaItem?.mediaId} ${new.positionMs} ms")
            }
            override fun onAudioSinkError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioSinkError: Exception) {
                android.util.Log.d("ink-seek", "sink: ${audioSinkError.message} at ${eventTime.currentPlaybackPositionMs} ms")
            }
            override fun onAudioInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                if (eventTime.timeline.isEmpty) return
                val id = eventTime.timeline.getWindow(eventTime.windowIndex, androidx.media3.common.Timeline.Window()).mediaItem.mediaId
                if (formats.size > 50) formats.clear()
                // without its tags (a file's embedded cover among them, 0.7 MB in a heap read 2026-11-11): only its rate,
                // depth and codec are asked of it here
                formats[id] = format.buildUpon().setMetadata(null).build()
                // exact to a usb dac: asked for this format before the player opens Android's track for it
                exactSaid = exactFor(format)
                tell()
            }

            // the signal path: which decoder, and what the player sends Android
            override fun onAudioDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long,
            ) { decoder = decoderName; tell() }

            override fun onAudioTrackInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
            ) {
                outBits = when (audioTrackConfig.encoding) {
                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 16; android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                    android.media.AudioFormat.ENCODING_PCM_8BIT -> 8; else -> 0
                }
                outRate = audioTrackConfig.sampleRate
                tell()
            }
        })
        clock = History.Clock(this).also { it.song(player.currentMediaItem, false) }
        // the widget shows the queue as it was left (paused), till the music says otherwise
        player.currentMediaItem?.let { Widget.show(this, it.mediaId, it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.artist?.toString().orEmpty(), false) }
        radio = prefs.getBoolean("radio", true)
        onAir = OnAir(player, main, net)
        // a paired computer told what plays (Sync): the session's player, so a speaker's what's told and paused
        live = Live(this, { onSpeaker }, { onSpeaker?.device.orEmpty() })
        player.addListener(object : Player.Listener {
            // a station's song, as its stream says it
            override fun onMetadata(metadata: androidx.media3.common.Metadata) { onAir?.metadata(metadata) }

            // a YouTube song's stream failing (turned away, its link run out, the network dropped): found again, signed
            // out, and played on from where it stopped, twice a song; the player had stopped on the error, and on
            // phones where the signed stream fails no YouTube song played (1.4.5)
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // the network's fault (any song's): tried again once it's back, whatever the tries below came to
                if (error.errorCode in 2000 until 3000) againWhenOnline()
                val current = player.currentMediaItem ?: return
                val uri = current.localConfiguration?.uri ?: return
                val selected = preferred.remove(uri.toString())
                if (selected != null) {
                    sourceMisses[uri.toString()] = android.os.SystemClock.elapsedRealtime() + 300_000L
                    val failedId = current.mediaId
                    val failedIndex = player.currentMediaItemIndex
                    android.util.Log.w("ink-addon", "${selected.title}: ${if (selected.fromAddon) "addon" else "YouTube"} stream failed; trying ${if (selected.fromAddon) "YouTube Music" else "original source"}")
                    work.launch {
                        val yt = if (selected.fromAddon) runCatching { youtubeMatch(selected.title, selected.artist, selected.durationMs) }.getOrNull() else null
                        if (player.currentMediaItem?.mediaId != failedId || player.currentMediaItemIndex != failedIndex) return@launch
                        if (yt != null) {
                            YouTubeMusic.remember(yt)
                            val targetUri = yt.uri
                            sourceMisses[targetUri.toString()] = android.os.SystemClock.elapsedRealtime() + 300_000L
                            player.replaceMediaItem(failedIndex, Playback.item(yt))
                            android.util.Log.i("ink-addon", "fallback to YouTube Music: ${yt.title}")
                        } else {
                            android.util.Log.w("ink-addon", "${selected.title}: no YouTube match; using original source")
                        }
                        player.prepare()
                    }
                    return
                }
                if (uri.scheme != "ytm" || error.errorCode !in 2000 until 3000) return
                val id = "y" + uri.host
                val tries = (retried[id] ?: 0) + 1
                android.util.Log.d("ink-youtube", "$id failed playing (${error.errorCodeName}), try $tries")
                if (tries > 2) return
                retried[id] = tries
                YouTubeMusic.refused(id)
                player.prepare()
            }

            override fun onEvents(p: Player, events: Player.Events) {
                // a seek too, so the others' lines jump with it; and ready, when a song's length is known at last (a
                // YouTube stream's isn't as it begins: told only then, the others' bars said 0:00 for the whole song)
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_STATE_CHANGED)) live.changed()
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) onAir?.changed()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) keep(p)
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) tell()   // the new song's format, if it's read
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                    val i = p.nextMediaItemIndex
                    val next = if (i == C.INDEX_UNSET) null else p.getMediaItemAt(i).localConfiguration?.uri
                    if (next?.scheme == "ytm") ahead.execute { YouTubeMusic.stream("y" + next.host, false, small = Quality.now(this@PlayerService) == Quality.SAVER) }
                }
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    told = null; startedAt = System.currentTimeMillis(); nowSaid = null; tubeTold = null
                    p.currentMediaItem?.mediaId?.takeIf { it.startsWith("n") && !Private.on(this@PlayerService) }?.let { id -> net.execute { Server.scrobble(this@PlayerService, id, done = false) } }
                }
                // playing now, on ListenBrainz: once a song is actually playing (its length known by then)
                if (p.isPlaying && !Private.on(this@PlayerService)) p.currentMediaItem?.takeIf { !it.mediaId.startsWith("s") && !it.mediaId.startsWith("e") && it.mediaId != nowSaid }?.let { item ->
                    nowSaid = item.mediaId
                    val said = said(item, p.duration.coerceAtLeast(0))
                    net.execute { ListenBrainz.playingNow(this@PlayerService, said); LastFm.playingNow(this@PlayerService, said) }
                }
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) { place(p); clock?.playing(p.isPlaying); episodePlace(p) }
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) { episodeBegins(p); skipTalking(p) }
                // the widget: what plays, and whether
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                    val md = p.mediaMetadata
                    Widget.show(this@PlayerService, p.currentMediaItem?.mediaId, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), p.playWhenReady)
                }
                // the song before written down for your year, if it was heard
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) clock?.song(p.currentMediaItem, p.isPlaying)
                // a new song, its tags read, the songs round it changed (a record in order or not)
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TRACKS_CHANGED, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_AUDIO_SESSION_ID)) levelling?.apply()
                // the phone's equalizer (and equalizer apps) told of ink music's sound, as a music app should
                if (events.contains(Player.EVENT_AUDIO_SESSION_ID)) { effects(true); equalize(); tell() }
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) topUp(p)
                if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED))
                    prefs.edit().putBoolean("shuffle", p.shuffleModeEnabled).putInt("repeat", p.repeatMode).apply()
            }

            // leaving an episode: where it was left kept, or heard through when it played to its end
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                val id = old.mediaItem?.mediaId?.takeIf { it.startsWith("e") } ?: return
                if (old.mediaItem?.mediaId == new.mediaItem?.mediaId) return
                val through = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                net.execute { if (through) Podcasts.setPlace(id, -1) else keepPlace(id, old.positionMs) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                player.currentMediaItem?.mediaId?.takeIf { it.startsWith("e") }?.let { id -> net.execute { Podcasts.setPlace(id, -1) } }
            }

            // sleeping at the song's end: it paused there, so the timer's done
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAt == SLEEP_END_OF_SONG)
                    session?.player?.let { setSleep(it, 0) }
            }
        })
        // Discord presence observes the player directly in this process; it never gates audio.
        DiscordAndroidPresence.start(this, player)
        main.post(keepPlace)
        main.post(dacSays)
        main.post { effects(true); equalize(); tell() }   // the session's there once onCreate is done
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, Main::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        exo = player
        val onSpeaker = OnSpeaker(Soft(player)) { b ->
            val s = session ?: return@OnSpeaker
            val c = caster ?: return@OnSpeaker
            s.sendCustomCommand(c, SessionCommand(CAST_ASK, Bundle.EMPTY), b)
        }.also { onSpeaker = it; val o = it; UsbDac.onChange = { o.dacChanged() } }
        session = MediaLibrarySession.Builder(this, onSpeaker, object : MediaLibrarySession.Callback {
                // Android Auto's library: records, artists, lists ([Auto])
                override fun onGetLibraryRoot(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(Auto.root(), params))

                override fun onGetChildren(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
                    val all = Auto.children(this@PlayerService, parentId)
                    val shown = if (pageSize in 1 until Int.MAX_VALUE) all.drop(page * pageSize).take(pageSize) else all
                    LibraryResult.ofItemList(shown, params)
                }

                override fun onGetItem(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
                ): ListenableFuture<LibraryResult<MediaItem>> = later {
                    val song = Auto.play(this@PlayerService, mediaId)?.let { (songs, at) -> songs[at] }
                    if (song != null) LibraryResult.ofItem(Playback.item(song), null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }

                override fun onSearch(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<Void>> = later {
                    Shelf.ready(this@PlayerService)
                    session.notifySearchResultChanged(browser, query, Auto.searchResults(query).size, params)
                    LibraryResult.ofVoid()
                }

                override fun onGetSearchResult(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
                    Shelf.ready(this@PlayerService)
                    val all = Auto.searchResults(query)
                    LibraryResult.ofItemList(if (pageSize in 1 until Int.MAX_VALUE) all.drop(page * pageSize).take(pageSize) else all, params)
                }

                // a song tapped in the car plays its record (or artist, or list) from there; "play ... on
                // ink music" plays what's found; a screen's queue as it comes
                override fun onSetMediaItems(
                    mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = later {
                    val one = mediaItems.singleOrNull()
                    val asked = one?.requestMetadata?.searchQuery
                    val tapped = one?.takeIf { it.requestMetadata.mediaUri == null }?.let { Auto.play(this@PlayerService, it.mediaId) }
                    when {
                        asked != null -> { Shelf.ready(this@PlayerService); Auto.found(asked).let { MediaSession.MediaItemsWithStartPosition(it.map(Playback::item), 0, 0) } }
                        tapped != null -> MediaSession.MediaItemsWithStartPosition(tapped.first.map(Playback::item), tapped.second, startPositionMs.coerceAtLeast(0))
                        // the song begun on cut where it was measured before, so it too goes into the next (Crossfade)
                        // (one never heard, on the phone, measured first: a moment at most)
                        else -> resolve(mediaItems).let { r ->
                            if (startPositionMs <= 5_000) crossfade?.measureFirst(r, startIndex.coerceAtLeast(0))
                            MediaSession.MediaItemsWithStartPosition(crossfade?.precut(r, startIndex, startPositionMs.coerceAtLeast(0)) ?: r, startIndex, startPositionMs)
                        }
                    }
                }

                // the sleep timer, a command of ink music's own
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(SessionCommand(SLEEP, Bundle.EMPTY))
                                .add(SessionCommand(LEVEL, Bundle.EMPTY)).add(SessionCommand(RADIO, Bundle.EMPTY))
                                .add(SessionCommand(WARM, Bundle.EMPTY)).add(SessionCommand(EQ, Bundle.EMPTY))
                                .add(SessionCommand(CAST, Bundle.EMPTY)).build(),
                        )
                        .build()

                // the screens gone while casting (closed, or killed): the session the phone's own again
                override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
                    if (controller == caster) { android.util.Log.d("ink-cast", "player: the screens gone"); caster = null; onSpeaker?.speaker(null) }
                }

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == SLEEP) setSleep(session.player, args.getLong(SLEEP_MS))
                    // casting: what the speaker does (OnSpeaker), or casting over
                    if (customCommand.customAction == CAST) {
                        // (and radio looked at again: the speaker's song may be the list's last, no song change here)
                        if (args.getBoolean("on")) { caster = controller; onSpeaker?.speaker(args); topUp(session.player) }
                        else { caster = null; onSpeaker?.speaker(null) }
                        live.changed()
                    }
                    // the equalizer changed on the screens: read again and applied
                    if (customCommand.customAction == EQ) {
                        equalize()
                        silence()
                        // exact switched: even volume rests or comes back (it stayed as it was till the player started
                        // again); the DAC held or let go, and Media3 asked again where each format goes
                        levelling?.on = prefs.getBoolean("levelling", true) && !Exact.on(this@PlayerService)
                        if (!Exact.on(this@PlayerService)) UsbDac.release(reset = true)
                        usbOutput?.changed()
                        reroute()
                        session.player.currentMediaItem?.mediaId?.let { formats[it] }?.let { exactSaid = exactFor(it) }
                        tell()
                    }
                    // a YouTube song about to be played (its page is up): its stream found now, so play starts at once
                    // only the latest asked is found: a remote moving along a shelf asks for each it rests on, and the
                    // older ones waiting would hold up the one played
                    if (customCommand.customAction == WARM) args.getString(WARM)?.takeIf { it.startsWith("y") }?.let { id ->
                        warmWanted = id
                        ahead.execute { if (warmWanted == id) YouTubeMusic.stream(id, false, small = Quality.now(this@PlayerService) == Quality.SAVER) }
                    }
                    if (customCommand.customAction == LEVEL) {
                        val on = args.getBoolean(LEVEL, true)
                        prefs.edit().putBoolean("levelling", on).apply()
                        levelling?.on = on && !Exact.on(this@PlayerService)
                    }
                    if (customCommand.customAction == RADIO) {
                        radio = args.getBoolean(RADIO, true)
                        prefs.edit().putBoolean("radio", radio).apply()
                        radioFrom = null
                        tell()
                        topUp(session.player)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                // a controller's items arrive without their address (Media3 keeps it from crossing
                // over): it rides in the request's metadata, put back here
                override fun onAddMediaItems(
                    session: MediaSession, controller: MediaSession.ControllerInfo, items: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    playNext = items.any { it.requestMetadata.extras?.getBoolean(NEXT) == true }
                    return later { resolve(items) }
                }

                // a headset's play, the system's resume card after a restart: the queue as it was left
                override fun onPlaybackResumption(
                    mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val (items, index, at) = kept() ?: return Futures.immediateFailedFuture(UnsupportedOperationException("nothing kept"))
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, index, at))
                }
            })
            .setSessionActivity(open)
            .build()
        tell()
    }

    companion object {
        const val SLEEP = "app.ink.music.SLEEP"
        const val SLEEP_MS = "ms"
        const val SLEEP_AT = "sleepAt"
        const val SLEEP_END_OF_SONG = -1L
        const val LEVEL = "app.ink.music.LEVEL"
        /** In an item's request extras: it was asked to play next (for [Shuffle]). */
        const val NEXT = "app.ink.music.NEXT"
        const val RADIO = "app.ink.music.RADIO"
        const val WARM = "app.ink.music.WARM"
        const val HEARD = "heard"
        /** The transitions, said for the player page (Mix: this song's mix point, the blend in hand). */
        const val MIX = "mix"
        const val AUDIO = "audioSession"
        const val EQ = "app.ink.music.EQ"
        /** The screens to the player: what a speaker does ("on", "playing", "moving", "position", "duration", "id", "volume"). */
        const val CAST = "app.ink.music.CAST"
        /** The player to the screens: an ask for the speaker ("what": play, pause, next, previous, seek "ms", jump "id", stop, volume "level"). */
        const val CAST_ASK = "app.ink.music.CAST_ASK"
        const val TOKEN = "token"
        /** A speaker's volume in steps of 5%, each press of the phone's buttons one. */
        const val STEPS = 20
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** The queue: each song's id, address and words, which is playing, and where. */
    private fun keep(p: Player) {
        val list = JSONArray()
        for (i in 0 until p.mediaItemCount) {
            val item = p.getMediaItemAt(i)
            val md = onAir?.plain(item.mediaId, item.mediaMetadata) ?: item.mediaMetadata
            val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: continue
            list.put(JSONObject().put("id", item.mediaId).put("uri", uri.toString())
                .put("title", md.title?.toString().orEmpty()).put("artist", md.artist?.toString().orEmpty())
                .put("album", md.albumTitle?.toString().orEmpty()).put("albumArtist", md.albumArtist?.toString().orEmpty())
                .put("art", md.artworkUri?.toString().orEmpty())
                .apply {
                    md.extras?.let { x ->
                        x.getFloat(Levelling.TRACK_GAIN, Float.NaN).takeIf { !it.isNaN() }?.let { put("gain", it.toDouble()) }
                        x.getFloat(Levelling.ALBUM_GAIN, Float.NaN).takeIf { !it.isNaN() }?.let { put("albumGain", it.toDouble()) }
                        put("record", x.getString(Levelling.RECORD).orEmpty())
                    }
                })
        }
        prefs.edit().putString("items", list.toString()).putInt("index", p.currentMediaItemIndex).putLong("at", p.currentPosition).apply()
    }

    private fun place(p: Player) {
        if (p.mediaItemCount > 0) prefs.edit().putInt("index", p.currentMediaItemIndex).putLong("at", p.currentPosition).apply()
    }

    /** The queue as kept, or null (none, or unreadable). */
    private fun kept(): Triple<List<MediaItem>, Int, Long>? = runCatching {
        val a = JSONArray(prefs.getString("items", null) ?: return null)
        val items = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val uri = Uri.parse(o.getString("uri"))
            MediaItem.Builder().setMediaId(o.getString("id")).setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(o.optString("title")).setArtist(o.optString("artist"))
                    .setAlbumTitle(o.optString("album")).setAlbumArtist(o.optString("albumArtist")).setIsPlayable(true)
                    .setExtras(Bundle().apply {
                        o.optDouble("gain").takeIf { !it.isNaN() }?.let { putFloat(Levelling.TRACK_GAIN, it.toFloat()) }
                        o.optDouble("albumGain").takeIf { !it.isNaN() }?.let { putFloat(Levelling.ALBUM_GAIN, it.toFloat()) }
                        putString(Levelling.RECORD, o.optString("record"))
                    })
                    .apply { o.optString("art").takeIf { it.isNotEmpty() }?.let { setArtworkUri(Uri.parse(it)) } }.build())
                .build()
        }
        if (items.isEmpty()) null
        else Triple(items, prefs.getInt("index", 0).coerceIn(items.indices), prefs.getLong("at", 0L).coerceAtLeast(0))
    }.getOrNull()

    override fun onDestroy() {
        DiscordAndroidPresence.stop()
        waiting?.let { cb -> runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        runCatching { unregisterReceiver(usbSeen) }
        crossfade?.release()
        UsbDac.release()
        effects(false)
        runCatching { eq?.release() }
        main.removeCallbacksAndMessages(null)
        net.shutdown()
        session?.player?.let(::place)
        clock?.song(null, false)
        work.cancel()
        levelling?.release()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
