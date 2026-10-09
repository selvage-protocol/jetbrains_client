package dev.dontblameme.selvage.engine

import dev.dontblameme.selvage.peer.Keepalive
import dev.dontblameme.selvage.peer.Selection
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.wire.JdkTransport
import dev.dontblameme.selvage.wire.SocketListener
import dev.dontblameme.selvage.wire.Transport
import dev.dontblameme.selvage.wire.WireSocket
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A transport whose open sockets a test can cut, so a session's socket drops as a real one does:
 * the listener hears the close first and the socket goes afterwards.
 */
private class Severable(
    private val inner: Transport = JdkTransport(),
) : Transport {
    private class Open(
        val socket: WireSocket,
        val listener: SocketListener,
        val cut: AtomicBoolean,
    )

    private val open = CopyOnWriteArrayList<Open>()

    override fun open(
        url: String,
        timeout: Duration,
        listener: SocketListener,
    ): CompletableFuture<WireSocket> {
        val cut = AtomicBoolean(false)
        val relayed =
            object : SocketListener {
                override fun onText(text: String) {
                    if (!cut.get()) listener.onText(text)
                }

                override fun onBinary(bytes: ByteArray) {
                    if (!cut.get()) listener.onBinary(bytes)
                }

                override fun onClose(
                    code: Int,
                    reason: String,
                ) {
                    if (cut.compareAndSet(false, true)) listener.onClose(code, reason)
                }
            }
        return inner.open(url, timeout, relayed).thenApply { socket ->
            socket.also { open.add(Open(it, listener, cut)) }
        }
    }

    /** Cuts every socket still open, and says how many that was. */
    fun sever(): Int {
        var count = 0
        for (each in open) {
            if (each.cut.compareAndSet(false, true)) {
                count += 1
                each.listener.onClose(1006, "cut by the test")
                each.socket.close()
            }
        }
        open.clear()
        return count
    }
}

/**
 * The engine against a real `selvaged` (SELVAGE_SELVAGED): a host mints, a second client joins,
 * they edit and converge, a caret and a rename cross, and the host leaving opens the guest's
 * host-away window, which then closes the session.
 */
@Tag("live")
class LiveSelvagedTest {
    private val renew = 300L
    private val expire = 1_500L

    private fun options(
        name: String,
        events: MutableList<SessionEvent>,
        transport: Transport = JdkTransport(),
        window: Long = expire,
    ) = SessionOptions(
        name,
        transport = transport,
        handshakeTimeout = Duration.ofSeconds(5),
        metaTimeout = Duration.ofSeconds(2),
        requestTimeout = Duration.ofSeconds(5),
        // Short windows so the host-away window closes inside the test; both clients agree on them.
        keepalive = Keepalive(30_000, renew, window),
        listener = { events.add(it) },
    )

    @Test
    fun `two clients converge through selvaged and the host leaving opens the grace window`() {
        val dir = Files.createTempDirectory("selvaged-live").toFile()
        val server = LiveServer.start(dir)
        val sessions = ArrayList<SelvageSession>()
        try {
            val files = mapOf("README.md" to "hello\n", "src/main.rs" to "fn main() {}\n")
            val hostEvents = CopyOnWriteArrayList<SessionEvent>()
            val host =
                SelvageSession
                    .host(server.base, HostContent({ files.keys.toList() }, { files[it] }), options("Ada", hostEvents))
                    .also { sessions.add(it) }
            val invite = host.invite ?: fail("the host has no invite")
            assertTrue(invite.contains("#k=") && invite.contains("&h="), invite)

            val guestEvents = CopyOnWriteArrayList<SessionEvent>()
            val guest = SelvageSession.join(invite, options("Bob", guestEvents)).also { sessions.add(it) }
            eventually("the guest is committed") { guest.ownRole() == Role.GUEST }
            assertEquals(listOf("README.md", "src/main.rs"), guest.listing())

            guest.open("README.md")
            eventually("the host serves README.md") { guest.text("README.md") == "hello\n" }

            guest.insert("README.md", 5, ", world")
            host.insert("README.md", 0, "> ")
            eventually("both replicas converge") {
                host.text("README.md") == "> hello, world\n" && guest.text("README.md") == host.text("README.md")
            }

            guest.setCursor("README.md", Selection(2, 7))
            eventually("the guest's caret reaches the host") {
                host.cursors().any { it.clientId == guest.awarenessClientId() && it.selection == Selection(2, 7) }
            }

            guest.rename("Robert").get(5, TimeUnit.SECONDS)
            eventually("the rename reaches the host") { host.peers().map { it.displayName } == listOf("Robert") }
            assertEquals("Robert", guest.displayName)

            host.leave()
            eventually("the guest sees the host away") { guestEvents.any { it is SessionEvent.HostAway } }
            val grace = guestEvents.filterIsInstance<SessionEvent.HostAway>().single().graceMs
            assertTrue(grace in 1..expire, "grace $grace")
            assertTrue((guest.hostAwayGraceMs() ?: 0) in 0..expire)
            eventually("the window closes the session", timeoutMs = expire + 5_000) {
                guest.ending == SessionEnding.HOST_AWAY
            }
        } finally {
            sessions.forEach { it.leave() }
            server.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a host whose socket drops rejoins its room and the guest sees it come back`() {
        val dir = Files.createTempDirectory("selvaged-host-return").toFile()
        val server = LiveServer.start(dir)
        val sessions = ArrayList<SelvageSession>()
        try {
            val files = mapOf("README.md" to "hello\n", "src/main.rs" to "fn main() {}\n")
            val hostEvents = CopyOnWriteArrayList<SessionEvent>()
            val severable = Severable()
            // A window longer than a return: the guest ends 10 s after the host's seat leaves.
            val window = 10_000L
            val host =
                SelvageSession
                    .host(
                        server.base,
                        HostContent({ files.keys.toList() }, { files[it] }),
                        options("Ada", hostEvents, severable, window),
                    ).also { sessions.add(it) }
            val invite = host.invite ?: fail("the host has no invite")
            val guestEvents = CopyOnWriteArrayList<SessionEvent>()
            val guest =
                SelvageSession
                    .join(invite, options("Bob", guestEvents, window = window))
                    .also { sessions.add(it) }
            eventually("the guest is committed", timeoutMs = 20_000) { guest.ownRole() == Role.GUEST }
            val listed = listOf("README.md", "src/main.rs")
            eventually("the guest has the listing", timeoutMs = 20_000) { guest.listing() == listed }
            guest.open("README.md")
            val served = { guest.text("README.md") == "hello\n" }
            eventually("the host serves the file the guest holds", timeoutMs = 20_000, condition = served)

            val room = host.roomId
            val first = host.seat!!
            assertEquals(1, severable.sever(), "one socket was cut")
            eventually("the host schedules a return") { hostEvents.any { it is SessionEvent.Reconnecting } }
            assertNull(host.ending, "a host's drop is recoverable")

            fun observed() =
                "seat=${host.seat} room=${host.roomId} (was $first in $room) " +
                    "ending=${host.ending} guestEnding=${guest.ending}"
            eventually("the host is back in the room it minted", timeoutMs = 20_000, observed = ::observed) {
                host.seat != first && host.roomId == room && hostEvents.count { it is SessionEvent.Seated } >= 2
            }
            eventually("the guest names the returning host", timeoutMs = 20_000, observed = ::observed) {
                guest.rolesBySeat()[host.seat] == Role.HOST
            }
            eventually("the guest sees the host back", timeoutMs = 20_000, observed = ::observed) {
                guestEvents.any { it is SessionEvent.HostBack }
            }

            host.insert("README.md", 0, "> ")
            guest.insert("README.md", 5, ", world")
            eventually("both replicas converge after the return", timeoutMs = 20_000, observed = ::observed) {
                host.text("README.md") == guest.text("README.md") && host.text("README.md") == "> hello, world\n"
            }
            assertNull(guest.ending)
        } finally {
            sessions.forEach { it.leave() }
            server.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a host alone in its room comes back to the same one inside the grace`() {
        val dir = Files.createTempDirectory("selvaged-host-alone").toFile()
        val server = LiveServer.start(dir)
        val sessions = ArrayList<SelvageSession>()
        try {
            val files = mapOf("README.md" to "hello\n")
            val events = CopyOnWriteArrayList<SessionEvent>()
            val severable = Severable()
            val host =
                SelvageSession
                    .host(
                        server.base,
                        HostContent({ files.keys.toList() }, { files[it] }),
                        options("Ada", events, severable),
                    ).also { sessions.add(it) }
            val invite = host.invite ?: fail("the host has no invite")
            val room = host.roomId
            val first = host.seat!!

            // The room's last connection goes: the server arms its grace and holds the room.
            assertEquals(1, severable.sever(), "one socket was cut")

            fun observed() = "seat=${host.seat} room=${host.roomId} (was $first in $room) ending=${host.ending}"
            eventually("the host is back in its room", timeoutMs = 20_000, observed = ::observed) {
                host.seat != first && host.roomId == room && events.count { it is SessionEvent.Seated } >= 2
            }
            assertNull(host.ending, "a host's drop is recoverable")

            // The room the returned host sits in is the one it minted: a fresh joiner lands in it
            // and hears the listing from the state the return published.
            val guestEvents = CopyOnWriteArrayList<SessionEvent>()
            val guest = SelvageSession.join(invite, options("Bob", guestEvents)).also { sessions.add(it) }
            eventually("a fresh joiner is committed", timeoutMs = 20_000, observed = ::observed) {
                guest.ownRole() == Role.GUEST
            }
            eventually("the returned host's listing reaches it", timeoutMs = 20_000) {
                guest.listing() == listOf("README.md")
            }
            guest.open("README.md")
            val served = { guest.text("README.md") == "hello\n" }
            eventually("the returned host still serves its file", timeoutMs = 20_000, condition = served)
        } finally {
            sessions.forEach { it.leave() }
            server.close()
            dir.deleteRecursively()
        }
    }
}
