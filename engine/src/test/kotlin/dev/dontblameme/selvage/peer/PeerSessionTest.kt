package dev.dontblameme.selvage.peer

import dev.dontblameme.selvage.crdt.Lib0Decoder
import dev.dontblameme.selvage.crdt.Sync
import dev.dontblameme.selvage.crdt.SyncMessage
import dev.dontblameme.selvage.crdt.TextDelta
import dev.dontblameme.selvage.sealed.Envelope
import dev.dontblameme.selvage.sealed.FrameCrypto
import dev.dontblameme.selvage.sealed.Frames
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.sealed.SessionKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two or more sessions over an in-memory relay, driven by one explicit clock. */
private class Room(
    listing: () -> List<String> = { listOf("README.md", "src/main.rs") },
) {
    val roomId = "r" + FrameCrypto.randomBytes(8).joinToString("") { "%02x".format(it) }
    val roomKey: ByteArray = FrameCrypto.randomBytes(32)
    val hostKey: SessionKey = SessionKey.mint()
    val keepalive = Keepalive(pingIntervalMs = 30_000, awarenessRenewMs = 300, awarenessExpireMs = 900)
    var clock = 0L
    val seats = LinkedHashMap<String, PeerSession>()

    /** Every frame relayed, with the session that sent it. */
    val relayed = ArrayList<Pair<PeerSession, ByteArray>>()

    val host: PeerSession =
        PeerSession(
            PeerOptions(
                roomId,
                roomKey,
                hostKey.public,
                keepalive,
                seat = "p-host",
                host = HostOptions(hostKey, listing),
            ),
        ).also { seats["p-host"] = it }

    fun join(
        seat: String,
        role: Role? = null,
    ): PeerSession {
        val guest =
            PeerSession(
                PeerOptions(roomId, roomKey, hostKey.public, keepalive, seat, seats.keys + seat, declaredRole = role),
            )
        for (other in seats.values) other.seatJoined(clock, seat)
        seats[seat] = guest
        guest.tick(clock)
        return guest
    }

    fun leave(seat: String) {
        seats.remove(seat)
        for (other in seats.values) other.seatLeft(clock, seat)
    }

    /** Relays every queued frame to every other seat until none is left. */
    fun settle() {
        repeat(64) {
            var moved = false
            for ((seat, from) in seats.entries.toList()) {
                for (frame in from.takeOutbound()) {
                    moved = true
                    relayed.add(from to frame)
                    for ((other, to) in seats) if (other != seat) to.deliver(clock, frame)
                }
            }
            for (peer in seats.values) peer.tick(clock)
            if (!moved && seats.values.none { it.hasOutbound() }) return
        }
        error("the room did not settle")
    }

    fun advance(ms: Long) {
        clock += ms
        for (peer in seats.values) peer.tick(clock)
        settle()
    }
}

/** The `(client id, clock)` of every awareness entry in [frames], in the order they were sent. */
private fun Room.awarenessEntries(frames: List<ByteArray>): List<Pair<Long, Long>> {
    val frameKey = Frames.frameKey(roomId, roomKey)
    return frames.flatMap { frame ->
        val envelope = Envelope.parse(frame) ?: error("a frame the room cannot parse")
        if (envelope.kind != 0L) return@flatMap emptyList()
        val plaintext = Frames.opens(roomId, frameKey, envelope) ?: error("a frame the room cannot open")
        Sync.decode(plaintext).filterIsInstance<SyncMessage.Awareness>().flatMap { message ->
            val decoder = Lib0Decoder(message.update)
            List(decoder.readVarUint().toInt()) {
                val entry = decoder.readVarUint() to decoder.readVarUint()
                decoder.readVarString()
                entry
            }
        }
    }
}

private fun PeerSession.hasOutbound(): Boolean {
    val frames = takeOutbound()
    return frames.isNotEmpty().also { if (it) error("frames left unrelayed") }
}

class PeerSessionTest {
    @Test
    fun `a guest is committed, served the text, and both sides converge`() {
        val room = Room()
        room.settle()
        val guest = room.join("p-guest")
        room.settle()
        assertTrue(guest.stateHeld())
        assertEquals(Role.GUEST, guest.ownRole())
        assertEquals(listOf("README.md", "src/main.rs"), guest.listing)

        guest.open("README.md")
        room.advance(0)
        assertEquals(listOf("README.md"), room.host.openSet())
        room.host.insert("README.md", 0, "hello\n")
        room.settle()
        assertEquals("hello\n", guest.text("README.md"))
        assertEquals(listOf(RemoteEdit("README.md", listOf(TextDelta.Insert("hello\n")))), guest.takeEdits())

        guest.insert("README.md", 5, ", world")
        room.host.insert("README.md", 0, "> ")
        room.settle()
        assertEquals("> hello, world\n", room.host.text("README.md"))
        assertEquals(room.host.text("README.md"), guest.text("README.md"))
        assertTrue(room.host.takeEdits().isNotEmpty())
        assertTrue(guest.takeEdits().isNotEmpty())
    }

    @Test
    fun `an empty document still reaches the room`() {
        val room = Room()
        val guest = room.join("p-guest")
        room.settle()
        room.host.insert("README.md", 0, "")
        room.settle()
        assertTrue(guest.has("README.md"))
        assertEquals("", guest.text("README.md"))
    }

    @Test
    fun `edits made before the state commits the key are held back and sent after`() {
        val room = Room()
        val guest = room.join("p-guest")
        assertFalse(guest.insert("README.md", 0, "early"))
        room.settle()
        assertEquals("early", room.host.text("README.md"))
    }

    @Test
    fun `a viewer's edits stay local`() {
        val room = Room()
        val viewer = room.join("p-viewer", Role.VIEWER)
        room.settle()
        assertEquals(Role.VIEWER, viewer.ownRole())
        assertFalse(viewer.insert("README.md", 0, "nope"))
        room.settle()
        assertFalse(room.host.has("README.md"))
    }

    @Test
    fun `a caret arrives as a resolved selection`() {
        val room = Room()
        val guest = room.join("p-guest")
        room.settle()
        room.host.insert("README.md", 0, "abcdef")
        room.settle()
        guest.setCursor("README.md", Selection(1, 4))
        room.settle()
        assertTrue(room.host.takePresenceMoved())
        val cursor = room.host.cursors().single()
        assertEquals(guest.awarenessClientId, cursor.clientId)
        assertEquals("README.md", cursor.path)
        assertEquals(Selection(1, 4), cursor.selection)

        room.host.insert("README.md", 0, "xy")
        room.settle()
        assertEquals(
            Selection(3, 6),
            room.host
                .cursors()
                .single()
                .selection,
        )
    }

    @Test
    fun `a peer's holds lapse when it stops renewing them`() {
        val room = Room()
        val guest = room.join("p-guest")
        room.settle()
        guest.open("src/main.rs")
        room.advance(0)
        assertEquals(listOf("src/main.rs"), room.host.peerHolds()[guest.sessionKey.spelling])
        room.seats.remove("p-guest")
        room.advance(900)
        assertNull(room.host.peerHolds()[guest.sessionKey.spelling])
    }

    @Test
    fun `a guest with no state ends at the no-state window`() {
        val room = Room()
        val guest =
            PeerSession(
                PeerOptions(
                    room.roomId,
                    room.roomKey,
                    room.hostKey.public,
                    room.keepalive,
                    "p-guest",
                    listOf("p-guest"),
                ),
            )
        guest.tick(899)
        assertNull(guest.ending)
        guest.tick(900)
        assertEquals(Ending.NO_STATE, guest.ending)
    }

    @Test
    fun `the host away past its window ends the guest, and its return within it does not`() {
        val room = Room()
        val guest = room.join("p-guest")
        room.settle()
        room.leave("p-host")
        room.advance(100)
        assertEquals("p-host", guest.hostSeat())
        assertEquals(800L, guest.hostAwayGraceMs(room.clock))
        guest.seatJoined(room.clock, "p-host")
        assertNull(guest.hostAwayGraceMs(room.clock))

        guest.seatLeft(room.clock, "p-host")
        room.advance(899)
        assertNull(guest.ending)
        room.advance(1)
        assertEquals(Ending.HOST_AWAY, guest.ending)
    }

    @Test
    fun `the host closing the room ends every guest`() {
        val room = Room()
        val guest = room.join("p-guest")
        room.settle()
        assertTrue(room.host.closeRoom())
        room.settle()
        assertEquals(Ending.CLOSING, guest.ending)
        assertEquals(Ending.CLOSING, room.host.ending)
    }

    @Test
    fun `a reconnected guest is committed again under a new key`() {
        val room = Room()
        val guest = room.join("p-guest")
        room.settle()
        val first = guest.sessionKey.spelling
        room.leave("p-guest")
        guest.detach()
        guest.reseat("p-guest-2", listOf("p-host", "p-guest-2"), guest.awarenessClientId)
        for (other in room.seats.values) other.seatJoined(room.clock, "p-guest-2")
        room.seats["p-guest-2"] = guest
        room.advance(0)
        // The host answers one announcement per renewal window.
        room.advance(300)
        assertTrue(first != guest.sessionKey.spelling)
        assertEquals(Role.GUEST, guest.ownRole())
        guest.insert("README.md", 0, "back")
        room.settle()
        assertEquals("back", room.host.text("README.md"))
    }

    @Test
    fun `a new listing reaches the guests`() {
        var paths = listOf("a.txt")
        val room = Room { paths }
        val guest = room.join("p-guest")
        room.settle()
        assertEquals(listOf("a.txt"), guest.listing)
        // §13.3 drops a path with a control character; `..` is a name like any other.
        paths = listOf("b.txt", "a.txt", "bad\u0007name", "a.txt", "../out")
        room.host.listingChanged(room.clock)
        room.settle()
        assertEquals(listOf("../out", "a.txt", "b.txt"), guest.listing)
    }

    @Test
    fun `a first awareness state for an id is published above clock 0, after a re-seat too`() {
        val room = Room()
        val guest = room.join("p-guest")
        room.settle()
        room.host.insert("README.md", 0, "abcdef")
        room.settle()
        room.host.setCursor("README.md", Selection(0, 0))
        guest.setCursor("README.md", Selection(1, 1))
        room.settle()
        val before = guest.awarenessClientId
        val after = if (before == 7L) 8L else 7L
        room.leave("p-guest")
        guest.detach()
        guest.reseat("p-guest-2", listOf("p-host", "p-guest-2"), after)
        for (other in room.seats.values) other.seatJoined(room.clock, "p-guest-2")
        room.seats["p-guest-2"] = guest
        room.advance(0)
        room.advance(300)
        assertEquals(Role.GUEST, guest.ownRole())
        guest.setCursor("README.md", Selection(2, 2))
        room.settle()

        // §8.2: y-protocols ignores a first entry for an id at clock 0, so none is ever sent.
        for ((sender, id) in listOf(room.host to room.host.awarenessClientId, guest to before, guest to after)) {
            val entries = room.awarenessEntries(room.relayed.filter { it.first === sender }.map { it.second })
            val first = entries.firstOrNull { it.first == id } ?: error("no awareness entry for $id in $entries")
            assertTrue(first.second > 0, "the first entry for $id is at clock ${first.second}")
        }
        assertEquals(
            Selection(2, 2),
            room.host
                .cursors()
                .single { it.clientId == after }
                .selection,
        )
    }
}
