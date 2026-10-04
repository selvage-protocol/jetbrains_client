package dev.dontblameme.selvage.engine

import dev.dontblameme.selvage.crdt.TextDelta
import dev.dontblameme.selvage.peer.RemoteEdit
import dev.dontblameme.selvage.peer.Selection
import dev.dontblameme.selvage.sealed.Bytes
import dev.dontblameme.selvage.sealed.DropReason
import dev.dontblameme.selvage.sealed.Frames
import dev.dontblameme.selvage.sealed.Invite
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.sealed.SessionKey
import dev.dontblameme.selvage.wire.Meta
import dev.dontblameme.selvage.wire.SocketListener
import dev.dontblameme.selvage.wire.Wire
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
        relay.settle()
        relay.shutdown()
        assertEquals(emptyList(), relay.escaped.map { it.toString() }, "thrown into the transport")
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

    private fun host(
        recorded: Recorded = Recorded(),
        read: (String) -> String? = { files[it] },
    ): SelvageSession =
        SelvageSession
            .host("ws://relay.test", HostContent({ listed }, read), options("Ada", recorded))
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
    fun `the host reads a file outside the session's lock`() {
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val host =
            host(read = { path ->
                if (path == "README.md") {
                    reading.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                files[path]
            })
        val guest = join(host)
        guest.open("README.md")
        assertTrue(reading.await(5, TimeUnit.SECONDS), "the host never read README.md")
        val answered =
            java.util.concurrent.CompletableFuture.supplyAsync {
                host.peers()
                host.text("README.md")
            }
        try {
            assertEquals("", answered.get(5, TimeUnit.SECONDS), "the room has no text for it until the read is done")
        } catch (e: java.util.concurrent.TimeoutException) {
            fail("the host's state waited on the read")
        } finally {
            release.countDown()
        }
        relay.settle()
        assertEquals("hello\n", guest.text("README.md"))
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
    fun `a reconnect whose handshake is refused retries once, and leaves no second seat`() {
        val host = host()
        val seen = Recorded()
        val guest = join(host, seen)
        relay.connection(guest.seat!!).drop()
        relay.settle()
        relay.refuseHello = "hello_required"
        advance(500)
        assertEquals(listOf(1, 2), seen.all<SessionEvent.Reconnecting>().map { it.attempt })
        advance(10_000)
        advance(300)
        assertEquals(listOf(1, 2), seen.all<SessionEvent.Reconnecting>().map { it.attempt })
        assertEquals(2, seen.all<SessionEvent.Seated>().size)
        assertEquals(listOf(guest.seat), host.peers().map { it.peerId })
        assertEquals(Role.GUEST, guest.ownRole())
    }

    @Test
    fun `a refused frame is reported with its reason and sender, and the session goes on`() {
        val host = host()
        val seen = Recorded()
        val guest = join(host, seen)
        val invite = (Invite.parse(host.invite!!) as Invite.Read.Ok).invite
        val stranger = SessionKey.mint()
        val sealed =
            Frames.seal(
                invite.room,
                Frames.frameKey(invite.room, invite.roomKey),
                0,
                7,
                stranger,
                byteArrayOf(0, 0, 1, 0),
            )
        val raw =
            relay
                .open(
                    host.invite!!.substringBefore('#'),
                    java.time.Duration.ofSeconds(1),
                    object : SocketListener {
                        override fun onText(text: String) = Unit

                        override fun onBinary(bytes: ByteArray) = Unit

                        override fun onClose(
                            code: Int,
                            reason: String,
                        ) = Unit
                    },
                ).get(1, TimeUnit.SECONDS)
        raw.sendText(Wire.hello("Mallory", 99))
        relay.settle()
        val before = seen.all<SessionEvent.FrameRefused>().size
        raw.sendBinary(byteArrayOf(1, 2, 3))
        raw.sendBinary(sealed)
        relay.settle()
        val refused = seen.all<SessionEvent.FrameRefused>().drop(before)
        assertEquals(
            listOf(
                Triple(DropReason.BAD_ENVELOPE, null, null),
                Triple(DropReason.UNCOMMITTED_KEY, Bytes.hex(stranger.id), 7L),
            ),
            refused.map { Triple(it.reason, it.sender, it.counter) },
        )
        assertTrue(refused[0].frame < refused[1].frame, "$refused")
        assertNull(guest.ending)
        assertTrue(guest.insert("README.md", 0, "still "))
        relay.settle()
        assertEquals("still ", host.text("README.md"))
    }

    @Test
    fun `a binary frame before the seat is not held for it`() {
        val host = host()
        val seen = Recorded()
        relay.preSeat = List(3) { byteArrayOf(1, 2, 3) }
        val guest = join(host, seen)
        assertEquals(Role.GUEST, guest.ownRole())
        assertEquals(emptyList(), seen.all<SessionEvent.FrameRefused>().filter { it.reason == DropReason.BAD_ENVELOPE })
    }

    @Test
    fun `text held before the seat is bounded in bytes, not only in frames`() {
        val host = host()
        val noise = "{\"event\":\"x.noise\",\"params\":{\"pad\":\"${"a".repeat(3_000_000)}\"},\"v\":\"selvage/2\"}"
        relay.preSeat = List(3) { noise }
        val refused =
            assertFailsWith<SessionException> { SelvageSession.join(host.invite!!, options("Bob", Recorded())) }
        assertTrue(refused.message!!.contains("inbound queue full"), refused.message)
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

    @Test
    fun `an ended session fails what it still owed and stops its own thread`() {
        fun sessionThreads() = Thread.getAllStackTraces().keys.count { it.name == "selvage-session" && it.isAlive }
        val before = sessionThreads()
        val host = host()
        val guest =
            SelvageSession
                .join(
                    host.invite!!,
                    SessionOptions("Bob", transport = relay, meta = {
                        _,
                        _,
                        ->
                        Meta(null, emptyList(), emptyList(), null, null)
                    }),
                ).also { sessions.add(it) }
        relay.settle()
        assertEquals(before + 1, sessionThreads())
        relay.holdRenames = true
        val renamed = guest.rename("Robert")
        relay.settle()
        assertTrue(!renamed.isDone)
        assertTrue(host.closeRoom())
        relay.settle()
        assertEquals(SessionEnding.CLOSING, guest.ending)
        assertTrue(renamed.isCompletedExceptionally, "$renamed")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (sessionThreads() != before) {
            if (System.nanoTime() >
                deadline
            ) {
                fail("${sessionThreads()} session threads 5 s after the end, $before before")
            }
            java.util.concurrent.locks.LockSupport
                .parkNanos(1_000_000)
        }
    }

    @Test
    fun `a departed peer's caret goes at once, unless a seated peer still claims its awareness id`() {
        val host = host()
        val guest = join(host)
        guest.open("README.md")
        relay.settle()
        guest.setCursor("README.md", Selection(1, 1))
        relay.settle()
        val id = guest.awarenessClientId()
        assertEquals(listOf(id), host.cursors().map { it.clientId })

        // A second connection claims the guest's awareness id and leaves (§8.4: the claim is a
        // number any token holder may make, and last-claimant-wins).
        val squatter = CopyOnWriteArrayList<String>()
        val claim =
            relay
                .open(
                    host.invite!!.substringBefore('#'),
                    java.time.Duration.ofSeconds(1),
                    object : SocketListener {
                        override fun onText(text: String) {
                            squatter.add(text)
                        }

                        override fun onBinary(bytes: ByteArray) = Unit

                        override fun onClose(
                            code: Int,
                            reason: String,
                        ) = Unit
                    },
                ).get(1, TimeUnit.SECONDS)
        claim.sendText(Wire.hello("Mallory", id))
        relay.settle()
        assertEquals(2, host.peers().size, "$squatter")
        claim.close(1000, "gone")
        relay.settle()
        assertEquals(listOf(id), host.cursors().map { it.clientId }, "the guest still claims $id")

        // The guest leaving is what drops it, without waiting out the expiry.
        guest.leave()
        relay.settle()
        assertEquals(emptyList(), host.cursors())
    }

    @Test
    fun `a path the host cannot read is declined, and the next one is still served`() {
        var reads = 0
        val seen = Recorded()
        val host =
            host(seen) {
                reads += 1
                if (it == "README.md") throw java.io.UncheckedIOException(java.io.IOException("unreadable"))
                files[it]
            }
        val guest = join(host)
        guest.open("README.md")
        relay.settle()
        // The pass that met the failed read finished: it said what moved.
        assertEquals(SessionEvent.OpenSet(listOf("README.md")), seen.all<SessionEvent.OpenSet>().lastOrNull())
        guest.open("src/main.rs")
        relay.settle()
        advance(300)
        assertEquals("fn main() {}\n", guest.text("src/main.rs"))
        assertEquals("", guest.text("README.md"))
        assertEquals(2, reads, "each path is read once")
        assertNull(host.ending)
    }

    @Test
    fun `an error while handling a frame does not reach the transport`() {
        var failed = 0
        val host =
            host {
                if (it == "src/main.rs") {
                    failed += 1
                    throw StackOverflowError()
                }
                files[it]
            }
        val guest = join(host)
        guest.open("src/main.rs")
        relay.settle()
        advance(300)
        assertNull(host.ending)
        guest.open("README.md")
        relay.settle()
        advance(300)
        assertEquals("hello\n", guest.text("README.md"))
        assertEquals(1, failed, "a path whose read failed is not read again")
        assertEquals(emptyList(), relay.escaped.map { it.toString() })
    }
}
