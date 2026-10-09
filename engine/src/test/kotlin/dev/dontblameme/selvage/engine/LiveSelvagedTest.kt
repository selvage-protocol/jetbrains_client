package dev.dontblameme.selvage.engine

import dev.dontblameme.selvage.peer.Keepalive
import dev.dontblameme.selvage.peer.Selection
import dev.dontblameme.selvage.sealed.Role
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

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
    ) = SessionOptions(
        name,
        handshakeTimeout = Duration.ofSeconds(5),
        metaTimeout = Duration.ofSeconds(2),
        requestTimeout = Duration.ofSeconds(5),
        // Short windows so the host-away window closes inside the test; both clients agree on them.
        keepalive = Keepalive(30_000, renew, expire),
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
}
