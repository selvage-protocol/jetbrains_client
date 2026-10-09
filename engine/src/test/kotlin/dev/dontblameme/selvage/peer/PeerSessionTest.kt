package dev.dontblameme.selvage.peer

import dev.dontblameme.selvage.crdt.Doc
import dev.dontblameme.selvage.crdt.Json
import dev.dontblameme.selvage.crdt.Lib0Decoder
import dev.dontblameme.selvage.crdt.Lib0Encoder
import dev.dontblameme.selvage.crdt.Sync
import dev.dontblameme.selvage.crdt.SyncMessage
import dev.dontblameme.selvage.crdt.TextDelta
import dev.dontblameme.selvage.crdt.Updates
import dev.dontblameme.selvage.sealed.DropReason
import dev.dontblameme.selvage.sealed.Envelope
import dev.dontblameme.selvage.sealed.FrameCrypto
import dev.dontblameme.selvage.sealed.Frames
import dev.dontblameme.selvage.sealed.Payload
import dev.dontblameme.selvage.sealed.PeerEntry
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.sealed.RoomState
import dev.dontblameme.selvage.sealed.SessionKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The awareness id of a session a case drives on its own, and the id a re-seat rotates it to. */
private const val AWARENESS = 3003L
private const val ROTATED = 4004L

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

/**
 * A session of its own over the room's keys, with no host: driven by the frames a test builds
 * rather than by a host's producer, so the clock and the state that commits a key are the test's.
 */
private fun Room.alone(
    seat: String,
    awarenessClientId: Long,
): PeerSession =
    PeerSession(
        PeerOptions(roomId, roomKey, hostKey.public, keepalive, seat, awarenessClientId = awarenessClientId),
    )

/** A `kind = 1` state the host sealed, committing one guest key per [peers] entry. */
private fun Room.stateFrame(
    issued: Long,
    counter: Long,
    vararg peers: Pair<SessionKey, String>,
): ByteArray =
    Frames.seal(
        roomId,
        Frames.frameKey(roomId, roomKey),
        1,
        counter,
        hostKey,
        RoomState(
            issued,
            listOf("README.md"),
            peers.associate { (key, seat) -> key.spelling to PeerEntry(seat, Role.GUEST) },
        ).encode(),
    )

/** The update that puts "hello" in README.md, from a replica of its own. */
private fun helloUpdate(): ByteArray =
    Updates.encodeStateAsUpdate(Doc(7).also { it.getText("README.md").insert(0, "hello") })

/** A `kind = 0` frame carrying [update] under [key]. */
private fun Room.contentFrame(
    key: SessionKey,
    counter: Long,
    update: ByteArray,
): ByteArray =
    Frames.seal(roomId, Frames.frameKey(roomId, roomKey), 0, counter, key, Sync.encode(SyncMessage.Update(update)))

/** The awareness state each client id carries in [frames], the last frame for an id winning. */
private fun Room.awarenessStates(frames: List<ByteArray>): Map<Long, String> {
    val frameKey = Frames.frameKey(roomId, roomKey)
    val states = LinkedHashMap<Long, String>()
    for (frame in frames) {
        val envelope = Envelope.parse(frame) ?: error("a frame the room cannot parse")
        if (envelope.kind != 0L) continue
        val plaintext = Frames.opens(roomId, frameKey, envelope) ?: error("a frame the room cannot open")
        for (message in Sync.decode(plaintext).filterIsInstance<SyncMessage.Awareness>()) {
            val decoder = Lib0Decoder(message.update)
            repeat(decoder.readVarUint().toInt()) {
                val client = decoder.readVarUint()
                decoder.readVarUint()
                states[client] = decoder.readVarString()
            }
        }
    }
    return states
}

/** The selection [json] names for [clientId] in a replica holding the room's own text. */
private fun selectionIn(
    json: String,
    clientId: Long,
): Selection? {
    val doc = Doc()
    Updates.applyUpdate(doc, helloUpdate(), "the room's text")
    return AwarenessState.resolve(clientId, Json.parse(json), doc).selection
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
    fun `a viewer's update behind an auth message is refused, not applied`() {
        val room = Room()
        val viewer = room.join("p-viewer", Role.VIEWER)
        val guest = room.join("p-guest")
        room.settle()
        val update = Updates.encodeStateAsUpdate(Doc(7).also { it.getText("README.md").insert(0, "viewer text") })
        // `02 01` is an auth message of status 1, which carries no reason (§7), then an Update.
        val plaintext = byteArrayOf(2, 1) + Sync.encode(SyncMessage.Update(update))
        assertEquals(listOf(0x02, 0x01, 0x00, 0x02), plaintext.take(4).map { it.toInt() })
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        val frame = Frames.seal(room.roomId, frameKey, 0, 1_000, viewer.sessionKey, plaintext)
        for (receiver in listOf(room.host, guest)) {
            val outcome = receiver.deliver(room.clock, frame)
            assertEquals(DropReason.UNAUTHORISED_CONTENT, (outcome as? Outcome.Dropped)?.reason, "$outcome")
            assertFalse(receiver.has("README.md"))
        }
    }

    @Test
    fun `a value nested past the bound is refused without failing the frame's reader`() {
        val room = Room()
        val viewer = room.join("p-viewer", Role.VIEWER)
        val guest = room.join("p-guest")
        room.settle()
        // The host answers one announcement per renewal window.
        room.advance(300)
        assertEquals(Role.GUEST, guest.ownRole())
        val deep = 10_000
        // A viewer's awareness is applied (§6.1 step 10), so its state reaches the JSON reader.
        val state = "[".repeat(deep) + "]".repeat(deep)
        val awareness =
            Lib0Encoder()
                .apply {
                    writeVarUint(1)
                    writeVarUint(4242)
                    writeVarUint(1)
                    writeVarString(state)
                }.toByteArray()

        // A guest's Update reaches the struct reader: an embed's JSON, and an Any.
        fun update(
            ref: Int,
            body: Lib0Encoder.() -> Unit,
        ) = Lib0Encoder()
            .apply {
                writeVarUint(1)
                writeVarUint(1)
                writeVarUint(4242)
                writeVarUint(0)
                writeUint8(ref)
                writeVarUint(1)
                writeVarString("README.md")
                body()
                writeVarUint(0)
            }.toByteArray()
        val embed = update(5) { writeVarString(state) }
        val any =
            update(8) {
                writeVarUint(1)
                repeat(deep) {
                    writeUint8(117)
                    writeVarUint(1)
                }
                writeUint8(126)
            }
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        val frames =
            listOf(
                Frames.seal(
                    room.roomId,
                    frameKey,
                    0,
                    1_000,
                    viewer.sessionKey,
                    Sync.encode(SyncMessage.Awareness(awareness)),
                ),
                Frames.seal(room.roomId, frameKey, 0, 1_000, guest.sessionKey, Sync.encode(SyncMessage.Update(embed))),
                Frames.seal(room.roomId, frameKey, 0, 1_001, guest.sessionKey, Sync.encode(SyncMessage.Update(any))),
            )
        for (frame in frames) assertEquals(Outcome.Applied(0), room.host.deliver(room.clock, frame))
        assertTrue(room.host.cursors().isEmpty())
        assertEquals("", room.host.text("README.md"))
        room.host.insert("README.md", 0, "still here")
        room.settle()
        assertEquals("still here", guest.text("README.md"))
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
    fun `a reconnect keeps the carets of the peers it saw`() {
        val room = Room()
        val guest = room.join("p-guest")
        room.settle()
        room.host.insert("README.md", 0, "abcdef")
        room.host.setCursor("README.md", Selection(2, 2))
        room.settle()
        assertEquals(listOf(room.host.awarenessClientId), guest.cursors().map { it.clientId })
        val before = guest.awarenessClientId
        room.leave("p-guest")
        guest.detach()
        guest.reseat("p-guest-2", listOf("p-host", "p-guest-2"), if (before == 7L) 8L else 7L)
        assertEquals(listOf(room.host.awarenessClientId), guest.cursors().map { it.clientId })
        assertTrue(guest.awarenessClientId != before)
    }

    @Test
    fun `a blank path is neither listed by the host nor kept by a guest`() {
        val blanks = listOf("   ", "\t", "\u00a0", "\u2003 ")
        val room = Room { listOf("a.txt") + blanks }
        val guest = room.join("p-guest")
        room.settle()
        assertEquals(listOf("a.txt"), room.host.listing)
        assertEquals(listOf("a.txt"), guest.listing)
        blanks.forEach { assertFalse(Payload.usablePath(it), "\"$it\"") }
        assertTrue(Payload.usablePath(" a.txt "))
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

    @Test
    fun `an awareness change held back before a committing state is published by it`() {
        val room = Room()
        val guest = room.alone("p-guest", AWARENESS)
        val other = SessionKey.mint()
        guest.tick(0)
        guest.takeOutbound()

        // A state that commits another key, and that peer's text, so the gate is shut while this
        // connection has something to select.
        assertEquals(Outcome.Applied(1), guest.deliver(1, room.stateFrame(1, 1, other to "p-other")))
        assertEquals(Outcome.Applied(0), guest.deliver(2, room.contentFrame(other, 1, helloUpdate())))
        // The clock the local change is stamped with, which the renewal below is measured from.
        guest.tick(3)
        guest.takeOutbound()

        guest.setCursor("README.md", Selection(1, 4))
        assertTrue(
            room.awarenessStates(guest.takeOutbound()).isEmpty(),
            "§13.1's step 4 holds the frame back",
        )

        // The state that commits this key carries the held change out with it: the caller changes
        // nothing else, and §8.2's renewal has not come round.
        assertEquals(Outcome.Applied(1), guest.deliver(4, room.stateFrame(2, 2, guest.sessionKey to "p-guest")))
        val out = guest.takeOutbound()
        assertEquals(listOf(AWARENESS to 2L), room.awarenessEntries(out), "one frame, for the local id")
        val state = room.awarenessStates(out).getValue(AWARENESS)
        assertEquals(Selection(1, 4), selectionIn(state, AWARENESS), "the state the caller set")

        // Neither renewal is due yet — the session's, measured from the flush, nor the awareness
        // set's, measured from the change — so a frame here would be the flush publishing twice.
        guest.tick(3 + room.keepalive.awarenessRenewMs - 1)
        assertTrue(
            room.awarenessEntries(guest.takeOutbound()).isEmpty(),
            "the flush published the held state twice",
        )
    }

    @Test
    fun `an awareness change made while the gate is open goes out at once, and only once`() {
        val room = Room()
        val guest = room.alone("p-guest", AWARENESS)
        val other = SessionKey.mint()
        guest.tick(0)
        guest.takeOutbound()
        assertEquals(
            Outcome.Applied(1),
            guest.deliver(1, room.stateFrame(1, 1, guest.sessionKey to "p-guest", other to "p-other")),
        )
        assertEquals(Outcome.Applied(0), guest.deliver(2, room.contentFrame(other, 1, helloUpdate())))
        guest.takeOutbound()

        guest.setCursor("README.md", Selection(0, 2))
        val out = guest.takeOutbound()
        assertEquals(listOf(AWARENESS to 2L), room.awarenessEntries(out), "one frame, at once")
        assertEquals(Selection(0, 2), selectionIn(room.awarenessStates(out).getValue(AWARENESS), AWARENESS))

        // Every later state that commits this key runs the flush; nothing was held for it, so it
        // publishes nothing.
        assertEquals(Outcome.Applied(1), guest.deliver(3, room.stateFrame(2, 2, guest.sessionKey to "p-guest")))
        assertTrue(
            room.awarenessEntries(guest.takeOutbound()).isEmpty(),
            "the flush republished a state it never held",
        )
    }

    @Test
    fun `a state cleared inside the gate is never published, by the flush or the renewal`() {
        val room = Room()
        val guest = room.alone("p-guest", AWARENESS)
        val other = SessionKey.mint()
        guest.tick(0)
        guest.takeOutbound()
        assertEquals(Outcome.Applied(1), guest.deliver(1, room.stateFrame(1, 1, other to "p-other")))
        guest.takeOutbound()

        guest.setCursor("README.md", Selection(0, 2))
        guest.setCursor(null)
        assertTrue(
            room.awarenessStates(guest.takeOutbound()).isEmpty(),
            "the clearing is held like the change before it",
        )

        assertEquals(Outcome.Applied(1), guest.deliver(2, room.stateFrame(2, 2, guest.sessionKey to "p-guest")))
        assertTrue(
            room.awarenessStates(guest.takeOutbound()).isEmpty(),
            "the flush published something for a cleared state",
        )

        guest.tick(2 + room.keepalive.awarenessRenewMs)
        assertTrue(
            room.awarenessStates(guest.takeOutbound()).isEmpty(),
            "§8.2's renewal resurrected a cleared state",
        )
    }

    @Test
    fun `a change set, cleared and set again inside the gate publishes only the last one`() {
        val room = Room()
        val guest = room.alone("p-guest", AWARENESS)
        val other = SessionKey.mint()
        guest.tick(0)
        guest.takeOutbound()
        assertEquals(Outcome.Applied(1), guest.deliver(1, room.stateFrame(1, 1, other to "p-other")))
        guest.takeOutbound()

        guest.setCursor("README.md")
        guest.setCursor(null)
        guest.setCursor("theirs.md")
        assertTrue(room.awarenessStates(guest.takeOutbound()).isEmpty(), "both changes are held inside the gate")

        assertEquals(Outcome.Applied(1), guest.deliver(2, room.stateFrame(2, 2, guest.sessionKey to "p-guest")))
        assertEquals(
            mapOf(AWARENESS to "{\"path\":\"theirs.md\"}"),
            room.awarenessStates(guest.takeOutbound()),
            "the state the caller last set, and no resurrection of the cleared one",
        )
    }

    @Test
    fun `a detached connection publishes no held awareness`() {
        val room = Room()
        val guest = room.alone("p-guest", AWARENESS)
        val other = SessionKey.mint()
        guest.tick(0)
        guest.takeOutbound()
        assertEquals(Outcome.Applied(1), guest.deliver(1, room.stateFrame(1, 1, other to "p-other")))
        guest.takeOutbound()

        guest.setCursor("README.md")
        guest.detach()
        assertEquals(Outcome.Applied(1), guest.deliver(2, room.stateFrame(2, 2, guest.sessionKey to "p-guest")))
        assertTrue(
            room.awarenessStates(guest.takeOutbound()).isEmpty(),
            "a detached session published the state its dead key was holding",
        )
    }

    @Test
    fun `a re-seat publishes the held awareness under the fresh id, never the old one`() {
        val room = Room()
        val guest = room.alone("p-guest", AWARENESS)
        val other = SessionKey.mint()
        val seen = ArrayList<ByteArray>()
        guest.tick(0)
        seen += guest.takeOutbound()
        assertEquals(Outcome.Applied(1), guest.deliver(1, room.stateFrame(1, 1, other to "p-other")))
        seen += guest.takeOutbound()

        guest.setCursor("README.md")
        guest.detach()
        guest.reseat("p-guest-2", listOf("p-host", "p-guest-2"), ROTATED)
        // The pump that follows a re-seat: the renewal republishes `localState` under the fresh id,
        // and the gate, still shut, holds it again rather than dropping it.
        guest.tick(2)
        assertEquals(Outcome.Applied(1), guest.deliver(3, room.stateFrame(2, 2, guest.sessionKey to "p-guest-2")))
        seen += guest.takeOutbound()

        assertEquals(
            mapOf(ROTATED to "{\"path\":\"README.md\"}"),
            room.awarenessStates(seen),
            "the held state, under the fresh id",
        )
    }
}
