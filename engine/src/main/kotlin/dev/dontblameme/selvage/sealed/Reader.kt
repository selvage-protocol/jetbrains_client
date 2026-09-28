package dev.dontblameme.selvage.sealed

import dev.dontblameme.selvage.crdt.Lib0Decoder

/** §6.1's local report for a refused frame, in the table's order. */
enum class DropReason(
    val wire: String,
) {
    BAD_ENVELOPE("bad_envelope"),
    UNKNOWN_KIND("unknown_kind"),
    UNKNOWN_EPOCH("unknown_epoch"),
    UNCOMMITTED_KEY("uncommitted_key"),
    REPLAYED_COUNTER("replayed_counter"),
    BAD_SIGNATURE("bad_signature"),
    BAD_AEAD("bad_aead"),
    BAD_PAYLOAD("bad_payload"),
    STALE_ISSUED("stale_issued"),
    UNAUTHORISED_CONTENT("unauthorised_content"),
}

/** A reader's verdict on one frame; [sender] is the key id in hex once the envelope parsed. */
class Verdict(
    val reason: DropReason?,
    val sender: String? = null,
    val kind: Long? = null,
    val counter: Long? = null,
    val plaintext: ByteArray = ByteArray(0),
    val payload: Payload? = null,
) {
    val ok: Boolean get() = reason == null
}

/** A key a state commits: the key, its id, its spelling, and what the state says of it. */
class Committed(
    val key: ByteArray,
    val id: String,
    val spelling: String,
    val role: Role,
    val peerId: String,
)

/** The two receiver checks a conformance run can switch off to show the vectors catch them. */
enum class ReaderGuard(
    val wire: String,
) {
    IGNORE_ISSUED("ignore-issued"),
    IGNORE_ROLES("ignore-roles"),
}

/**
 * A receiver of one room's sealed frames (`CANONICAL.md` §6.1, `PROTOCOL.md` §13.2–§13.3): the
 * frame key and host key the invite carries, the keys the applied state commits, and the marks.
 */
class Reader(
    val roomId: String,
    roomKey: ByteArray,
    val hostKey: ByteArray,
) {
    val frameKey: ByteArray = Frames.frameKey(roomId, roomKey)
    private val hostId: String = Bytes.hex(SessionKey.keyId(hostKey))

    /** The applied state's keys, by spelling, ordered by spelling. */
    var committed: Map<String, Committed> = emptyMap()
        private set
    private var byId: Map<String, List<Committed>> = emptyMap()
    val marks = HashMap<String, Long>()
    var listing: List<String> = emptyList()
        private set
    val holds = HashMap<String, List<String>>()
    var issued = 0L
    var ended = false
    val guards = HashSet<ReaderGuard>()

    val entries: Collection<Committed> get() = committed.values

    fun byKeyId(id: String): List<Committed> = byId[id].orEmpty()

    fun spellingOf(id: String): String? = byId[id]?.firstOrNull()?.spelling

    fun roleOfKey(key: ByteArray): Role? = committed.values.firstOrNull { it.key.contentEquals(key) }?.role

    fun read(frame: ByteArray): Verdict {
        val envelope = Envelope.parse(frame) ?: return Verdict(DropReason.BAD_ENVELOPE)
        if (envelope.kind > 4) return refused(DropReason.UNKNOWN_KIND, envelope)
        if (envelope.epoch != 0L) return refused(DropReason.UNKNOWN_EPOCH, envelope)
        return if (envelope.kind == 4L) readAnnouncement(envelope) else readOrdinary(envelope)
    }

    /** The host applies the state it publishes to its own reader. */
    fun applyOwn(state: RoomState) = applyState(state)

    private fun readOrdinary(envelope: Envelope): Verdict {
        val id = Bytes.hex(envelope.keyId)
        val kind = envelope.kind
        val candidates =
            if (kind == 1L || kind == 2L) {
                if (id == hostId) listOf(hostKey) else emptyList()
            } else {
                byKeyId(id).map { it.key }
            }
        if (candidates.isEmpty()) return refused(DropReason.UNCOMMITTED_KEY, envelope)
        if ((kind == 0L || kind == 3L) &&
            replayed(id, envelope.counter)
        ) {
            return refused(DropReason.REPLAYED_COUNTER, envelope)
        }
        val sender =
            candidates.firstOrNull { Frames.authentic(roomId, envelope, it) }
                ?: return refused(DropReason.BAD_SIGNATURE, envelope)
        val plaintext = Frames.opens(roomId, frameKey, envelope) ?: return refused(DropReason.BAD_AEAD, envelope)
        val payload = Payload.read(kind, plaintext) ?: return refused(DropReason.BAD_PAYLOAD, envelope)
        val stated = payload.stated
        if (stated != null && stated <= issued && ReaderGuard.IGNORE_ISSUED !in guards) {
            return refused(DropReason.STALE_ISSUED, envelope)
        }
        if (kind == 0L && ReaderGuard.IGNORE_ROLES !in guards && isContent(plaintext) &&
            roleOfKey(sender) == Role.VIEWER
        ) {
            return refused(DropReason.UNAUTHORISED_CONTENT, envelope)
        }
        if (kind == 0L || kind == 3L) advance(id, envelope.counter)
        when (payload) {
            is Payload.State -> {
                applyState(payload.state)
            }

            is Payload.Closing -> {
                issued = payload.issued
                ended = true
            }

            is Payload.Holds -> {
                holds[id] = payload.holds.filter(Payload::usablePath)
            }

            else -> {}
        }
        return Verdict(null, id, kind, envelope.counter, plaintext, payload)
    }

    private fun readAnnouncement(envelope: Envelope): Verdict {
        val plaintext = Frames.opens(roomId, frameKey, envelope) ?: return refused(DropReason.BAD_AEAD, envelope)
        val announcement = Payload.readAnnouncement(plaintext) ?: return refused(DropReason.BAD_PAYLOAD, envelope)
        val key = KeyCodec.decode(announcement.key) ?: return refused(DropReason.BAD_PAYLOAD, envelope)
        val id = Bytes.hex(SessionKey.keyId(key))
        if (id != Bytes.hex(envelope.keyId)) return refused(DropReason.UNCOMMITTED_KEY, envelope)
        if (!Frames.authentic(roomId, envelope, key)) return refused(DropReason.BAD_SIGNATURE, envelope)
        if (replayed(id, envelope.counter)) return refused(DropReason.REPLAYED_COUNTER, envelope)
        advance(id, envelope.counter)
        return Verdict(null, id, envelope.kind, envelope.counter, plaintext, announcement)
    }

    private fun applyState(state: RoomState) {
        val next = ArrayList<Committed>()
        for ((spelling, entry) in state.peers) {
            val key = KeyCodec.decode(spelling) ?: continue
            next.add(Committed(key, Bytes.hex(SessionKey.keyId(key)), spelling, entry.role, entry.peerId))
        }
        next.sortBy { it.spelling }
        committed = next.associateByTo(LinkedHashMap()) { it.spelling }
        byId = next.groupBy { it.id }
        listing = state.listing.filter(Payload::usablePath)
        issued = state.issued
    }

    private fun replayed(
        id: String,
        counter: Long,
    ) = counter <= (marks[id] ?: 0L)

    private fun advance(
        id: String,
        counter: Long,
    ) {
        marks[id] = maxOf(marks[id] ?: 0L, counter)
    }

    private fun refused(
        reason: DropReason,
        envelope: Envelope,
    ) = Verdict(reason, Bytes.hex(envelope.keyId), envelope.kind, envelope.counter)

    companion object {
        /**
         * Whether a `kind = 0` plaintext carries document content: a SyncStep2 or an Update
         * anywhere in its stream, not only in its first message (§6.1 step 10).
         */
        fun isContent(plaintext: ByteArray): Boolean {
            val decoder = Lib0Decoder(plaintext)
            var content = false
            try {
                while (decoder.hasContent()) {
                    when (decoder.readVarUint()) {
                        0L -> {
                            val subtype = decoder.readVarUint()
                            content = content || subtype == 1L || subtype == 2L
                            decoder.readVarUint8Array()
                        }

                        1L, 2L -> {
                            decoder.readVarUint8Array()
                        }

                        3L -> {}

                        else -> {
                            break
                        }
                    }
                }
            } catch (e: RuntimeException) {
                return content
            }
            return content
        }
    }
}
