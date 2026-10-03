package dev.dontblameme.selvage.engine

import dev.dontblameme.selvage.crdt.TextDelta
import dev.dontblameme.selvage.peer.RemoteEdit
import dev.dontblameme.selvage.peer.Selection
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.wire.Meta
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelvageSessionTest {
    private val relay = FakeRelay()
    private val scheduler = ManualScheduler()
    private val sessions = ArrayList<SelvageSession>()
    private val listed = listOf("README.md", "src/main.rs")

    /** What the host's working copy would read, the unlisted file included: the listing is the guard. */
    private val files = mapOf("README.md" to "hello\n", "src/main.rs" to "fn main() {}\n", "secret.txt" to "key")

    @AfterTest
    fun close() {
        sessions.forEach { it.leave() }
        relay.shutdown()
    }

    private class Recorded {
        val events = CopyOnWriteArrayList<SessionEvent>()

        inline fun <reified T : SessionEvent> all(): List<T> = events.filterIsInstance<T>()
    }

    private fun options(
        name: String,
        recorded: Recorded,
    ) = SessionOptions(
        name,
        transport = relay,
        scheduler = scheduler,
        meta = { _, _ -> Meta(null, emptyList(), emptyList(), null, 2_000) },
        listener = { recorded.events.add(it) },
    )

    private fun host(recorded: Recorded = Recorded()): SelvageSession =
        SelvageSession
            .host("ws://relay.test", HostContent({ listed }, { files[it] }), options("Ada", recorded))
            .also { sessions.add(it) }
            .also { relay.settle() }

    private fun join(
        host: SelvageSession,
        recorded: Recorded = Recorded(),
        name: String = "Bob",
        role: Role? = null,
    ): SelvageSession {
        val guest = SelvageSession.join(host.invite!!, options(name, recorded), role).also { sessions.add(it) }
        relay.settle()
        advance(300)
        return guest
    }

    private fun advance(ms: Long) = scheduler.advance(ms) { relay.settle() }

    @Test
    fun `a guest joins, is served what it opens, and both sides converge`() {
        val host = host()
        val seen = Recorded()
        val guest = join(host, seen)
        assertEquals(Role.GUEST, guest.ownRole())
        assertEquals(listOf("README.md", "src/main.rs"), guest.listing())
        assertTrue(seen.all<SessionEvent.Seated>().size == 1)

        guest.open("README.md")
        relay.settle()
        assertEquals("hello\n", guest.text("README.md"))
        assertEquals(
            listOf(RemoteEdit("README.md", listOf(TextDelta.Insert("hello\n")))),
            seen.all<SessionEvent.RemoteEdits>().flatMap { it.edits },
        )

        assertTrue(guest.insert("README.md", 5, ", world"))
        host.insert("README.md", 0, "> ")
        relay.settle()
        assertEquals("> hello, world\n", host.text("README.md"))
        assertEquals(host.text("README.md"), guest.text("README.md"))
    }

    @Test
    fun `a path that is not listed is not served`() {
        val host = host()
        val guest = join(host)
        guest.open("secret.txt")
        relay.settle()
        assertEquals(listOf("secret.txt"), host.openSet())
        assertTrue(!host.has("secret.txt"))
    }

    @Test
    fun `a caret arrives at the host as a selection`() {
        val seen = Recorded()
        val host = host(seen)
        val guest = join(host)
        guest.open("README.md")
        relay.settle()
        guest.setCursor("README.md", Selection(1, 3))
        relay.settle()
        val cursor =
            seen
                .all<SessionEvent.Presence>()
                .last()
                .cursors
                .single()
        assertEquals(guest.awarenessClientId(), cursor.clientId)
        assertEquals(Selection(1, 3), cursor.selection)
    }

    @Test
    fun `a rename is answered and reaches the room`() {
        val host = host()
        val guest = join(host)
        guest.rename("Robert").get(5, TimeUnit.SECONDS)
        relay.settle()
        assertEquals("Robert", guest.displayName)
        assertEquals(listOf("Robert"), host.peers().map { it.displayName })
        assertFailsWith<IllegalArgumentException> { guest.rename("a".repeat(33)) }
    }

    @Test
    fun `the host leaving opens the grace window, and the guest ends at its close`() {
        val host = host()
        val seen = Recorded()
        val guest = join(host, seen)
        host.leave()
        relay.settle()
        assertEquals(listOf(SessionEvent.HostAway(900)), seen.all<SessionEvent.HostAway>())
        advance(899)
        assertEquals(1L, guest.hostAwayGraceMs())
        assertNull(guest.ending)
        advance(1)
        assertEquals(SessionEnding.HOST_AWAY, guest.ending)
        assertEquals(listOf(SessionEvent.Ended(SessionEnding.HOST_AWAY)), seen.all<SessionEvent.Ended>())
    }

    @Test
    fun `a dropped guest reconnects under a new seat and key and edits again`() {
        val host = host()
        val seen = Recorded()
        val guest = join(host, seen)
        val first = guest.seat!!
        relay.connection(first).drop()
        relay.settle()
        assertEquals(listOf(SessionEvent.Reconnecting(1)), seen.all<SessionEvent.Reconnecting>())
        // Detached: the edit is held back and sent once a state commits the new key.
        assertTrue(!guest.insert("README.md", 0, "offline "))
        advance(500)
        advance(300)
        assertTrue(guest.seat != first)
        assertEquals(2, seen.all<SessionEvent.Seated>().size)
        assertEquals(Role.GUEST, guest.ownRole())
        guest.insert("README.md", 0, "back ")
        relay.settle()
        assertEquals(guest.text("README.md"), host.text("README.md"))
        assertTrue(host.text("README.md").startsWith("back offline "), host.text("README.md"))
    }

    @Test
    fun `a refused link and a refused room are said, not retried`() {
        val host = host()
        val noKeys = host.invite!!.substringBefore('#')
        val refused = assertFailsWith<SessionException> { SelvageSession.join(noKeys, options("Bob", Recorded())) }
        assertEquals("invite", refused.code)
        val wrongToken = host.invite!!.replace("token=", "token=x")
        val unknown = assertFailsWith<SessionException> { SelvageSession.join(wrongToken, options("Bob", Recorded())) }
        assertEquals("room_unknown", unknown.code)
        assertFailsWith<SessionException> { SelvageSession.join(host.invite!!, options(" ", Recorded())) }
    }

    @Test
    fun `the host closing the room ends the guest`() {
        val host = host()
        val seen = Recorded()
        val guest = join(host, seen)
        assertTrue(host.closeRoom())
        relay.settle()
        assertEquals(SessionEnding.CLOSING, guest.ending)
        assertEquals(SessionEnding.CLOSING, host.ending)
    }
}
