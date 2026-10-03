package dev.dontblameme.selvage.engine

import dev.dontblameme.selvage.crdt.Json
import dev.dontblameme.selvage.crdt.YAny
import dev.dontblameme.selvage.peer.Ending
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

/** What a burst types: one and several code units, a surrogate pair among them. */
private val PIECES = listOf("a", "bc", "😀", "é", "\n", "xyz")

/**
 * This engine and the TypeScript one the other three clients share (`vscode_client/src/engine` and
 * `src/bridge`), in one room on a real `selvaged`, each in both roles: the listing, a document's
 * content, concurrent edits, carets past a surrogate pair, a rename each way, and the host leaving.
 */
@Tag("live")
class CrossImplementationTest {
    private val renew = 300L
    private val expire = 1_500L
    private val path = "README.md"

    // Not in sorted order, so a listing that is reordered on the way is seen.
    private val files =
        linkedMapOf(
            "zeta/notes.txt" to "last\n",
            path to "héllo 😀 wörld\n",
            "src/main.rs" to "fn main() {}\n",
        )

    /** A caret or selection as a side sees a peer's: who, where, and the offsets it resolved to. */
    data class Caret(
        val clientId: Long,
        val name: String?,
        val path: String?,
        val selection: Selection?,
    )

    /** One participant, whichever engine it is. */
    interface Side {
        val label: String

        fun role(): String?

        fun awarenessId(): Long

        fun listing(): List<String>

        fun open(path: String)

        /** Null when the replica holds nothing for [path]. */
        fun text(path: String): String?

        fun insert(
            path: String,
            index: Int,
            text: String,
        )

        fun delete(
            path: String,
            index: Int,
            length: Int,
        )

        fun select(
            path: String,
            anchor: Int,
            head: Int,
        )

        fun carets(): List<Caret>

        /**
         * [count] edits back to back, each placed against this replica as it then stands, chosen
         * by a 48-bit linear congruential generator seeded with [seed] (`ts-peer.mjs`'s `burst`).
         */
        fun burst(
            path: String,
            seed: Long,
            count: Int,
        )

        fun rename(name: String)

        fun displayName(): String

        fun peerNames(): List<String>

        /** The grace each host-away report carried, in order. */
        fun hostAway(): List<Long>

        /** Each ending reported, as its wire name and the sentence a user is shown. */
        fun endings(): List<Pair<String, String?>>

        fun leave()

        fun state(): String
    }

    private inner class KotlinSide(
        override val label: String,
        val session: SelvageSession,
        val events: MutableList<SessionEvent>,
    ) : Side {
        override fun role() = session.ownRole()?.name?.lowercase()

        override fun awarenessId() = session.awarenessClientId()

        override fun listing() = session.listing()

        override fun open(path: String) = session.open(path)

        override fun text(path: String) = if (session.has(path)) session.text(path) else null

        override fun insert(
            path: String,
            index: Int,
            text: String,
        ) {
            assertTrue(session.insert(path, index, text), "$label published no insert")
        }

        override fun delete(
            path: String,
            index: Int,
            length: Int,
        ) {
            assertTrue(session.delete(path, index, length), "$label published no delete")
        }

        override fun select(
            path: String,
            anchor: Int,
            head: Int,
        ) = session.setCursor(path, Selection(anchor, head))

        override fun carets(): List<Caret> {
            val peers = session.peers()
            return session.cursors().map { cursor ->
                Caret(
                    cursor.clientId,
                    peers.firstOrNull { it.awarenessClientId == cursor.clientId }?.displayName,
                    cursor.path,
                    cursor.selection,
                )
            }
        }

        override fun burst(
            path: String,
            seed: Long,
            count: Int,
        ) {
            var state = seed

            fun next(bound: Int): Int {
                state = (state * 25_214_903_917L + 11L) and ((1L shl 48) - 1)
                return ((state ushr 17) % bound).toInt()
            }

            fun whole(
                text: String,
                at: Int,
            ) = if (at > 0 && at < text.length && text[at - 1].isHighSurrogate()) at - 1 else at
            repeat(count) {
                val text = session.text(path)
                try {
                    if (text.isNotEmpty() && next(3) == 0) {
                        val start = whole(text, next(text.length))
                        val end = whole(text, minOf(text.length, start + 1 + next(3)))
                        if (end > start) session.delete(path, start, end - start)
                    } else {
                        session.insert(path, whole(text, next(text.length + 1)), PIECES[next(PIECES.size)])
                    }
                } catch (e: IndexOutOfBoundsException) {
                    // A remote edit shortened the text between the read and the edit; the next one reads again.
                }
            }
        }

        override fun rename(name: String) {
            session.rename(name).get(5, TimeUnit.SECONDS)
        }

        override fun displayName() = session.displayName

        override fun peerNames() = session.peers().map { it.displayName }

        override fun hostAway() = events.filterIsInstance<SessionEvent.HostAway>().map { it.graceMs }

        override fun endings() =
            events.filterIsInstance<SessionEvent.Ended>().map { it.ending.wire to it.ending.sentence }

        override fun leave() = session.leave()

        override fun state() =
            "$label{role=${role()}, listing=${listing()}, texts=${session.documents().associateWith(session::text)}, " +
                "carets=${carets()}, peers=${session.peers()}, ending=${session.ending}, " +
                "events=${events.filter { it !is SessionEvent.RemoteEdits && it !is SessionEvent.Presence }}}"
    }

    private class TsSide(
        override val label: String,
        val peer: TsPeer,
    ) : Side {
        fun report(): YAny.Obj = peer.call("report")

        override fun role() = report().str("role")

        override fun awarenessId() = report().num("awarenessClientId") ?: fail("$label has no awareness id")

        override fun listing() = report().strings("listing")

        override fun open(path: String) {
            peer.call("open", "path" to YAny.Str(path))
        }

        override fun text(path: String) = report().obj("texts")?.str(path)

        override fun insert(
            path: String,
            index: Int,
            text: String,
        ) {
            val reply =
                peer.call(
                    "insert",
                    "path" to YAny.Str(path),
                    "index" to YAny.of(index),
                    "text" to YAny.Str(text),
                )
            assertEquals(YAny.Bool(true), reply["published"], "$label published no insert")
        }

        override fun delete(
            path: String,
            index: Int,
            length: Int,
        ) {
            val reply =
                peer.call("delete", "path" to YAny.Str(path), "index" to YAny.of(index), "length" to YAny.of(length))
            assertEquals(YAny.Bool(true), reply["published"], "$label published no delete")
        }

        override fun select(
            path: String,
            anchor: Int,
            head: Int,
        ) {
            peer.call("select", "path" to YAny.Str(path), "anchor" to YAny.of(anchor), "head" to YAny.of(head))
        }

        override fun carets(): List<Caret> =
            report().arr("presence").map { raw ->
                val record = raw as YAny.Obj
                val resolved = record.obj("resolved")
                Caret(
                    record.num("clientId") ?: fail("a presence record without a client id: $record"),
                    record.str("displayName"),
                    record.str("path"),
                    resolved?.let { Selection(it.num("anchor")!!.toInt(), it.num("head")!!.toInt()) },
                )
            }

        override fun burst(
            path: String,
            seed: Long,
            count: Int,
        ) {
            peer.call("burst", "path" to YAny.Str(path), "seed" to YAny.of(seed), "count" to YAny.of(count))
        }

        override fun rename(name: String) {
            peer.call("rename", "name" to YAny.Str(name))
        }

        override fun displayName() = report().str("displayName") ?: ""

        override fun peerNames() = report().arr("peers").mapNotNull { (it as? YAny.Obj)?.str("display_name") }

        private fun events(type: String) =
            report().arr("events").map { it as YAny.Obj }.filter { it.str("type") == type }

        override fun hostAway() = events("hostDetached").map { it.num("graceMs") ?: -1 }

        override fun endings(): List<Pair<String, String?>> {
            val report = report()
            val ending = report.str("ending")
            // The engine's adapter event for an ending is `roomGone`, carrying the sentence; the
            // wire name is the relay's own reading of the same ending.
            return report
                .arr("events")
                .map { it as YAny.Obj }
                .filter { it.str("type") == "roomGone" || it.str("type") == "disconnected" }
                .map { (ending ?: "?") to it.str("reason") }
        }

        override fun leave() {
            peer.call("leave")
        }

        override fun state() = "$label${Json.stringify(report())}"
    }

    private fun options(
        name: String,
        events: MutableList<SessionEvent>,
    ) = SessionOptions(
        name,
        handshakeTimeout = Duration.ofSeconds(5),
        metaTimeout = Duration.ofSeconds(2),
        requestTimeout = Duration.ofSeconds(5),
        keepalive = Keepalive(30_000, renew, expire),
        listener = { events.add(it) },
    )

    private val tsKeepalive =
        "keepalive" to
            YAny.Obj.of(
                "ping_interval_ms" to YAny.of(30_000),
                "awareness_renew_ms" to YAny.of(renew),
                "awareness_expire_ms" to YAny.of(expire),
            )

    private fun kotlinHost(server: LiveServer): KotlinSide {
        val events = CopyOnWriteArrayList<SessionEvent>()
        val session =
            SelvageSession.host(
                server.base,
                HostContent({ files.keys.toList() }, { files[it] }),
                options("Ada", events),
            )
        return KotlinSide("kotlin-host", session, events)
    }

    private fun kotlinGuest(invite: String): KotlinSide {
        val events = CopyOnWriteArrayList<SessionEvent>()
        return KotlinSide("kotlin-guest", SelvageSession.join(invite, options("Bob", events)), events)
    }

    private fun tsHost(
        server: LiveServer,
        peer: TsPeer,
    ): Pair<TsSide, String> {
        val files = YAny.Obj.of(files.map { (k, v) -> k to YAny.Str(v) })
        val reply =
            peer.call("host", "base" to YAny.Str(server.base), "name" to YAny.Str("Ada"), "files" to files, tsKeepalive)
        return TsSide("ts-host", peer) to (reply.str("invite") ?: fail("the TypeScript host minted no invite: $reply"))
    }

    private fun tsGuest(
        invite: String,
        peer: TsPeer,
    ): TsSide {
        peer.call("join", "invite" to YAny.Str(invite), "name" to YAny.Str("Bob"), tsKeepalive)
        return TsSide("ts-guest", peer)
    }

    private fun room(body: (LiveServer, MutableList<AutoCloseable>) -> Unit) {
        val dir = Files.createTempDirectory("selvage-crossing").toFile()
        val owned = ArrayList<AutoCloseable>()
        val server = LiveServer.start(dir)
        try {
            body(server, owned)
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}\nselvaged stderr:\n${server.stderr()}", e)
        } finally {
            owned.asReversed().forEach { runCatching { it.close() } }
            server.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a Kotlin host and a TypeScript guest`() =
        room { server, owned ->
            val host = kotlinHost(server).also { side -> owned.add(AutoCloseable { side.leave() }) }
            val invite = host.session.invite ?: fail("the host has no invite")
            val peer = TsPeer().also(owned::add)
            exercise(host, tsGuest(invite, peer))
        }

    @Test
    fun `a TypeScript host and a Kotlin guest`() =
        room { server, owned ->
            val peer = TsPeer().also(owned::add)
            val (host, invite) = tsHost(server, peer)
            val guest = kotlinGuest(invite).also { side -> owned.add(AutoCloseable { side.leave() }) }
            exercise(host, guest)
        }

    private fun exercise(
        host: Side,
        guest: Side,
    ) {
        val both = { "\n  ${host.state()}\n  ${guest.state()}" }

        // The listing, as the host published it.
        eventually("the guest is committed and holds the host's listing", observed = both) {
            guest.role() == "guest" && guest.listing().toSet() == files.keys
        }
        assertEquals(Role.HOST.name.lowercase(), host.role())
        assertEquals(host.listing(), guest.listing(), "the listing's order")

        // A document's content, served on the guest's hold.
        guest.open(path)
        eventually("the host serves $path", observed = both) { guest.text(path) == files[path] }
        assertEquals(files[path], host.text(path))

        // Concurrent inserts: both sides type at the start without waiting for each other, so
        // the two engines order each other's items at one position.
        val base = files[path]!!
        val tokens = (0 until 5).flatMap { listOf("g$it", "h$it") }
        for (i in 0 until 5) {
            guest.insert(path, 0, "g$i")
            host.insert(path, 0, "h$i")
        }
        eventually("both replicas converge on the concurrent inserts", observed = both) {
            val text = host.text(path)
            text != null && text.length == base.length + tokens.sumOf { it.length } && text == guest.text(path)
        }
        val burst = host.text(path)!!
        assertTrue(burst.endsWith(base), burst)
        tokens.forEach { assertEquals(1, burst.windowed(it.length).count(it::equals), "$it in $burst") }

        // Then a replacement from each side, unordered between them: the guest's later in the text
        // than the host's, so neither side's offsets depend on whether the other's edit has landed.
        val later = burst.indexOf("wörld")
        guest.delete(path, later, "wörld".length)
        guest.insert(path, later, "world")
        val earlier = burst.indexOf("héllo")
        host.delete(path, earlier, "héllo".length)
        host.insert(path, earlier, "hello")
        val expected = burst.replace("wörld", "world").replace("héllo", "hello")
        eventually("both replicas converge on the replacements", observed = both) {
            host.text(path) == expected && guest.text(path) == expected
        }
        val converged = expected

        // Carets in UTF-16 offsets, past a surrogate pair, each way.
        val emojiAt = converged.indexOf("😀")
        val after = emojiAt + 2
        guest.select(path, after, after)
        host.select(path, after, emojiAt)
        val guestId = guest.awarenessId()
        val hostId = host.awarenessId()

        fun sees(
            viewer: Side,
            id: Long,
            name: String,
            selection: Selection,
        ) = viewer.carets().any { it.clientId == id && it.name == name && it.path == path && it.selection == selection }
        eventually("each side sees the other's caret after the surrogate pair", observed = both) {
            sees(host, guestId, "Bob", Selection(after, after)) && sees(guest, hostId, "Ada", Selection(after, emojiAt))
        }

        // An insert ahead of both carets moves them by its length on both sides.
        host.insert(path, 0, "🎉")
        eventually("both carets follow an insert ahead of them", observed = both) {
            guest.text(path) == host.text(path) &&
                sees(host, guestId, "Bob", Selection(after + 2, after + 2)) &&
                sees(guest, hostId, "Ada", Selection(after + 2, emojiAt + 2))
        }

        // Both sides editing at once, each against its own replica, until neither has more to say.
        var failure: Throwable? = null
        val alongside =
            Thread({ runCatching { guest.burst(path, 7, 150) }.onFailure { failure = it } }, "guest-burst")
                .apply { start() }
        host.burst(path, 11, 150)
        alongside.join(TimeUnit.SECONDS.toMillis(TsPeer.REPLY_TIMEOUT_S))
        if (alongside.isAlive) fail("the guest's burst did not finish in ${TsPeer.REPLY_TIMEOUT_S} s")
        failure?.let { throw AssertionError("the guest's burst failed", it) }
        eventually("both replicas converge after both bursts", observed = both) {
            val text = host.text(path)
            text != null && text == guest.text(path)
        }

        // A rename each way.
        guest.rename("Robert")
        host.rename("Adeline")
        eventually("each rename reaches the other side", observed = both) {
            host.peerNames() == listOf("Robert") &&
                guest.peerNames() == listOf("Adeline") &&
                guest.displayName() == "Robert" &&
                host.displayName() == "Adeline"
        }

        // The host leaving: the guest's host-away window opens, then closes the session.
        host.leave()
        eventually("the guest sees the host away", observed = both) { guest.hostAway().isNotEmpty() }
        val grace = guest.hostAway().single()
        assertTrue(grace in 1..expire, "grace $grace")
        eventually("the window ends the guest's session", timeoutMs = expire + 5_000, observed = { guest.state() }) {
            guest.endings().isNotEmpty()
        }
        assertEquals(listOf("host-away" to Ending.HOST_AWAY.sentence), guest.endings(), guest.state())
        assertEquals(1, guest.hostAway().size, guest.state())
    }
}
