package dev.dontblameme.selvage.peer

import dev.dontblameme.selvage.sealed.Frames
import dev.dontblameme.selvage.sealed.Payload
import dev.dontblameme.selvage.sealed.PeerEntry
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.sealed.RoomState
import dev.dontblameme.selvage.sealed.SessionKey

/** Why a host publishes a room state (`PROTOCOL.md` §13.2). */
enum class HostReason { MINT, ROSTER, LISTING, ANNOUNCEMENT }

/** A state or closing a host sealed; [fresh] is false for a republished frame. */
class HostPublication(
    val frame: ByteArray,
    val issued: Long,
    val fresh: Boolean,
    val state: RoomState? = null,
)

/**
 * The host's side of §13.2: which key sits in which seat under which role, the room state that
 * says so, and the closing. It seals with the host key; everything else it is told.
 */
class HostProducer(
    private val roomId: String,
    private val frameKey: ByteArray,
    private val renew: Long,
    val host: SessionKey,
    private val listing: () -> List<String>,
) {
    private class SeatEntry(
        val spelling: String,
        val seat: String,
        val role: Role,
        val order: Long,
    )

    private val roster = ArrayList<String>()
    private val seats = LinkedHashMap<String, SeatEntry>()
    private var ownSeat: String? = null
    private var ownKey: SessionKey? = null
    private var issued = 0L
    private var verified = 0L
    private var hostCounter = 0L
    private var order = 0L
    private var windowFrom: Long? = null
    private var owed = false
    private var lastState: RoomState? = null
    private var lastFrame: ByteArray? = null
    private var gone = false
    private var closed = false

    /** Every frame this room has sealed, which the key's budget bounds (§6.2). */
    var roomFrames = 0L

    val publishedIssued: Long get() = issued

    fun ready(): Boolean = !gone && !closed && ownSeat != null && ownKey != null

    fun seated(
        seat: String,
        sessionKey: SessionKey,
    ) {
        if (seat.isEmpty()) return
        ownSeat = seat
        ownKey = sessionKey
        addSeat(seat)
    }

    fun seatJoined(seat: String) {
        if (seat == ownSeat) gone = false
        addSeat(seat)
    }

    fun seatLeft(seat: String) {
        if (seat == ownSeat) gone = true
        roster.remove(seat)
        seats.values.removeIf { it.seat == seat }
    }

    /** A verified announcement: commit a new key to a free seat, or answer a known one again. */
    fun announcement(
        key: String,
        declared: Role?,
    ) {
        if (key !in seats) commit(key, declared)
        owed = true
    }

    /** When an owed answer may next be published, if one is owed. */
    fun owedAt(): Long? {
        val from = windowFrom
        if (!owed || !ready() || from == null) return null
        return from + renew
    }

    fun verifiedState(issued: Long) {
        if (issued > verified) verified = issued
    }

    fun publish(
        clock: Long,
        reason: HostReason,
    ): HostPublication? {
        if (!ready()) return null
        if (reason == HostReason.ANNOUNCEMENT && (!owed || !windowOpen(clock))) return null
        owed = false
        val paths = roomListing()
        val peers = peerEntries()
        val held = lastState
        val heldFrame = lastFrame
        if (held != null && heldFrame != null && held.issued >= verified && held.listing == paths &&
            held.peers == peers
        ) {
            window(clock, reason)
            return HostPublication(heldFrame, held.issued, fresh = false, state = held)
        }
        val next = maxOf(issued, verified) + 1
        val state = RoomState(next, paths, peers)
        val frame = seal(1, state.encode())
        issued = next
        lastState = state
        lastFrame = frame
        window(clock, reason)
        return HostPublication(frame, next, fresh = true, state = state)
    }

    fun closing(): HostPublication? {
        if (!ready()) return null
        val next = maxOf(issued, verified) + 1
        val frame = seal(2, Payload.closing(next))
        issued = next
        closed = true
        return HostPublication(frame, next, fresh = true)
    }

    private fun addSeat(seat: String) {
        if (seat !in roster) roster.add(seat)
    }

    private fun window(
        clock: Long,
        reason: HostReason,
    ) {
        if (reason == HostReason.ANNOUNCEMENT) windowFrom = clock
    }

    private fun windowOpen(clock: Long): Boolean = windowFrom.let { it == null || clock - it >= renew }

    private fun commit(
        spelling: String,
        declared: Role?,
    ) {
        val seat = label() ?: return
        if (seat != ownSeat) seats.values.removeIf { it.seat == seat }
        seats[spelling] = SeatEntry(spelling, seat, declared ?: Role.GUEST, order++)
    }

    /** The seat a new key is committed to: a free one, else the oldest guest's, else the host's. */
    private fun label(): String? {
        val own = ownSeat ?: return null
        val taken = HashSet<String>().apply { add(own) }
        seats.values.filter { it.seat in roster }.mapTo(taken) { it.seat }
        roster.firstOrNull { it !in taken }?.let { return it }
        return seats.values
            .filter { it.seat != own && it.seat in roster }
            .minByOrNull { it.order }
            ?.seat ?: own
    }

    private fun peerEntries(): Map<String, PeerEntry> {
        val peers = LinkedHashMap<String, PeerEntry>()
        seats.values
            .filter { it.seat in roster }
            .sortedBy { it.spelling }
            .forEach { peers[it.spelling] = PeerEntry(it.seat, it.role) }
        val seat = ownSeat
        val key = ownKey
        if (seat != null && key != null) peers[key.spelling] = PeerEntry(seat, Role.HOST)
        return peers
    }

    private fun roomListing(): List<String> {
        val paths = ArrayList<String>()
        val seen = HashSet<String>()
        var bytes = 0L
        for (path in listing()) {
            if (!Payload.usablePath(path) || path in seen) continue
            val size = path.toByteArray(Charsets.UTF_8).size
            if (paths.size >= MAX_LISTING_PATHS || bytes + size > MAX_LISTING_BYTES) break
            seen.add(path)
            paths.add(path)
            bytes += size
        }
        return paths.sorted()
    }

    private fun seal(
        kind: Long,
        plaintext: ByteArray,
    ): ByteArray = Frames.seal(roomId, frameKey, kind, ++hostCounter, host, plaintext)

    companion object {
        const val MAX_LISTING_PATHS = 100_000
        const val MAX_LISTING_BYTES = 4L * 1024 * 1024

        /** How many frames one room key may seal before the room must end (§6.2). */
        const val FRAME_BUDGET = 1L shl 31
    }
}
