package dev.dontblameme.selvage.engine

import dev.dontblameme.selvage.peer.Cursor
import dev.dontblameme.selvage.peer.Ending
import dev.dontblameme.selvage.peer.HostOptions
import dev.dontblameme.selvage.peer.Keepalive
import dev.dontblameme.selvage.peer.PeerOptions
import dev.dontblameme.selvage.peer.PeerSession
import dev.dontblameme.selvage.peer.RemoteEdit
import dev.dontblameme.selvage.peer.Selection
import dev.dontblameme.selvage.sealed.FrameCrypto
import dev.dontblameme.selvage.sealed.Invite
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.sealed.SessionKey
import dev.dontblameme.selvage.sealed.Urls
import dev.dontblameme.selvage.wire.JdkTransport
import dev.dontblameme.selvage.wire.Meta
import dev.dontblameme.selvage.wire.ReconnectPolicy
import dev.dontblameme.selvage.wire.Seating
import dev.dontblameme.selvage.wire.ServerMessage
import dev.dontblameme.selvage.wire.SocketListener
import dev.dontblameme.selvage.wire.Transport
import dev.dontblameme.selvage.wire.Wire
import dev.dontblameme.selvage.wire.WireError
import dev.dontblameme.selvage.wire.WirePeer
import dev.dontblameme.selvage.wire.WireSocket
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** A refusal: the server's code (§11) and words, or the client's own for a link it will not dial. */
class SessionException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Why a session is over. */
enum class SessionEnding(
    val wire: String,
    val sentence: String,
) {
    CLOSING("closing", Ending.CLOSING.sentence),
    HOST_AWAY("host-away", Ending.HOST_AWAY.sentence),
    FRAME_BUDGET("frame-budget", Ending.FRAME_BUDGET.sentence),
    NO_STATE("no-state", Ending.NO_STATE.sentence),
    ROOM_GONE("room-gone", "the room is gone"),
    CONNECTION_LOST("connection-lost", "the connection ended and could not be restored"),
    ;

    companion object {
        fun of(ending: Ending): SessionEnding = valueOf(ending.name)
    }
}

sealed interface SessionEvent {
    data object Seated : SessionEvent

    /** §9.1's bounded retry is running; [Seated] follows when it lands, [Ended] when it gives up. */
    data class Reconnecting(
        val attempt: Int,
    ) : SessionEvent

    data class Peers(
        val peers: List<WirePeer>,
    ) : SessionEvent

    data class Listing(
        val paths: List<String>,
    ) : SessionEvent

    /** The room's open set: what this client and its peers hold (§13.7). */
    data class OpenSet(
        val paths: List<String>,
    ) : SessionEvent

    data class RemoteEdits(
        val edits: List<RemoteEdit>,
    ) : SessionEvent

    data class Presence(
        val cursors: List<Cursor>,
    ) : SessionEvent

    /** The host's seat is empty; the room ends in [graceMs] unless it comes back (§13.8). */
    data class HostAway(
        val graceMs: Long,
    ) : SessionEvent

    data object HostBack : SessionEvent

    data class Ended(
        val ending: SessionEnding,
    ) : SessionEvent

    data class Failed(
        val error: WireError,
    ) : SessionEvent
}

fun interface SessionListener {
    fun onEvent(event: SessionEvent)
}

class SessionOptions(
    val displayName: String,
    val transport: Transport = JdkTransport(),
    /** Null gives the session its own thread, stopped when it is left. */
    val scheduler: Scheduler? = null,
    val handshakeTimeout: Duration = Duration.ofSeconds(10),
    val metaTimeout: Duration = Duration.ofSeconds(2),
    val requestTimeout: Duration = Duration.ofSeconds(10),
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
    /** Free-form client identifier for the server's diagnostics (§5). */
    val client: String? = "selvage-jetbrains",
    /** Overrides the server's clocks, which a conformance harness may want; a client does not. */
    val keepalive: Keepalive? = null,
    /** `GET /meta`, which the room's grace is read from (§9.1). */
    val meta: (String, Duration) -> Meta = { base, timeout -> Meta.fetch(base, timeout) },
    /** Attached before the first event, so `Seated` is not missed. */
    val listener: SessionListener? = null,
)

/** What a host serves: the names it grants, and a document's text when a peer opens one. */
class HostContent(
    val listing: () -> List<String>,
    /** Null declines the path. Called on the session's threads. */
    val read: (String) -> String?,
)

/**
 * One `selvage/2` connection: a socket, a [PeerSession] and the clocks between them.
 *
 * Every method may be called from any thread; listeners are called one event at a time, in order,
 * outside the session's lock, and must not block.
 */
class SelvageSession private constructor(
    private val options: SessionOptions,
    private val content: HostContent?,
    private val declaredRole: Role?,
) {
    private val scheduler: Scheduler = options.scheduler ?: ThreadScheduler()
    private val ownsScheduler = options.scheduler == null
    private val lock = Any()
    private var depth = 0
    private val events = ConcurrentLinkedQueue<SessionEvent>()
    private val dispatching = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<SessionListener>()

    private var peer: PeerSession? = null
    private var socket: WireSocket? = null
    private var generation = 0
    private var handshaking = false
    private var pendingSeat: CompletableFuture<Seating>? = null
    private val inbox = ArrayDeque<Any>()
    private var start = 0L
    private var timer: Cancellable? = null
    private var retryTimer: Cancellable? = null
    private var tickWindow = 0L
    private var tickAt = 0L
    private var attempts = 0
    private var retryBudget = options.reconnect.maxAttempts
    private var awarenessId = 0L
    private var requestId = 1L
    private val requests = HashMap<Long, CompletableFuture<Unit>>()
    private var dialUrl = ""
    private var ownName = options.displayName
    private var left = false

    var invite: String? = null
        private set
    var roomId: String? = null
        private set
    var ending: SessionEnding? = null
        private set
    private var peerList: List<WirePeer> = emptyList()
    private var lastListing: List<String> = emptyList()
    private var lastOpen: List<String> = emptyList()
    private var lastPeers: List<WirePeer> = emptyList()
    private var hostAway = false
    private val declined = HashSet<String>()

    init {
        options.listener?.let(listeners::add)
    }

    val isHost: Boolean get() = content != null

    fun addListener(listener: SessionListener): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    // --- reads ---------------------------------------------------------------------

    val seat: String? get() = locked { peer?.seat }
    val displayName: String get() = locked { ownName }

    fun peers(): List<WirePeer> = locked { peerList }

    fun listing(): List<String> = locked { peer?.listing ?: emptyList() }

    fun openSet(): List<String> = locked { peer?.openSet() ?: emptyList() }

    fun documents(): List<String> = locked { peer?.documents() ?: emptyList() }

    fun text(path: String): String = locked { peer?.text(path) ?: "" }

    fun has(path: String): Boolean = locked { peer?.has(path) ?: false }

    fun cursors(): List<Cursor> = locked { peer?.cursors() ?: emptyList() }

    fun ownRole(): Role? = locked { peer?.ownRole() }

    fun rolesBySeat(): Map<String, Role> = locked { peer?.rolesBySeat() ?: emptyMap() }

    fun awarenessClientId(): Long = locked { awarenessId }

    /** How long the host has left to come back, while it is away (§13.8). */
    fun hostAwayGraceMs(): Long? = locked { peer?.hostAwayGraceMs(clock()) }

    // --- local changes ---------------------------------------------------------------

    fun open(path: String) =
        locked {
            peer?.open(path)
            pump()
        }

    fun release(path: String? = null) =
        locked {
            peer?.release(path)
            pump()
        }

    /** True when the edit was published; false when it is held back or stays local (a viewer). */
    fun insert(
        path: String,
        index: Int,
        text: String,
    ): Boolean = locked { (peer?.insert(path, index, text) ?: false).also { afterChange() } }

    fun delete(
        path: String,
        index: Int,
        length: Int,
    ): Boolean = locked { (peer?.delete(path, index, length) ?: false).also { afterChange() } }

    fun setCursor(
        path: String?,
        selection: Selection? = null,
    ) = locked {
        peer?.setCursor(path, selection)
        afterChange()
    }

    /** The host's working tree changed: publish the listing again (§13.3). */
    fun listingChanged() =
        locked {
            peer?.listingChanged(clock())
            afterChange()
        }

    /** The host ends the room for everyone (§13.10). */
    fun closeRoom(): Boolean = locked { (peer?.closeRoom() ?: false).also { afterChange() } }

    /** `session.rename`: completes when the server accepts it, bounded by the request timeout. */
    fun rename(displayName: String): CompletableFuture<Unit> =
        locked {
            Wire.displayNameProblem(displayName)?.let { throw IllegalArgumentException(it) }
            val open =
                socket
                    ?: return@locked CompletableFuture.failedFuture(
                        SessionException("not_connected", "the session is not connected"),
                    )
            requestId += 1
            val id = requestId
            val answer = CompletableFuture<Unit>()
            requests[id] = answer
            open.sendText(Wire.rename(id, displayName))
            answer.orTimeout(options.requestTimeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete { _, _ ->
                locked { requests.remove(id) }
            }
        }

    /** Leaves the room: the socket closed, the clocks stopped. The replica stays readable. */
    fun leave() {
        locked {
            if (left) return@locked
            left = true
            stopTimers()
            generation += 1
            socket?.close(1000, "left")
            socket = null
            peer?.detach()
            requests.values.forEach { it.completeExceptionally(SessionException("left", "the session was left")) }
            requests.clear()
        }
        if (ownsScheduler) scheduler.shutdown()
    }

    // --- opening ---------------------------------------------------------------------

    private fun clock(): Long = scheduler.nowMs() - start

    private fun mint(base: String): CompletableFuture<Unit> {
        val roomKey = FrameCrypto.randomBytes(32)
        val hostKey = SessionKey.mint()
        return dial(Urls.sessionUrl(base)).thenApply { info ->
            locked {
                val token = info.token ?: throw SessionException("bad_message", "room.created carried no token")
                roomId = info.roomId
                seat(
                    info,
                    PeerOptions(
                        info.roomId,
                        roomKey,
                        hostKey.public,
                        options.keepalive ?: info.keepalive,
                        seat = info.self.peerId,
                        roster = info.peers.map { it.peerId },
                        awarenessClientId = awarenessId,
                        host = HostOptions(hostKey, content!!.listing),
                        recordFrames = false,
                    ),
                )
                invite = Invite.link(Urls.sessionUrl(base, info.roomId, token), roomKey, hostKey.public)
            }
        }
    }

    private fun admit(invite: Invite): CompletableFuture<Unit> {
        val base =
            Urls.baseOf(invite.socketUrl)
                ?: throw SessionException("invite", "the invite does not address a session endpoint")
        return CompletableFuture
            .runAsync { applyGrace(base) }
            .thenCompose { dial(invite.socketUrl) }
            .thenApply { info ->
                locked {
                    roomId = invite.room
                    seat(
                        info,
                        PeerOptions(
                            invite.room,
                            invite.roomKey,
                            invite.hostKey,
                            options.keepalive ?: info.keepalive,
                            seat = info.self.peerId,
                            roster = info.peers.map { it.peerId } + info.self.peerId,
                            declaredRole = declaredRole,
                            awarenessClientId = awarenessId,
                            recordFrames = false,
                        ),
                    )
                }
            }
    }

    /** §9.1: keep retrying at least through the room's advertised grace, best effort. */
    private fun applyGrace(base: String) {
        if (!options.reconnect.enabled) return
        val grace =
            try {
                options.meta(base, options.metaTimeout).roomGraceMs
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } ?: return
        if (grace > 0) locked { retryBudget = maxOf(retryBudget, options.reconnect.attemptsForGrace(grace)) }
    }

    /** Opens a socket, says `session.hello`, and completes when the room seats it. */
    private fun dial(url: String): CompletableFuture<Seating> {
        val seated = CompletableFuture<Seating>()
        val attempt =
            locked {
                generation += 1
                dialUrl = url
                handshaking = true
                pendingSeat = seated
                generation
            }
        val listener =
            object : SocketListener {
                override fun onText(text: String) = locked { if (attempt == generation) received(text) }

                override fun onBinary(bytes: ByteArray) = locked { if (attempt == generation) received(bytes) }

                override fun onClose(
                    code: Int,
                    reason: String,
                ) = locked {
                    if (attempt != generation) return@locked
                    if (handshaking) {
                        seated.completeExceptionally(
                            SessionException(
                                closeCode(code),
                                "the socket closed before the session was seated: $code $reason",
                            ),
                        )
                    } else {
                        dropped(code, reason)
                    }
                }
            }
        options.transport.open(url, options.handshakeTimeout, listener).whenComplete { open, error ->
            if (error != null) {
                seated.completeExceptionally(unwrap(error))
                return@whenComplete
            }
            locked {
                if (attempt != generation || left) {
                    open.close(1000, "superseded")
                    seated.completeExceptionally(
                        SessionException("superseded", "the connection attempt was superseded"),
                    )
                    return@locked
                }
                socket = open
                awarenessId = FrameCrypto.randomBytes(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
                open.sendText(Wire.hello(ownName, awarenessId, client = options.client))
            }
        }
        return seated.orTimeout(options.handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete { _, error ->
            if (error == null) return@whenComplete
            locked {
                if (attempt == generation) {
                    handshaking = false
                    socket?.close(1000, "handshake failed")
                    socket = null
                }
            }
        }
    }

    private fun seat(
        info: Seating,
        peerOptions: PeerOptions,
    ) {
        start = scheduler.nowMs()
        peerList = info.peers
        peer = PeerSession(peerOptions)
        tickWindow = peerOptions.keepalive.awarenessRenewMs
        tickAt = tickWindow
        events.add(SessionEvent.Seated)
        drainInbox()
        pump()
    }

    // --- the frame path ----------------------------------------------------------------

    private fun received(frame: Any) {
        if (frame is String && handshaking) {
            val message = ServerMessage.parse(frame) ?: return
            when (message.event) {
                Wire.ROOM_CREATED, Wire.ROOM_JOINED -> {
                    val info = Seating.of(message.params)
                    handshaking = false
                    if (info == null) {
                        pendingSeat?.completeExceptionally(
                            SessionException("bad_message", "the handshake reply was not a room description"),
                        )
                    } else {
                        pendingSeat?.complete(info)
                    }
                    return
                }

                Wire.SESSION_ERROR -> {
                    val fault = message.fault()
                    pendingSeat?.completeExceptionally(SessionException(fault.code, fault.message))
                    return
                }
            }
        }
        if (inbox.size >= MAX_INBOX_FRAMES) {
            // §2.1: a client bounds what it holds; past it the connection is dropped like any drop.
            inbox.clear()
            socket?.close(1008, "inbound queue full")
            return
        }
        inbox.addLast(frame)
        if (!handshaking && peer != null) drainInbox()
    }

    private fun drainInbox() {
        val session = peer ?: return
        while (inbox.isNotEmpty() && !handshaking) {
            when (val frame = inbox.removeFirst()) {
                is ByteArray -> session.deliver(clock(), frame)
                is String -> event(session, frame)
            }
        }
        afterChange()
    }

    private fun event(
        session: PeerSession,
        text: String,
    ) {
        val message = ServerMessage.parse(text) ?: return
        message.id?.let { id ->
            val answer = requests.remove(id) ?: return
            val error = message.error
            if (error !=
                null
            ) {
                answer.completeExceptionally(SessionException(error.code, error.message))
            } else {
                answer.complete(Unit)
            }
            return
        }
        val params = message.params
        when (message.event) {
            Wire.PEER_JOINED -> {
                val joined = WirePeer.of(params) ?: return
                peerList = peerList.filter { it.peerId != joined.peerId } + joined
                session.seatJoined(clock(), joined.peerId)
            }

            Wire.PEER_LEFT -> {
                val id = params?.string("peer_id") ?: return
                peerList.firstOrNull { it.peerId == id }?.awarenessClientId?.let(session::forgetAwareness)
                peerList = peerList.filter { it.peerId != id }
                session.seatLeft(clock(), id)
            }

            Wire.PEER_RENAMED -> {
                val id = params?.string("peer_id") ?: return
                val name = params.string("display_name") ?: return
                peerList = peerList.map { if (it.peerId == id) it.copy(displayName = name) else it }
                if (id == session.seat) ownName = name
            }

            Wire.ROOM_GONE -> {
                end(SessionEnding.ROOM_GONE)
            }

            Wire.SESSION_ERROR -> {
                events.add(SessionEvent.Failed(message.fault()))
            }
        }
    }

    private fun dropped(
        code: Int,
        reason: String,
    ) {
        socket = null
        val session = peer
        if (left || ending != null) return
        // A host has no resume on this wire: no host store, so its drop ends the session.
        if (isHost || !options.reconnect.enabled || session == null) {
            end(if (code == Wire.CLOSE_ROOM_GONE) SessionEnding.ROOM_GONE else SessionEnding.CONNECTION_LOST)
            return
        }
        session.detach()
        inbox.clear()
        stopTimers()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (left || ending != null) return
        if (attempts >= retryBudget) {
            end(SessionEnding.CONNECTION_LOST)
            return
        }
        val delay = options.reconnect.delay(attempts)
        attempts += 1
        events.add(SessionEvent.Reconnecting(attempts))
        retryTimer = scheduler.schedule(delay) { retry() }
    }

    private fun retry() {
        val url = locked { if (left || ending != null) null else dialUrl } ?: return
        dial(url).whenComplete { info, error ->
            locked {
                if (left || ending != null) return@locked
                if (error != null) {
                    val refusal = unwrap(error) as? SessionException
                    if (refusal != null && Wire.isTerminal(refusal.code)) {
                        events.add(SessionEvent.Failed(WireError(refusal.code, refusal.message ?: "")))
                        end(SessionEnding.ROOM_GONE)
                    } else {
                        scheduleReconnect()
                    }
                    return@locked
                }
                val session = peer ?: return@locked end(SessionEnding.CONNECTION_LOST)
                attempts = 0
                peerList = info.peers
                session.reseat(info.self.peerId, info.peers.map { it.peerId } + info.self.peerId, awarenessId)
                events.add(SessionEvent.Seated)
                drainInbox()
                pump()
            }
        }
    }

    // --- the clocks ------------------------------------------------------------------------

    private fun pump() {
        val session = peer ?: return
        if (ending != null || left) return
        val now = clock()
        session.tick(now)
        afterChange()
        if (now >= tickAt) tickAt = now + tickWindow
        arm(now)
    }

    /** The next tick: the renewal grid, or sooner when the session owes something (§13.1, §13.2). */
    private fun arm(now: Long) {
        timer?.cancel()
        timer = null
        if (left || ending != null || peer == null) return
        val deadline = peer?.nextDeadline()
        val at = if (deadline == null || deadline > tickAt) tickAt else deadline
        timer = scheduler.schedule(maxOf(1, at - now)) { locked { pump() } }
    }

    /** Sends what the session queued and says what moved. */
    private fun afterChange() {
        val session = peer ?: return
        serve(session)
        val out = socket
        for (frame in session.takeOutbound()) out?.sendBinary(frame)
        val edits = session.takeEdits()
        if (edits.isNotEmpty()) events.add(SessionEvent.RemoteEdits(edits))
        if (session.takePresenceMoved()) events.add(SessionEvent.Presence(session.cursors()))
        val listing = session.listing
        if (listing != lastListing) {
            lastListing = listing
            events.add(SessionEvent.Listing(listing))
        }
        val open = session.openSet()
        if (open != lastOpen) {
            lastOpen = open
            events.add(SessionEvent.OpenSet(open))
        }
        if (peerList != lastPeers) {
            lastPeers = peerList
            events.add(SessionEvent.Peers(peerList))
        }
        val grace = session.hostAwayGraceMs(clock())
        if (grace != null && !hostAway) {
            hostAway = true
            events.add(SessionEvent.HostAway(grace))
        } else if (grace == null && hostAway) {
            hostAway = false
            events.add(SessionEvent.HostBack)
        }
        session.ending?.let { end(SessionEnding.of(it)) }
    }

    /** A host seeds a path its listing grants and a peer holds, when the replica lacks it (§13.3, §13.7). */
    private fun serve(session: PeerSession) {
        val host = content ?: return
        val listed = session.listing.toHashSet()
        for (path in session.openSet()) {
            if (path !in listed || session.has(path) || path in declined) continue
            val text =
                try {
                    host.read(path)
                } catch (e: IOException) {
                    null
                }
            if (text == null) declined.add(path) else session.insert(path, 0, text)
        }
    }

    private fun end(why: SessionEnding) {
        if (ending != null) return
        ending = why
        stopTimers()
        socket?.let { open ->
            for (frame in peer?.takeOutbound() ?: emptyList()) open.sendBinary(frame)
            generation += 1
            open.close(1000, why.wire)
        }
        socket = null
        events.add(SessionEvent.Ended(why))
    }

    private fun stopTimers() {
        timer?.cancel()
        timer = null
        retryTimer?.cancel()
        retryTimer = null
    }

    // --- the lock and the listeners -------------------------------------------------------

    private fun <T> locked(block: () -> T): T {
        val result: T
        synchronized(lock) {
            depth += 1
            try {
                result = block()
            } finally {
                depth -= 1
            }
            if (depth > 0) return result
        }
        dispatch()
        return result
    }

    /** One thread at a time hands the queued events over, in the order they were queued. */
    private fun dispatch() {
        while (events.isNotEmpty() && dispatching.compareAndSet(false, true)) {
            try {
                while (true) {
                    val next = events.poll() ?: break
                    for (listener in listeners) {
                        try {
                            listener.onEvent(next)
                        } catch (e: RuntimeException) {
                            System.err.println("a session listener failed: $e")
                        }
                    }
                }
            } finally {
                dispatching.set(false)
            }
        }
    }

    companion object {
        /** The most frames queued ahead of the session before the connection is dropped. */
        const val MAX_INBOX_FRAMES = 4096

        /** Mints a room at [baseUrl]; this connection is its host. */
        fun host(
            baseUrl: String,
            content: HostContent,
            options: SessionOptions,
        ): SelvageSession {
            Wire.displayNameProblem(options.displayName)?.let { throw SessionException("bad_params", it) }
            val base = Urls.sessionBase(baseUrl) ?: throw SessionException("address", "not a session address: $baseUrl")
            val session = SelvageSession(options, content, null)
            return session.await(session.mint(base))
        }

        /** Joins the room an invite link names; the fragment never reaches the socket (§5.1). */
        fun join(
            link: String,
            options: SessionOptions,
            declaredRole: Role? = null,
        ): SelvageSession {
            Wire.displayNameProblem(options.displayName)?.let { throw SessionException("bad_params", it) }
            require(declaredRole != Role.HOST) { "a joiner declares guest or viewer" }
            val invite =
                when (val read = Invite.parse(link)) {
                    is Invite.Read.Ok -> read.invite
                    is Invite.Read.Refused -> throw SessionException("invite", read.reason)
                }
            val session = SelvageSession(options, null, declaredRole)
            return session.await(session.admit(invite))
        }

        private fun unwrap(error: Throwable): Throwable {
            var cause = error
            while ((cause is CompletionException || cause is ExecutionException) &&
                cause.cause != null
            ) {
                cause = cause.cause!!
            }
            return if (cause is TimeoutException) {
                SessionException(
                    "timeout",
                    "the server did not seat the session in time",
                    cause,
                )
            } else {
                cause
            }
        }

        private fun closeCode(code: Int): String =
            when (code) {
                Wire.CLOSE_PROTOCOL_ERROR -> "protocol_error"
                Wire.CLOSE_ROOM_UNKNOWN -> "room_unknown"
                Wire.CLOSE_TOKEN_INVALID -> "token_invalid"
                Wire.CLOSE_ROOM_GONE -> "room_gone"
                Wire.CLOSE_HOST_PRESENT -> "host_present"
                else -> "closed"
            }
    }

    private fun await(started: CompletableFuture<Unit>): SelvageSession {
        val bound = options.metaTimeout + options.handshakeTimeout + Duration.ofSeconds(1)
        try {
            started.get(bound.toMillis(), TimeUnit.MILLISECONDS)
            return this
        } catch (e: Exception) {
            leave()
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw unwrap(e) as? SessionException ?: SessionException("connect", unwrap(e).message ?: e.toString(), e)
        }
    }
}
