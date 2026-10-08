package dev.dontblameme.selvage.peer

import dev.dontblameme.selvage.crdt.Awareness
import dev.dontblameme.selvage.crdt.DecodeException
import dev.dontblameme.selvage.crdt.Doc
import dev.dontblameme.selvage.crdt.RelativePosition
import dev.dontblameme.selvage.crdt.Sync
import dev.dontblameme.selvage.crdt.SyncMessage
import dev.dontblameme.selvage.crdt.TextDelta
import dev.dontblameme.selvage.crdt.Updates
import dev.dontblameme.selvage.crdt.YAny
import dev.dontblameme.selvage.sealed.DropReason
import dev.dontblameme.selvage.sealed.Frames
import dev.dontblameme.selvage.sealed.Payload
import dev.dontblameme.selvage.sealed.Reader
import dev.dontblameme.selvage.sealed.ReaderGuard
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.sealed.SessionKey
import dev.dontblameme.selvage.sealed.Verdict

/** The session clock a room advertises (`PROTOCOL.md` §8.2), in milliseconds. */
data class Keepalive(
    val pingIntervalMs: Long = 30_000,
    val awarenessRenewMs: Long = 15_000,
    val awarenessExpireMs: Long = 30_000,
)

/** Why a peer session ended on its own account (§13.10, §13.3, §6.2). */
enum class Ending(
    val wire: String,
    val sentence: String,
) {
    CLOSING("closing", "the room closed"),
    HOST_AWAY("host-away", "the host has been away past its window"),
    FRAME_BUDGET("frame-budget", "the room has sealed as many frames as its key allows; start a new room"),
    NO_STATE("no-state", "no state arrived within the no-state window"),
}

/** What a delivered frame did. */
sealed interface Outcome {
    data class Applied(
        val kind: Long,
    ) : Outcome

    /** Refused at [reason]; [sender], [kind] and [counter] are the envelope's once it parsed. */
    data class Dropped(
        val reason: DropReason,
        val sender: String? = null,
        val kind: Long? = null,
        val counter: Long? = null,
    ) : Outcome

    data class Ignored(
        val kind: Long,
    ) : Outcome
}

data class AppliedFrame(
    val frame: Int,
    val kind: Long,
)

data class DroppedFrame(
    val frame: Int,
    val reason: DropReason,
)

/** A guard of §13.11's table that a conformance run removes to show a vector catches it. */
enum class PeerMutation(
    val wire: String,
) {
    IGNORE_ROLES("ignore-roles"),
    IGNORE_ISSUED("ignore-issued"),
    ANNOUNCE_ONCE("announce-once"),
    NO_LEASE("no-lease"),
    ANY_CLOSING("any-closing"),
    WAIT_FOR_EVER("wait-for-ever"),
    ;

    companion object {
        fun of(wire: String): PeerMutation? = entries.firstOrNull { it.wire == wire }
    }
}

/** A host's seed and the paths it grants (§13.2). */
class HostOptions(
    val hostKey: SessionKey,
    val listing: () -> List<String>,
)

/** A change the room made to a document: the delta against the text before it. */
data class RemoteEdit(
    val path: String,
    val delta: List<TextDelta>,
)

class PeerOptions(
    val roomId: String,
    val roomKey: ByteArray,
    val hostKey: ByteArray,
    val keepalive: Keepalive,
    /** The seat the server gave this connection; the host needs one. */
    val seat: String? = null,
    val roster: Collection<String> = emptyList(),
    /** Fixed only by a conformance subject; a client mints a key per connection (§13.1). */
    val sessionKey: SessionKey? = null,
    val declaredRole: Role? = null,
    val awarenessClientId: Long? = null,
    val host: HostOptions? = null,
    val recordFrames: Boolean = true,
    val frameBudget: Long = HostProducer.FRAME_BUDGET,
)

/**
 * One client's side of `PROTOCOL.md` §13 over the sealed frames of `CANONICAL.md` §6: it reads
 * what the relay delivers, decides what to apply, and queues what to publish.
 *
 * Nothing here reads a clock or a socket. Every entry point takes the session clock in
 * milliseconds from the start of the session, and published frames wait in [takeOutbound], so
 * the caller owns time and transport. Not thread-safe: one caller drives it.
 */
class PeerSession(
    options: PeerOptions,
) {
    val roomId: String = options.roomId
    private val reader = Reader(options.roomId, options.roomKey, options.hostKey)
    private val frameKey = reader.frameKey
    private var session: SessionKey = options.sessionKey ?: SessionKey.mint()
    private val declaredRole: Role? = options.declaredRole
    private val renew = options.keepalive.awarenessRenewMs
    private val expire = options.keepalive.awarenessExpireMs
    private val recordFrames = options.recordFrames
    private val frameBudget = options.frameBudget
    var seat: String? = options.seat
        private set
    private var roster: MutableSet<String> = LinkedHashSet(options.roster)
    private val host: HostProducer? =
        options.host?.let { HostProducer(roomId, frameKey, renew, it.hostKey, it.listing) }

    private val doc = Doc()
    private var clockOfLastMove = 0L
    private val awareness = newAwareness(options.awarenessClientId ?: Doc.randomClientId(kotlin.random.Random))
    private var localState: YAny.Obj? = null
    private var awarenessRenewedAt: Long? = null

    /**
     * Whether a local awareness change was made while §13.1's step 4 held this connection back.
     *
     * The state is in [localState] and in the awareness set; what the gate holds is the frame,
     * which [flushHeldBackAwareness] publishes once a state commits this key. Left to §8.2's
     * renewal clock alone the room shows this connection's previous presence for a whole window,
     * so a client that has just joined or been re-seated is invisible, caret and selection alike.
     */
    private var awarenessHeld = false

    private val outbound = ArrayList<ByteArray>()
    private var detached = false
    private val held = sortedSetOf<String>()
    private var holdsSent: List<String> = emptyList()
    private var holdsAnnouncedAt: Long? = null
    private val leases = HashMap<String, Long>()
    private val departed = HashSet<String>()
    private var counter = 0L
    private var announcedAt: Long? = null
    private var resyncFrom: Long? = null
    private var handshakenAt: Long? = null
    private var stateIssued: Long? = null
    private var hostAwaySince: Long? = null
    private var roomFrames = 0L
    private val unsent = ArrayList<ByteArray>()
    private var heldStateFrame: ByteArray? = null
    private val observed = HashSet<String>()
    private val edits = ArrayList<RemoteEdit>()
    private var presenceMoved = false

    var published = 0
        private set
    var handshake = 0
        private set

    /** Every binary frame delivered, which a dropped or applied entry indexes. */
    var frames = 0
        private set
    val applied = ArrayList<AppliedFrame>()
    val dropped = ArrayList<DroppedFrame>()
    var ending: Ending? = null
        private set
    var mutation: PeerMutation? = null
        private set

    init {
        val own = seat
        if (host != null) {
            require(own != null) { "a host is seated before it publishes" }
            host.seated(own, session)
            roster.add(own)
            publishState(0, HostReason.MINT)
        }
    }

    val sessionKey: SessionKey get() = session
    val isHost: Boolean get() = host != null
    val issued: Long? get() = stateIssued
    val listing: List<String> get() = reader.listing
    val awarenessClientId: Long get() = awareness.clientID

    fun stateHeld(): Boolean = stateIssued != null

    fun heldPaths(): List<String> = held.toList()

    /** What each committed peer holds, keyed by its key spelling (§13.7). */
    fun peerHolds(): Map<String, List<String>> =
        reader.holds.entries.associate { (id, paths) ->
            (
                reader.spellingOf(id)
                    ?: id
            ) to
                paths.sorted()
        }

    /** Every path this client or a peer holds: the room's open set (§13.7). */
    fun openSet(): List<String> = (held + reader.holds.values.flatten()).toSortedSet().toList()

    fun text(path: String): String = if (path in doc.rootNames) doc.getText(path).toString() else ""

    fun has(path: String): Boolean = path in doc.rootNames

    fun documents(): List<String> = doc.rootNames.sorted()

    fun ownRole(): Role? = reader.roleOfKey(session.public)

    /** The role each committed seat holds, the first key of a seat deciding it. */
    fun rolesBySeat(): Map<String, Role> {
        val out = LinkedHashMap<String, Role>()
        for (entry in reader.entries) out.putIfAbsent(entry.peerId, entry.role)
        return out
    }

    fun hostSeat(): String? = reader.entries.firstOrNull { it.role == Role.HOST }?.peerId

    /** How long the host has left to come back, while it is away. */
    fun hostAwayGraceMs(clock: Long): Long? = hostAwaySince?.let { maxOf(0, expire - (clock - it)) }

    fun takeOutbound(): List<ByteArray> = ArrayList(outbound).also { outbound.clear() }

    fun takeEdits(): List<RemoteEdit> = ArrayList(edits).also { edits.clear() }

    fun takePresenceMoved(): Boolean = presenceMoved.also { presenceMoved = false }

    /** Every remote client's awareness state, resolved against this replica. */
    fun cursors(): List<Cursor> =
        awareness.states
            .filterKeys { it != awareness.clientID }
            .map { (client, state) -> AwarenessState.resolve(client, state, doc) }
            .sortedBy { it.clientId }

    fun forgetAwareness(clientId: Long) {
        if (clientId != awareness.clientID) awareness.removeStates(listOf(clientId), REMOVED)
    }

    // --- delivery -------------------------------------------------------------

    fun deliver(
        clock: Long,
        frame: ByteArray,
    ): Outcome {
        clockOfLastMove = clock
        frames += 1
        countFrame()
        val index = frames - 1
        val issuedBefore = reader.issued
        val endedBefore = reader.ended
        val verdict = reader.read(frame)
        val reason = verdict.reason
        if (reason != null) {
            if (verdict.kind == 0L && resyncFrom == null) resyncFrom = clock
            if (recordFrames) dropped.add(DroppedFrame(index, reason))
            return Outcome.Dropped(reason, verdict.sender, verdict.kind, verdict.counter)
        }
        val kind = verdict.kind ?: 0L
        if (verdict.payload is Payload.State) heldStateFrame = frame
        if (verdict.payload is Payload.Closing && !stateHeld() && mutation != PeerMutation.ANY_CLOSING) {
            // §13.10: a closing with no verified state below it ends nothing and is not refused.
            reader.issued = issuedBefore
            reader.ended = endedBefore
            return Outcome.Ignored(kind)
        }
        fold(clock, verdict)
        if (recordFrames) applied.add(AppliedFrame(index, kind))
        return Outcome.Applied(kind)
    }

    private fun fold(
        clock: Long,
        verdict: Verdict,
    ) {
        when (val payload = verdict.payload) {
            is Payload.State -> {
                afterState(clock, payload.state.issued)
            }

            is Payload.Closing -> {
                ending = Ending.CLOSING
            }

            is Payload.Holds -> {
                verdict.sender?.let { leases[it] = clock }
            }

            is Payload.Announcement -> {
                hearAnnouncement(clock, payload)
            }

            Payload.Content -> {
                applyContent(verdict.plaintext)
            }

            null -> {}
        }
    }

    private fun afterState(
        clock: Long,
        issued: Long,
    ) {
        stateIssued = issued
        host?.verifiedState(issued)
        refreshHostAway(clock, restart = true)
        dropDepartedHolds()
        if (commitsOurs()) {
            if (handshakenAt == null) syncStep1(clock)
            flushHeldBackEdits()
            flushHeldBackAwareness()
            announceHolds(clock)
        } else {
            announce(clock)
        }
    }

    private fun applyContent(plaintext: ByteArray) {
        if (!stateHeld()) return
        val messages =
            try {
                Sync.decode(plaintext)
            } catch (e: DecodeException) {
                return
            }
        val before = doc.rootNames.toSet()
        val replies = ArrayList<ByteArray>()
        try {
            for (message in messages) {
                when (message) {
                    is SyncMessage.Step1 -> {
                        if (replies.size < MAX_REPLIES_PER_FRAME) {
                            val diff = Updates.encodeStateAsUpdate(doc, message.stateVector)
                            replies.add(Sync.encode(SyncMessage.Step2(diff)))
                        }
                    }

                    is SyncMessage.Step2 -> {
                        Updates.applyUpdate(doc, message.update, APPLIED)
                    }

                    is SyncMessage.Update -> {
                        Updates.applyUpdate(doc, message.update, APPLIED)
                    }

                    is SyncMessage.Awareness -> {
                        awareness.applyUpdate(message.update, APPLIED)
                    }

                    else -> {}
                }
            }
        } catch (e: RuntimeException) {
            return
        } finally {
            observeNew(before)
        }
        if (ownRole() == Role.VIEWER || !commitsOurs()) return
        for (reply in replies) publish(Publication.SYNC, reply)
    }

    /** Roots a remote update created were not observed while it ran: they arrive whole. */
    private fun observeNew(before: Set<String>) {
        for (path in doc.rootNames) {
            if (path in before || path in observed) continue
            observe(path)
            val text = doc.getText(path).toString()
            edits.add(RemoteEdit(path, if (text.isEmpty()) emptyList() else listOf(TextDelta.Insert(text))))
        }
    }

    private fun observe(path: String) {
        if (!observed.add(path)) return
        doc.getText(path).observe { event ->
            if (event.origin !== LOCAL) edits.add(RemoteEdit(path, event.delta))
        }
    }

    private fun hearAnnouncement(
        clock: Long,
        announcement: Payload.Announcement,
    ) {
        val host = host ?: return
        host.announcement(announcement.key, announcement.role)
        publishState(clock, HostReason.ANNOUNCEMENT)
    }

    // --- the clock --------------------------------------------------------------

    /** The soonest clock at which [tick] has something due, other than the renewal window. */
    fun nextDeadline(): Long? {
        if (ending != null || detached) return null
        var soonest = host?.owedAt()
        val at = announcedAt
        if (host == null && !commitsOurs() && mutation != PeerMutation.ANNOUNCE_ONCE && at != null) {
            val due = at + renew
            if (soonest == null || due < soonest) soonest = due
        }
        return soonest
    }

    fun tick(clock: Long) {
        clockOfLastMove = clock
        expireLeases(clock)
        refreshHostAway(clock, restart = false)
        if (ending != null) return
        if (budgetSpent()) return
        if (windowPassed(clock)) return
        publishState(clock, HostReason.ANNOUNCEMENT)
        reannounce(clock)
        resync(clock)
        announceHolds(clock)
        renewAwareness(clock)
        awareness.tick()
    }

    private fun renewAwareness(clock: Long) {
        val state = localState ?: return
        val at = awarenessRenewedAt
        if (at != null && clock - at < renew) return
        awarenessRenewedAt = clock
        awareness.setLocalState(state)
    }

    // --- the roster ---------------------------------------------------------------

    fun seatJoined(
        clock: Long,
        seat: String,
    ) {
        clockOfLastMove = clock
        host?.seatJoined(seat)
        departed.remove(seat)
        roster.add(seat)
        holdsAnnouncedAt = null
        refreshHostAway(clock, restart = false)
        if (host != null) {
            publishState(clock, HostReason.ROSTER)
            return
        }
        // §13.3: with the host away, a newcomer gets the last state from a peer that holds it.
        val hostSeat = hostSeat()
        val frame = heldStateFrame
        if (frame != null && (hostSeat == null || hostSeat !in roster)) republish(frame)
    }

    fun seatLeft(
        clock: Long,
        seat: String,
    ) {
        clockOfLastMove = clock
        host?.seatLeft(seat)
        roster.remove(seat)
        departed.add(seat)
        dropDepartedHolds()
        refreshHostAway(clock, restart = false)
        if (host != null) publishState(clock, HostReason.ROSTER)
    }

    /** A reconnect: a new seat, a new session key, and everything tied to the old key reset. */
    fun reseat(
        seat: String,
        roster: Collection<String>,
        awarenessClientId: Long,
    ) {
        session = SessionKey.mint()
        detached = false
        this.seat = seat
        this.roster = LinkedHashSet(roster)
        awareness.rotate(awarenessClientId)
        awarenessRenewedAt = null
        // The held frame belonged to the connection that went; the re-seat republishes
        // `localState` on its own clock, under the fresh id, and never under the dead key (§9.1,
        // §8.4). The tick that follows a re-seat is what holds it again while the gate is shut.
        awarenessHeld = false
        counter = 0
        announcedAt = null
        handshakenAt = null
        resyncFrom = null
        holdsAnnouncedAt = null
        ending = null
        if (host != null) {
            roomFrames += ABSENCE_CHARGE
            host.seated(seat, session)
        }
    }

    fun detach() {
        detached = true
        outbound.clear()
    }

    fun listingChanged(clock: Long) {
        clockOfLastMove = clock
        publishState(clock, HostReason.LISTING)
    }

    /** The host ends the room for everyone (§13.10). */
    fun closeRoom(): Boolean {
        val host = host ?: return false
        val publication = host.closing() ?: return false
        countFrame()
        outbound.add(publication.frame)
        published += 1
        ending = Ending.CLOSING
        return true
    }

    // --- local changes ------------------------------------------------------------

    /** Hold [path] open: the holds are announced on the next tick (§13.7). */
    fun open(path: String) {
        held.add(path)
    }

    fun release(path: String? = null) {
        if (path == null) held.clear() else held.remove(path)
    }

    fun insert(
        path: String,
        index: Int,
        text: String,
    ): Boolean {
        val publishing = text.isEmpty() && path !in doc.rootNames
        val update =
            editLocally(path) { handle ->
                if (index < 0 || index > handle.length) {
                    throw IndexOutOfBoundsException("there is no offset $index in \"$path\"")
                }
                if (publishing) {
                    // An empty document still has to reach the room: a mark put in and taken out.
                    handle.insert(0, EMPTY_DOCUMENT_MARK)
                    handle.delete(0, EMPTY_DOCUMENT_MARK.length)
                } else {
                    handle.insert(index, text)
                }
            }
        return publishEdit(update)
    }

    fun delete(
        path: String,
        index: Int,
        length: Int,
    ): Boolean {
        val update =
            editLocally(path) { handle ->
                if (index < 0 || length < 0 || index + length > handle.length) {
                    throw IndexOutOfBoundsException("there is no range $index..${index + length} in \"$path\"")
                }
                handle.delete(index, length)
            }
        return publishEdit(update)
    }

    private fun editLocally(
        path: String,
        edit: (dev.dontblameme.selvage.crdt.YText) -> Unit,
    ): ByteArray? {
        val captured = ArrayList<ByteArray>()
        val handle = doc.getText(path)
        observe(path)
        val stop = doc.onUpdate { update, origin, _ -> if (origin === LOCAL) captured.add(update) }
        try {
            doc.transact(LOCAL) { edit(handle) }
        } finally {
            stop()
        }
        return if (captured.isEmpty()) null else Updates.mergeUpdates(captured)
    }

    private fun publishEdit(update: ByteArray?): Boolean {
        if (update == null) return false
        if (!mayPublish() || ownRole() == Role.VIEWER) {
            if (ownRole() != Role.VIEWER) unsent.add(update)
            return false
        }
        publish(Publication.CONTENT, Sync.encode(SyncMessage.Update(update)))
        return true
    }

    /** Where this client is: a document, and a selection in it when [selection] is given. */
    fun setCursor(
        path: String?,
        selection: Selection? = null,
    ) {
        val state =
            when {
                path == null -> {
                    null
                }

                selection == null || path !in doc.rootNames -> {
                    AwarenessState.of(path, null, null)
                }

                else -> {
                    val text = doc.getText(path)
                    if (selection.anchor > text.length || selection.head > text.length) {
                        AwarenessState.of(path, null, null)
                    } else {
                        AwarenessState.of(
                            path,
                            RelativePosition.fromIndex(text, selection.anchor),
                            RelativePosition.fromIndex(text, selection.head),
                        )
                    }
                }
            }
        if (state == localState) return
        localState = state
        awareness.setLocalState(state)
    }

    // --- guards ---------------------------------------------------------------------

    fun mutate(name: PeerMutation) {
        when (name) {
            PeerMutation.IGNORE_ROLES -> {
                reader.guards.add(ReaderGuard.IGNORE_ROLES)
            }

            PeerMutation.IGNORE_ISSUED -> {
                reader.guards.add(ReaderGuard.IGNORE_ISSUED)
            }

            else -> {}
        }
        mutation = name
    }

    // --- publishing -------------------------------------------------------------------

    private fun newAwareness(clientId: Long): Awareness {
        val made = Awareness(clientId, renew, expire) { clockOfLastMove }
        made.setLocalState(null)
        made.onUpdate { change, origin ->
            if (origin != Awareness.LOCAL) return@onUpdate
            val clients = change.added + change.updated + change.removed
            if (clients.isEmpty()) return@onUpdate
            if (!mayPublish()) {
                // §13.1's step 4 holds the frame, not the state: `localState` keeps it, and the
                // state that commits this key publishes it without a renewal window's wait.
                awarenessHeld = true
                return@onUpdate
            }
            publishAwareness(clients)
        }
        made.onChange { _, origin -> if (origin != Awareness.LOCAL) presenceMoved = true }
        return made
    }

    /** One awareness frame for [clients], with §8.2's renewal clock re-based on it. */
    private fun publishAwareness(clients: Collection<Long>) {
        publish(Publication.CONTENT, Sync.encode(SyncMessage.Awareness(awareness.encodeUpdate(clients))))
        awarenessRenewedAt = clockOfLastMove
    }

    private fun flushHeldBackEdits() {
        if (unsent.isEmpty()) return
        val merged = Updates.mergeUpdates(ArrayList(unsent))
        unsent.clear()
        if (ownRole() == Role.VIEWER) return
        publish(Publication.CONTENT, Sync.encode(SyncMessage.Update(merged)))
    }

    /**
     * Publishes the local awareness state a change made before a state committed this key.
     *
     * §13.1's step 4 held the frame; the state is in [localState], and leaving the frame to
     * §8.2's renewal clock is a whole window in which the room still shows this connection's
     * previous presence. What goes out is [localState], so the latest change is the one published,
     * and a cleared state stays cleared: an empty state would put a live presence with a cursor at
     * nowhere back on the wire.
     */
    private fun flushHeldBackAwareness() {
        if (!awarenessHeld) return
        if (localState == null) {
            // A clearing owes nothing; the next change that does set a state is held on its own.
            awarenessHeld = false
            return
        }
        if (!mayPublish()) return
        awarenessHeld = false
        publishAwareness(listOf(awareness.clientID))
    }

    private fun commitsOurs(): Boolean = reader.entries.any { it.key.contentEquals(session.public) }

    private fun mayPublish(): Boolean = !detached && ending == null && stateHeld() && commitsOurs()

    private fun announce(clock: Long) {
        if (host != null) return
        publish(Publication.ANNOUNCEMENT, Payload.announcement(session.spelling, declaredRole))
        announcedAt = clock
    }

    private fun reannounce(clock: Long) {
        if (commitsOurs() || mutation == PeerMutation.ANNOUNCE_ONCE) return
        val at = announcedAt
        if (at == null || clock - at >= renew) announce(clock)
    }

    private fun announceHolds(clock: Long) {
        if (!mayPublish()) return
        if (held.isEmpty() && holdsSent.isEmpty() && holdsAnnouncedAt == null) return
        val paths = heldPaths()
        val at = holdsAnnouncedAt
        val due = paths != holdsSent || at == null || clock - at >= renew
        if (!due) return
        publish(Publication.HOLDS, Payload.holds(paths))
        holdsSent = paths
        holdsAnnouncedAt = clock
    }

    private fun publishState(
        clock: Long,
        reason: HostReason,
    ) {
        val host = host ?: return
        if (roomFrames >= frameBudget) return
        val publication = host.publish(clock, reason) ?: return
        if (publication.fresh) countFrame()
        outbound.add(publication.frame)
        published += 1
        val state = publication.state
        if (!publication.fresh || state == null) return
        reader.applyOwn(state)
        afterState(clock, publication.issued)
    }

    private fun republish(frame: ByteArray) {
        if (ending != null) return
        outbound.add(frame)
        published += 1
    }

    private fun expireLeases(clock: Long) {
        if (mutation == PeerMutation.NO_LEASE) return
        val lapsed = leases.filterValues { clock - it >= expire }.keys
        for (id in lapsed) {
            leases.remove(id)
            reader.holds.remove(id)
        }
    }

    private fun refreshHostAway(
        clock: Long,
        restart: Boolean,
    ) {
        val seat = hostSeat()
        val absent = seat != null && seat !in roster
        hostAwaySince =
            when {
                !absent -> null
                restart || hostAwaySince == null -> clock
                else -> hostAwaySince
            }
    }

    private fun dropDepartedHolds() {
        for (entry in reader.entries) {
            if (entry.peerId in departed) {
                leases.remove(entry.id)
                reader.holds.remove(entry.id)
            }
        }
    }

    private fun countFrame() {
        roomFrames += 1
        host?.roomFrames = roomFrames
    }

    private fun budgetSpent(): Boolean {
        if (roomFrames < frameBudget) return false
        host?.closing()?.let {
            countFrame()
            outbound.add(it.frame)
            published += 1
        }
        ending = Ending.FRAME_BUDGET
        return true
    }

    private fun windowPassed(clock: Long): Boolean {
        val away = hostAwaySince
        if (away != null && clock - away >= expire) {
            ending = Ending.HOST_AWAY
            return true
        }
        if (!stateHeld() && mutation != PeerMutation.WAIT_FOR_EVER && clock >= expire) {
            ending = Ending.NO_STATE
            return true
        }
        return false
    }

    private fun syncStep1(clock: Long) {
        publish(Publication.SYNC, Sync.encode(SyncMessage.Step1(Updates.encodeStateVector(doc))))
        handshakenAt = clock
    }

    private fun resync(clock: Long) {
        if (resyncFrom == null || !mayPublish()) return
        val at = handshakenAt
        if (at == null || clock - at >= renew) {
            resyncFrom = null
            syncStep1(clock)
        }
    }

    private enum class Publication(
        val kind: Long,
    ) {
        ANNOUNCEMENT(4),
        HOLDS(3),
        CONTENT(0),
        SYNC(0),
    }

    private fun publish(
        what: Publication,
        plaintext: ByteArray,
    ) {
        if (ending != null || roomFrames >= frameBudget) return
        counter += 1
        outbound.add(Frames.seal(roomId, frameKey, what.kind, counter, session, plaintext))
        countFrame()
        if (what == Publication.SYNC) handshake += 1 else published += 1
    }

    companion object {
        /** What a host is charged for a reconnect whose frames it could not count (§6.2). */
        const val ABSENCE_CHARGE = 1L shl 21
        private const val MAX_REPLIES_PER_FRAME = 1
        private const val EMPTY_DOCUMENT_MARK = "x"
        private val LOCAL = Any()
        private val APPLIED = Any()
        private const val REMOVED = "left"
    }
}
