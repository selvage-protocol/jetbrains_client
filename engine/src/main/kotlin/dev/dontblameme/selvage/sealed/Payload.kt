package dev.dontblameme.selvage.sealed

import dev.dontblameme.selvage.canonical.CanonicalJson
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.canonical.MalformedJson
import dev.dontblameme.selvage.canonical.json

/** A seat's role in the room state (`PROTOCOL.md` §13.4). */
enum class Role(
    val wire: String,
) {
    HOST("host"),
    GUEST("guest"),
    VIEWER("viewer"),
    ;

    companion object {
        fun of(wire: String?): Role? = entries.firstOrNull { it.wire == wire }
    }
}

/** A `peers` entry: the seat the host believes a key is seated under, and the key's role. */
data class PeerEntry(
    val peerId: String,
    val role: Role,
)

/** The sealed room state (`kind = 1`); [peers] keeps the frame's order, keyed by key spelling. */
data class RoomState(
    val issued: Long,
    val listing: List<String>,
    val peers: Map<String, PeerEntry>,
) {
    /** The canonical plaintext a host seals. */
    fun encode(): ByteArray =
        CanonicalJson.writeBytes(
            JsonValue.Obj.of(
                "issued" to issued.json(),
                "listing" to JsonValue.Arr(listing.map { it.json() }),
                "peers" to
                    JsonValue.Obj(
                        peers.mapValuesTo(LinkedHashMap()) { (_, entry) ->
                            JsonValue.Obj.of("peer_id" to entry.peerId.json(), "role" to entry.role.wire.json())
                        },
                    ),
            ),
        )
}

/** What a frame's plaintext says, read by its kind (§6.1 step 8). */
sealed interface Payload {
    data object Content : Payload

    data class State(
        val state: RoomState,
    ) : Payload

    data class Closing(
        val issued: Long,
    ) : Payload

    data class Holds(
        val holds: List<String>,
    ) : Payload

    data class Announcement(
        val key: String,
        val role: Role?,
    ) : Payload

    /** The `issued` a state or a closing carries (§6.1 step 9). */
    val stated: Long?
        get() =
            when (this) {
                is State -> state.issued
                is Closing -> issued
                else -> null
            }

    companion object {
        const val MAX_PATH_BYTES = 4096

        /** A path a receiver will carry: `PROTOCOL.md` §5's rule, applied by dropping the path. */
        fun usablePath(path: String): Boolean =
            path.isNotBlank() && path.none { Character.getType(it) == Character.CONTROL.toInt() } &&
                path.toByteArray(Charsets.UTF_8).size <= MAX_PATH_BYTES

        fun closing(issued: Long): ByteArray =
            CanonicalJson.writeBytes(
                JsonValue.Obj.of(
                    "closing" to true.json(),
                    "issued" to issued.json(),
                ),
            )

        fun holds(paths: List<String>): ByteArray =
            CanonicalJson.writeBytes(
                JsonValue.Obj.of("holds" to JsonValue.Arr(paths.map { it.json() })),
            )

        fun announcement(
            key: String,
            role: Role?,
        ): ByteArray = CanonicalJson.writeBytes(JsonValue.Obj.of("key" to key.json(), "role" to role?.wire?.json()))

        /** The plaintext of a `kind` 0 to 3 frame as its kind's object, or null (`bad_payload`). */
        fun read(
            kind: Long,
            plaintext: ByteArray,
        ): Payload? {
            if (kind == 0L) return Content
            val value = objectOf(plaintext) ?: return null
            return when (kind) {
                1L -> {
                    val issued = value.count("issued") ?: return null
                    val listing = paths(value["listing"]) ?: return null
                    val peers = peers(value["peers"]) ?: return null
                    State(RoomState(issued, listing, peers))
                }

                2L -> {
                    if (value["closing"] != JsonValue.Bool(true)) return null
                    Closing(value.count("issued") ?: return null)
                }

                3L -> {
                    Holds(paths(value["holds"]) ?: return null)
                }

                else -> {
                    null
                }
            }
        }

        /** An announcement's plaintext, or null; the key must also decode canonically. */
        fun readAnnouncement(plaintext: ByteArray): Announcement? {
            val value = objectOf(plaintext) ?: return null
            val key = value.string("key") ?: return null
            val role =
                when (value["role"]) {
                    null -> null
                    JsonValue.Str("guest") -> Role.GUEST
                    JsonValue.Str("viewer") -> Role.VIEWER
                    else -> return null
                }
            return Announcement(key, role)
        }

        private fun objectOf(plaintext: ByteArray): JsonValue.Obj? =
            try {
                CanonicalJson.parse(plaintext) as? JsonValue.Obj
            } catch (e: MalformedJson) {
                null
            }

        private fun paths(value: JsonValue?): List<String>? {
            val items = (value as? JsonValue.Arr)?.items ?: return null
            return items.map { (it as? JsonValue.Str)?.value ?: return null }
        }

        private fun peers(value: JsonValue?): Map<String, PeerEntry>? {
            val members = (value as? JsonValue.Obj)?.members ?: return null
            val out = LinkedHashMap<String, PeerEntry>()
            for ((spelling, entry) in members) {
                if (KeyCodec.decode(spelling) == null) return null
                val obj = entry as? JsonValue.Obj ?: return null
                val peerId = obj.string("peer_id") ?: return null
                val role = Role.of(obj.string("role")) ?: return null
                out[spelling] = PeerEntry(peerId, role)
            }
            return out
        }
    }
}
