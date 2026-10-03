package dev.dontblameme.selvage.subject

import dev.dontblameme.selvage.canonical.CanonicalJson
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.canonical.MalformedJson
import dev.dontblameme.selvage.canonical.json
import dev.dontblameme.selvage.peer.Keepalive
import dev.dontblameme.selvage.peer.PeerMutation
import dev.dontblameme.selvage.peer.PeerOptions
import dev.dontblameme.selvage.peer.PeerSession
import dev.dontblameme.selvage.sealed.Bytes
import dev.dontblameme.selvage.sealed.Invite
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.sealed.SessionKey
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The engine as a corpus subject: `specification/runner/subject.py`'s protocol over stdin and
 * stdout, one JSON command in and one JSON reply out per line, so the peer corpus's decision
 * vectors run against this client. The runner plays the relay; nothing here opens a socket.
 */
class Subject(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Running(
        val peer: PeerSession,
        val start: Long,
    )

    private val lock = Any()
    private var running: Running? = null
    private var clear = false
    private val linkMutations = HashSet<String>()

    private fun clock(now: Running): Long = (nanoTime() - now.start) / 1_000_000

    fun tick() =
        synchronized(lock) {
            running?.let { it.peer.tick(clock(it)) }
        }

    /** One command line, answered; null once `quit` has been answered. */
    fun serve(line: String): Pair<String, Boolean> =
        synchronized(lock) {
            val command =
                try {
                    CanonicalJson.parse(line) as? JsonValue.Obj ?: throw MalformedJson("not an object")
                } catch (e: MalformedJson) {
                    return failure("a command is one JSON object per line: ${e.message}") to false
                }
            try {
                val stop = handle(command)
                val reply = if (stop) JsonValue.Obj.of("ok" to true.json()) else success()
                CanonicalJson.write(reply) to stop
            } catch (e: SubjectRefusal) {
                failure(e.message ?: "refused") to false
            } catch (e: RuntimeException) {
                failure(e.message ?: e.toString()) to false
            }
        }

    private fun success() = JsonValue.Obj.of("ok" to true.json(), "report" to report())

    private fun failure(words: String) =
        CanonicalJson.write(
            JsonValue.Obj.of(
                "ok" to false.json(),
                "error" to words.json(),
            ),
        )

    private fun handle(command: JsonValue.Obj): Boolean {
        when (val cmd = text(command, "cmd")) {
            "join" -> {
                join(command)
            }

            "deliver" -> {
                val now = current()
                val raw =
                    try {
                        Bytes.fromHex(text(command, "frame"))
                    } catch (e: IllegalArgumentException) {
                        throw SubjectRefusal("a `deliver` frame is hex")
                    }
                now.peer.deliver(clock(now), raw)
                now.peer.tick(clock(now))
            }

            "insert" -> {
                val now = current()
                val index =
                    (command["index"] as? JsonValue.Number)?.integer()?.toInt()
                        ?: throw SubjectRefusal("an `insert` command needs an `index`")
                now.peer.insert(text(command, "path"), index, text(command, "text"))
                now.peer.tick(clock(now))
            }

            "announce" -> {
                val now = current()
                now.peer.release()
                now.peer.open(text(command, "path"))
                now.peer.tick(clock(now))
            }

            "mutate" -> {
                val name = text(command, "name")
                if (running == null && !clear && name in LINK_MUTATIONS) {
                    linkMutations.add(name)
                } else {
                    val mutation = PeerMutation.of(name) ?: throw SubjectRefusal("no mutation is named \"$name\"")
                    current().peer.mutate(mutation)
                }
            }

            "report" -> {}

            "quit" -> {
                running = null
                clear = false
                linkMutations.clear()
                return true
            }

            else -> {
                throw SubjectRefusal("unknown command \"$cmd\"")
            }
        }
        return false
    }

    private fun join(command: JsonValue.Obj) {
        if (command["offline"] != JsonValue.Bool(true)) {
            throw SubjectRefusal("this subject opens no socket: `join` needs `\"offline\": true`")
        }
        if (running != null || clear) throw SubjectRefusal("a session is already running")
        val link = text(command, "invite")
        val invite =
            when (val read = Invite.parse(link)) {
                is Invite.Read.Ok -> {
                    read.invite
                }

                is Invite.Read.Refused -> {
                    if ("accept-partial-fragment" in linkMutations && '#' in link) {
                        clear = true
                        return
                    }
                    throw SubjectRefusal(read.reason)
                }
            }
        val keepalive = command.obj("keepalive") ?: throw SubjectRefusal("`join` needs a `keepalive`")
        val seed = command.string("session_key")?.let(::seed)
        val options =
            PeerOptions(
                roomId = invite.room,
                roomKey = invite.roomKey,
                hostKey = invite.hostKey,
                keepalive =
                    Keepalive(
                        pingIntervalMs = millis(keepalive, "ping_interval_ms"),
                        awarenessRenewMs = millis(keepalive, "awareness_renew_ms"),
                        awarenessExpireMs = millis(keepalive, "awareness_expire_ms"),
                    ),
                seat = command.string("seat"),
                roster = command.arr("roster")?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList(),
                sessionKey = seed?.let(SessionKey::fromSeed),
                declaredRole = Role.of(command.string("role"))?.takeIf { it != Role.HOST },
            )
        val peer = PeerSession(options)
        command.string("path")?.let(peer::open)
        val now = Running(peer, nanoTime())
        running = now
        peer.tick(0)
    }

    private fun report(): JsonValue.Obj {
        val peer =
            running?.peer ?: return JsonValue.Obj.of(
                "text" to JsonValue.Obj(emptyMap()),
                "documents" to JsonValue.Arr(emptyList()),
                "applied" to JsonValue.Arr(emptyList()),
                "dropped" to JsonValue.Arr(emptyList()),
                "published" to 0.json(),
                "handshake" to 0.json(),
                "frames" to 0.json(),
                "ended" to false.json(),
                "ending" to JsonValue.Null,
                "listing" to JsonValue.Arr(emptyList()),
                "holds" to JsonValue.Obj(emptyMap()),
                "mutation" to JsonValue.Null,
            )
        val documents = peer.documents()
        return JsonValue.Obj.of(
            "text" to JsonValue.Obj(documents.associateWith { peer.text(it).json() }),
            "documents" to JsonValue.Arr(documents.map { it.json() }),
            "applied" to
                JsonValue.Arr(
                    peer.applied.map { JsonValue.Obj.of("frame" to it.frame.json(), "kind" to it.kind.json()) },
                ),
            "dropped" to
                JsonValue.Arr(
                    peer.dropped.map {
                        JsonValue.Obj.of(
                            "frame" to it.frame.json(),
                            "reason" to it.reason.wire.json(),
                        )
                    },
                ),
            "published" to peer.published.json(),
            "handshake" to peer.handshake.json(),
            "frames" to peer.frames.json(),
            "ended" to (peer.ending != null).json(),
            "ending" to (peer.ending?.wire?.json() ?: JsonValue.Null),
            "listing" to JsonValue.Arr(peer.listing.map { it.json() }),
            "holds" to
                JsonValue.Obj(peer.peerHolds().mapValues { (_, paths) -> JsonValue.Arr(paths.map { it.json() }) }),
            "mutation" to (peer.mutation?.wire?.json() ?: JsonValue.Null),
        )
    }

    private fun current(): Running = running ?: throw SubjectRefusal("no session: `join` first")

    private class SubjectRefusal(
        words: String,
    ) : RuntimeException(words)

    private companion object {
        val LINK_MUTATIONS = setOf("accept-partial-fragment")

        fun text(
            command: JsonValue.Obj,
            member: String,
        ): String = command.string(member) ?: throw SubjectRefusal("a command needs a `$member`")

        fun millis(
            keepalive: JsonValue.Obj,
            member: String,
        ): Long = keepalive.count(member) ?: throw SubjectRefusal("`keepalive.$member` is a count of milliseconds")

        fun seed(value: String): ByteArray {
            val raw =
                try {
                    Bytes.fromHex(value)
                } catch (e: IllegalArgumentException) {
                    null
                }
            if (raw == null ||
                raw.size != 32
            ) {
                throw SubjectRefusal("a session key seed is 32 bytes, and \"$value\" is not")
            }
            return raw
        }
    }
}

object Main {
    private const val TICK_MS = 10L

    @JvmStatic
    fun main(args: Array<String>) {
        val subject = Subject()
        val out = PrintStream(System.out, false, StandardCharsets.UTF_8)
        val ticker =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "subject-ticker").apply { isDaemon = true }
            }
        ticker.scheduleAtFixedRate(
            {
                try {
                    subject.tick()
                } catch (e: RuntimeException) {
                    System.err.println("tick: $e")
                }
            },
            TICK_MS,
            TICK_MS,
            TimeUnit.MILLISECONDS,
        )
        val input = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val (reply, stop) = subject.serve(line)
            out.print(reply)
            out.print('\n')
            out.flush()
            if (stop) break
        }
        ticker.shutdownNow()
        exitProcess(0)
    }
}
