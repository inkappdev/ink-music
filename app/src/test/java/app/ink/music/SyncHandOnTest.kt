package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A computer hands its YouTube sign-in on only to a device that asks for it (the iPhone), in the same exchange. */
class SyncHandOnTest {
    private fun side(dir: java.io.File, name: String, asks: Boolean, got: MutableList<String>, hands: String?) = object : Sync.Side {
        val asked = HashSet<String>()
        override val dir = dir
        override val name = name
        override fun mine(line: JSONObject): JSONObject? = null
        override fun now(): JSONObject? = null
        override fun loved(key: String, on: Boolean, song: JSONObject) {}
        override fun signedIn(what: String) {}
        override fun heardChanged() {}
        override fun theirs(peer: Sync.Peer, now: JSONObject?) {}
        override fun wants() = if (asks) listOf("youtube") else emptyList()
        override fun wanted(peer: Sync.Peer, what: List<String>) { if ("youtube" in what) asked += peer.id else asked -= peer.id }
        override fun youtube(peer: Sync.Peer) = if (peer.id in asked) hands else null
        override fun takeYoutube(auth: String) { got += auth }
    }

    private fun run(asks: Boolean): List<String> {
        val a = java.nio.file.Files.createTempDirectory("computer").toFile(); val b = java.nio.file.Files.createTempDirectory("iphone").toFile()
        val computer = Sync.Kept(a); val iphone = Sync.Kept(b)
        val key = b64(randomBytes(32))
        computer.put(Sync.Peer(iphone.id(), "iphone", key)); iphone.put(Sync.Peer(computer.id(), "computer", key))
        val got = ArrayList<String>()
        val hub = side(a, "computer", false, ArrayList(), """{"cookie":"SAPISID=x"}""")
        val phone = side(b, "iphone", asks, got, null)
        val peer = iphone.peers().first()
        val answer = Sync.answer(hub, computer, Sync.ask(phone, iphone, peer), "here:1")!!
        Sync.answered(phone, iphone, peer, answer, "here:1")
        a.deleteRecursively(); b.deleteRecursively()
        return got
    }

    @Test fun handedOnlyWhenAsked() {
        assertEquals(listOf("""{"cookie":"SAPISID=x"}"""), run(asks = true))
        assertNull(run(asks = false).firstOrNull())
    }
}
