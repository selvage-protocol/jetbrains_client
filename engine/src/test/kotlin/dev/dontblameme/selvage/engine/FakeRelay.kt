package dev.dontblameme.selvage.engine

import dev.dontblameme.selvage.canonical.CanonicalJson
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.canonical.json
import dev.dontblameme.selvage.wire.SocketListener
import dev.dontblameme.selvage.wire.Transport
import dev.dontblameme.selvage.wire.WireSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.test.fail

/**
 * A `selvage/2` relay in memory: membership, `room.created`/`room.joined`, the peer events,
 * `session.rename`, and binary frames to everyone else. Every delivery runs on one relay thread,
 * so [settle] can wait for the room to go quiet.
 */
class FakeRelay : Transport {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "fake-relay").apply { isDaemon = true } }
    private val submitted = AtomicLong()
    private val completed = AtomicLong()
    private val rooms = HashMap<String, Room>()

    /** What a socket listener threw into the relay; a real transport fails the socket on it. */
    val escaped = CopyOnWriteArrayList<Throwable>()
    private var next = 0

    private class Room(
        val token: String,
    ) {
        val conns = LinkedHashMap<String, Conn>()
    }

    inner class Conn(
        private val url: String,
        val listener: SocketListener,
    ) : WireSocket {
        var peerId: String? = null
        var name = ""
        var awareness: Long? = null
        private var room: Room? = null
        private var closed = false

        override fun sendText(text: String) = relay { if (!closed) request(CanonicalJson.parseObject(text)) }

        override fun sendBinary(bytes: ByteArray) =
            relay {
                val at = room ?: return@relay
                if (closed) return@relay
                for (other in at.conns.values) if (other !== this) other.listener.onBinary(bytes.copyOf())
            }

        override fun close(
            code: Int,
            reason: String,
        ) = relay { gone(code, reason) }

        /** The server drops the connection with no close frame. */
        fun drop() = relay { gone(1006, "dropped") }

        private fun gone(
            code: Int,
            reason: String,
        ) {
            if (closed) return
            closed = true
            val at = room
            val id = peerId
            if (at != null && id != null) {
                at.conns.remove(id)
                for (other in at.conns.values) other.event("peer.left", JsonValue.Obj.of("peer_id" to id.json()))
            }
            listener.onClose(code, reason)
        }

        private fun request(message: JsonValue.Obj) {
            val params = message.obj("params")
            when (message.string("method")) {
                "session.hello" -> {
                    hello(params!!)
                }

                "session.rename" -> {
                    if (holdRenames) return
                    name = params!!.string("display_name")!!
                    send(JsonValue.Obj.of("id" to message["id"], "result" to JsonValue.Obj(emptyMap())))
                    for (other in room!!.conns.values) {
                        other.event(
                            "peer.renamed",
                            JsonValue.Obj.of(
                                "display_name" to name.json(),
                                "peer_id" to peerId!!.json(),
                            ),
                        )
                    }
                }
            }
        }

        private fun hello(params: JsonValue.Obj) {
            refuseHello?.let { code ->
                refuseHello = null
                event("session.error", JsonValue.Obj.of("code" to code.json(), "message" to "refused".json()))
                return
            }
            for (frame in preSeat) {
                when (frame) {
                    is ByteArray -> listener.onBinary(frame)
                    is String -> listener.onText(frame)
                }
            }
            name = params.string("display_name")!!
            awareness = (params["awareness_client_id"] as? JsonValue.Number)?.count()
            val query =
                URI(url).rawQuery?.split('&')?.associate { it.substringBefore('=') to it.substringAfter('=') }
                    ?: emptyMap()
            val roomId = query["room"]
            peerId = "p-${++next}"
            val keepalive =
                JsonValue.Obj.of(
                    "awareness_expire_ms" to 900.json(),
                    "awareness_renew_ms" to 300.json(),
                    "ping_interval_ms" to 30_000.json(),
                )
            if (roomId == null) {
                val id = "room-${++next}"
                val made = Room("token-$id")
                rooms[id] = made
                room = made
                made.conns[peerId!!] = this
                event(
                    "room.created",
                    JsonValue.Obj.of(
                        "room_id" to id.json(),
                        "self" to info(),
                        "peers" to JsonValue.Arr(emptyList()),
                        "capabilities" to JsonValue.Arr(listOf("y-protocols/1".json(), "awareness".json())),
                        "keepalive" to keepalive,
                        "token" to made.token.json(),
                    ),
                )
                return
            }
            val found = rooms[roomId]
            if (found == null || found.token != query["token"]) {
                event(
                    "session.error",
                    JsonValue.Obj.of(
                        "code" to "room_unknown".json(),
                        "message" to "no such room".json(),
                    ),
                )
                gone(4001, "room_unknown")
                return
            }
            val others = found.conns.values.toList()
            room = found
            found.conns[peerId!!] = this
            event(
                "room.joined",
                JsonValue.Obj.of(
                    "room_id" to roomId.json(),
                    "self" to info(),
                    "peers" to JsonValue.Arr(others.map { it.info() }),
                    "capabilities" to JsonValue.Arr(listOf("y-protocols/1".json(), "awareness".json())),
                    "keepalive" to keepalive,
                ),
            )
            for (other in others) other.event("peer.joined", JsonValue.Obj.of("peer" to info()))
        }

        fun info(): JsonValue.Obj =
            JsonValue.Obj.of(
                "peer_id" to peerId!!.json(),
                "display_name" to name.json(),
                "awareness_client_id" to awareness?.json(),
            )

        fun event(
            name: String,
            params: JsonValue.Obj,
        ) = send(JsonValue.Obj.of("event" to name.json(), "params" to params))

        private fun send(message: JsonValue.Obj) {
            if (closed) return
            listener.onText(CanonicalJson.write(JsonValue.Obj(message.members + ("v" to "selvage/2".json()))))
        }
    }

    val connections = ArrayList<Conn>()

    /** A `session.rename` is left unanswered. */
    @Volatile
    var holdRenames = false

    /** Sent to each connection ahead of its `room.created` or `room.joined`, as no server does. */
    @Volatile
    var preSeat: List<Any> = emptyList()

    /** The next `session.hello` is answered with this `session.error` code, the socket left open. */
    @Volatile
    var refuseHello: String? = null

    override fun open(
        url: String,
        timeout: Duration,
        listener: SocketListener,
    ): CompletableFuture<WireSocket> {
        val conn = Conn(url, listener)
        synchronized(connections) { connections.add(conn) }
        return CompletableFuture.completedFuture(conn)
    }

    fun connection(peerId: String): Conn = synchronized(connections) { connections.first { it.peerId == peerId } }

    private fun relay(work: () -> Unit) {
        submitted.incrementAndGet()
        thread.execute {
            try {
                work()
            } catch (e: Throwable) {
                escaped.add(e)
            } finally {
                completed.incrementAndGet()
            }
        }
    }

    /** Waits until every delivery, and every delivery it caused, has run. */
    fun settle(timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (completed.get() != submitted.get()) {
            if (System.nanoTime() > deadline) fail("the relay did not settle")
            LockSupport.parkNanos(200_000)
        }
    }

    fun shutdown() {
        thread.shutdownNow()
    }
}

/** A clock and timers the test moves by hand. */
class ManualScheduler : Scheduler {
    private class Task(
        val at: Long,
        val seq: Long,
        val run: Runnable,
    ) {
        var cancelled = false
    }

    private var now = 1_000L
    private var seq = 0L
    private val tasks = ArrayList<Task>()

    @Synchronized
    override fun nowMs(): Long = now

    @Synchronized
    override fun schedule(
        delayMs: Long,
        task: Runnable,
    ): Cancellable {
        val made = Task(now + delayMs.coerceAtLeast(0), seq++, task)
        tasks.add(made)
        return Cancellable { synchronized(this) { made.cancelled = true } }
    }

    /** Moves the clock by [ms], running every timer due on the way, in order. */
    fun advance(
        ms: Long,
        between: () -> Unit = {},
    ) {
        val target = synchronized(this) { now + ms }
        while (true) {
            val due =
                synchronized(this) {
                    tasks.removeIf { it.cancelled }
                    val first =
                        tasks.filter { it.at <= target }.minWithOrNull(compareBy({ it.at }, { it.seq }))
                            ?: return@synchronized null
                    tasks.remove(first)
                    now = maxOf(now, first.at)
                    first
                } ?: break
            due.run.run()
            between()
        }
        synchronized(this) { now = target }
        between()
    }
}
