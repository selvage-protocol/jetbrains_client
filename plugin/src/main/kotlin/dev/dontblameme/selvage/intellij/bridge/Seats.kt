package dev.dontblameme.selvage.intellij.bridge

import dev.dontblameme.selvage.sealed.Role

/**
 * The room's seats and the colour each wears, ported from `vscode_client/src/bridge/seats.ts`. The
 * host's seat leads, every other keeps its order, and a seat past the palette keeps its peer colour.
 * Yellow is reserved for the host's crown.
 */
object Seats {
    val SEAT_PALETTE: List<String> =
        listOf(
            "#cba6f7",
            "#94e2d5",
            "#f5e0dc",
            "#f2cdcd",
            "#f5c2e7",
            "#f38ba8",
            "#eba0ac",
            "#fab387",
            "#a6e3a1",
            "#89dceb",
            "#74c7ec",
            "#89b4fa",
            "#b4befe",
        )

    val SEAT_LIMIT: Int = SEAT_PALETTE.size

    data class Seat(
        val peerId: String,
        val role: Role,
    )

    fun seatColours(seats: List<Seat>): Map<String, String> {
        val host = seats.firstOrNull { it.role == Role.HOST }
        val order = if (host == null) seats else listOf(host) + seats.filter { it !== host }
        val colours = LinkedHashMap<String, String>()
        order.take(SEAT_LIMIT).forEachIndexed { index, seat -> colours[seat.peerId] = SEAT_PALETTE[index] }
        return colours
    }
}

/** Roster names, ported from `vscode_client/src/bridge/names.ts`: plain while unique, else a short peer id. */
object Names {
    data class NamedPeer(
        val displayName: String,
        val peerId: String,
    )

    private const val SHORT_TAIL = 4

    fun rosterLabel(
        peer: NamedPeer,
        all: List<NamedPeer>,
    ): String {
        val namesakes = all.filter { it.peerId != peer.peerId && it.displayName == peer.displayName }
        if (namesakes.isEmpty()) return peer.displayName
        var length = SHORT_TAIL
        while (length < peer.peerId.length &&
            namesakes.any { it.peerId.jsTail(length) == peer.peerId.jsTail(length) }
        ) {
            length += 1
        }
        return "${peer.displayName} · ${peer.peerId.jsTail(length)}"
    }

    /** `String.prototype.slice(-n)`: the last [n] code units, or the whole string when shorter. */
    private fun String.jsTail(n: Int): String = if (n >= length) this else substring(length - n)
}

/** A badge's initials, ported from `vscode_client/src/bridge/initials.ts`. */
object Initials {
    const val ANONYMOUS_INITIALS = "•"
    const val INITIALS_LIMIT = 2

    /** The first [INITIALS_LIMIT] code points, whole, or the bullet when there are none. */
    fun initials(label: String): String {
        val out = StringBuilder()
        var taken = 0
        var index = 0
        while (index < label.length && taken < INITIALS_LIMIT) {
            val point = label.codePointAt(index)
            out.appendCodePoint(point)
            index += Character.charCount(point)
            taken += 1
        }
        return if (taken == 0) ANONYMOUS_INITIALS else out.toString()
    }
}

/** The colour a peer is drawn in without a seat, ported from `vscode_client/src/bridge/cursors.ts`. */
object PeerColours {
    val PEER_PALETTE: List<String> =
        listOf("#e06c75", "#e5c07b", "#98c379", "#56b6c2", "#61afef", "#c678dd", "#d19a66", "#b48ead")

    /** FNV-1a over UTF-16 code units, unsigned 32-bit as the TypeScript computes it. */
    private fun peerHash(peerId: String): Long {
        var hash = 0x811c9dc5L
        for (c in peerId) {
            hash = hash xor c.code.toLong()
            hash = (hash * 0x01000193L) and 0xffffffffL
        }
        return hash
    }

    fun peerColourIndex(peerId: String): Int = (peerHash(peerId) % PEER_PALETTE.size).toInt()

    fun peerColour(peerId: String): String = PEER_PALETTE[peerColourIndex(peerId)]

    fun peerName(
        displayName: String,
        peerId: String,
    ): String = if (displayName == "") peerId else displayName
}
