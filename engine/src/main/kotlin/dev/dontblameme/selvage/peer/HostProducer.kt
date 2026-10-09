package dev.dontblameme.selvage.peer

import dev.dontblameme.selvage.sealed.Frames
import dev.dontblameme.selvage.sealed.Payload
import dev.dontblameme.selvage.sealed.PeerEntry
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.sealed.RoomState
import dev.dontblameme.selvage.sealed.SessionKey

/** Why a host publishes a room state (`PROTOCOL.md` §13.2, §9.1). */
enum class HostReason { MINT, ROSTER, LISTING, ANNOUNCEMENT, RETURN }

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
    private val store: HostStore? = null,
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
    private var frames = 0L

    /** The count the store last holds, and the clock it was written at (`CANONICAL.md` §6.1). */
    private var savedFrames = -1L
    private var savedAt: Long? = null
    private var windowFrom: Long? = null
    private var owed = false
    private var lastState: RoomState? = null
    private var lastFrame: ByteArray? = null
    private var gone = false
    private var closed = false

    init {
        val persisted = store?.load()
        if (persisted != null && persisted.hostSeed.contentEquals(host.seed)) {
            if (persisted.issued > 0) issued = persisted.issued
            // §6.1: a reload is a return, so it costs the absence charge. A record with no count
            // cannot say what the room has sealed, so it reads as a budget already spent.
            val known = persisted.frames
            frames = if (known != null && known >= 0) known + ABSENCE_CHARGE else FRAME_BUDGET
            savedFrames = known ?: savedFrames
        }
    }

    /** The room's frame count this host continues from: `0` at a mint, the stored one on a reload. */
    val roomFrames: Long get() = frames

    val publishedIssued: Long get() = issued

    fun ready(): Boolean = !gone && !closed && ownSeat != null && ownKey != null

    /** The session's count as it moves, kept here so every write carries it beside `issued`. */
    fun countFrames(count: Long) {
        frames = count
    }

    /** Writes the count now, whatever the window: a return charges it and a reload continues from it. */
    fun saveFrames() {
        if (frames != savedFrames) save(savedAt)
    }

    /** §6.1: the count is written at least once every renewal window while it moves. */
    fun flushFrames(clock: Long) {
        if (frames == savedFrames) return
        val at = savedAt
        if (at != null && clock - at < renew) return
        save(clock)
    }

    fun seated(
        seat: String,
        sessionKey: SessionKey,
    ) {
        if (seat.isEmpty()) return
        ownSeat = seat
        ownKey = sessionKey
        addSeat(seat)
    }

    /**
     * The roster a re-hello hands back, which is the room's and not this host's memory of one: a
     * seat it does not name is gone, and the role committed to that seat goes with it.
     */
    fun reseated(roster: Collection<String>) {
        this.roster.clear()
        this.roster.addAll(roster)
        seats.values.removeIf { it.seat !in this.roster }
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
        save(clock)
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
        save(savedAt)
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

    /** What §7.1 has a host keep: the key, its `issued`, and the room's count (§6.1). */
    private fun save(clock: Long?) {
        val to = store ?: return
        to.save(PersistedHost(host.seed, issued, frames))
        savedFrames = frames
        savedAt = clock
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

        /**
         * What every return of the host — a reconnect or a reload — costs its count:
         * `CANONICAL.md` §6.1's absence charge, a fixed ceiling on the frames one absence can hide.
         */
        const val ABSENCE_CHARGE = 1L shl 21
    }
}
