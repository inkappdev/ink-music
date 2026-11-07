package app.ink.music

import kotlin.concurrent.Volatile

/**
 * The phone and the computer kept as one (2026-10-29), over the home wifi,
 * no cloud: paired once by a six-digit code ([Handover]: the pair agree a key no one on the network can work out, and
 * keep it), then whenever the two are on the same wifi the phone calls the computer (`POST /sync`, `_inksync._tcp`)
 * and each hands the other what it hasn't got, sealed under that key (AES-GCM):
 *  - what you've heard (heard.jsonl): each asks for the other's lines from where it got to, a line it already has
 *    (the same start and title) passed over, a line it got from that one never sent back;
 *  - loves, recent searches, and the sign-ins (your server, ListenBrainz, Last.fm; YouTube Music's, the phone's to the
 *    computer): the latest change wins;
 *  - what each was playing, and where in it, to carry on with on the other ("continue here").
 * Shared (the iPhone's too); each app reads and writes its own parts of it.
 */
object Sync {
    const val SERVICE = "_inksync._tcp"
    /** The computer's port, the same each run where it's free (so the phone finds it again without looking). */
    const val PORT = 47823

    /** A device paired with this one: its id and name, the key the two keep, how far each got with the other's history. */
    data class Peer(val id: String, val name: String, val key: String, val gotHeard: Int = 0, val sentHeard: Int = 0,
                    val seen: Long = 0, val at: String = "", val hub: Boolean = false)

    // ---- this device and its pairs, kept in the app's folder (sync.json) ----

    class Kept(private val dir: File) {
        private val file get() = File(dir, "sync.json")
        private val guard = Guard()

        fun read(): JSONObject = guard.run { runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) }

        private fun write(o: JSONObject) = guard.run { Keep.write(file, o.toString()) }

        /** This device's own id, made once. */
        fun id(): String = read().optString("id").ifEmpty {
            val made = b64url(randomBytes(9))
            write(read().put("id", made)); made
        }

        fun peers(): List<Peer> = read().optJSONArray("peers")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Peer(it.getString("id"), it.optString("name"), it.getString("key"), it.optInt("gotHeard"), it.optInt("sentHeard"), it.optLong("seen"), it.optString("at"), it.optBoolean("hub"))
        } }.orEmpty()

        fun put(p: Peer) = guard.run {
            val o = read()
            val list = peers().filter { it.id != p.id } + p
            write(o.put("peers", JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("key", it.key)
                .put("gotHeard", it.gotHeard).put("sentHeard", it.sentHeard).put("seen", it.seen).put("at", it.at).put("hub", it.hub) })))
        }

        /**
         * Heard from `id` (a sealed message of its opened): in step with it as of now. Devices the phone introduced
         * (the TV and a computer) never call each other's /sync, only say what plays: this is how they're seen in step.
         * Kept at most once a minute (a phone's /watch comes every 25 s).
         */
        fun heard(id: String) = guard.run k@{
            val p = peers().firstOrNull { it.id == id } ?: return@k
            val now = nowMs()
            if (now - p.seen > 60_000) put(p.copy(seen = now))
        }

        fun forget(id: String) = guard.run {
            val o = read()
            write(o.put("peers", JSONArray(peers().filter { it.id != id }.map { JSONObject().put("id", it.id).put("name", it.name).put("key", it.key)
                .put("gotHeard", it.gotHeard).put("sentHeard", it.sentHeard).put("seen", it.seen).put("at", it.at).put("hub", it.hub) })))
        }

        /** Loves as last changed on either: song key to {on, at, song}. */
        fun loves(): JSONObject = read().optJSONObject("loves") ?: JSONObject()
        fun putLove(key: String, on: Boolean, at: Long, song: JSONObject) = guard.run {
            val o = read(); val l = o.optJSONObject("loves") ?: JSONObject()
            l.put(key, JSONObject().put("on", on).put("at", at).put("song", song))
            write(o.put("loves", l))
        }

        /**
         * The key two of this phone's devices share, made here once and kept (both are handed it by [intros]), so the
         * two tell each other what they play without a code of their own.
         */
        fun introKey(a: String, b: String): String = guard.run k@{
            val pair = listOf(a, b).sorted().joinToString("|")
            val o = read(); val keys = o.optJSONObject("intros") ?: JSONObject()
            keys.optString(pair).takeIf { it.isNotEmpty() }?.let { return@k it }
            val made = b64(randomBytes(32))
            write(o.put("intros", keys.put(pair, made)))
            made
        }

        /** When this device carried on with what `peer` was playing ("continue here"): that one stops, if it hasn't played since. */
        fun handedAt(peer: String): Long = read().optJSONObject("handed")?.optLong(peer) ?: 0L
        fun handed(peer: String, at: Long) = guard.run {
            val o = read(); write(o.put("handed", (o.optJSONObject("handed") ?: JSONObject()).put(peer, at)))
        }

        /** When each sign-in last changed here ("server", "listenbrainz", "lastfm", "youtube"). */
        fun signedAt(what: String): Long = read().optJSONObject("signed")?.optLong(what) ?: 0L
        fun signed(what: String, at: Long) = guard.run {
            val o = read(); write(o.put("signed", (o.optJSONObject("signed") ?: JSONObject()).put(what, at)))
        }
        /**
         * Whether each sign-in's file was here at the last exchange: one gone since was signed out of here, and is
         * stamped so (ListenBrainz's and Last.fm's disconnect only delete their file; the other's was put back over it).
         */
        fun there(what: String, here: Boolean): Boolean = guard.run {
            val o = read(); val t = o.optJSONObject("there") ?: JSONObject()
            val was = t.optBoolean(what)
            if (was != here) write(o.put("there", t.put(what, here)))
            was
        }
    }

    // ---- sealed under the key a pair keeps ----

    fun seal(key: String, text: String): Pair<String, String> {
        val iv = randomBytes(12)
        return b64(iv) to b64(gcmSeal(unb64(key), iv, text.encodeToByteArray()))
    }

    fun open(key: String, iv: String, data: String): String = gcmOpen(unb64(key), unb64(iv), unb64(data)).decodeToString()

    // ---- what you've heard ----

    /** A line's identity: when it began and what it was called (ids differ between the two: a phone's file, a Mac's). */
    fun heardKey(o: JSONObject) = o.optLong("at").toString() + "/" + o.optString("title").lowercase()

    /** Lines of heard.jsonl from `from` on (all of them if the file's been cleared since), but none that came from `to`. */
    fun heardFrom(file: File, from: Int, to: String): Pair<JSONArray, Int> {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        val start = if (from > lines.size) 0 else from
        val out = JSONArray()
        lines.drop(start).forEach { l -> runCatching { JSONObject(l) }.getOrNull()?.takeIf { it.optString("from") != to }?.let { out.put(it) } }
        return out to lines.size
    }

    /**
     * Their lines into heard.jsonl, each marked as from them; one already there passed over; `local` makes a line
     * yours where you have the same song (its id and record yours, so the orders count it with yours). How many were new.
     */
    private val heardGuard = Guard()

    fun takeHeard(file: File, lines: JSONArray, from: String, local: (JSONObject) -> JSONObject?): Int = heardGuard.run k@{
        if (lines.length() == 0) return@k 0
        val have = runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { runCatching { heardKey(JSONObject(it)) }.getOrNull() }.toHashSet()
        val add = StringBuilder()
        var n = 0
        for (i in 0 until lines.length()) {
            val o = lines.optJSONObject(i) ?: continue
            if (!have.add(heardKey(o))) continue
            val mine = local(o)
            if (mine != null) { o.put("id", mine.optString("id")); mine.optString("record").takeIf { it.isNotEmpty() }?.let { o.put("record", it) } }
            add.append(o.put("from", from).toString()).append('\n'); n++
        }
        if (n > 0) file.appendText(add.toString())
        n
    }

    // ---- a song across the two ----

    /** A song as the other is told it: enough to find it there (its id where both know it: a server's, YouTube's, a station's). */
    fun song(id: String, title: String, artist: String, album: String, albumId: String, albumArtist: String, duration: Long, art: String?) =
        JSONObject().put("id", id).put("title", title).put("artist", artist).put("album", album).put("albumId", albumId)
            .put("albumArtist", albumArtist).put("duration", duration).put("art", art ?: "")

    /** The key a song is matched by where ids differ: its name and its artist, plain. */
    fun songKey(title: String, artist: String) =
        (artist.substringBefore(",").lowercase().filter { it.isLetterOrDigit() } + "/" + title.lowercase().replace(Regex("\\s*[(\\[].*?[)\\]]"), "").filter { it.isLetterOrDigit() })

    // ---- one app's side ----

    /** What only an app knows: its folder (heard.jsonl, the sign-ins' files, sync.json), its songs, its player. */
    interface Side {
        val dir: File
        val name: String
        /** The same song here, for a heard line from the other ({id, record}), if you have it. */
        fun mine(line: JSONObject): JSONObject?
        /** What's playing here, to carry on with there ({queue: [song], index, position, playing, at}), or null. */
        fun now(): JSONObject?
        /** A love (or its undoing) from the other, done here. */
        fun loved(key: String, on: Boolean, song: JSONObject)
        /** The phone's YouTube Music sign-in, to hand the computer (null: none, or not the phone). */
        fun youtube(): String? = null
        /** A YouTube Music sign-in from the phone (the computer). */
        fun takeYoutube(auth: String) {}
        /** The phone's Spotify passes (an hour's), for the computer's canvases ([SpotifyCanvas.Pass]); null: none. */
        fun spotify(): String? = null
        /** Spotify passes from the phone (the computer). */
        fun takeSpotify(pass: String) {}
        /** What this side asks the other for, beyond what's always sent ("spotify": its sign-in, a TV's). */
        fun wants(): List<String> = emptyList()
        /** The other asked for these (kept for the next call). */
        fun wanted(peer: Peer, what: List<String>) {}
        /** The phone's Spotify sign-in (its cookies), for a device that asked for it ([wants]); null: none, or not asked. */
        fun spotifySignIn(peer: Peer): String? = null
        /** Spotify's sign-in from the phone (a TV): kept, so it makes its own passes from then on. */
        fun takeSpotifySignIn(cookies: String) {}
        /**
         * The phone's other devices, for `peer` to know directly ({id, name, key, at}): two that listen (a computer, a
         * TV) then tell each other what they play, with a key the phone made for the two. Empty on all but the phone.
         */
        fun intros(peer: Peer): JSONArray = JSONArray()
        /** Devices the phone introduced: kept as peers that listen (hub), to tell what plays here and be steered. */
        fun introduced(list: JSONArray) {}
        /** A sign-in's file written from the other: read it again ("server", "listenbrainz", "lastfm"). */
        fun signedIn(what: String)
        /** heard.jsonl has the other's listens in it now. */
        fun heardChanged()
        /** The shows followed or an episode's place taken from the other: shown again. */
        fun podcastsChanged() {}
        /** What the other was playing, as it last said. */
        fun theirs(peer: Peer, now: JSONObject?)
        /** When play was last pressed here (a queue begun, or carried on), so a stop asked for before it is passed over. */
        fun resumedAt(): Long = 0L
        /** The other carried on with what plays here: stop. */
        fun stop() {}
    }

    /** The sign-ins that go across, each its file in the app's folder. */
    val SIGNINS = mapOf("server" to "server-account.json", "listenbrainz" to "listenbrainz.json", "lastfm" to "lastfm.json")

    private fun heardFile(side: Side) = File(side.dir, "heard.jsonl")

    /** This side's part of an exchange with `peer`: its listens from where `peer` got to, loves, sign-ins, what's playing. */
    fun message(side: Side, kept: Kept, peer: Peer, want: Int): JSONObject {
        val (lines, upto) = heardFrom(heardFile(side), peer.sentHeard, peer.id)
        val signed = JSONObject()
        SIGNINS.forEach { (what, name) ->
            val f = File(side.dir, name)
            val here = f.exists()
            // signed out of here since the last exchange: not put back by the other's, unless it's signed in after
            if (kept.there(what, here) && !here) kept.signed(what, nowMs())
            if (here) runCatching { signed.put(what, JSONObject().put("text", f.readText()).put("at", f.lastModified())) }
        }
        return JSONObject().put("id", kept.id()).put("name", side.name)
            .put("heard", JSONObject().put("lines", lines).put("upto", upto).put("want", want))
            .put("loves", kept.loves()).put("signed", signed).put("youtube", side.youtube() ?: "")
            .put("spotify", side.spotify() ?: "")
            .put("spotifySignIn", side.spotifySignIn(peer) ?: "")
            .put("wants", JSONArray(side.wants()))
            .put("intros", side.intros(peer))
            .put("podcasts", Podcasts.syncPart() ?: JSON_NULL)
            .put("now", side.now() ?: JSON_NULL).put("handed", kept.handedAt(peer.id))
    }

    /** The other's part taken in here: its listens, the loves and sign-ins newer than these, what it's playing. */
    fun take(side: Side, kept: Kept, peer: Peer, m: JSONObject) {
        val h = m.optJSONObject("heard")
        if (h != null && takeHeard(heardFile(side), h.optJSONArray("lines") ?: JSONArray(), peer.id, side::mine) > 0) side.heardChanged()
        // loves: the latest change wins
        val mine = kept.loves()
        m.optJSONObject("loves")?.let { theirs ->
            theirs.keys().forEach { k ->
                val t = theirs.optJSONObject(k) ?: return@forEach
                if (t.optLong("at") > (mine.optJSONObject(k)?.optLong("at") ?: 0L)) {
                    kept.putLove(k, t.optBoolean("on"), t.optLong("at"), t.optJSONObject("song") ?: JSONObject())
                    runCatching { side.loved(k, t.optBoolean("on"), t.optJSONObject("song") ?: JSONObject()) }
                }
            }
        }
        // sign-ins: theirs where it's newer than this one's (or this one has none, and wasn't signed out since)
        m.optJSONObject("signed")?.let { theirs ->
            SIGNINS.forEach { (what, name) ->
                val t = theirs.optJSONObject(what) ?: return@forEach
                val f = File(side.dir, name)
                val text = t.optString("text"); val at = t.optLong("at")
                val here = if (f.exists()) f.lastModified() else kept.signedAt(what)
                if (text.isNotEmpty() && at > here && (!f.exists() || f.readText() != text)) {
                    runCatching { f.writeText(text); f.setLastModified(at); kept.there(what, true); side.signedIn(what) }
                }
            }
        }
        // podcasts: the shows followed and each episode's place, the latest change wins
        m.optJSONObject("podcasts")?.let { if (runCatching { Podcasts.takeSync(it) }.getOrDefault(false)) runCatching { side.podcastsChanged() } }
        m.optString("youtube").takeIf { it.isNotEmpty() }?.let { runCatching { side.takeYoutube(it) } }
        m.optString("spotify").takeIf { it.isNotEmpty() }?.let { runCatching { side.takeSpotify(it) } }
        m.optString("spotifySignIn").takeIf { it.isNotEmpty() }?.let { runCatching { side.takeSpotifySignIn(it) } }
        m.optJSONArray("wants")?.let { a -> runCatching { side.wanted(peer, (0 until a.length()).map(a::optString)) } }
        m.optJSONArray("intros")?.takeIf { it.length() > 0 }?.let { runCatching { side.introduced(it) } }
        side.theirs(peer, m.optJSONObject("now"))
        // the other carried on with what plays here, since it was last pressed play: it stops here
        val handed = m.optLong("handed")
        if (handed > 0 && handed > side.resumedAt()) runCatching { side.stop() }
    }

    /** The phone's call: its part, sealed for `peer`, as sent. */
    fun ask(side: Side, kept: Kept, peer: Peer): String {
        val (iv, data) = seal(peer.key, message(side, kept, peer, peer.gotHeard).toString())
        return JSONObject().put("id", kept.id()).put("iv", iv).put("data", data).toString()
    }

    /** The computer's answer to a call: the phone's part taken, its own sent back sealed; null for a phone it doesn't know. */
    fun answer(side: Side, kept: Kept, body: String, at: String): String? {
        val o = JSONObject(body)
        val peer = kept.peers().firstOrNull { it.id == o.optString("id") } ?: return null
        val m = JSONObject(open(peer.key, o.getString("iv"), o.getString("data")))
        take(side, kept, peer, m)
        val h = m.optJSONObject("heard") ?: JSONObject()
        // its listens from where it asked; it now has these, so next time from here
        val now = peer.copy(name = m.optString("name").ifEmpty { peer.name }, sentHeard = h.optInt("want"), gotHeard = h.optInt("upto"),
            seen = nowMs(), at = at)
        val (iv, data) = seal(peer.key, message(side, kept, now, now.gotHeard).toString())
        kept.put(now)
        return JSONObject().put("iv", iv).put("data", data).toString()
    }

    /** The computer's answer taken in on the phone; how far each got kept for next time. */
    fun answered(side: Side, kept: Kept, peer: Peer, body: String, at: String) {
        val o = JSONObject(body)
        val m = JSONObject(open(peer.key, o.getString("iv"), o.getString("data")))
        take(side, kept, peer, m)
        val h = m.optJSONObject("heard") ?: JSONObject()
        kept.put(peer.copy(name = m.optString("name").ifEmpty { peer.name }, gotHeard = h.optInt("upto"), sentHeard = h.optInt("want"),
            seen = nowMs(), at = at))
    }

    // ---- the phone's player, live (playing with the app closed too) ----

    /** A short message for `peer`, sealed: what plays now (`/now`), or waiting for a stop (`/wait`). */
    fun sealed(kept: Kept, peer: Peer, m: JSONObject): String {
        val (iv, data) = seal(peer.key, m.toString())
        return JSONObject().put("id", kept.id()).put("iv", iv).put("data", data).toString()
    }

    /** One opened on the computer: which phone, and what it said; null for a phone it doesn't know. */
    fun unsealed(kept: Kept, body: String): Pair<Peer, JSONObject>? {
        val o = JSONObject(body)
        val peer = kept.peers().firstOrNull { it.id == o.optString("id") } ?: return null
        val m = JSONObject(open(peer.key, o.getString("iv"), o.getString("data")))
        kept.heard(peer.id)
        return peer to m
    }

    /** The computer's answer to one, sealed. */
    fun reply(peer: Peer, m: JSONObject): String {
        val (iv, data) = seal(peer.key, m.toString())
        return JSONObject().put("iv", iv).put("data", data).toString()
    }

    /** That answer opened on the phone. */
    fun replied(peer: Peer, body: String): JSONObject {
        val o = JSONObject(body)
        return JSONObject(open(peer.key, o.getString("iv"), o.getString("data")))
    }

    // ---- what plays on every device, as each says it (2026-11-04: every ink app showing every other, live) ----

    /**
     * A device's playing as it tells it, ready to send: what [Sync.song] gives for each in the queue, where, and
     * whether it plays. `position` is where it is at the moment it's sent (`sent`, the sender's clock): the one
     * reading it works out where it is now from when it heard it, its own clock, so two clocks never meet.
     */
    fun nowOf(queue: JSONArray, index: Int, position: Long, playing: Boolean, device: String, changed: Long): JSONObject =
        JSONObject().put("queue", queue).put("index", index).put("position", position).put("positionAt", nowMs())
            .put("playing", playing).put("at", changed).put("device", device)

    /** Where in its song a told playing was when it was heard (sent and heard close enough to be one moment). */
    fun positionWhenSent(now: JSONObject): Long {
        val p = now.optLong("position")
        val sent = now.optLong("sent"); val at = now.optLong("positionAt")
        return if (now.optBoolean("playing") && sent > 0 && at > 0) p + (sent - at).coerceIn(0, 60_000) else p
    }

    /**
     * What plays on each device this one knows (its own too), each with when it was heard here: kept by a device that
     * listens (a computer, a TV), every one told it (`/now`), asked for whole by a phone (`/watch`), which waits on it
     * till something changes. The ages it gives are this device's own, so a phone reading them needs no clock of ours.
     */
    class Household {
        private val lock = Signal()
        private val all = LinkedHashMap<String, JSONObject>()
        @Volatile var version = 0L
            private set
        /** Told on each change (the screens show it). */
        @Volatile var changed: (() -> Unit)? = null

        /** A device's playing heard now (null: it stopped, or plays nothing). */
        fun put(id: String, name: String, now: JSONObject?) {
            lock.run {
                val p = now?.let { positionWhenSent(it) }
                all[id] = JSONObject().put("id", id).put("name", name).put("heard", nowMs())
                    .put("now", now?.let { JSONObject(it.toString()).put("position", p) } ?: JSON_NULL)
                version++
                lock.wake()
            }
            runCatching { changed?.invoke() }
        }

        /** Everyone's, as heard here: each with its age (ms since heard) for another to place in its own time. */
        fun snapshot(): JSONArray = lock.run {
            val t = nowMs()
            JSONArray(all.values.map { JSONObject(it.toString()).put("age", t - it.optLong("heard")) })
        }

        /** Each device's entry as heard here ({id, name, heard, now}). */
        fun entries(): List<JSONObject> = lock.run { all.values.map { JSONObject(it.toString()) } }

        /** Another device's whole household taken in (a phone's, from `/watch`): each placed in this device's time. */
        fun takeSnapshot(a: JSONArray) {
            lock.run {
                val t = nowMs()
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    val id = e.optString("id").ifEmpty { continue }
                    val heard = t - e.optLong("age")
                    // the newer of what's known here and what it says wins (a phone may have heard it itself)
                    if ((all[id]?.optLong("heard") ?: 0L) > heard) continue
                    all[id] = JSONObject().put("id", id).put("name", e.optString("name")).put("heard", heard).put("now", e.opt("now") ?: JSON_NULL)
                }
                version++
                lock.wake()
            }
            runCatching { changed?.invoke() }
        }

        /** Waits (a while at most) till something's changed since `since`; the version then. */
        fun await(since: Long, ms: Long): Long {
            return lock.run {
                val until = nowMs() + ms
                while (version <= since && nowMs() < until) lock.await(until - nowMs())
                version
            }
        }
    }

    /**
     * Where a heard playing is now, in this device's time: where it was when heard, and on since if it plays (a song's
     * length at most: an hour asleep didn't run a song on past its end).
     */
    fun positionNow(entry: JSONObject, length: Long = 0L): Long {
        val now = entry.optJSONObject("now") ?: return 0L
        val p = now.optLong("position")
        if (!now.optBoolean("playing")) return p
        val run = (nowMs() - entry.optLong("heard")).coerceIn(0, 6 * 3_600_000L)
        return if (length > 0) minOf(length, p + run) else p + run
    }
}
