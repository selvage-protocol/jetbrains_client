package dev.dontblameme.selvage.peer

import dev.dontblameme.selvage.crdt.Awareness
import dev.dontblameme.selvage.crdt.DecodeException
import dev.dontblameme.selvage.crdt.Doc
import dev.dontblameme.selvage.crdt.EditorCommit
import dev.dontblameme.selvage.crdt.EditorText
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
    /**
     * Test-only: makes the frames a predicate names fail to seal (§6.1), as a provider that
     * cannot seal does. The attempt still spends its counter and room-frame budget and queues
     * nothing, which is what a test needs to drive a persistent publication failure.
     */
    internal val sealFault: ((ByteArray) -> Boolean)? = null,
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
    private val sealFault = options.sealFault
    var seat: String? = options.seat
        private set
    private var roster: MutableSet<String> = LinkedHashSet(options.roster)
    private val host: HostProducer? =
        options.host?.let { HostProducer(roomId, frameKey, renew, it.hostKey, it.listing) }

    /**
     * The session replica. An ordinary session has one document; a viewer resolution adopts the
     * clean UNKNOWN-role [baseline] instead, so nothing here keeps a permanent pair.
     */
    private var doc = Doc()

    /** A clone taken before the first UNKNOWN-role local edit, so a viewer resolution can adopt it. */
    private var baseline: Doc? = null
    private val publicationDoc: Doc get() = baseline ?: doc

    /** The token an adapter compares; a viewer resolution replaces it with the adopted replica. */
    private var identity: Any = Any()
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

    /**
     * The committed writer keys whose first accepted `SyncStep1` this connection has answered with
     * a request of its own, by canonical key spelling.
     *
     * One entry per continuous writer eligibility: a duplicate or later request from a key already
     * here arms nothing, and a state that revokes or demotes the key prunes it so a later promotion
     * can arm once more. This is what stops the reciprocal request from echoing for ever.
     */
    private val reciprocalServed = HashSet<String>()

    /**
     * The committed writer keys whose reciprocal request is owed and not yet on the wire.
     *
     * Per key, so that a state which revokes or demotes one of them retires just that cause and
     * leaves a refusal, local readiness or another still-pending writer's request standing (§13.6).
     */
    private val reciprocalPending = LinkedHashSet<String>()

    /** Whether this key's own initial `SyncStep1` is owed because the first attempt did not queue. */
    private var initialPending = false

    /** Readiness owed for this local key's renewed continuous writer eligibility. */
    private var localReadinessPending = false

    /** The role the last applied state gave this connection's own key, for continuous eligibility. */
    private var roleSeen: Role? = null

    /**
     * The clock at which the last authored `SyncStep1` attempt failed to queue, if any.
     *
     * §13.6's shared clock advances only on a successful request, so a refused attempt leaves the
     * obligations in place — but an automatic retry must not ride the same overdue deadline it
     * would have had before the attempt: that is a wakeup every millisecond, not one per renewal
     * interval. The owning session stamps this with the clock it reads after the attempt returned,
     * so a synchronous seal that took real time is not credited with zero elapsed time. This is
     * the later floor the automatic path waits out, and it is cleared by a success and by a
     * fresh-key re-seat.
     */
    private var failedStep1At: Long? = null

    /** Whether an attempt failed since the last stamp, which the owner's next note settles. */
    private var step1FailurePending = false
    private var stateIssued: Long? = null
    private var hostAwaySince: Long? = null
    private var roomFrames = 0L
    private val unsent = ArrayList<Held>()
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

    /**
     * An opaque token for the replica this session applies to, for an adapter whose cached editor
     * basis is bound to one document. It changes exactly when a viewer resolution adopts the
     * baseline, which is the moment a bound editor's own basis goes stale even when its text is
     * equal. Nothing mutable is handed out: the adapter only compares tokens.
     */
    internal val docIdentity: Any get() = identity

    fun stateHeld(): Boolean = stateIssued != null

    fun heldPaths(): List<String> = held.toList()

    /** The committed writer spellings this connection has already owed a reciprocal request. */
    internal fun reciprocalCensus(): Set<String> = reciprocalServed.toSet()

    /** The applied replica's state vector, for a test that asserts two peers converged. */
    internal fun stateVector(): ByteArray = Updates.encodeStateVector(doc)

    /** Every authored `SyncStep1` this connection attempted, queued or not, for a test's bound. */
    internal var step1Attempts = 0
        private set

    /** The clock the owning session stamped for the last failed `SyncStep1` attempt, if any. */
    internal val step1FailureAt: Long? get() = failedStep1At

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
                applyContent(verdict.plaintext, verdict.verified)
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
        pruneReciprocal()
        val role = ownRole()
        if (role == Role.HOST || role == Role.GUEST) {
            // §13.6: an already-handshaken local key that re-enters writer commitment after a
            // viewer or an absence owes one coalesced readiness request. A first commitment uses
            // the immediate initial handshake instead, and an uninterrupted writer owes nothing.
            if (handshakenAt != null && roleSeen != Role.HOST && roleSeen != Role.GUEST) {
                localReadinessPending = true
            }
        } else {
            localReadinessPending = false
        }
        roleSeen = role
        if (commitsOurs()) {
            // §13.9's clean history is adopted before any request or reply is encoded.
            if (role == Role.VIEWER) {
                discardSpeculative()
            } else if (role == Role.HOST || role == Role.GUEST) {
                acceptSpeculation()
            }
            // §13.1's steps 6 and 4: the request and the held obligations a state that commits this
            // key owes. A step 6 seal fault is reported to the caller, but it must not take the held
            // edit, awareness or hold with it: no later state may come to serve them.
            var failure: Exception? = null
            if (handshakenAt == null) {
                try {
                    initialHandshake(clock)
                } catch (e: Exception) {
                    failure = e
                }
            }
            try {
                flushHeldBackEdits()
                flushHeldBackAwareness()
                announceHolds(clock)
            } catch (e: Exception) {
                if (failure == null) {
                    failure = e
                } else if (failure !== e) {
                    failure.addSuppressed(e)
                }
            }
            if (failure != null) throw failure
        } else {
            announce(clock)
        }
    }

    /**
     * Retires the census and pending entries for keys the applied state no longer commits as
     * writers.
     *
     * §13.3 replaces the room's keys wholesale, so a revoked or demoted key is no longer eligible
     * and a later promotion may arm one request again; a key that stays continuously committed
     * keeps its served mark, which is what stops a duplicate or later request from re-arming. A
     * key that loses eligibility owes nothing, so its pending request goes with the mark — and
     * only that key's, leaving a refusal, local readiness or another writer's request standing.
     */
    private fun pruneReciprocal() {
        fun eligible(spelling: String): Boolean {
            val entry = reader.committed[spelling] ?: return false
            return entry.role == Role.HOST || entry.role == Role.GUEST
        }
        reciprocalServed.removeAll { !eligible(it) }
        reciprocalPending.removeAll { !eligible(it) }
    }

    /**
     * Runs the first request a newly committed key owes, retaining the obligation when the attempt
     * neither queues nor returns.
     *
     * §13.6's step 6 request is owed whether the seal refused it or threw: a real JCA, randomness
     * or signing fault is reported to the caller as it happened, and the obligation stays for the
     * automatic path to serve inside the paced window rather than being lost with the exception.
     */
    private fun initialHandshake(clock: Long) {
        try {
            if (!syncStep1(clock)) initialPending = true
        } catch (e: Exception) {
            initialPending = true
            throw e
        }
    }

    /**
     * Arms one reciprocal request for the first accepted `SyncStep1` from a continuously committed
     * writer key.
     *
     * §13.1's step 6 asks the room for what this replica lacks; a peer's own request is evidence
     * that it has applied a committing state and can answer, so answering it with our own request
     * makes the exchange reciprocal. A request from a `viewer` is not one: a viewer sends no
     * content, so reciprocating would solicit nothing. The obligation belongs to the sending key's
     * eligibility, not this connection's: one armed while this key cannot publish is retained and
     * served when publication becomes possible and the clock is due (§13.6).
     */
    private fun armReciprocal(verified: ByteArray?) {
        val key = verified ?: return
        val entry = reader.committedOf(key) ?: return
        if (entry.role != Role.HOST && entry.role != Role.GUEST) return
        if (!reciprocalServed.add(entry.spelling)) return
        reciprocalPending.add(entry.spelling)
    }

    /**
     * Takes the clean baseline before the first UNKNOWN-role local edit.
     *
     * A clone by encode/apply is a document of its own, never a store alias, and it keeps the
     * accepted pending structs and delete sets the ordinary replica holds; one baseline serves
     * every speculative keystroke until the role resolves.
     */
    private fun beginSpeculation() {
        if (baseline != null) return
        baseline = Doc().also { Updates.applyUpdate(it, Updates.encodeStateAsUpdate(doc)) }
    }

    /** Destroys the baseline: the applied state resolved this key as a writer. */
    private fun acceptSpeculation() {
        baseline = null
        for (entry in unsent) entry.speculative = false
    }

    /**
     * Adopts the clean baseline after a viewer resolution, dropping the speculative local edits.
     *
     * The ordinary replica is replaced, so every observer moves with it, and a private selection
     * that no longer resolves in the adopted document is republished without one (§8.1).
     */
    private fun discardSpeculative() {
        unsent.removeAll { it.speculative }
        val next = baseline ?: return
        baseline = null
        doc = next
        identity = Any()
        observed.clear()
        for (path in doc.rootNames) observe(path)
        // Deltas computed against the discarded replica would name positions it no longer holds;
        // an adapter reconciles from the adopted text instead.
        edits.clear()
        dropUnresolvedSelection()
    }

    /**
     * §8.1: a private selection whose anchors do not resolve in the adopted document is published
     * without one, rather than left naming a position the replica no longer holds.
     */
    private fun dropUnresolvedSelection() {
        val state = localState ?: return
        val obj = state as? YAny.Obj ?: return
        if (obj["selection"] == null) return
        if (AwarenessState.resolve(awareness.clientID, state, doc).selection != null) return
        val path = (obj["path"] as? YAny.Str)?.value ?: return
        setCursor(path)
    }

    private fun applyContent(
        plaintext: ByteArray,
        verified: ByteArray?,
    ) {
        if (!stateHeld()) return
        // §13.9: a viewer publishes its SyncStep1, awareness and holds, and nothing else — a
        // SyncStep2 is document content (§13.5) and is not its to send. An uncommitted key is the
        // same: nothing it would answer with is publishable yet.
        val answers = mayPublish() && ownRole() != Role.VIEWER
        var requested = false
        val replies = ArrayList<ByteArray>()
        val before = doc.rootNames.toSet()
        try {
            // One walk, message by message, over one validated prefix applied to both replicas: a
            // malformed suffix stops it for both, so the speculative and the ordinary document
            // never disagree about which of the frame's messages were accepted (§13.6).
            Sync.read(plaintext) { message ->
                when (message) {
                    is SyncMessage.Step1 -> {
                        // Read and past the request whatever the cap did with it, and refuse a
                        // malformed inner vector rather than allocate a room-sized diff for it.
                        if (!answers || replies.size >= MAX_REPLIES_PER_FRAME) {
                            Updates.decodeStateVector(message.stateVector)
                        } else {
                            val diff = Updates.encodeStateAsUpdate(publicationDoc, message.stateVector)
                            replies.add(Sync.encode(SyncMessage.Step2(diff)))
                        }
                        requested = true
                    }

                    is SyncMessage.Step2 -> {
                        applyContentUpdate(message.update)
                    }

                    is SyncMessage.Update -> {
                        applyContentUpdate(message.update)
                    }

                    is SyncMessage.Awareness -> {
                        awareness.applyUpdate(message.update, APPLIED)
                    }

                    else -> {}
                }
            }
        } catch (e: DecodeException) {
            // A malformed suffix ends the walk; what the valid prefix applied and asked for stands.
        } catch (e: RuntimeException) {
            // The same for a reader that failed some other way.
        }
        observeNew(before)
        if (requested) armReciprocal(verified)
        if (!answers) return
        for (reply in replies) publish(Publication.SYNC, reply)
    }

    /**
     * Applies one accepted content message to the publication replica and, while a viewer's own
     * edits are still speculative, to the ordinary replica too: the same content has to reach the
     * document a writer promotion keeps and the clean baseline a viewer resolution adopts (§13.9).
     * A clone by encode/apply is a document of its own, never a store alias, so both are updated.
     */
    private fun applyContentUpdate(update: ByteArray) {
        val publication = publicationDoc
        Updates.applyUpdate(publication, update, APPLIED)
        if (publication !== doc) Updates.applyUpdate(doc, update, APPLIED)
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

    /** The soonest clock at which a tick has something due, other than the renewal window. */
    fun nextDeadline(): Long? {
        if (ending != null || detached) return null
        var soonest = host?.owedAt()
        val at = announcedAt
        if (host == null && !commitsOurs() && mutation != PeerMutation.ANNOUNCE_ONCE && at != null) {
            val due = at + renew
            if (soonest == null || due < soonest) soonest = due
        }
        // A refusal, an initial or reciprocal request and local readiness share one successful
        // request clock: the deadline is the earliest a coalesced `SyncStep1` becomes due, so a
        // request that lands inside a window does not wait a whole one for it.
        if (mayPublish() && hasRepair()) {
            // The later of the successful-request eligibility and a failed attempt's own cooldown.
            val due = maxOf(handshakenAt ?: 0L, failedStep1At ?: 0L) + renew
            if (soonest == null || due < soonest) soonest = due
        }
        return soonest
    }

    /** Whether a refuse, initial, reciprocal or readiness cause still owes a `SyncStep1`. */
    private fun hasRepair(): Boolean =
        resyncFrom != null || initialPending || reciprocalPending.isNotEmpty() || localReadinessPending

    /**
     * The session's own clocks, as a caller driving them by hand runs them.
     *
     * An obligation left owed by a failed `SyncStep1` is retried at once once the shared request
     * clock is due: a caller that owns the clock keeps the immediate retry §13.6 allows.
     */
    fun tick(clock: Long) = tickOne(clock, automatic = false)

    /**
     * One tick as this connection's own timer runs it.
     *
     * It differs from [tick] in one way: a repair request left owed by a failed `SyncStep1`
     * publication waits a renewal window from the attempt's completion, so a persistent seal
     * failure draws one automatic attempt a window rather than one per pump.
     */
    fun autoTick(clock: Long) = tickOne(clock, automatic = true)

    private fun tickOne(
        clock: Long,
        automatic: Boolean,
    ) {
        clockOfLastMove = clock
        expireLeases(clock)
        refreshHostAway(clock, restart = false)
        if (ending != null) return
        if (budgetSpent()) return
        if (windowPassed(clock)) return
        publishState(clock, HostReason.ANNOUNCEMENT)
        reannounce(clock)
        resync(clock, automatic)
        announceHolds(clock)
        renewAwareness(clock)
        awareness.tick()
    }

    /**
     * Stamps the clock at which a failed `SyncStep1` publication completed.
     *
     * A synchronous seal throws or refuses on the calling thread, so the owner reads its monotone
     * clock after the tick or delivery returned: the clock the operation was given is the moment
     * it started, and encoding, randomness, JCA sealing or a preceding serialized operation can
     * leave it older than a renewal window. A no-op when no attempt failed, and it never refunds
     * the counter or the reserved room frame count.
     */
    internal fun noteStep1Failure(clock: Long) {
        if (!step1FailurePending) return
        step1FailurePending = false
        failedStep1At = clock
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
        // §9.1: the reciprocal census and the readiness obligation belong to the connection, not
        // the replica — a fresh key starts eligible again, and the marks the receiver keeps for
        // room frames, the replica and the paths this connection holds are untouched.
        reciprocalServed.clear()
        reciprocalPending.clear()
        initialPending = false
        localReadinessPending = false
        failedStep1At = null
        step1FailurePending = false
        roleSeen = null
        holdsAnnouncedAt = null
        ending = null
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
        // Refuse before creating CRDT history a later promotion could export (§13.9).
        if (ownRole() == Role.VIEWER) return false
        val speculative = ownRole() == null
        if (speculative) beginSpeculation()
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
        return publishEdit(update, speculative)
    }

    fun delete(
        path: String,
        index: Int,
        length: Int,
    ): Boolean {
        if (ownRole() == Role.VIEWER) return false
        val speculative = ownRole() == null
        if (speculative) beginSpeculation()
        val update =
            editLocally(path) { handle ->
                if (index < 0 || length < 0 || index + length > handle.length) {
                    throw IndexOutOfBoundsException("there is no range $index..${index + length} in \"$path\"")
                }
                handle.delete(index, length)
            }
        return publishEdit(update, speculative)
    }

    fun editorText(path: String): EditorText = EditorText.capture(doc, path)

    fun commitEditorIf(
        path: String,
        editor: EditorText,
        expected: String,
        maxBytes: Int,
    ): EditorCommit {
        if (path != editor.path || ownRole() == Role.VIEWER) return EditorCommit.Refused
        val speculative = ownRole() == null
        if (speculative) beginSpeculation()
        val (refusal, plan) = editor.plan(doc, expected, maxBytes)
        if (refusal != null) return refusal
        var visible: EditorText? = null
        val update = editLocally(path) { visible = plan!!.apply(doc) }
        publishEdit(update, speculative)
        return EditorCommit.Applied(EditorText.capture(doc, path), visible!!)
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

    /** Puts one local edit's update on the wire, or holds it back (§13.1's step 4). */
    private fun publishEdit(
        update: ByteArray?,
        speculative: Boolean,
    ): Boolean {
        if (update == null) return false
        val entry = Held(update, speculative)
        unsent.add(entry)
        if (!mayPublish() || ownRole() == Role.VIEWER) return false
        acceptSpeculation()
        if (!publish(Publication.CONTENT, Sync.encode(SyncMessage.Update(update)))) return false
        unsent.remove(entry)
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
        val frame = Sync.encode(SyncMessage.Awareness(awareness.encodeUpdate(clients)))
        if (publish(Publication.CONTENT, frame)) awarenessRenewedAt = clockOfLastMove
    }

    /**
     * Serves the edits §13.1's step 4 held back, retaining them until the merged frame actually
     * queues.
     *
     * The owed entries leave [unsent] only on success: a throwing merge, encode or seal, a refusal
     * and an unavailable publication all leave them for the next state that commits this key. The
     * frame is queued whole or not at all, so nothing is lost and nothing is published twice.
     */
    private fun flushHeldBackEdits() {
        if ((ownRole() != Role.HOST && ownRole() != Role.GUEST) || unsent.isEmpty()) return
        val held = ArrayList(unsent)
        val merged = Updates.mergeUpdates(held.map { it.update })
        acceptSpeculation()
        if (!mayPublish()) return
        if (!publish(Publication.CONTENT, Sync.encode(SyncMessage.Update(merged)))) return
        unsent.removeAll(held)
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
        // The renewal clock is only re-based on a frame that actually queued: a refused
        // announcement stays owed, and the next tick tries again.
        if (publish(Publication.ANNOUNCEMENT, Payload.announcement(session.spelling, declaredRole))) {
            announcedAt = clock
        }
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
        if (!publish(Publication.HOLDS, Payload.holds(paths))) return
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

    /**
     * §13.1's step 6: a `SyncStep1` with this replica's state vector.
     *
     * The vector is the publication replica's: §13.9 keeps a viewer's own history out of it, so
     * the room is asked for what the room-safe history lacks. The successful-request clock and
     * every obligation are only consumed when the frame actually queued.
     */
    private fun syncStep1(clock: Long): Boolean {
        step1Attempts += 1
        val queued =
            try {
                publish(Publication.SYNC, Sync.encode(SyncMessage.Step1(Updates.encodeStateVector(publicationDoc))))
            } catch (e: Exception) {
                // A seal that throws is a failed attempt like a refused one: the owner stamps its
                // completion, and the caller still sees the fault.
                step1FailurePending = true
                throw e
            }
        if (!queued) {
            step1FailurePending = true
            return false
        }
        handshakenAt = clock
        resyncFrom = null
        reciprocalPending.clear()
        initialPending = false
        localReadinessPending = false
        failedStep1At = null
        step1FailurePending = false
        return true
    }

    /**
     * Refusal, initial, reciprocal and local readiness obligations share the successful request
     * clock: at most one locally generated `SyncStep1` per renewal interval, whichever asked for
     * it.
     *
     * An automatic tick additionally waits out a failed attempt's cooldown, measured from its
     * completion, so the ordinary renewal grid cannot preempt it; a caller driving the clocks by
     * hand keeps the immediate retry §13.6 allows.
     */
    private fun resync(
        clock: Long,
        automatic: Boolean,
    ) {
        if (!hasRepair() || !mayPublish()) return
        val at = handshakenAt
        val due = at == null || clock - at >= renew
        // An automatic attempt also waits while a failure the owning session has not stamped yet
        // is outstanding: an unstamped failure carries no completion moment to pace from.
        val stamped = failedStep1At?.let { clock - it >= renew } ?: true
        val paced = !automatic || (!step1FailurePending && stamped)
        if (due && paced) syncStep1(clock)
    }

    /** One local edit held while §13.1's step 4 keeps this connection from publishing it. */
    private class Held(
        val update: ByteArray,
        var speculative: Boolean,
    )

    private enum class Publication(
        val kind: Long,
    ) {
        ANNOUNCEMENT(4),
        HOLDS(3),
        CONTENT(0),
        SYNC(0),
    }

    /**
     * One frame sealed under the frame key and signed by this connection's session key.
     *
     * The attachment, session-life, commitment/content and budget gates all apply here; each
     * returns false before the counter and room-frame budget are reserved. Once they are reserved,
     * a failed seal (the test-only [sealFault] predicate answering true, or [Frames.seal]
     * throwing) keeps the reservation: the counter is spent and the room frame counted, neither
     * is refunded and the counter is never reused. Sealing is synchronous, so a frame is queued
     * whole in this call or not at all.
     */
    private fun publish(
        what: Publication,
        plaintext: ByteArray,
    ): Boolean {
        if (detached || ending != null || roomFrames >= frameBudget) return false
        if (what != Publication.ANNOUNCEMENT && !mayPublish()) return false
        counter += 1
        countFrame()
        if (sealFault?.invoke(plaintext) == true) return false
        outbound.add(Frames.seal(roomId, frameKey, what.kind, counter, session, plaintext))
        if (what == Publication.SYNC) handshake += 1 else published += 1
        return true
    }

    companion object {
        private const val MAX_REPLIES_PER_FRAME = 1
        private const val EMPTY_DOCUMENT_MARK = "x"
        private val LOCAL = Any()
        private val APPLIED = Any()
        private const val REMOVED = "left"
    }
}
