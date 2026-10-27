package app.ink.music

import org.junit.Assert.assertTrue
import org.junit.Test

/** The InnerTube reader against YouTube Music itself, signed out (the same renderers a library answers in). */
class InnerTubeTest {
    private val yt = InnerTube()

    @Test fun playlistSongs() {
        // a long public playlist: three pages, so continuations are followed
        val t = yt.tracks("VLPL0GvsLQil0MmYC96KEs_7dTNsLm1PS6JX", max = 600)
        println("songs ${t.size}"); t.take(5).forEach(::println)
        assertTrue(t.size > 200)   // it says 500; YouTube serves 244, six greyed out
        assertTrue(t.count { it.artists.isNotEmpty() && it.seconds > 0 } > t.size * 9 / 10)
    }

    @Test fun recordToPlaylist() {
        val home = yt.items("FEmusic_home", max = 60)
        println("home ${home.size}"); home.take(8).forEach(::println)
        val record = yt.items("FEmusic_new_releases_albums", max = 20).firstOrNull { it.kind == InnerTube.Kind.RECORD }
        println("record $record")
        assertTrue(record != null)
        val list = yt.albumPlaylist(record!!.id)
        println("its playlist $list")
        assertTrue(list != null)
    }

    @Test fun artistPage() {
        val a = yt.artist("UCk0rl7syWi43334DctQNKZQ")   // MJ Lenderman
        println("artist ${a.name} (${a.audience}): ${a.top.size} songs, ${a.albums.size} records, ${a.singles.size} singles, like ${a.like.map { it.title }}")
        println("photo ${a.photo}\nabout ${a.about.take(120)}"); a.top.take(3).forEach(::println); a.albums.take(3).forEach(::println)
        assertTrue(a.name.isNotEmpty() && a.top.isNotEmpty() && a.albums.isNotEmpty() && a.like.isNotEmpty() && a.photo != null && a.about.isNotEmpty())
    }

    @Test fun albumPage() {
        val t0 = System.currentTimeMillis()
        val (list, songs) = yt.album("MPREb_cxmxsUKrfy4")   // Manning Fireworks
        println("album $list: ${songs.size} songs in ${System.currentTimeMillis() - t0} ms"); songs.take(3).forEach(::println)
        assertTrue(list != null && songs.size >= 9 && songs.all { it.title.isNotEmpty() })
        // the same record as its playlist (a search's record comes as one)
        val again = yt.tracks("VL$list")
        println("as its playlist: ${again.size} songs"); again.take(2).forEach(::println)
        assertTrue(again.size == songs.size)
    }

    @Test fun sign() { assertTrue(InnerTube.sign("abc", 1).startsWith("SAPISIDHASH 1_")) }
}
