package dev.dontblameme.selvage.wire

import dev.dontblameme.selvage.canonical.CanonicalJson
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.canonical.MalformedJson
import dev.dontblameme.selvage.canonical.json
import dev.dontblameme.selvage.peer.Keepalive

/** The session envelope's vocabulary (`PROTOCOL.md` §4–§6, §11). */
object Wire {
    const val VERSION = "selvage/2"

    val CAPABILITIES: List<String> = listOf("y-protocols/1", "awareness")

    const val ROOM_CREATED = "room.created"
    const val ROOM_JOINED = "room.joined"
    const val PEER_JOINED = "peer.joined"
    const val PEER_LEFT = "peer.left"
    const val PEER_RENAMED = "peer.renamed"
    const val ROOM_GONE = "room.gone"
    const val SESSION_ERROR = "session.error"

    const val CLOSE_PROTOCOL_ERROR = 4000
    const val CLOSE_ROOM_UNKNOWN = 4001
    const val CLOSE_TOKEN_INVALID = 4002
    const val CLOSE_ROOM_GONE = 4003

    /**
     * §11's capacity close, IANA's `try again later`: the reference server's connection cap and
     * its inbound budget (§2.1, §12). It is outside the private-use range, so it carries no
     * session meaning and a client MUST NOT read one into it — but it is not a refusal either.
     */
    const val CLOSE_TRY_AGAIN_LATER = 1013

    /** What [CLOSE_TRY_AGAIN_LATER] is reported as, so an adapter can name the outcome it tells apart. */
    const val TRY_AGAIN_LATER = "try_again_later"

    /** Refusals a retry of the same URL cannot change (§9.1, §11). */
    val TERMINAL_CODES: Set<String> = setOf("room_unknown", "token_invalid", "room_gone")

    /** §5's bound on a display name, in UTF-16 code units. */
    const val MAX_DISPLAY_NAME_UNITS = 32

    /** The largest WebSocket message this client buffers (§2.1's informative bound). */
    const val MAX_INBOUND_MESSAGE_BYTES = 16 * 1024 * 1024

    /** §9.1: a code in the reserved `x.` namespace is as final as the named ones. */
    fun isTerminal(code: String): Boolean = code.startsWith("x.") || code in TERMINAL_CODES

    /** Why §5 refuses [name], or null when a server would seat it. */
    fun displayNameProblem(name: String): String? =
        when {
            name.any { Character.getType(it) == Character.CONTROL.toInt() } -> {
                "a display name carries no control character"
            }

            name.isBlank() -> {
                "a display name is not blank"
            }

            name.length > MAX_DISPLAY_NAME_UNITS -> {
                "a display name is at most $MAX_DISPLAY_NAME_UNITS UTF-16 code units, and this one is ${name.length}"
            }

            else -> {
                null
            }
        }

    fun hello(
        displayName: String,
        awarenessClientId: Long? = null,
        capabilities: List<String>? = CAPABILITIES,
        client: String? = null,
    ): String =
        request(
            1,
            "session.hello",
            JsonValue.Obj.of(
                "display_name" to displayName.json(),
                "awareness_client_id" to awarenessClientId?.json(),
                "capabilities" to capabilities?.let { list -> JsonValue.Arr(list.map { it.json() }) },
                "client" to client?.json(),
            ),
        )

    fun rename(
        id: Long,
        displayName: String,
    ): String = request(id, "session.rename", JsonValue.Obj.of("display_name" to displayName.json()))

    private fun request(
        id: Long,
        method: String,
        params: JsonValue.Obj,
    ): String =
        CanonicalJson.write(
            JsonValue.Obj.of("v" to VERSION.json(), "id" to id.json(), "method" to method.json(), "params" to params),
        )
}

/** A peer as `selvage/2` records it: §6.1's `PeerInfo` without a role. */
data class WirePeer(
    val peerId: String,
    val displayName: String,
    val awarenessClientId: Long? = null,
) {
    companion object {
        /** One record, inline or under `peer` as `peer.joined` wraps it. */
        fun of(value: JsonValue?): WirePeer? {
            val outer = value as? JsonValue.Obj ?: return null
            val record = outer.obj("peer") ?: outer
            val id = record.string("peer_id") ?: return null
            val name = record.string("display_name") ?: return null
            return WirePeer(id, name, (record["awareness_client_id"] as? JsonValue.Number)?.count())
        }
    }
}

data class WireError(
    val code: String,
    val message: String,
)

/** The handshake's answer: `room.created` or `room.joined` (§6.1). */
data class Seating(
    val roomId: String,
    val self: WirePeer,
    val peers: List<WirePeer>,
    val capabilities: List<String>,
    val keepalive: Keepalive,
    /** Only on `room.created`. */
    val token: String?,
) {
    companion object {
        fun of(params: JsonValue.Obj?): Seating? {
            if (params == null) return null
            val roomId = params.string("room_id") ?: return null
            val self = WirePeer.of(params["self"]) ?: return null
            val peers = params.arr("peers")?.items?.mapNotNull { WirePeer.of(it) } ?: emptyList()
            val keepalive = params.obj("keepalive")
            val defaults = Keepalive()
            return Seating(
                roomId,
                self,
                peers,
                params.arr("capabilities")?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList(),
                Keepalive(
                    keepalive?.count("ping_interval_ms") ?: defaults.pingIntervalMs,
                    keepalive?.count("awareness_renew_ms") ?: defaults.awarenessRenewMs,
                    keepalive?.count("awareness_expire_ms") ?: defaults.awarenessExpireMs,
                ),
                params.string("token"),
            )
        }
    }
}

/**
 * A server → client text frame (§4.2, §4.3): an event, or the answer to a request. Unknown
 * members are ignored; a frame that is not one SJ-C/1 object, or whose `v` is not exactly
 * `selvage/2`, is not an envelope (§10).
 */
class ServerMessage(
    val id: Long?,
    val event: String?,
    val params: JsonValue.Obj?,
    val result: JsonValue?,
    val error: WireError?,
) {
    /** The `code` and `message` of a `session.error` event. */
    fun fault(): WireError =
        WireError(params?.string("code") ?: "error", params?.string("message") ?: "the server reported a fault")

    companion object {
        fun parse(text: String): ServerMessage? {
            val obj =
                try {
                    CanonicalJson.parse(text) as? JsonValue.Obj
                } catch (e: MalformedJson) {
                    null
                } ?: return null
            if (obj.string("v") != Wire.VERSION) return null
            val error =
                obj.obj("error")?.let {
                    WireError(it.string("code") ?: "error", it.string("message") ?: "the server reported a fault")
                }
            return ServerMessage(
                (obj["id"] as? JsonValue.Number)?.count(),
                obj.string("event"),
                obj.obj("params"),
                obj["result"],
                error,
            )
        }
    }
}
