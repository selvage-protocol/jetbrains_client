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
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    sealFault: ((ByteArray) -> Boolean)? = null,
): PeerSession =
    PeerSession(
        PeerOptions(
            roomId,
            roomKey,
            hostKey.public,
            keepalive,
            seat,
            awarenessClientId = awarenessClientId,
            sealFault = sealFault,
        ),
    )

/** A `SyncStep1` whose inner state vector is one unterminated varint: a malformed request. */
private fun malformedStep1(): ByteArray = Sync.encode(SyncMessage.Step1(byteArrayOf(0x80.toByte())))

/** Whether [plaintext] opens a `SyncStep1` (`Sync.MESSAGE_SYNC`, `Sync.SYNC_STEP1`). */
private fun isStep1(plaintext: ByteArray): Boolean =
    plaintext.size >= 2 && plaintext[0] == 0.toByte() && plaintext[1] == 0.toByte()

/** Whether [plaintext] opens a content `Update` (`Sync.MESSAGE_SYNC`, `Sync.SYNC_UPDATE`). */
private fun isContentUpdate(plaintext: ByteArray): Boolean =
    plaintext.size >= 2 && plaintext[0] == 0.toByte() && plaintext[1] == 2.toByte()

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

/** A `kind = 1` state the host sealed, committing each key at the role its triple names. */
private fun Room.rolesFrame(
    issued: Long,
    counter: Long,
    vararg peers: Triple<SessionKey, String, Role>,
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
            peers.associate { (key, seat, role) -> key.spelling to PeerEntry(seat, role) },
        ).encode(),
    )

/** Every `SyncStep1`/`SyncStep2`/`Update` in [frames], as ("step1"/"step2"/"update", payload). */
private fun Room.syncSteps(frames: List<ByteArray>): List<Pair<String, ByteArray>> {
    val frameKey = Frames.frameKey(roomId, roomKey)
    return frames.flatMap { frame ->
        val envelope = Envelope.parse(frame) ?: return@flatMap emptyList()
        if (envelope.kind != 0L) return@flatMap emptyList()
        val plaintext = Frames.opens(roomId, frameKey, envelope) ?: return@flatMap emptyList()
        val out = ArrayList<Pair<String, ByteArray>>()
        Sync.read(plaintext) { message ->
            when (message) {
                is SyncMessage.Step1 -> {
                    out.add("step1" to message.stateVector)
                }

                is SyncMessage.Step2 -> {
                    out.add("step2" to message.update)
                }

                is SyncMessage.Update -> {
                    out.add("update" to message.update)
                }

                else -> {}
            }
        }
        out
    }
}

/** The README.md text [frames]' updates produce when applied to a replica of their own. */
private fun Room.publishedText(frames: List<ByteArray>): String {
    val doc = Doc()
    for ((kind, payload) in syncSteps(frames)) if (kind != "step1") Updates.applyUpdate(doc, payload)
    return if ("README.md" in doc.rootNames) doc.getText("README.md").toString() else ""
}

/** Every `kind = 3` holds frame in [frames] as its path list, in the order it was sent. */
private fun Room.holds(frames: List<ByteArray>): List<String> {
    val frameKey = Frames.frameKey(roomId, roomKey)
    return frames.flatMap { frame ->
        val envelope = Envelope.parse(frame) ?: return@flatMap emptyList()
        if (envelope.kind != 3L) return@flatMap emptyList()
        val plaintext = Frames.opens(roomId, frameKey, envelope) ?: return@flatMap emptyList()
        (Payload.read(envelope.kind, plaintext) as? Payload.Holds)?.holds ?: emptyList()
    }
}

/** What an UNKNOWN-role replica shows before and after a role resolution, and what it publishes. */
private class Speculation(
    val before: String,
    val visible: String,
    val published: String,
)

/**
 * Drives one UNKNOWN-role replica over [messages]: a local edit takes the clean baseline, one
 * content frame carries [messages], and a state resolves this key at [role]. The returned
 * [Speculation] holds the replica's text before that resolution, after it, and what a subsequent
 * room request makes it publish.
 */
private fun Room.speculationOutcome(
    seat: String,
    messages: ByteArray,
    role: Role,
): Speculation {
    val other = SessionKey.mint()
    val frameKey = Frames.frameKey(roomId, roomKey)
    val peer = alone(seat, AWARENESS)
    assertEquals(Outcome.Applied(1), peer.deliver(1, rolesFrame(1, 1, Triple(other, "p-other", Role.GUEST))))
    peer.takeOutbound()
    assertFalse(peer.insert("README.md", 0, "secret"))
    assertEquals(Outcome.Applied(0), peer.deliver(2, Frames.seal(roomId, frameKey, 0, 1, other, messages)))
    val before = peer.text("README.md")
    assertEquals(
        Outcome.Applied(1),
        peer.deliver(3, rolesFrame(2, 2, Triple(peer.sessionKey, seat, role), Triple(other, "p-other", Role.GUEST))),
    )
    val visible = peer.text("README.md")
    peer.takeOutbound()
    // A request from the room makes the replica publish what it holds, when it may answer at all.
    peer.deliver(4, Frames.seal(roomId, frameKey, 0, 2, other, Sync.encode(SyncMessage.Step1(byteArrayOf(0)))))
    return Speculation(before, visible, publishedText(peer.takeOutbound()))
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
    fun `a thrown committing request still publishes the edit, awareness and hold the state owed`() {
        val room = Room()
        val guest =
            room.alone("p-guest", AWARENESS) { plaintext ->
                if (isStep1(plaintext)) throw GeneralSecurityException("the seal fault") else false
            }
        val other = SessionKey.mint()
        guest.tick(0)
        guest.takeOutbound()

        // §13.1's step 4: while this key is uncommitted its edit, awareness and hold are held, and
        // nothing is publishable for any of them.
        guest.open("README.md")
        assertFalse(guest.insert("README.md", 0, "hello"))
        guest.setCursor("README.md", Selection(0, 2))
        assertTrue(guest.takeOutbound().isEmpty(), "nothing is publishable before the committing state")

        // The state that commits this key is the one whose §13.1 step 6 request cannot seal. The
        // fault reaches the caller; the obligations the same commitment owes are still served.
        assertFailsWith<GeneralSecurityException> {
            guest.deliver(1, room.stateFrame(1, 1, guest.sessionKey to "p-guest", other to "p-other"))
        }

        val out = guest.takeOutbound()
        assertEquals("hello", room.publishedText(out), "the held edit goes out with the committing state")
        assertEquals(listOf(AWARENESS to 2L), room.awarenessEntries(out), "the held awareness does too")
        assertEquals(listOf("README.md"), room.holds(out), "and so does the hold taken before the state")
    }

    @Test
    fun `a transient content seal fault on the initial flush keeps the owed edit for a later state`() {
        val room = Room()
        val initial = GeneralSecurityException("the initial step-6 seal fault")
        val content = GeneralSecurityException("the held-content seal fault")
        var step1Faults = 0
        var contentFaults = 0
        val guest =
            room.alone("p-guest", AWARENESS) { plaintext ->
                if (isStep1(plaintext) && step1Faults++ == 0) throw initial
                if (isContentUpdate(plaintext) && contentFaults++ == 0) throw content
                false
            }
        val other = SessionKey.mint()
        guest.tick(0)
        guest.takeOutbound()

        // §13.1's step 4: while this key is uncommitted its edit and awareness are held, and the
        // local edit is in the replica even though nothing may publish it yet.
        guest.open("README.md")
        assertFalse(guest.insert("README.md", 0, "hello"))
        guest.setCursor("README.md", Selection(0, 2))
        assertTrue(guest.takeOutbound().isEmpty(), "nothing is publishable before the committing state")
        assertEquals("hello", guest.text("README.md"), "the local edit stands in the replica")

        // The committing state's own step 6 request throws first; the flush it newly reaches then
        // hits a second, transient content seal fault. The first fault reaches the caller, the
        // second is suppressed onto it, and neither failed seal queued a frame.
        val publishedBefore = guest.published
        val handshakeBefore = guest.handshake
        val thrown =
            assertFailsWith<GeneralSecurityException> {
                guest.deliver(1, room.stateFrame(1, 1, guest.sessionKey to "p-guest", other to "p-other"))
            }
        assertSame(initial, thrown, "the initial request's fault is the one reported")
        assertEquals(listOf(content), thrown.suppressed.toList(), "the content fault is suppressed onto it")
        assertTrue(guest.takeOutbound().isEmpty(), "neither failed seal queued a frame")
        assertEquals(publishedBefore, guest.published, "no failed seal counted as a publication")
        assertEquals(handshakeBefore, guest.handshake, "no failed seal counted as a request")
        assertEquals("hello", guest.text("README.md"), "the local edit still stands in the replica")

        // Sealing recovers. A newer state that commits this key is the existing flush trigger, and
        // it must still serve the edit the failed flush owed — no new local edit, no tick.
        assertEquals(
            Outcome.Applied(1),
            guest.deliver(2, room.stateFrame(2, 2, guest.sessionKey to "p-guest", other to "p-other")),
        )
        val out = guest.takeOutbound()
        assertEquals("hello", room.publishedText(out), "the owed edit reaches the room on the newer state")
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

    @Test
    fun `a valid request before a malformed suffix keeps its reply and its readiness`() {
        val room = Room()
        val guest = room.alone("p-guest", AWARENESS)
        val other = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        assertEquals(
            Outcome.Applied(1),
            guest.deliver(
                1,
                room.rolesFrame(
                    1,
                    1,
                    Triple(guest.sessionKey, "p-guest", Role.GUEST),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        guest.takeOutbound()
        val valid = Sync.encode(SyncMessage.Step1(byteArrayOf(0)))

        // A valid Step1 with an empty vector, then a Step1 whose one-byte inner vector is unterminated.
        val inner = valid + byteArrayOf(0, 0, 1, 0x80.toByte())
        assertEquals(listOf(0, 0, 1, 0, 0, 0, 1, 0x80), inner.map { it.toInt() and 0xff })
        assertEquals(Outcome.Applied(0), guest.deliver(2, Frames.seal(room.roomId, frameKey, 0, 1, other, inner)))
        assertEquals(
            listOf("step2"),
            room.syncSteps(guest.takeOutbound()).map { it.first },
            "the prefix's one reply stands",
        )
        assertTrue(guest.reciprocalCensus().contains(other.spelling), "the prefix's request armed readiness")

        // A valid Step1, then a Step1 whose outer vector is longer than the frame carries.
        val outer = valid + byteArrayOf(0, 0, 5, 1, 2)
        assertEquals(Outcome.Applied(0), guest.deliver(3, Frames.seal(room.roomId, frameKey, 0, 2, other, outer)))
        assertEquals(listOf("step2"), room.syncSteps(guest.takeOutbound()).map { it.first })
    }

    @Test
    fun `a malformed first request arms nothing and reads nothing after it`() {
        val room = Room()
        val guest = room.alone("p-guest", AWARENESS)
        val other = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        assertEquals(
            Outcome.Applied(1),
            guest.deliver(
                1,
                room.rolesFrame(
                    1,
                    1,
                    Triple(guest.sessionKey, "p-guest", Role.GUEST),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        guest.takeOutbound()

        // A Step1 whose inner vector is malformed, then a well-formed request and a content
        // update behind it.
        val badFirst =
            byteArrayOf(0, 0, 1, 0x80.toByte()) +
                Sync.encode(SyncMessage.Step1(byteArrayOf(0))) +
                Sync.encode(SyncMessage.Update(helloUpdate()))
        assertEquals(Outcome.Applied(0), guest.deliver(2, Frames.seal(room.roomId, frameKey, 0, 1, other, badFirst)))
        assertEquals(
            emptyList(),
            room.syncSteps(guest.takeOutbound()),
            "no reply, and the valid request behind it is unread",
        )
        assertEquals("", guest.text("README.md"), "the update behind it is unread too")
        assertTrue(guest.reciprocalCensus().isEmpty(), "a malformed request is not readiness")
    }

    @Test
    fun `an UNKNOWN replica reads the same validated prefix as the ordinary one, either way it resolves`() {
        // A valid request, a malformed one, and a visible update behind them; then the bad-first
        // shape. Both replicas must stop at the same message, for a writer and a viewer alike.
        val prefix =
            Sync.encode(SyncMessage.Step1(byteArrayOf(0))) + malformedStep1() +
                Sync.encode(SyncMessage.Update(helloUpdate()))
        val badFirst = malformedStep1() + Sync.encode(SyncMessage.Update(helloUpdate()))
        for ((name, messages) in listOf("prefix" to prefix, "bad-first" to badFirst)) {
            val writer = Room().speculationOutcome("p-writer", messages, Role.GUEST)
            assertEquals("secret", writer.before, "$name: the trailing update is behind the malformed request")
            assertEquals("secret", writer.visible, "$name: a writer resolution keeps the replica without the suffix")
            assertEquals("secret", writer.published, "$name: the publishable history excludes the unread suffix")

            val viewer = Room().speculationOutcome("p-viewer", messages, Role.VIEWER)
            assertEquals("secret", viewer.before, "$name: the trailing update is behind the malformed request")
            assertEquals("", viewer.visible, "$name: a viewer resolution adopts the clean baseline")
            assertEquals("", viewer.published, "$name: a viewer publishes none of it")
        }
    }

    @Test
    fun `a remote request is retired when its writer loses eligibility`() {
        val room = Room()
        val renew = room.keepalive.awarenessRenewMs
        val peer = room.alone("p-peer", AWARENESS)
        val writer = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                1,
                room.rolesFrame(1, 1, Triple(peer.sessionKey, "p-peer", Role.GUEST), Triple(writer, "p-w", Role.GUEST)),
            ),
        )
        peer.takeOutbound()
        // The writer's first valid request arms one reciprocal cause for its key.
        assertEquals(
            Outcome.Applied(0),
            peer.deliver(
                2,
                Frames.seal(room.roomId, frameKey, 0, 1, writer, Sync.encode(SyncMessage.Step1(byteArrayOf(0)))),
            ),
        )
        assertEquals(setOf(writer.spelling), peer.reciprocalCensus())
        assertEquals(listOf("step2"), room.syncSteps(peer.takeOutbound()).map { it.first })

        // A newer state retires the writer before the shared request clock is due; this key stays
        // a writer, so nothing but the retired cause would have asked.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(3, room.rolesFrame(2, 2, Triple(peer.sessionKey, "p-peer", Role.GUEST))),
        )
        peer.takeOutbound()
        assertTrue(peer.reciprocalCensus().isEmpty(), "the retired key's mark goes with it")
        peer.tick(1 + renew)
        assertEquals(emptyList(), room.syncSteps(peer.takeOutbound()), "a retired cause is never sent")
    }

    @Test
    fun `retiring one remote cause leaves another request pending`() {
        val room = Room()
        val renew = room.keepalive.awarenessRenewMs
        val peer = room.alone("p-peer", AWARENESS)
        val first = SessionKey.mint()
        val second = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        val state =
            room.rolesFrame(
                1,
                1,
                Triple(peer.sessionKey, "p-peer", Role.GUEST),
                Triple(first, "p-first", Role.GUEST),
                Triple(second, "p-second", Role.GUEST),
            )
        assertEquals(Outcome.Applied(1), peer.deliver(1, state))
        peer.takeOutbound()
        val ask = Sync.encode(SyncMessage.Step1(byteArrayOf(0)))
        assertEquals(Outcome.Applied(0), peer.deliver(2, Frames.seal(room.roomId, frameKey, 0, 1, first, ask)))
        assertEquals(Outcome.Applied(0), peer.deliver(2, Frames.seal(room.roomId, frameKey, 0, 1, second, ask)))
        assertEquals(setOf(first.spelling, second.spelling), peer.reciprocalCensus())
        peer.takeOutbound()

        // The newer state retires the first key and keeps the second: one cause survives.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                3,
                room.rolesFrame(
                    2,
                    2,
                    Triple(peer.sessionKey, "p-peer", Role.GUEST),
                    Triple(second, "p-second", Role.GUEST),
                ),
            ),
        )
        peer.takeOutbound()
        assertEquals(setOf(second.spelling), peer.reciprocalCensus())
        peer.tick(1 + renew)
        assertEquals(
            listOf("step1"),
            room.syncSteps(peer.takeOutbound()).map { it.first },
            "one coalesced request serves the surviving cause",
        )
    }

    @Test
    fun `a reciprocal request armed while this key cannot publish is served when it can`() {
        val room = Room()
        val renew = room.keepalive.awarenessRenewMs
        val peer = room.alone("p-peer", AWARENESS)
        val writer = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        // This key's own initial request goes out at 1, as a viewer's does.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                1,
                room.rolesFrame(
                    1,
                    1,
                    Triple(peer.sessionKey, "p-peer", Role.VIEWER),
                    Triple(writer, "p-w", Role.GUEST),
                ),
            ),
        )
        assertEquals(listOf("step1"), room.syncSteps(peer.takeOutbound()).map { it.first })

        // A state that omits this key: it cannot publish anything at all.
        assertEquals(Outcome.Applied(1), peer.deliver(2, room.rolesFrame(2, 2, Triple(writer, "p-w", Role.GUEST))))
        peer.takeOutbound()
        assertNull(peer.ownRole())
        // The writer's first valid request arms the cause for its key while this key is ineligible.
        assertEquals(
            Outcome.Applied(0),
            peer.deliver(
                3,
                Frames.seal(room.roomId, frameKey, 0, 1, writer, Sync.encode(SyncMessage.Step1(byteArrayOf(0)))),
            ),
        )
        assertEquals(
            setOf(writer.spelling),
            peer.reciprocalCensus(),
            "the obligation is keyed to the writer's eligibility",
        )
        assertEquals(emptyList(), room.syncSteps(peer.takeOutbound()), "nothing is published while ineligible")

        // A state makes this key a viewer again: the retained cause is due on the shared clock.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                4,
                room.rolesFrame(
                    3,
                    3,
                    Triple(peer.sessionKey, "p-peer", Role.VIEWER),
                    Triple(writer, "p-w", Role.GUEST),
                ),
            ),
        )
        peer.takeOutbound()
        peer.tick(1 + renew)
        assertEquals(
            listOf("step1"),
            room.syncSteps(peer.takeOutbound()).map { it.first },
            "the retained request is served once publication is possible",
        )

        // Companion: a writer retired before this key comes back leaves nothing to send.
        val other = Room()
        val second = other.alone("p-peer", AWARENESS)
        val dropped = SessionKey.mint()
        val otherKey = Frames.frameKey(other.roomId, other.roomKey)
        assertEquals(
            Outcome.Applied(1),
            second.deliver(
                1,
                other.rolesFrame(
                    1,
                    1,
                    Triple(second.sessionKey, "p-peer", Role.VIEWER),
                    Triple(dropped, "p-w", Role.GUEST),
                ),
            ),
        )
        second.takeOutbound()
        assertEquals(Outcome.Applied(1), second.deliver(2, other.rolesFrame(2, 2, Triple(dropped, "p-w", Role.GUEST))))
        second.takeOutbound()
        assertEquals(
            Outcome.Applied(0),
            second.deliver(
                3,
                Frames.seal(other.roomId, otherKey, 0, 1, dropped, Sync.encode(SyncMessage.Step1(byteArrayOf(0)))),
            ),
        )
        assertEquals(
            Outcome.Applied(1),
            second.deliver(4, other.rolesFrame(3, 3, Triple(second.sessionKey, "p-peer", Role.VIEWER))),
        )
        second.takeOutbound()
        assertTrue(second.reciprocalCensus().isEmpty())
        second.tick(1 + renew)
        assertEquals(emptyList(), other.syncSteps(second.takeOutbound()), "a cause retired while gated is never sent")
    }

    @Test
    fun `an explicit tick retries a failed due request at once, and the automatic pump waits`() {
        val room = Room()
        val renew = room.keepalive.awarenessRenewMs
        var failing = false
        val peer = room.alone("p-peer", AWARENESS) { failing && isStep1(it) }
        val writer = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                1,
                room.rolesFrame(1, 1, Triple(peer.sessionKey, "p-peer", Role.GUEST), Triple(writer, "p-w", Role.GUEST)),
            ),
        )
        assertEquals(listOf("step1"), room.syncSteps(peer.takeOutbound()).map { it.first })
        // The writer's request arms one cause, so a repair is due at the shared clock.
        assertEquals(
            Outcome.Applied(0),
            peer.deliver(
                2,
                Frames.seal(room.roomId, frameKey, 0, 1, writer, Sync.encode(SyncMessage.Step1(byteArrayOf(0)))),
            ),
        )
        peer.takeOutbound()

        // The repair at the due boundary fails, and the owning session stamps its completion.
        failing = true
        peer.tick(1 + renew)
        peer.noteStep1Failure(1 + renew)
        assertEquals(2, peer.step1Attempts)
        assertEquals(1 + renew, peer.step1FailureAt)
        // An automatic pump a millisecond later waits out the cooldown ...
        peer.autoTick(1 + renew + 1)
        assertEquals(2, peer.step1Attempts, "the automatic path waits a window from the failure's completion")
        // ... while a caller driving the clock by hand retries at once.
        failing = false
        peer.tick(1 + renew + 1)
        assertEquals(3, peer.step1Attempts)
        assertEquals(listOf("step1"), room.syncSteps(peer.takeOutbound()).map { it.first })
    }

    @Test
    fun `an automatic attempt waits for the owning session to stamp a failure`() {
        val room = Room()
        val renew = room.keepalive.awarenessRenewMs
        var failing = true
        val peer = room.alone("p-peer", AWARENESS) { failing && isStep1(it) }
        val writer = SessionKey.mint()
        // The first commitment's request fails; the owning session has not stamped its completion.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                1,
                room.rolesFrame(1, 1, Triple(peer.sessionKey, "p-peer", Role.GUEST), Triple(writer, "p-w", Role.GUEST)),
            ),
        )
        peer.takeOutbound()
        assertEquals(1, peer.step1Attempts)
        assertNull(peer.step1FailureAt, "the failure is not stamped until the owner says so")

        // An automatic tick with the shared clock already due still waits for that stamp.
        peer.autoTick(1 + renew)
        assertEquals(1, peer.step1Attempts, "an unstamped failure is not retried automatically")

        // The owner stamps the completion; the automatic path then paces from it and serves.
        failing = false
        peer.noteStep1Failure(1 + renew)
        peer.autoTick(1 + 2 * renew)
        assertEquals(2, peer.step1Attempts, "the stamped completion paces the automatic retry")
    }

    @Test
    fun `a failed initial viewer handshake survives a remote retirement`() {
        val room = Room()
        val renew = room.keepalive.awarenessRenewMs
        var failing = true
        val peer = room.alone("p-peer", AWARENESS) { failing && isStep1(it) }
        val writer = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        // This key's first commitment is a viewer's; its initial request fails and is retained.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                1,
                room.rolesFrame(
                    1,
                    1,
                    Triple(peer.sessionKey, "p-peer", Role.VIEWER),
                    Triple(writer, "p-w", Role.GUEST),
                ),
            ),
        )
        assertEquals(1, peer.step1Attempts)
        // The writer's own request arms one remote cause as well.
        assertEquals(
            Outcome.Applied(0),
            peer.deliver(
                2,
                Frames.seal(room.roomId, frameKey, 0, 1, writer, Sync.encode(SyncMessage.Step1(byteArrayOf(0)))),
            ),
        )
        // A state retires the writer — and with it only the remote cause, not this key's own request.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(3, room.rolesFrame(2, 2, Triple(peer.sessionKey, "p-peer", Role.VIEWER))),
        )
        peer.takeOutbound()
        assertTrue(peer.reciprocalCensus().isEmpty(), "the retired writer's mark goes with it")
        assertEquals(2, peer.step1Attempts, "the retirement state's own immediate handshake was attempted")

        // Restored sealing and a due clock serve the retained viewer request with no new state.
        peer.noteStep1Failure(3)
        failing = false
        peer.autoTick(3 + renew)
        assertEquals(3, peer.step1Attempts, "the viewer's own initial request survives the retirement")
        assertEquals(1, peer.handshake, "and it is sealed exactly once")
    }

    @Test
    fun `three peers converge and a settled room asks for nothing on the renewal clock`() {
        val room = Room()
        room.settle()
        // The host answers one announcement per renewal window, so each join settles on its own.
        val first = room.join("p-first")
        room.advance(room.keepalive.awarenessRenewMs)
        val second = room.join("p-second")
        room.advance(room.keepalive.awarenessRenewMs)
        room.host.insert("README.md", 0, "hello\n")
        room.settle()
        assertEquals("hello\n", first.text("README.md"))
        assertEquals("hello\n", second.text("README.md"))

        // Two windows discharge the reciprocal handshake, and every writer has then served the rest.
        room.advance(room.keepalive.awarenessRenewMs)
        room.advance(room.keepalive.awarenessRenewMs)
        assertTrue(
            room.host.reciprocalCensus().containsAll(listOf(first.sessionKey.spelling, second.sessionKey.spelling)),
            "host census ${room.host.reciprocalCensus()}",
        )
        assertTrue(
            first.reciprocalCensus().containsAll(listOf(room.host.sessionKey.spelling, second.sessionKey.spelling)),
            "first census ${first.reciprocalCensus()}",
        )
        assertTrue(
            second.reciprocalCensus().containsAll(listOf(room.host.sessionKey.spelling, first.sessionKey.spelling)),
            "second census ${second.reciprocalCensus()}",
        )

        // Three quiet windows after them ask for nothing.
        val before = room.relayed.size
        room.advance(room.keepalive.awarenessRenewMs)
        room.advance(room.keepalive.awarenessRenewMs)
        room.advance(room.keepalive.awarenessRenewMs)
        assertEquals(
            emptyList(),
            room.syncSteps(room.relayed.drop(before).map { it.second }),
            "a settled room asks for nothing on the renewal clock",
        )
    }

    @Test
    fun `an already-handshaken key that re-enters writer commitment owes one readiness request`() {
        val room = Room()
        val renew = room.keepalive.awarenessRenewMs
        val peer = room.alone("p-peer", AWARENESS)
        val other = SessionKey.mint()

        fun state(
            issued: Long,
            vararg keys: SessionKey,
        ) = room.rolesFrame(
            issued,
            issued,
            *keys.mapIndexed { index, key -> Triple(key, "p-$index", Role.GUEST) }.toTypedArray(),
        )

        assertEquals(Outcome.Applied(1), peer.deliver(1, state(1, peer.sessionKey, other)))
        peer.takeOutbound()
        // An applied state omits this unchanged key: its writer eligibility ends.
        assertEquals(Outcome.Applied(1), peer.deliver(2, state(2, other)))
        peer.takeOutbound()
        // A later signed state recommits the same key, with no viewer edition and no new edit.
        assertEquals(Outcome.Applied(1), peer.deliver(3, state(3, peer.sessionKey, other)))
        assertTrue(room.syncSteps(peer.takeOutbound()).isEmpty(), "a re-entry is not a redundant handshake")
        peer.tick(3)
        assertTrue(room.syncSteps(peer.takeOutbound()).isEmpty(), "readiness waits for the shared renewal clock")
        peer.tick(3 + renew)
        assertEquals(listOf("step1"), room.syncSteps(peer.takeOutbound()).map { it.first })

        // An uninterrupted writer and a listing-only edition re-arm nothing.
        val fresh = room.alone("p-fresh", AWARENESS)
        assertEquals(Outcome.Applied(1), fresh.deliver(1, state(1, fresh.sessionKey, other)))
        fresh.takeOutbound()
        assertEquals(Outcome.Applied(1), fresh.deliver(2, state(2, fresh.sessionKey, other)))
        fresh.tick(2 + renew)
        assertTrue(room.syncSteps(fresh.takeOutbound()).isEmpty(), "an uninterrupted writer owes nothing")
    }

    @Test
    fun `a viewer's speculative edit is discarded and never exported on promotion`() {
        val room = Room()
        val peer = room.alone("p-peer", AWARENESS)
        val other = SessionKey.mint()
        assertFalse(peer.insert("README.md", 0, "secret"))
        assertEquals("secret", peer.text("README.md"))
        peer.takeOutbound()
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                1,
                room.rolesFrame(
                    1,
                    1,
                    Triple(peer.sessionKey, "p-peer", Role.VIEWER),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        assertEquals(Role.VIEWER, peer.ownRole())
        assertEquals("", peer.text("README.md"), "the viewer resolution adopts the clean baseline")
        peer.takeOutbound()
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                2,
                room.rolesFrame(
                    2,
                    2,
                    Triple(peer.sessionKey, "p-peer", Role.GUEST),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        assertEquals(Role.GUEST, peer.ownRole())
        val out = peer.takeOutbound()
        assertEquals("", room.publishedText(out), "a promotion exports no viewer history")
        assertTrue(room.syncSteps(out).none { it.first == "update" })
    }

    @Test
    fun `a detached writer keeps its held edit and flushes it when a state commits it again`() {
        val room = Room()
        val peer = room.alone("p-peer", AWARENESS)
        val other = SessionKey.mint()
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                1,
                room.rolesFrame(
                    1,
                    1,
                    Triple(peer.sessionKey, "p-peer", Role.GUEST),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        peer.takeOutbound()
        peer.detach()
        assertFalse(peer.insert("README.md", 0, "offline"))
        assertEquals("offline", peer.text("README.md"))
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                2,
                room.rolesFrame(
                    2,
                    2,
                    Triple(peer.sessionKey, "p-peer", Role.GUEST),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        assertEquals(emptyList(), room.syncSteps(peer.takeOutbound()), "a detached session publishes nothing")
        peer.reseat("p-peer-2", listOf("p-peer-2"), ROTATED)
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                3,
                room.rolesFrame(
                    3,
                    3,
                    Triple(peer.sessionKey, "p-peer-2", Role.GUEST),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        val out = peer.takeOutbound()
        assertTrue(room.syncSteps(out).any { it.first == "update" }, "the held writer edit is flushed")
        assertEquals("offline", room.publishedText(out))
    }

    @Test
    fun `the clean baseline keeps an accepted pending delete set across a viewer adoption`() {
        val room = Room()
        val peer = room.alone("p-peer", AWARENESS)
        val other = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        // A state that commits the other key and not this one: this replica is UNKNOWN.
        assertEquals(Outcome.Applied(1), peer.deliver(1, room.rolesFrame(1, 1, Triple(other, "p-other", Role.GUEST))))
        peer.takeOutbound()

        val author = Doc(9)
        author.getText("README.md").insert(0, "abcdef")
        val seeded = Updates.encodeStateAsUpdate(author)
        author.getText("README.md").delete(1, 2)
        val deletion = Updates.encodeStateAsUpdate(author, Updates.encodeStateVector(author))
        // The deletion arrives first and cannot integrate: it is pending, and no text arrives.
        assertEquals(
            Outcome.Applied(0),
            peer.deliver(2, Frames.seal(room.roomId, frameKey, 0, 1, other, Sync.encode(SyncMessage.Update(deletion)))),
        )
        assertEquals("", peer.text("README.md"))

        // A speculative edit takes the baseline, and a viewer resolution adopts it.
        assertFalse(peer.insert("README.md", 0, "secret"))
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                3,
                room.rolesFrame(
                    2,
                    2,
                    Triple(peer.sessionKey, "p-peer", Role.VIEWER),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        assertEquals(Role.VIEWER, peer.ownRole())
        assertEquals("", peer.text("README.md"), "the adopted baseline kept no speculative text")
        // The dependency arrives: the baseline kept the pending delete set, so "bc" stays deleted.
        assertEquals(
            Outcome.Applied(0),
            peer.deliver(4, Frames.seal(room.roomId, frameKey, 0, 2, other, Sync.encode(SyncMessage.Update(seeded)))),
        )
        assertEquals("adef", peer.text("README.md"))
    }

    @Test
    fun `the clean baseline keeps a pending struct across a viewer adoption, and is not an alias`() {
        val room = Room()
        val peer = room.alone("p-peer", AWARENESS)
        val other = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        assertEquals(Outcome.Applied(1), peer.deliver(1, room.rolesFrame(1, 1, Triple(other, "p-other", Role.GUEST))))
        peer.takeOutbound()

        val author = Doc(9)
        author.getText("README.md").insert(0, "hello")
        val seeded = Updates.encodeStateAsUpdate(author)
        val beforeSecond = Updates.encodeStateVector(author)
        author.getText("README.md").insert(5, "!")
        val dependent = Updates.encodeStateAsUpdate(author, beforeSecond)
        // The dependent struct arrives first and cannot integrate: it is pending, and no text shows.
        assertEquals(
            Outcome.Applied(0),
            peer.deliver(
                2,
                Frames.seal(room.roomId, frameKey, 0, 1, other, Sync.encode(SyncMessage.Update(dependent))),
            ),
        )
        assertEquals("", peer.text("README.md"))

        // A speculative edit takes the clean baseline, and a viewer resolution adopts it.
        assertFalse(peer.insert("README.md", 0, "secret"))
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                3,
                room.rolesFrame(
                    2,
                    2,
                    Triple(peer.sessionKey, "p-peer", Role.VIEWER),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        assertEquals(Role.VIEWER, peer.ownRole())
        assertEquals("", peer.text("README.md"), "the adopted baseline kept no speculative text")
        // The viewer's own initial request goes out with the resolution; nothing else follows it.
        assertEquals(listOf("step1"), room.syncSteps(peer.takeOutbound()).map { it.first })

        // The dependency arrives: the baseline's own pending struct integrates and no alias shows.
        assertEquals(
            Outcome.Applied(0),
            peer.deliver(4, Frames.seal(room.roomId, frameKey, 0, 2, other, Sync.encode(SyncMessage.Update(seeded)))),
        )
        assertEquals("hello!", peer.text("README.md"))
        assertEquals(emptyList(), room.syncSteps(peer.takeOutbound()), "a viewer exports none of it")
    }

    @Test
    fun `a promoted writer publishes the room's history, never the discarded viewer edits`() {
        val room = Room()
        val renew = room.keepalive.awarenessRenewMs
        val peer = room.alone("p-peer", AWARENESS)
        val other = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)
        // The room's own history: "abcdef" with a "bc" tombstone over it.
        val author = Doc(9)
        author.getText("README.md").insert(0, "abcdef")
        val seeded = Updates.encodeStateAsUpdate(author)
        val beforeDelete = Updates.encodeStateVector(author)
        author.getText("README.md").delete(1, 2)
        val deletion = Updates.encodeStateAsUpdate(author, beforeDelete)
        val roomHistory = Updates.mergeUpdates(listOf(seeded, deletion))

        // This key starts UNKNOWN; a speculative insert and delete leave a ghost and a tombstone.
        assertEquals(Outcome.Applied(1), peer.deliver(1, room.rolesFrame(1, 1, Triple(other, "p-other", Role.GUEST))))
        peer.takeOutbound()
        assertFalse(peer.insert("README.md", 0, "ghost"))
        assertFalse(peer.delete("README.md", 0, 1))
        assertEquals("host", peer.text("README.md"))

        // A viewer resolution discards both and adopts the clean baseline.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                2,
                room.rolesFrame(
                    2,
                    2,
                    Triple(peer.sessionKey, "p-peer", Role.VIEWER),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        assertEquals("", peer.text("README.md"))
        peer.takeOutbound()

        // Promotion to a writer owes one readiness request at the shared clock, and no content.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                3,
                room.rolesFrame(
                    3,
                    3,
                    Triple(peer.sessionKey, "p-peer", Role.GUEST),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        val promoted = peer.takeOutbound()
        assertTrue(room.syncSteps(promoted).none { it.first == "update" }, "a promotion exports no viewer history")
        peer.tick(2 + renew)
        assertEquals(listOf("step1"), room.syncSteps(peer.takeOutbound()).map { it.first })

        // A later SyncStep2 carries the room's history, tombstones included: the ghost is not in it.
        assertEquals(
            Outcome.Applied(0),
            peer.deliver(
                3 + renew,
                Frames.seal(room.roomId, frameKey, 0, 1, other, Sync.encode(SyncMessage.Step2(roomHistory))),
            ),
        )
        assertEquals("adef", peer.text("README.md"), "the room's text without the discarded edits")
        // And the promoted writer would publish exactly that.
        peer.deliver(
            4 + renew,
            Frames.seal(room.roomId, frameKey, 0, 2, other, Sync.encode(SyncMessage.Step1(byteArrayOf(0)))),
        )
        assertEquals("adef", room.publishedText(peer.takeOutbound()))
    }

    @Test
    fun `a lost insertion is repaired when the same key re-enters writer commitment`() {
        val room = Room()
        val renew = room.keepalive.awarenessRenewMs
        val a = room.alone("p-a", 101)
        val b = room.alone("p-b", 202)
        val both =
            room.rolesFrame(
                1,
                1,
                Triple(a.sessionKey, "p-a", Role.GUEST),
                Triple(b.sessionKey, "p-b", Role.GUEST),
            )
        assertEquals(Outcome.Applied(1), a.deliver(1, both))
        assertEquals(Outcome.Applied(1), b.deliver(1, both))
        val aInitial = a.takeOutbound().single()
        val bInitial = b.takeOutbound().single()
        // Each answers the other's initial request, then owes and serves its reciprocal one.
        assertEquals(Outcome.Applied(0), b.deliver(2, aInitial))
        assertEquals(Outcome.Applied(0), a.deliver(2, bInitial))
        a.takeOutbound()
        b.takeOutbound()
        a.tick(1 + renew)
        b.tick(1 + renew)
        val aAsk = a.takeOutbound()
        val bAsk = b.takeOutbound()
        assertEquals(listOf("step1"), room.syncSteps(aAsk).map { it.first })
        assertEquals(listOf("step1"), room.syncSteps(bAsk).map { it.first })
        assertEquals(Outcome.Applied(0), b.deliver(1 + renew, aAsk.single()))
        assertEquals(Outcome.Applied(0), a.deliver(1 + renew, bAsk.single()))
        a.takeOutbound()
        b.takeOutbound()

        // B's first update is lost on the wire; its dependent second one is accepted and stays pending.
        assertTrue(b.insert("README.md", 0, "off "))
        b.takeOutbound()
        assertTrue(b.insert("README.md", 4, "on "))
        val dependent = b.takeOutbound().single()
        assertEquals(Outcome.Applied(0), a.deliver(2 + renew, dependent))
        assertEquals("", a.text("README.md"), "the dependent update is pending")
        assertEquals("off on ", b.text("README.md"))
        assertFalse(a.stateVector().contentEquals(b.stateVector()))

        // An applied state omits B's unchanged key, then a later one recommits it as a guest.
        val omission = room.rolesFrame(2, 2, Triple(a.sessionKey, "p-a", Role.GUEST))
        assertEquals(Outcome.Applied(1), a.deliver(3 + renew, omission))
        assertEquals(Outcome.Applied(1), b.deliver(3 + renew, omission))
        a.takeOutbound()
        b.takeOutbound()
        val regrant =
            room.rolesFrame(3, 3, Triple(a.sessionKey, "p-a", Role.GUEST), Triple(b.sessionKey, "p-b", Role.GUEST))
        assertEquals(Outcome.Applied(1), a.deliver(4 + renew, regrant))
        assertEquals(Outcome.Applied(1), b.deliver(4 + renew, regrant))
        a.takeOutbound()
        assertTrue(room.syncSteps(b.takeOutbound()).isEmpty(), "no new edit and no redundant handshake")

        // The recommitted key owes one coalesced request on the shared renewal clock.
        b.tick(1 + 2 * renew)
        val readiness = b.takeOutbound()
        assertEquals(listOf("step1"), room.syncSteps(readiness).map { it.first })
        assertEquals(Outcome.Applied(0), a.deliver(2 + 2 * renew, readiness.single()))
        a.takeOutbound()
        // A's census was pruned by the omission, so that request arms one of A's own.
        a.tick(2 + 2 * renew)
        val reciprocal = a.takeOutbound()
        assertEquals(listOf("step1"), room.syncSteps(reciprocal).map { it.first })
        assertEquals(Outcome.Applied(0), b.deliver(3 + 2 * renew, reciprocal.single()))

        // B answers A's request with the history A lacks, and A converges with no new edit.
        for (frame in b.takeOutbound()) {
            if (room.syncSteps(listOf(frame)).any { it.first == "step2" }) a.deliver(4 + 2 * renew, frame)
        }
        assertEquals("off on ", a.text("README.md"))
        assertEquals("off on ", b.text("README.md"))
        assertTrue(a.stateVector().contentEquals(b.stateVector()))
    }

    @Test
    fun `a lost deletion is repaired although both state vectors are equal`() {
        val room = Room()
        val renew = room.keepalive.awarenessRenewMs
        val a = room.alone("p-a", 101)
        val b = room.alone("p-b", 202)
        val both =
            room.rolesFrame(
                1,
                1,
                Triple(a.sessionKey, "p-a", Role.GUEST),
                Triple(b.sessionKey, "p-b", Role.GUEST),
            )
        assertEquals(Outcome.Applied(1), a.deliver(1, both))
        assertEquals(Outcome.Applied(1), b.deliver(1, both))
        val aInitial = a.takeOutbound().single()
        val bInitial = b.takeOutbound().single()
        assertEquals(Outcome.Applied(0), b.deliver(2, aInitial))
        assertEquals(Outcome.Applied(0), a.deliver(2, bInitial))
        a.takeOutbound()
        b.takeOutbound()
        a.tick(1 + renew)
        b.tick(1 + renew)
        val aAsk = a.takeOutbound()
        val bAsk = b.takeOutbound()
        assertEquals(Outcome.Applied(0), b.deliver(1 + renew, aAsk.single()))
        assertEquals(Outcome.Applied(0), a.deliver(1 + renew, bAsk.single()))
        a.takeOutbound()
        b.takeOutbound()

        assertTrue(b.insert("README.md", 0, "abcdef"))
        val seeded = b.takeOutbound().single()
        assertEquals(Outcome.Applied(0), a.deliver(2 + renew, seeded))
        assertEquals("abcdef", a.text("README.md"))

        // The deletion is lost on the wire: equal state vectors, different visible text.
        assertTrue(b.delete("README.md", 1, 2))
        b.takeOutbound()
        assertEquals("adef", b.text("README.md"))
        assertEquals("abcdef", a.text("README.md"))
        assertTrue(a.stateVector().contentEquals(b.stateVector()), "a deletion advances no clock")

        val omission = room.rolesFrame(2, 2, Triple(a.sessionKey, "p-a", Role.GUEST))
        assertEquals(Outcome.Applied(1), a.deliver(3 + renew, omission))
        assertEquals(Outcome.Applied(1), b.deliver(3 + renew, omission))
        a.takeOutbound()
        b.takeOutbound()
        val regrant =
            room.rolesFrame(3, 3, Triple(a.sessionKey, "p-a", Role.GUEST), Triple(b.sessionKey, "p-b", Role.GUEST))
        assertEquals(Outcome.Applied(1), a.deliver(4 + renew, regrant))
        assertEquals(Outcome.Applied(1), b.deliver(4 + renew, regrant))
        a.takeOutbound()
        b.takeOutbound()

        b.tick(1 + 2 * renew)
        val readiness = b.takeOutbound()
        assertEquals(listOf("step1"), room.syncSteps(readiness).map { it.first })
        assertEquals(Outcome.Applied(0), a.deliver(2 + 2 * renew, readiness.single()))
        a.takeOutbound()
        a.tick(2 + 2 * renew)
        val reciprocal = a.takeOutbound()
        assertEquals(listOf("step1"), room.syncSteps(reciprocal).map { it.first })
        assertEquals(Outcome.Applied(0), b.deliver(3 + 2 * renew, reciprocal.single()))
        for (frame in b.takeOutbound()) {
            if (room.syncSteps(listOf(frame)).any { it.first == "step2" }) a.deliver(4 + 2 * renew, frame)
        }
        assertEquals("adef", a.text("README.md"))
        assertEquals("adef", b.text("README.md"))
    }

    @Test
    fun `a fully deleted speculative ghost leaves no history after promotion and a fresh key`() {
        val room = Room()
        val peer = room.alone("p-peer", AWARENESS)
        val other = SessionKey.mint()
        val frameKey = Frames.frameKey(room.roomId, room.roomKey)

        // The room's own history: "abcdef" with a "bc" tombstone over it.
        val author = Doc(9)
        author.getText("README.md").insert(0, "abcdef")
        val seeded = Updates.encodeStateAsUpdate(author)
        val beforeDelete = Updates.encodeStateVector(author)
        author.getText("README.md").delete(1, 2)
        val deletion = Updates.encodeStateAsUpdate(author, beforeDelete)
        val roomHistory = Updates.mergeUpdates(listOf(seeded, deletion))

        // This key starts UNKNOWN: a speculative insert taken straight back leaves no visible text.
        assertEquals(Outcome.Applied(1), peer.deliver(1, room.rolesFrame(1, 1, Triple(other, "p-other", Role.GUEST))))
        peer.takeOutbound()
        assertFalse(peer.insert("README.md", 0, "ghost"))
        assertFalse(peer.delete("README.md", 0, 5))
        assertEquals("", peer.text("README.md"), "the ghost is fully gone from the visible text")

        // The ghost's structs are real history in the replica before the resolution, and the raw
        // state vector names them, so their later absence is a fact about history, not about text.
        val before = Updates.decodeStateVector(peer.stateVector())
        assertEquals(1, before.size, "the speculative edit left exactly one client's clocks")
        val ghostClient = before.keys.single()
        assertTrue(before.getValue(ghostClient) > 0, "the ghost's insert advanced its clock")

        // A viewer resolution discards the ghost and adopts the clean baseline.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                2,
                room.rolesFrame(
                    2,
                    2,
                    Triple(peer.sessionKey, "p-peer", Role.VIEWER),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        assertEquals(Role.VIEWER, peer.ownRole())
        peer.takeOutbound()
        assertFalse(
            ghostClient in Updates.decodeStateVector(peer.stateVector()),
            "the discarded replica took the ghost's whole client history with it",
        )

        // A promotion to writer, then a fresh key: neither revives the discarded history.
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                3,
                room.rolesFrame(
                    3,
                    3,
                    Triple(peer.sessionKey, "p-peer", Role.GUEST),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        peer.takeOutbound()
        peer.reseat("p-peer-2", listOf("p-peer-2"), ROTATED)
        assertEquals(
            Outcome.Applied(1),
            peer.deliver(
                4,
                room.rolesFrame(
                    4,
                    4,
                    Triple(peer.sessionKey, "p-peer-2", Role.GUEST),
                    Triple(other, "p-other", Role.GUEST),
                ),
            ),
        )
        peer.takeOutbound()

        // A later SyncStep2 carries the room's history; the promoted writer's publication holds it
        // and the tombstones that came with it, and nothing of the ghost's.
        assertEquals(
            Outcome.Applied(0),
            peer.deliver(
                5,
                Frames.seal(room.roomId, frameKey, 0, 1, other, Sync.encode(SyncMessage.Step2(roomHistory))),
            ),
        )
        assertEquals("adef", peer.text("README.md"), "the room's text without the discarded edits")
        peer.deliver(
            6,
            Frames.seal(room.roomId, frameKey, 0, 2, other, Sync.encode(SyncMessage.Step1(byteArrayOf(0)))),
        )
        val published = room.syncSteps(peer.takeOutbound()).filter { it.first == "step2" }.map { it.second }
        assertTrue(published.isNotEmpty(), "the promoted writer answers the request with its history")
        val decoded = Updates.decodeUpdate(published.last())
        assertTrue(decoded.structs.none { it.id.client == ghostClient }, "no ghost struct is published")
        assertTrue(ghostClient !in decoded.deleteSet.clients, "no ghost tombstone is published")
    }
}
