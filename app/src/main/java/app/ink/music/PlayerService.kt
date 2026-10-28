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
            session?.player?.let { if (it.isPlaying) { place(it); heard(it) } }
            main.postDelayed(this, 15_000)
        }
    }
    // a server song heard far enough (half of it, or four minutes, as Last.fm counts) is told to
    // the server once; "playing now" as it starts
    private val net = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** The next YouTube song's stream found while this one plays, so a skip doesn't wait for it. */
    private val ahead = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var told: String? = null
    private var nowSaid: String? = null
    private var tubeTold: String? = null

    private fun heard(p: Player) {
        val item = p.currentMediaItem ?: return
        val id = item.mediaId
        // a YouTube song 30 s in: told to YouTube Music, signed in (its history, its mixes)
        if (id.startsWith("y") && tubeTold != id && p.currentPosition >= History.ENOUGH) {
            tubeTold = id
            net.execute { YouTubeAccount.played(this, id) }
        }
        if (told == id || id.startsWith("s")) return
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
        val e = eq ?: return
        val st = Eq.read(this)
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
        )
    }

    // radio: on, the queue's last songs bring more like them (Radio); asked once per seed
    private var radio = true
    private var radioFrom: String? = null

    /** One song or none left (repeat off): ten more like the last, added at the end. */
    private fun topUp(p: Player) {
        if (!radio || p.repeatMode != Player.REPEAT_MODE_OFF || p.mediaItemCount == 0) return
        val next = p.nextMediaItemIndex
        val after = if (next == C.INDEX_UNSET) C.INDEX_UNSET else p.currentTimeline.getNextWindowIndex(next, p.repeatMode, p.shuffleModeEnabled)
        if (after != C.INDEX_UNSET) return
        val last = p.getMediaItemAt(if (next != C.INDEX_UNSET) next else p.currentMediaItemIndex)
        // a station plays on forever: nothing to follow it
        if (radioFrom == last.mediaId || last.mediaId.startsWith("s")) return
        radioFrom = last.mediaId
        val md = last.mediaMetadata
        val title = md.title?.toString().orEmpty()
        val artist = md.artist?.toString().orEmpty()
        val queued = HashSet<String>()
        for (i in 0 until p.mediaItemCount) p.getMediaItemAt(i).let {
            queued += it.mediaId
            queued += Radio.key(it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.artist?.toString().orEmpty())
        }
        net.execute {
            val more = Radio.after(this, last.mediaId, title, artist, queued)
            android.util.Log.d("ink-radio", "after $title - $artist: ${more.size} (${more.joinToString { it.title + " - " + it.artist }})")
            if (more.isEmpty()) return@execute
            main.post {
                if (!radio) return@post
                val ended = p.playbackState == Player.STATE_ENDED
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
            if (!on() || !isPlaying) { fading++; leaving = false; return super.pause() }
            fade(volume, 0f, 250) { leaving = false; super.pause(); volume = level() }
            leaving = true
        }

        /** Stop: faded out, let go, and back to the song's start (rewound once quiet: before, its first moment was heard). */
        override fun stop() {
            if (ending) return
            prefs.edit().putBoolean("stopped", true).apply()
            if (!on() || !isPlaying) { fading++; leaving = false; super.stop(); return super.seekTo(currentMediaItemIndex, 0) }
            fade(volume, 0f, 250) { super.stop(); super.seekTo(currentMediaItemIndex, 0); volume = level() }
            ending = true
        }

        override fun play() {
            prefs.edit().putBoolean("stopped", false).apply()
            if (!on()) { fading++; leaving = false; return super.play() }
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
    // YouTube songs found again after their stream failed, and how often (onPlayerError)
    private val retried = HashMap<String, Int>()
    /** Each song's stream format as the player read it (by media id): a song's comes in a little before it plays. */
    private val formats = java.util.concurrent.ConcurrentHashMap<String, androidx.media3.common.Format>()

    override fun onCreate() {
        super.onCreate()
        // this process's WebView keeps its own data (YouTube's poToken runs one here, and the
        // screens' process may run another: two processes sharing one data folder crash)
        runCatching { android.webkit.WebView.setDataDirectorySuffix("player") }
        YouTubeMusic.attach(this)
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
                when {
                    spec.uri.scheme == "ytm" -> spec.withUri(Uri.parse(
                        YouTubeMusic.stream("y" + spec.uri.host, false, small = quality() == Quality.SAVER)?.first
                            ?: throw java.io.IOException("no stream")))
                    // a server song kept on the phone plays from there, no signal needed (Offline)
                    spec.uri.path?.endsWith("/rest/stream") == true -> spec.uri.getQueryParameter("id")?.let { Offline.file(this, "n$it") }
                        ?.let { android.util.Log.d("ink-offline", "kept: ${it.name}"); spec.withUri(Uri.fromFile(it)) }
                        ?: spec.withUri(Quality.server(spec.uri, quality()))
                    else -> spec
                }
            },
        )
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)   // headphones out: pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // the queue as it was left: there, paused, ready to go on
        // the queue as it was left; stopped (stop pressed) it stays stopped, the mini player away till something's chosen
        // (it came back paused whenever ink music started again)
        kept()?.let { (items, index, at) -> player.setMediaItems(items, index, at); if (!prefs.getBoolean("stopped", false)) player.prepare() }
        player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        levelling = Levelling(player, this).also { it.on = prefs.getBoolean("levelling", true); it.onApply = { tell() } }
        // what reaches your ears (Heard): each song's format as the player reads it, by the song it's for
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                if (eventTime.timeline.isEmpty) return
                val id = eventTime.timeline.getWindow(eventTime.windowIndex, androidx.media3.common.Timeline.Window()).mediaItem.mediaId
                if (formats.size > 50) formats.clear()
                formats[id] = format
                tell()
            }
        })
        clock = History.Clock(this).also { it.song(player.currentMediaItem, false) }
        // the widget shows the queue as it was left (paused), till the music says otherwise
        player.currentMediaItem?.let { Widget.show(this, it.mediaId, it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.artist?.toString().orEmpty(), false) }
        radio = prefs.getBoolean("radio", true)
        onAir = OnAir(player, main, net)
        player.addListener(object : Player.Listener {
            // a station's song, as its stream says it
            override fun onMetadata(metadata: androidx.media3.common.Metadata) { onAir?.metadata(metadata) }

            // a YouTube song's stream failing (turned away, its link run out, the network dropped): found again, signed
            // out, and played on from where it stopped, twice a song; the player had stopped on the error, and on
            // phones where the signed stream fails no YouTube song played (1.4.5)
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val uri = player.currentMediaItem?.localConfiguration?.uri ?: return
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
                    p.currentMediaItem?.mediaId?.takeIf { it.startsWith("n") }?.let { id -> net.execute { Server.scrobble(this@PlayerService, id, done = false) } }
                }
                // playing now, on ListenBrainz: once a song is actually playing (its length known by then)
                if (p.isPlaying) p.currentMediaItem?.takeIf { !it.mediaId.startsWith("s") && it.mediaId != nowSaid }?.let { item ->
                    nowSaid = item.mediaId
                    val said = said(item, p.duration.coerceAtLeast(0))
                    net.execute { ListenBrainz.playingNow(this@PlayerService, said); LastFm.playingNow(this@PlayerService, said) }
                }
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) { place(p); clock?.playing(p.isPlaying) }
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

            // sleeping at the song's end: it paused there, so the timer's done
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAt == SLEEP_END_OF_SONG)
                    session?.player?.let { setSleep(it, 0) }
            }
        })
        main.post(keepPlace)
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
        }.also { onSpeaker = it }
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
                        else -> MediaSession.MediaItemsWithStartPosition(resolve(mediaItems), startIndex, startPositionMs)
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
                        if (args.getBoolean("on")) { caster = controller; onSpeaker?.speaker(args) }
                        else { caster = null; onSpeaker?.speaker(null) }
                    }
                    // the equalizer changed on the screens: read again and applied
                    if (customCommand.customAction == EQ) { equalize(); tell() }
                    // a YouTube song about to be played (its page is up): its stream found now, so play starts at once
                    if (customCommand.customAction == WARM) args.getString(WARM)?.takeIf { it.startsWith("y") }?.let { id ->
                        ahead.execute { YouTubeMusic.stream(id, false, small = Quality.now(this@PlayerService) == Quality.SAVER) }
                    }
                    if (customCommand.customAction == LEVEL) {
                        val on = args.getBoolean(LEVEL, true)
                        prefs.edit().putBoolean("levelling", on).apply()
                        levelling?.on = on
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
                ): ListenableFuture<MutableList<MediaItem>> = later { resolve(items) }

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
        const val RADIO = "app.ink.music.RADIO"
        const val WARM = "app.ink.music.WARM"
        const val HEARD = "heard"
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
