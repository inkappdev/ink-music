package app.ink.music

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The phone and the computer kept as one (Bronson, 2026-10-29: "make everything seamlessly sync"), over the home wifi,
 * no cloud: paired once by a six-digit code ([Handover]: the pair agree a key no one on the network can work out, and
 * keep it), then whenever the two are on the same wifi the phone calls the computer (`POST /sync`, `_inksync._tcp`)
 * and each hands the other what it hasn't got, sealed under that key (AES-GCM):
 *  - what you've heard (heard.jsonl): each asks for the other's lines from where it got to, a line it already has
 *    (the same start and title) passed over, a line it got from that one never sent back;
 *  - loves, recent searches, and the sign-ins (your server, ListenBrainz, Last.fm; YouTube Music's, the phone's to the
 *    computer): the latest change wins;
 *  - what each was playing, and where in it, to carry on with on the other ("continue here").
 * Plain Java, shared; each app reads and writes its own parts of it.
 */
object Sync {
    const val SERVICE = "_inksync._tcp"
    /** The computer's port, the same each run where it's free (so the phone finds it again without looking). */
    const val PORT = 47823

    /** A device paired with this one: its id and name, the key the two keep, how far each got with the other's history. */
    data class Peer(val id: String, val name: String, val key: String, val gotHeard: Int = 0, val sentHeard: Int = 0,
                    val seen: Long = 0, val at: String = "")

    // ---- this device and its pairs, kept in the app's folder (sync.json) ----

    class Kept(private val dir: File) {
        private val file get() = File(dir, "sync.json")

        @Synchronized fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

        @Synchronized private fun write(o: JSONObject) {
            val tmp = File(dir, "sync.json.part")
            tmp.writeText(o.toString()); tmp.renameTo(file)
        }

        /** This device's own id, made once. */
        fun id(): String = read().optString("id").ifEmpty {
            val made = ByteArray(9).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            write(read().put("id", made)); made
        }

        fun peers(): List<Peer> = read().optJSONArray("peers")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Peer(it.getString("id"), it.optString("name"), it.getString("key"), it.optInt("gotHeard"), it.optInt("sentHeard"), it.optLong("seen"), it.optString("at"))
        } }.orEmpty()

        @Synchronized fun put(p: Peer) {
            val o = read()
            val list = peers().filter { it.id != p.id } + p
            write(o.put("peers", JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("key", it.key)
                .put("gotHeard", it.gotHeard).put("sentHeard", it.sentHeard).put("seen", it.seen).put("at", it.at) })))
        }

        @Synchronized fun forget(id: String) {
            val o = read()
            write(o.put("peers", JSONArray(peers().filter { it.id != id }.map { JSONObject().put("id", it.id).put("name", it.name).put("key", it.key)
                .put("gotHeard", it.gotHeard).put("sentHeard", it.sentHeard).put("seen", it.seen).put("at", it.at) })))
        }

        /** Loves as last changed on either: song key to {on, at, song}. */
        fun loves(): JSONObject = read().optJSONObject("loves") ?: JSONObject()
        @Synchronized fun putLove(key: String, on: Boolean, at: Long, song: JSONObject) {
            val o = read(); val l = o.optJSONObject("loves") ?: JSONObject()
            l.put(key, JSONObject().put("on", on).put("at", at).put("song", song))
            write(o.put("loves", l))
        }

        /** When this device carried on with what `peer` was playing ("continue here"): that one stops, if it hasn't played since. */
        fun handedAt(peer: String): Long = read().optJSONObject("handed")?.optLong(peer) ?: 0L
        @Synchronized fun handed(peer: String, at: Long) {
            val o = read(); write(o.put("handed", (o.optJSONObject("handed") ?: JSONObject()).put(peer, at)))
        }

        /** When each sign-in last changed here ("server", "listenbrainz", "lastfm", "youtube"). */
        fun signedAt(what: String): Long = read().optJSONObject("signed")?.optLong(what) ?: 0L
        @Synchronized fun signed(what: String, at: Long) {
            val o = read(); write(o.put("signed", (o.optJSONObject("signed") ?: JSONObject()).put(what, at)))
        }
    }

    // ---- sealed under the key a pair keeps ----

    fun seal(key: String, text: String): Pair<String, String> {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(key), "AES"), GCMParameterSpec(128, iv))
        }
        val b = Base64.getEncoder()
        return b.encodeToString(iv) to b.encodeToString(c.doFinal(text.toByteArray()))
    }

    fun open(key: String, iv: String, data: String): String {
        val d = Base64.getDecoder()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(d.decode(key), "AES"), GCMParameterSpec(128, d.decode(iv)))
        }
        return String(c.doFinal(d.decode(data)))
    }

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
    @Synchronized fun takeHeard(file: File, lines: JSONArray, from: String, local: (JSONObject) -> JSONObject?): Int {
        if (lines.length() == 0) return 0
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
        return n
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
        /** A sign-in's file written from the other: read it again ("server", "listenbrainz", "lastfm"). */
        fun signedIn(what: String)
        /** heard.jsonl has the other's listens in it now. */
        fun heardChanged()
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
            if (f.exists()) runCatching { signed.put(what, JSONObject().put("text", f.readText()).put("at", f.lastModified())) }
        }
        return JSONObject().put("id", kept.id()).put("name", side.name)
            .put("heard", JSONObject().put("lines", lines).put("upto", upto).put("want", want))
            .put("loves", kept.loves()).put("signed", signed).put("youtube", side.youtube() ?: "")
            .put("spotify", side.spotify() ?: "")
            .put("now", side.now() ?: JSONObject.NULL).put("handed", kept.handedAt(peer.id))
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
                    runCatching { f.writeText(text); f.setLastModified(at); side.signedIn(what) }
                }
            }
        }
        m.optString("youtube").takeIf { it.isNotEmpty() }?.let { runCatching { side.takeYoutube(it) } }
        m.optString("spotify").takeIf { it.isNotEmpty() }?.let { runCatching { side.takeSpotify(it) } }
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
            seen = System.currentTimeMillis(), at = at)
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
            seen = System.currentTimeMillis(), at = at))
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
        return peer to JSONObject(open(peer.key, o.getString("iv"), o.getString("data")))
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
}
