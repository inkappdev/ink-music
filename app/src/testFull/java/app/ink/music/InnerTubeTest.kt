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
        val (name, songs, records) = yt.artist("UCk0rl7syWi43334DctQNKZQ")   // MJ Lenderman
        println("artist $name: ${songs.size} songs, ${records.size} records"); songs.take(3).forEach(::println); records.take(3).forEach(::println)
        assertTrue(name.isNotEmpty() && songs.isNotEmpty() && records.isNotEmpty())
    }

    @Test fun sign() { assertTrue(InnerTube.sign("abc", 1).startsWith("SAPISIDHASH 1_")) }
}
