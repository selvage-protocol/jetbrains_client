package dev.dontblameme.selvage.engine

import dev.dontblameme.selvage.peer.Keepalive
import dev.dontblameme.selvage.peer.Selection
import dev.dontblameme.selvage.sealed.Role
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
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

    private fun binary(): File {
        val path =
            System.getProperty("selvage.selvaged")
                ?: fail(
                    "SELVAGE_SELVAGED is not set: the live test spawns a real selvaged, so point it at one " +
                        "(cargo build --release -p selvaged in reference_server)",
                )
        val file = File(path)
        if (!file.canExecute()) fail("SELVAGE_SELVAGED is $path, which is not an executable file")
        return file
    }

    private class Server(
        val process: Process,
        val base: String,
    )

    private fun spawn(
        binary: File,
        dir: File,
    ): Server {
        val process =
            ProcessBuilder(binary.path, "--listen", "127.0.0.1:0", "--room-grace-ms", "5000")
                .redirectError(File(dir, "selvaged.err"))
                .start()
        val lines = LinkedBlockingQueue<String>()
        Thread({ process.inputStream.bufferedReader().forEachLine { lines.add(it) } }, "selvaged-out")
            .apply { isDaemon = true }
            .start()
        val first = lines.poll(10, TimeUnit.SECONDS)
        if (first == null) {
            process.destroyForcibly()
            fail("selvaged said nothing within 10 s; stderr: ${File(dir, "selvaged.err").readText().take(2000)}")
        }
        val url = Regex("ws://\\S+/session").find(first)?.value ?: fail("selvaged's first line names no URL: $first")
        return Server(process, url.removeSuffix("/session"))
    }

    private fun eventually(
        what: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("not within $timeoutMs ms: $what")
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
    }

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
        val binary = binary()
        val dir = Files.createTempDirectory("selvaged-live").toFile()
        val server = spawn(binary, dir)
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
            server.process.destroy()
            if (!server.process.waitFor(
                    5,
                    TimeUnit.SECONDS,
                )
            ) {
                server.process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
            dir.deleteRecursively()
        }
    }
}
