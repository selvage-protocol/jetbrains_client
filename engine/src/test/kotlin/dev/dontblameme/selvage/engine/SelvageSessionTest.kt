package dev.dontblameme.selvage.engine

import dev.dontblameme.selvage.crdt.TextDelta
import dev.dontblameme.selvage.peer.HostProducer
import dev.dontblameme.selvage.peer.HostStore
import dev.dontblameme.selvage.peer.PersistedHost
import dev.dontblameme.selvage.peer.RemoteEdit
import dev.dontblameme.selvage.peer.Selection
import dev.dontblameme.selvage.sealed.Bytes
import dev.dontblameme.selvage.sealed.DropReason
import dev.dontblameme.selvage.sealed.FrameCrypto
import dev.dontblameme.selvage.sealed.Frames
import dev.dontblameme.selvage.sealed.Invite
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.sealed.SessionKey
import dev.dontblameme.selvage.wire.Meta
import dev.dontblameme.selvage.wire.ReconnectPolicy
import dev.dontblameme.selvage.wire.SocketListener
import dev.dontblameme.selvage.wire.Wire
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        errors: ErrorSink = StderrErrorSink,
        listener: SessionListener? = SessionListener { recorded.events.add(it) },
        graceMs: Long = 2_000,
        hostStore: HostStore? = null,
        hostSeed: ByteArray? = null,
    ) = SessionOptions(
        name,
        transport = relay,
        scheduler = scheduler,
        meta = { _, _ -> Meta(null, emptyList(), emptyList(), null, graceMs) },
        listener = listener,
        errors = errors,
        hostStore = hostStore,
        hostSeed = hostSeed,
    )

    /** A host store in memory that keeps every record, so a test can read what the host wrote. */
    private class RecordingHostStore(
        vararg seeding: PersistedHost,
    ) : HostStore {
        val records = CopyOnWriteArrayList<PersistedHost>()

        init {
            seeding.forEach(records::add)
        }

        override fun load(): PersistedHost? = records.lastOrNull()

        override fun save(persisted: PersistedHost) {
            records.add(persisted)
        }
    }

    private fun host(
        recorded: Recorded = Recorded(),
        errors: ErrorSink = StderrErrorSink,
        graceMs: Long = 2_000,
        store: HostStore? = null,
        seed: ByteArray? = null,
        read: (String) -> String? = { files[it] },
    ): SelvageSession =
        SelvageSession
            .host(
                "ws://relay.test",
                HostContent({ listed }, read),
                options("Ada", recorded, errors, graceMs = graceMs, hostStore = store, hostSeed = seed),
            ).also { sessions.add(it) }
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

    /**
     * Steps the session clock until [condition] holds, and fails saying what it observed when it
     * does not: no fixed wait is a test of an effect that arrives on another thread.
     */
    private fun awaitEvent(
        what: String,
        describe: () -> String,
        virtualMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        var moved = 0L
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (moved >= virtualMs || System.nanoTime() > deadline) {
                fail("$what did not happen: ${describe()} after ${moved}ms of session clock")
            }
            advance(100)
            moved += 100
        }
    }

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
    fun `the people are said again once the roles that name them land`() {
        val host = host()
        val guest = SelvageSession.join(host.invite!!, options("Bob", Recorded())).also { sessions.add(it) }
        val atJoin = guest.rolesBySeat()
        val atPeers = CopyOnWriteArrayList<Map<String, Role>>()
        guest.addListener { if (it is SessionEvent.Peers) atPeers.add(guest.rolesBySeat()) }
        relay.settle()
        advance(300)
        val roles = guest.rolesBySeat()
        assertEquals(Role.HOST, roles[host.seat], "the guest knows who hosts")
        assertEquals(roles, atPeers.lastOrNull() ?: atJoin, "the last people the guest heard named the roles it holds")
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
        val roomUrl = (Invite.parse(host.invite!!) as Invite.Read.Ok).invite.socketUrl
        relay.connection(first).drop()
        relay.settle()
        assertEquals(listOf(SessionEvent.Reconnecting(1)), seen.all<SessionEvent.Reconnecting>())
        // Detached: the edit is held back and sent once a state commits the new key.
        assertTrue(!guest.insert("README.md", 0, "offline "))
        advance(500)
        advance(300)
        assertTrue(guest.seat != first)
        assertEquals(2, seen.all<SessionEvent.Seated>().size)
        assertEquals(roomUrl, relay.connection(guest.seat!!).url, "a guest re-hellos the room URL its invite carries")
        assertEquals(Role.GUEST, guest.ownRole())
        guest.insert("README.md", 0, "back ")
        relay.settle()
        assertEquals(guest.text("README.md"), host.text("README.md"))
        assertTrue(host.text("README.md").startsWith("back offline "), host.text("README.md"))
    }

    @Test
    fun `a host whose socket drops reconnects on its room URL and resumes above the edition it held`() {
        val store = RecordingHostStore()
        val seen = Recorded()
        val hosting = host(seen, store = store)
        val guestEvents = Recorded()
        val guest = join(hosting, guestEvents)
        hosting.insert("README.md", 0, "before ")
        advance(1_000)
        val room = hosting.roomId
        val roomUrl = (Invite.parse(hosting.invite!!) as Invite.Read.Ok).invite.socketUrl
        val first = hosting.seat!!
        val edition = store.records.last().issued
        val frames = store.records.last().frames!!
        val written = store.records.size

        relay.connection(first).drop()
        relay.settle()
        assertEquals(listOf(SessionEvent.Reconnecting(1)), seen.all<SessionEvent.Reconnecting>())
        assertNull(hosting.ending, "a host's drop is recoverable")
        assertTrue(!hosting.insert("README.md", 0, "offline "))

        advance(500)
        advance(300)
        assertTrue(hosting.seat != first, "the host is a new peer")
        assertEquals(room, hosting.roomId, "the retry must not mint a second room")
        assertEquals(
            roomUrl,
            relay.connection(hosting.seat!!).url,
            "a host re-hellos the room URL its invite carries, token and all",
        )

        fun observed() =
            "seat=${hosting.seat} ending=${hosting.ending} " +
                "reconnecting=${seen.all<SessionEvent.Reconnecting>().map { it.attempt }}"
        awaitEvent("the guest names the returning host", ::observed) {
            guest.rolesBySeat()[hosting.seat] == Role.HOST
        }
        awaitEvent("the guest sees the host back", ::observed) {
            guestEvents.all<SessionEvent.HostBack>().isNotEmpty()
        }

        // §9.1: the return is a state above the room's edition, and §6.1 charges its count for
        // the absence. The count the store holds lags the room's by at most the frames sealed
        // since the last write, which no test approaches the 2²¹ charge by.
        val resumed =
            store.records.drop(written).firstOrNull { it.issued > edition }
                ?: fail("the returning host published no state: ${store.records.drop(written)}")
        assertEquals(edition + 1, resumed.issued, "the resumed state is above the edition it held")
        assertTrue(
            resumed.frames!! - frames >= HostProducer.ABSENCE_CHARGE,
            "the return carries the absence charge: ${resumed.frames} after $frames",
        )
        assertEquals(2, seen.all<SessionEvent.Seated>().size)

        hosting.insert("README.md", 0, "back ")
        awaitEvent("both replicas hold every edit", ::observed) {
            hosting.text("README.md") == guest.text("README.md") &&
                guest.text("README.md").startsWith("back offline ")
        }
    }

    /** What a host comes back to: the seat it held before the drop, and one that left while it was away. */
    private class ReturnedRoom(
        val hosting: SelvageSession,
        val oldSeat: String,
        val goneSeat: String,
    )

    /**
     * A host whose socket drops, a guest that leaves while it is away, and the host back on the
     * room URL. The drop of its own seat and that `peer.left` both miss it, so the roster the
     * server hands back on the re-hello is the only place its return can learn what the room is.
     */
    private fun hostReturnedToADepartedRoom(): ReturnedRoom {
        val hosting = host()
        val stays = join(hosting)
        val leaves = join(hosting, name = "Cy")

        fun roles() = "host=${hosting.seat} roles=${hosting.rolesBySeat()}"
        awaitEvent("each guest to hold the role the host gave it", ::roles) {
            hosting.rolesBySeat()[stays.seat] == Role.GUEST &&
                hosting.rolesBySeat()[leaves.seat] == Role.GUEST
        }
        val oldSeat = hosting.seat!!
        val goneSeat = leaves.seat!!

        relay.connection(oldSeat).drop()
        relay.settle()
        leaves.leave()
        relay.settle()
        advance(500)
        advance(300)
        assertTrue(hosting.seat != oldSeat, "the host is a new peer")

        awaitEvent("the state the return publishes", ::roles) {
            hosting.rolesBySeat()[hosting.seat] == Role.HOST
        }
        return ReturnedRoom(hosting, oldSeat, goneSeat)
    }

    @Test
    fun `a returning host drops the seats that left while it was away`() {
        val room = hostReturnedToADepartedRoom()
        assertTrue(
            room.goneSeat !in room.hosting.rolesBySeat(),
            "the return seats a key where the guest that left during the absence was: ${room.hosting.rolesBySeat()}",
        )
    }

    @Test
    fun `a returning host seats a newcomer in the room, not in its own dead seat`() {
        val room = hostReturnedToADepartedRoom()
        val newcomer = join(room.hosting, name = "Dee")

        fun observed() = "newcomer=${newcomer.seat} roles=${room.hosting.rolesBySeat()}"
        awaitEvent("the host to answer the newcomer's announcement", ::observed) { newcomer.ownRole() != null }
        assertEquals(
            Role.GUEST,
            room.hosting.rolesBySeat()[newcomer.seat],
            "the seat the newcomer was committed to: ${observed()}",
        )
        assertTrue(
            room.oldSeat !in room.hosting.rolesBySeat(),
            "the newcomer was seated where the host's dead seat was: ${observed()}",
        )
    }

    @Test
    fun `a refused return ends the hosting session rather than retrying`() {
        val seen = Recorded()
        val hosting = host(seen)
        relay.connection(hosting.seat!!).drop()
        relay.settle()
        assertEquals(listOf(SessionEvent.Reconnecting(1)), seen.all<SessionEvent.Reconnecting>())

        relay.refuseHello = "room_unknown"
        advance(500)
        advance(1_000)
        assertEquals(SessionEnding.ROOM_GONE, hosting.ending)
        assertEquals(listOf("room_unknown"), seen.all<SessionEvent.Failed>().map { it.error.code })
        assertEquals(listOf(1), seen.all<SessionEvent.Reconnecting>().map { it.attempt }, "a refusal is not retried")
    }

    @Test
    fun `a host's retry budget is sized from the room's grace, as a guest's is`() {
        val seen = Recorded()
        val grace = 60_000L
        val hosting = host(seen, graceMs = grace)
        relay.connection(hosting.seat!!).drop()
        relay.settle()
        // Every hello is refused with a code a retry can change, so the retries run to the budget.
        relay.refuseEveryHello = "hello_required"
        advance(2 * grace)
        val expected = ReconnectPolicy().attemptsForGrace(grace)
        assertTrue(expected > ReconnectPolicy().maxAttempts, "the grace is meant to raise the budget")
        assertEquals(
            (1..expected).toList(),
            seen.all<SessionEvent.Reconnecting>().map { it.attempt },
            "the room's grace, not the default attempt count, is what the host's budget refuses to pass",
        )
        assertEquals(SessionEnding.CONNECTION_LOST, hosting.ending, "giving up is observable")
    }

    @Test
    fun `a host whose store holds its key continues the edition and the count from it`() {
        val seed = FrameCrypto.randomBytes(32)
        val store = RecordingHostStore(PersistedHost(seed, issued = 7, frames = 100))
        val seen = Recorded()
        val hosting = host(seen, store = store, seed = seed)
        val invite = (Invite.parse(hosting.invite!!) as Invite.Read.Ok).invite
        assertTrue(
            invite.hostKey.contentEquals(SessionKey.fromSeed(seed).public),
            "the stored seed is the room's host key",
        )
        assertEquals(8, store.records.last().issued, "the series continues above the stored edition")
        assertTrue(
            store.records.last().frames!! >= 100 + HostProducer.ABSENCE_CHARGE,
            "a reload is a return, so the stored count takes the charge: ${store.records.last().frames}",
        )
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
    fun `a close outside the close vocabulary during a reconnect schedules another attempt`() {
        // §11: 4000–4003 are the whole close vocabulary, and a client MUST NOT read a meaning into
        // a close outside it. 4004 is the `host_present` code this client used to read, 4005 is a
        // private-use code the reference server never sent, and 1013 is IANA's capacity fault.
        for (code in listOf(Wire.CLOSE_TRY_AGAIN_LATER, 4004, 4005)) {
            val host = host()
            val seen = Recorded()
            val guest = join(host, seen)
            relay.connection(guest.seat!!).drop()
            relay.settle()
            relay.closeHello = code
            relay.closeHelloReason = "a reason no protocol names"

            fun observed() =
                "ending=${guest.ending} reconnecting=${seen.all<SessionEvent.Reconnecting>().map { it.attempt }} " +
                    "failed=${seen.all<SessionEvent.Failed>()} ended=${seen.all<SessionEvent.Ended>()}"
            awaitEvent("a second attempt after a $code close", ::observed) {
                seen.all<SessionEvent.Reconnecting>().map { it.attempt } == listOf(1, 2)
            }
            awaitEvent("the seat a $code close was followed by", ::observed) {
                seen.all<SessionEvent.Seated>().size == 2
            }
            assertEquals(emptyList(), seen.all<SessionEvent.Failed>(), "what a $code close said")
            assertEquals(emptyList(), seen.all<SessionEvent.Ended>(), "how a $code close ended the session")
            assertNull(guest.ending, "the ending a $code close left")
        }
    }

    @Test
    fun `a capacity close at join is a refusal a retry can change`() {
        val host = host()
        relay.closeHello = Wire.CLOSE_TRY_AGAIN_LATER
        relay.closeHelloReason = "capacity reached"
        val refused =
            assertFailsWith<SessionException> { SelvageSession.join(host.invite!!, options("Bob", Recorded())) }
        assertEquals(Wire.TRY_AGAIN_LATER, refused.code)
        assertTrue(refused.message!!.contains("1013 capacity reached"), refused.message)
        assertTrue(!Wire.isTerminal(refused.code), "a full server is transient")
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

    @Test
    fun `an error at the handling boundary reaches the supplied sink with the throwable`() {
        val reported = CopyOnWriteArrayList<Pair<String, Throwable>>()
        val thrown = StackOverflowError()
        val host =
            host(
                errors = { what, error -> reported.add(what to error) },
                read = { if (it == "src/main.rs") throw thrown else files[it] },
            )
        val guest = join(host)
        guest.open("src/main.rs")
        relay.settle()
        advance(300)
        assertEquals(1, reported.size, "the sink hears the error the boundary caught")
        assertSame(thrown, reported.single().second)
        assertEquals("the session could not handle an event", reported.single().first)
        assertNull(host.ending, "the caught error did not end the session")
    }

    @Test
    fun `an error a listener raises reaches the supplied sink with the throwable`() {
        val reported = CopyOnWriteArrayList<Pair<String, Throwable>>()
        val thrown = IllegalStateException("a listener cannot read this event")
        val first = AtomicBoolean(true)
        SelvageSession
            .host(
                "ws://relay.test",
                HostContent({ listed }, { files[it] }),
                options(
                    "Ada",
                    Recorded(),
                    errors = { what, error -> reported.add(what to error) },
                    listener = SessionListener { if (first.compareAndSet(true, false)) throw thrown },
                ),
            ).also { sessions.add(it) }
        relay.settle()
        assertEquals(1, reported.size, "the sink hears one report for one raised exception")
        assertSame(thrown, reported.single().second)
        assertEquals("a session listener failed", reported.single().first)
    }

    @Test
    fun `with no sink the default reports one bounded line and the session survives`() {
        val thrown = StackOverflowError()
        val printed = ByteArrayOutputStream()
        val original = System.err
        var session: SelvageSession? = null
        System.setErr(PrintStream(printed, true, StandardCharsets.UTF_8))
        try {
            session =
                host(
                    read = { if (it == "src/main.rs") throw thrown else files[it] },
                )
            val guest = join(session)
            guest.open("src/main.rs")
            relay.settle()
            advance(300)
        } finally {
            System.setErr(original)
        }
        val lines = printed.toString(StandardCharsets.UTF_8).lines().filter { it.isNotBlank() }
        assertEquals(
            listOf("the session could not handle an event: $thrown"),
            lines,
            "one bounded line, not a stack trace per frame",
        )
        assertNull(session.ending, "the default sink kept the session")
    }
}
