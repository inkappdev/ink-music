package app.ink.music

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Your YouTube Music, signed in (the full build only): your playlists, the records and artists in
 * your library, what you played there lately, and your liked songs, which are the heart's: loving a
 * YouTube song likes it there. Signed in on YouTube's own page in a WebView; its cookies are kept on
 * the phone, sealed by a key in Android's keystore, and go only to music.youtube.com. What's told to
 * YouTube: the like, and a YouTube song played here (as its own app tells it; off in settings).
 * Signing out forgets the cookies and the WebView's data.
 */
object YouTubeAccount {
    const val available = true
    private val _who = MutableStateFlow<TubeWho?>(null)
    val who: StateFlow<TubeWho?> = _who
    val playlists = MutableStateFlow<List<TubeList>>(emptyList())
    val records = MutableStateFlow<List<TubeAlbum>>(emptyList())
    val history = MutableStateFlow<List<Song>>(emptyList())
    /** Your liked songs (liked music), newest first: loved here too. */
    val liked = MutableStateFlow<List<Song>>(emptyList())
    val artists = MutableStateFlow<List<TubeArtist>>(emptyList())
    /** YouTube Music's own mixes for you (My Supermix, Discover Mix, New Release Mix, Replay Mix...), from your home there. */
    val mixes = MutableStateFlow<List<TubeList>>(emptyList())
    /** Your artists' records from this year (and last, early in the year), newest first. */
    val fresh = MutableStateFlow<List<TubeAlbum>>(emptyList())
    val reading = MutableStateFlow(false)
    /** The songs you've uploaded to YouTube Music yourself (they play only signed in), newest first. */
    val uploads = MutableStateFlow<List<Song>>(emptyList())
    /** The artists you follow there, by their page's id ("UC..."): an artist's page says so. */
    val followed = MutableStateFlow<Set<String>>(emptySet())
    private var tube: InnerTube? = null
    @Volatile private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(c: Context) = c.getSharedPreferences("youtube", Context.MODE_PRIVATE)

    /**
     * A YouTube song heard here (the player's process, blocking), told to YouTube Music as its own app
     * would: your history there, and what its mixes learn from. Unless you've said not to ([tells]).
     */
    fun played(c: Context, songId: String) {
        if (!tells(c)) return
        InnerTube.trace = { android.util.Log.d("ink-youtube", it) }
        val t = signed(c) ?: return
        // youtube now and then calls a song unplayable for a moment (no tracking link): asked once more
        val told = t.played(songId.removePrefix("y")) || run { Thread.sleep(3_000); t.played(songId.removePrefix("y")) }
        android.util.Log.d("ink-youtube", "played $songId: told $told")
    }

    /**
     * YouTube Music as you, in either process (the player's never signs in itself): the sign-in read fresh each
     * time, so a sign-in or sign-out made in the other process counts at once (its prefs would be cached). Null
     * when no one's signed in.
     */
    fun signed(c: Context): InnerTube? {
        @Suppress("DEPRECATION") val p = c.getSharedPreferences("youtube", Context.MODE_MULTI_PROCESS)
        val auth = p.getString("auth", null)?.let { runCatching { JSONObject(open(it)) }.getOrNull() } ?: return null
        return InnerTube(auth.optString("cookie"), auth.optString("visitor").ifEmpty { null },
            auth.optString("sync").ifEmpty { null }, auth.optString("version").ifEmpty { InnerTube.VERSION })
    }

    // told or not, kept as a file (both processes read it): there, YouTube isn't told
    private fun quiet(c: Context) = java.io.File(c.filesDir, "youtube-quiet")
    fun tells(c: Context) = !quiet(c).exists()
    fun tell(c: Context, on: Boolean) { if (on) quiet(c).delete() else quiet(c).createNewFile() }

    fun start(c: Context) {
        InnerTube.trace = { android.util.Log.d("ink-youtube", it) }
        app = c.applicationContext
        dir = java.io.File(c.filesDir, "youtube")
        if (tube != null) return
        val p = prefs(c)
        val auth = p.getString("auth", null)?.let { runCatching { JSONObject(open(it)) }.getOrNull() } ?: return
        tube = InnerTube(auth.optString("cookie"), auth.optString("visitor").ifEmpty { null },
            auth.optString("sync").ifEmpty { null }, auth.optString("version").ifEmpty { InnerTube.VERSION })
        _who.value = TubeWho(p.getString("name", "").orEmpty(), p.getString("handle", "").orEmpty())
        // your library as it was last read, at once (nothing then arrives later and pushes the page down); then read again
        keptRaw("library")?.let(::putLibrary)
        sync()
    }

    /** Signed in on YouTube's page: these cookies tried (who are they?), kept if they work. */
    suspend fun signIn(c: Context, cookie: String, visitor: String?, dataSyncId: String?, version: String?): Boolean = withContext(Dispatchers.IO) {
        // "<brand>||<you>": a brand account speaks on behalf of its id; your own has nothing before the bars
        val brand = dataSyncId?.split("||")?.firstOrNull()?.takeIf { it.isNotBlank() && dataSyncId.contains("||") && dataSyncId.substringAfter("||").isNotBlank() }
        val t = InnerTube(cookie, visitor, brand, version ?: InnerTube.VERSION)
        val w = t.who() ?: return@withContext false
        val auth = JSONObject().put("cookie", cookie).put("visitor", visitor.orEmpty()).put("sync", brand.orEmpty()).put("version", version.orEmpty())
        prefs(c).edit().putString("auth", seal(auth.toString())).putString("name", w.name).putString("handle", w.handle).apply()
        tube = t; _who.value = w
        sync()
        true
    }

    fun signOut(c: Context) {
        prefs(c).edit().clear().apply()
        tube = null; _who.value = null
        playlists.value = emptyList(); records.value = emptyList(); history.value = emptyList()
        liked.value = emptyList(); artists.value = emptyList(); mixes.value = emptyList(); fresh.value = emptyList(); followed.value = emptySet()
        uploads.value = emptyList()
        lists.clear(); dir?.deleteRecursively()
        forgetWeb()
    }

    /** The WebView's cookies and storage gone, so the next sign-in starts clean; then [done]. */
    fun forgetWeb(done: () -> Unit = {}) {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done() }
    }

    /** Your library read again: playlists, records, history (each on its own; one failing leaves the others). */
    fun sync() {
        val t = tube ?: return
        reading.value = true
        scope.launch {
            val lists0 = launch {
                runCatching { t.items("FEmusic_liked_playlists") }.onSuccess { found ->
                    // songs only (not "episodes for later", a podcast list), each saying what isn't already said:
                    // not "YouTube Music" (the row says it), nor your own name
                    val owners = setOf("YouTube Music", _who.value?.name.orEmpty())
                    // liked music is loved, so it's there (and on the heart), not a playlist
                    playlists.value = found.filter { it.id.startsWith("VL") && it.id != "VLSE" && it.id != "VLLM" }.map {
                        TubeList(it.id.removePrefix("VL"), it.title, it.subtitle.split(" • ", " · ").filter { w -> w.isNotBlank() && w !in owners }.joinToString("   "), it.art)
                    }
                }.onFailure { android.util.Log.d("ink-youtube", "playlists: $it") }
            }
            val recs = launch {
                runCatching { t.items("FEmusic_liked_albums") }.onSuccess { found ->
                    records.value = found.filter { it.kind == InnerTube.Kind.RECORD || it.id.startsWith("MPREb") }
                        .map { TubeAlbum("${InnerTube.BASE}/browse/${it.id}", it.title, artistOf(it.subtitle), it.art) }
                }.onFailure { android.util.Log.d("ink-youtube", "records: $it") }
            }
            val heard = launch {
                runCatching { t.tracks("FEmusic_history", max = 100) }.onSuccess { found -> history.value = found.map(::song) }
                    .onFailure { android.util.Log.d("ink-youtube", "history: $it") }
            }
            val mine = launch {
                runCatching { t.tracks("FEmusic_library_privately_owned_tracks", max = 2_000) }.onSuccess { found -> uploads.value = found.map(::song) }
                    .onFailure { android.util.Log.d("ink-youtube", "uploads: $it") }
            }
            val likes = launch {
                runCatching { t.tracks("VLLM") }.onSuccess { found -> liked.value = found.map(::song) }
                    .onFailure { android.util.Log.d("ink-youtube", "liked: $it") }
            }
            // the artists you follow, else the ones your library's songs are by
            val people = launch {
                runCatching {
                    fun of(id: String) = t.items(id).filter { it.kind == InnerTube.Kind.ARTIST && it.id.startsWith("UC") }
                    of("FEmusic_library_corpus_artists").also { f -> followed.value = f.map { it.id }.toSet() }
                        .ifEmpty { of("FEmusic_library_corpus_track_artists") }
                }.onSuccess { found -> artists.value = found.map { TubeArtist(it.title, it.art, it.id) } }
                    .onFailure { android.util.Log.d("ink-youtube", "artists: $it") }
            }
            // your mixes: the home page's personal mixes ("RDTMAK" playlists; a shelf called a mix as well)
            val home = launch {
                runCatching { t.home() }.onSuccess { shelves ->
                    android.util.Log.d("ink-youtube", "home: " + shelves.joinToString { "${it.title} (${it.items.size})" })
                    mixes.value = shelves.flatMap { s -> s.items.filter { it.kind == InnerTube.Kind.PLAYLIST &&
                        (it.id.startsWith("VLRDTMAK") || (s.title.contains("mix", ignoreCase = true) && it.id.startsWith("VLRD"))) } }
                        .distinctBy { it.id }
                        .map { TubeList(it.id.removePrefix("VL"), it.title, it.subtitle.split(" • ", " · ").firstOrNull { w -> w.isNotBlank() }.orEmpty(), it.art) }
                }.onFailure { android.util.Log.d("ink-youtube", "home: $it") }
            }
            lists0.join(); recs.join(); heard.join(); likes.join(); people.join(); home.join(); mine.join()
            keepRaw("library", libraryJson())   // kept now, and again once your artists' new records are in
            // your mixes and playlists read ahead, beside what follows
            app?.let { c -> launch { warm(c, t) } }
            // then what your artists put out lately (their pages, once they're known)
            runCatching { fresh.value = freshFrom(t, artists.value) }.onFailure { android.util.Log.d("ink-youtube", "fresh: $it") }
            keepRaw("library", libraryJson())
            android.util.Log.d("ink-youtube", "read ${playlists.value.size} playlists, ${records.value.size} records, " +
                "${history.value.size} heard, ${liked.value.size} liked, ${artists.value.size} artists")
            reading.value = false
        }
    }

    // your library written, each shown at once and put back if YouTube refuses

    /** Whether a record is in your library there. */
    fun saved(a: TubeAlbum): Boolean = records.value.any { it.url == a.url || (it.title.equals(a.title, true) && it.artist.equals(a.artist, true)) }

    /** A record saved to your library there, or not: by its own playlist ("OLAK5uy_...", found from its page if need be). */
    suspend fun save(a: TubeAlbum, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val t = tube ?: return@withContext false
        val list = Regex("[?&]list=([^&]+)").find(a.url)?.groupValues?.get(1)
            ?: runCatching { t.album(a.url.substringAfterLast('/')).first }.getOrNull() ?: return@withContext false
        val before = records.value
        val same = { r: TubeAlbum -> r.url == a.url || (r.title.equals(a.title, true) && r.artist.equals(a.artist, true)) }
        records.value = if (on) listOf(a) + before.filterNot(same) else before.filterNot(same)
        t.save(list, on).also { if (!it) records.value = before }
    }

    /** An artist followed there, or not. */
    suspend fun follow(a: TubeArtist, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val t = tube ?: return@withContext false
        if (a.id.isEmpty()) return@withContext false
        val before = followed.value to artists.value
        followed.value = if (on) followed.value + a.id else followed.value - a.id
        if (on && artists.value.none { it.id == a.id }) artists.value = listOf(a) + artists.value
        if (!on) artists.value = artists.value.filterNot { it.id == a.id }
        t.follow(a.id, on).also { if (!it) { followed.value = before.first; artists.value = before.second } }
    }

    /** A song added to one of your playlists there: a song from elsewhere found there by its words first. */
    suspend fun addTo(list: TubeList, s: Song): Boolean = withContext(Dispatchers.IO) {
        val t = tube ?: return@withContext false
        val vid = (if (s.tube) s.id else YouTubeMusic.find(s.title, s.artist)?.id)?.removePrefix("y") ?: return@withContext false
        t.addTo(list.id, vid).also { if (it) { lists.remove(list.id); dir?.let { d -> java.io.File(d, "${list.id}.json").delete() } } }
    }

    /** A new playlist of yours there (private), these songs in it, in order (each from elsewhere found there first): it, and how many went in; null if refused. */
    suspend fun makePlaylistOf(name: String, songs: List<Song>): Pair<TubeList, Int>? = withContext(Dispatchers.IO) {
        val t = tube ?: return@withContext null
        val ids = songs.mapNotNull { s -> (if (s.tube) s.id else YouTubeMusic.find(s.title, s.artist)?.id)?.removePrefix("y") }.distinct()
        val id = t.makePlaylist(name, ids) ?: return@withContext null
        val made = TubeList(id, name, "", songs.firstOrNull()?.art)
        playlists.value = listOf(made) + playlists.value
        made to ids.size
    }

    /** A new playlist of yours there (private), with this song in it. */
    suspend fun makePlaylist(name: String, s: Song?): Boolean = withContext(Dispatchers.IO) {
        val t = tube ?: return@withContext false
        val vid = s?.let { (if (it.tube) it.id else YouTubeMusic.find(it.title, it.artist)?.id)?.removePrefix("y") }
        val id = t.makePlaylist(name, listOfNotNull(vid)) ?: return@withContext false
        playlists.value = listOf(TubeList(id, name, "", s?.art)) + playlists.value
        true
    }

    /** Whether a playlist is yours there (so it can be deleted here): a mix never is. */
    suspend fun mine(id: String): Boolean = withContext(Dispatchers.IO) {
        val t = tube ?: return@withContext false
        !id.startsWith("RD") && t.mine(id)
    }

    /** One of your playlists deleted there: gone from the list at once, back if YouTube refuses. */
    suspend fun deletePlaylist(id: String): Boolean = withContext(Dispatchers.IO) {
        val t = tube ?: return@withContext false
        val before = playlists.value
        playlists.value = before.filterNot { it.id == id }
        t.deletePlaylist(id).also {
            if (it) { lists.remove(id); dir?.let { d -> java.io.File(d, "$id.json").delete() } } else playlists.value = before
        }
    }

    /** A song liked on YouTube Music, or not (the heart): shown at once, put back if YouTube refuses. */
    fun like(s: Song, on: Boolean) {
        val t = tube ?: return
        val before = liked.value
        liked.value = if (on) listOf(s) + before.filter { it.id != s.id } else before.filter { it.id != s.id }
        scope.launch {
            if (!t.like(s.id.removePrefix("y"), on)) {
                android.util.Log.d("ink-youtube", "like ${s.id} $on refused")
                liked.value = before
            }
        }
    }

    /**
     * An artist's page on YouTube Music, by their page's id, or found by their name (only one called just
     * that: a near name is someone else). As it was last read at once (kept on the phone), then read again.
     */
    fun artist(name: String, id: String = ""): Flow<ArtistInfo?> = flow {
        val key = artistKey(name, id)
        val was = keptRaw(key)?.let(::artistFrom)
        if (was != null) emit(was)
        val found = readShared(name, id)
        if (found != null) emit(found) else if (was == null) emit(null)
    }.flowOn(Dispatchers.IO)

    private val artistReads = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ArtistInfo?>>()

    /**
     * Their page read and kept, one read at a time: asked again while it's under way (a row pressed, then its page
     * opening; read ahead, then tapped) the same read is waited on, not a second begun.
     */
    private suspend fun readShared(name: String, id: String): ArtistInfo? {
        val key = artistKey(name, id)
        val d = artistReads.computeIfAbsent(key) {
            ahead.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val was = keptRaw(key)?.let(::artistFrom)
                readArtist(name, id.ifEmpty { was?.id.orEmpty() })?.also { keepRaw(key, json(it)) }
            }
        }
        d.invokeOnCompletion { artistReads.remove(key, d) }
        d.start()
        return d.await()
    }

    private fun artistKey(name: String, id: String) = "artist-" + (id.ifEmpty { name.lowercase() }).replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Their page from YouTube Music: by its id, else found by name first (a search, half a second more). */
    private fun readArtist(name: String, id: String): ArtistInfo? {
        val t0 = System.currentTimeMillis()
        val found = runCatching {
            val page = id.ifEmpty { YouTubeMusic.artist(name)?.takeIf { it.name.equals(name, ignoreCase = true) }?.id.orEmpty() }
            if (page.isEmpty()) return@runCatching null
            val a = (tube ?: InnerTube()).artist(page)
            val who = a.name.ifEmpty { name }
            fun record(it: InnerTube.Item) = TubeAlbum("${InnerTube.BASE}/browse/${it.id}", it.title, who, it.art)
            ArtistInfo(page, who, a.photo, a.about, a.audience, a.top.map(::song), a.albums.map(::record), a.singles.map(::record),
                a.like.map { TubeArtist(it.title, it.art, it.id) })
        }.onFailure { android.util.Log.d("ink-youtube", "artist $name $id: $it") }.getOrNull()
        android.util.Log.d("ink-youtube", "artist $name: ${found?.top?.size} songs, ${found?.albums?.size} records in ${System.currentTimeMillis() - t0} ms")
        return found
    }

    private val warming = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val ahead = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * An artist's page read ahead, their photo too, so it opens at once when tapped: the fans-also-like on an artist's
     * page, the artist of what's playing, of a record. Only on wifi (pages most won't open, not on someone's data);
     * one read in the last half hour is left as it is.
     */
    fun warm(ctx: Context, name: String, id: String = "") {
        if (!wifi(ctx)) return
        val app = ctx.applicationContext
        if (due(name, id, 30 * 60_000L)) ahead.launch { warmOne(app, name, id, inMemory = true) }
    }

    @Volatile private var libraryWarmed = false

    /**
     * The artists you have most songs by (`names`, most first), their pages and photos read ahead once a day, one at a
     * time with a breath between (never in the way of what's playing or opening), so a first visit from the library
     * opens at once, as a second one does. Wifi only; stops if it goes. Once a run.
     */
    fun warmLibrary(ctx: Context, names: List<String>) {
        if (libraryWarmed || !wifi(ctx)) return
        libraryWarmed = true
        val app = ctx.applicationContext
        ahead.launch {
            var read = 0
            for (name in names) {
                if (!wifi(app)) break
                if (!due(name, "", 24 * 3_600_000L)) continue
                warmOne(app, name, "", inMemory = false)
                read++
                kotlinx.coroutines.delay(1_500)
            }
            android.util.Log.d("ink-youtube", "library artists read ahead: $read of ${names.size}")
        }
    }

    private fun wifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return dir != null && !cm.isActiveNetworkMetered
    }

    /** Not read within `age` ms (here or kept): marked as being read now. */
    private fun due(name: String, id: String, age: Long): Boolean {
        if (name.isBlank()) return false
        val key = artistKey(name, id)
        val now = System.currentTimeMillis()
        if (now - (warming[key] ?: 0L) < age) return false
        val kept = dir?.let { java.io.File(it, "$key.json") }
        if (kept != null && now - kept.lastModified() < age) { warming[key] = kept.lastModified(); return false }
        warming[key] = now
        return true
    }

    private suspend fun warmOne(app: Context, name: String, id: String, inMemory: Boolean) {
        val a = readShared(name, id) ?: return
        val photo = a.photo ?: return
        // what's likely next (fans also like, a row pressed) held ready to draw; the library's many only on the phone
        if (inMemory) Covers.of(app, photoSong(name, photo), 1080)
        else Covers.keepOnDisk(app, photoSong(name, photo), 1080)
    }

    /** An artist row pressed: their page read from the touch, not once it's open (a fifth of a second sooner). */
    fun warmNow(ctx: Context, name: String) {
        val app = ctx.applicationContext
        if (dir != null && due(name, "", 30 * 60_000L)) ahead.launch { warmOne(app, name, "", inMemory = true) }
    }

    // your library kept as one file (files/youtube/library.json), gone at sign-out with the rest
    private fun libraryJson(): String {
        fun lists(l: List<TubeList>) = org.json.JSONArray(l.map { JSONObject().put("id", it.id).put("name", it.name).put("said", it.said).put("art", it.art.orEmpty()) })
        fun records(l: List<TubeAlbum>) = org.json.JSONArray(l.map { JSONObject().put("url", it.url).put("title", it.title).put("artist", it.artist).put("art", it.art.orEmpty()) })
        fun songs(l: List<Song>) = org.json.JSONArray(l.map { it.json() })
        return JSONObject().put("playlists", lists(playlists.value)).put("mixes", lists(mixes.value))
            .put("records", records(records.value)).put("fresh", records(fresh.value))
            .put("history", songs(history.value)).put("liked", songs(liked.value)).put("uploads", songs(uploads.value))
            .put("artists", org.json.JSONArray(artists.value.map { JSONObject().put("name", it.name).put("face", it.face.orEmpty()).put("id", it.id) }))
            .put("followed", org.json.JSONArray(followed.value.toList()))
            .toString()
    }

    private fun putLibrary(text: String) = runCatching {
        val o = JSONObject(text)
        fun each(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        fun lists(k: String) = each(k).map { TubeList(it.getString("id"), it.getString("name"), it.optString("said"), it.optString("art").ifEmpty { null }) }
        fun records(k: String) = each(k).map { TubeAlbum(it.getString("url"), it.getString("title"), it.optString("artist"), it.optString("art").ifEmpty { null }) }
        fun songs(k: String) = each(k).map { songOf(it).also(YouTubeMusic::remember) }
        playlists.value = lists("playlists"); mixes.value = lists("mixes")
        records.value = records("records"); fresh.value = records("fresh")
        history.value = songs("history"); liked.value = songs("liked"); uploads.value = songs("uploads")
        artists.value = each("artists").map { TubeArtist(it.getString("name"), it.optString("face").ifEmpty { null }, it.optString("id")) }
        followed.value = o.optJSONArray("followed")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
    }.onFailure { android.util.Log.d("ink-youtube", "kept library: $it") }

    private fun json(a: ArtistInfo): String = JSONObject().put("id", a.id).put("name", a.name).put("photo", a.photo.orEmpty())
        .put("about", a.about).put("audience", a.audience)
        .put("top", org.json.JSONArray(a.top.map { it.json() }))
        .put("albums", org.json.JSONArray((a.albums + a.singles).map { r -> JSONObject().put("url", r.url).put("title", r.title).put("art", r.art.orEmpty()).put("single", r in a.singles) }))
        .put("like", org.json.JSONArray(a.like.map { JSONObject().put("name", it.name).put("face", it.face.orEmpty()).put("id", it.id) }))
        .toString()

    private fun artistFrom(text: String): ArtistInfo? = runCatching {
        val o = JSONObject(text)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        val records = list("albums").map { (TubeAlbum(it.getString("url"), it.getString("title"), o.getString("name"), it.optString("art").ifEmpty { null })) to it.optBoolean("single") }
        ArtistInfo(o.getString("id"), o.getString("name"), o.optString("photo").ifEmpty { null }, o.optString("about"), o.optString("audience"),
            list("top").map { songOf(it).also(YouTubeMusic::remember) }, records.filter { !it.second }.map { it.first }, records.filter { it.second }.map { it.first },
            list("like").map { TubeArtist(it.getString("name"), it.optString("face").ifEmpty { null }, it.optString("id")) })
    }.getOrNull()

    /**
     * One of your playlists' songs, in its order ("LM" is liked music); a mix's, as its queue when it has no page.
     * As it was last read at once (kept on the phone), then as it's read again, page by page. A mix
     * ("RD...") goes on for ever, page after page of more like it: its first page only.
     */
    fun playlist(id: String): Flow<List<Song>> = flow {
        val was = kept(id)
        if (was != null) emit(was)
        val t = tube ?: return@flow
        val t0 = System.currentTimeMillis()
        val pages = Channel<List<Song>>(Channel.CONFLATED)
        val read = scope.async { read(t, id) { if (was == null) pages.trySend(it) } }
        read.invokeOnCompletion { pages.close() }
        for (p in pages) emit(p)
        val all = runCatching { read.await() }.onFailure { android.util.Log.d("ink-youtube", "playlist $id: $it") }.getOrNull()
        android.util.Log.d("ink-youtube", "playlist $id: ${all?.size} songs in ${System.currentTimeMillis() - t0} ms")
        if (!all.isNullOrEmpty()) emit(all)
    }.flowOn(Dispatchers.IO)

    /** A list read from YouTube Music and kept (memory and phone); [each] told it as it grows. */
    private fun read(t: InnerTube, id: String, each: (List<Song>) -> Unit = {}): List<Song> {
        val mix = id.startsWith("RD")
        val songs = t.tracks("VL$id", max = if (mix) 50 else 2_000) { each(it.map(::song)) }
            .ifEmpty { if (mix) t.queue(id) else emptyList() }.map(::song)
        if (songs.isNotEmpty()) keep(id, songs)
        return songs
    }

    // lists as last read: in memory, and as files (files/youtube/<id>.json) so they open at once next time
    private val lists = java.util.concurrent.ConcurrentHashMap<String, List<Song>>()
    @Volatile private var dir: java.io.File? = null

    private fun kept(id: String): List<Song>? = lists[id] ?: runCatching {
        val a = org.json.JSONArray(java.io.File(dir ?: return null, "$id.json").readText())
        (0 until a.length()).map { songOf(a.getJSONObject(it)).also(YouTubeMusic::remember) }
    }.getOrNull()?.also { lists[id] = it }

    private fun keptRaw(key: String): String? = runCatching { java.io.File(dir ?: return null, "$key.json").readText() }.getOrNull()
    private fun keepRaw(key: String, text: String) { val d = dir ?: return; runCatching { d.mkdirs(); java.io.File(d, "$key.json").writeText(text) } }

    private fun keep(id: String, songs: List<Song>) {
        lists[id] = songs
        val d = dir ?: return
        runCatching { d.mkdirs(); java.io.File(d, "$id.json").writeText(org.json.JSONArray(songs.map { it.json() }).toString()) }
    }

    /**
     * Your mixes and playlists read ahead (after a sync), so they open at once: each one not kept yet,
     * and on wifi each one not read these 6 hours (a mix's page is a megabyte; a playlist's a megabyte a hundred songs).
     */
    private fun warm(c: Context, t: InnerTube) {
        val metered = Quality.metered(c)
        (mixes.value + playlists.value).take(16).forEach { l ->
            val at = dir?.let { java.io.File(it, "${l.id}.json") }?.takeIf { it.exists() }?.lastModified()
            if (at != null && (metered || System.currentTimeMillis() - at < 6 * 3_600_000L)) return@forEach
            runCatching { read(t, l.id) }.onFailure { android.util.Log.d("ink-youtube", "warm ${l.id}: $it") }
        }
    }

    /**
     * Your artists' records out this year (last year's too in January and February), newest first, a few an
     * artist: their pages read side by side, at most 20 artists, each time the library is read.
     */
    private suspend fun freshFrom(t: InnerTube, people: List<TubeArtist>): List<TubeAlbum> = coroutineScope {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val since = if (cal.get(java.util.Calendar.MONTH) <= java.util.Calendar.FEBRUARY) year - 1 else year
        val yearOf = Regex("\\b(19|20)\\d{2}\\b")
        people.take(20).map { a ->
            async {
                runCatching { t.records(a.id) }.getOrDefault(emptyList()).mapNotNull { r ->
                    val y = yearOf.find(r.subtitle)?.value?.toInt() ?: return@mapNotNull null
                    if (y < since) null else Triple(y, a.name, TubeAlbum("${InnerTube.BASE}/browse/${r.id}", r.title, a.name, r.art))
                }.take(3)
            }
        }.awaitAll().flatten().sortedByDescending { it.first }.map { it.third }.distinctBy { it.url }.take(20)
    }

    /** A record's artist from its subtitle ("Album • Phoebe Bridgers • 2020"): what isn't the kind or the year. */
    private fun artistOf(subtitle: String) = subtitle.split(" • ").map { it.trim() }
        .firstOrNull { it.isNotEmpty() && it !in setOf("Album", "Single", "EP") && !it.matches(Regex("\\d{4}")) }.orEmpty()

    private fun song(t: InnerTube.Track): Song {
        val artist = t.artists.joinToString(", ").ifEmpty { "unknown" }
        return Song(
            id = "y${t.videoId}", uri = Uri.parse("ytm://${t.videoId}"), title = t.title, artist = artist,
            album = t.album.orEmpty(), albumId = t.albumId?.let { "ya$it" } ?: "y${t.videoId}", albumArtist = t.artists.firstOrNull() ?: artist,
            duration = t.seconds * 1000L, disc = 1, track = 0, year = 0, art = t.art, format = "youtube",
        ).also(YouTubeMusic::remember)
    }

    // the cookies sealed: AES-GCM by a key that never leaves Android's keystore
    private const val KEY = "ink-youtube"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun seal(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(c.iv + c.doFinal(text.toByteArray()))
    }

    private fun open(sealed: String): String {
        val b = Base64.getDecoder().decode(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12))
    }

    /** Signing in, on Google's own page: a screen of its own ([TubeSignIn]). */
    fun openSignIn(c: Context) {
        c.startActivity(android.content.Intent(c, TubeSignIn::class.java))
    }
}
