package dev.dontblameme.selvage.sealed

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

/** A parsed invite: where to connect, the room and token, and the fragment's two keys. */
class Invite(
    /** The connection URL, fragment stripped: the one part of a link that is dialled. */
    val socketUrl: String,
    val room: String,
    val token: String,
    val roomKey: ByteArray,
    val hostKey: ByteArray,
) {
    override fun toString(): String = "Invite($socketUrl)"

    /** The outcome of reading a link: an invite, or the reason in words (`PROTOCOL.md` §5.1). */
    sealed interface Read {
        data class Ok(
            val invite: Invite,
        ) : Read

        data class Refused(
            val reason: String,
        ) : Read
    }

    companion object {
        const val MISSING_FRAGMENT =
            "the invite carries no fragment, so neither its room key nor its host key is here: ask for the whole link, `#` and all"

        /** Reads a link in either form, the connection URL or the page link. */
        fun parse(link: String): Read {
            val wire = Urls.wireInvite(link)
            val hash = wire.indexOf('#')
            if (hash == -1) return Read.Refused(MISSING_FRAGMENT)
            val address = wire.substring(0, hash)
            val queryAt = address.indexOf('?')
            val endpoint = if (queryAt == -1) address else address.substring(0, queryAt)
            if (!endpoint.endsWith(Urls.ENDPOINT_PATH)) {
                return Read.Refused("\"$address\" does not address the session endpoint")
            }
            var room: String? = null
            var token: String? = null
            for ((name, value) in pairs(if (queryAt == -1) "" else address.substring(queryAt + 1))) {
                when (name) {
                    "room" -> {
                        if (room != null) return Read.Refused("the invite names `room` twice")
                        room = value
                    }

                    "token" -> {
                        if (token != null) return Read.Refused("the invite names `token` twice")
                        token = value
                    }
                }
            }
            if (room == null) return Read.Refused("the invite names no room")
            if (token == null) return Read.Refused("the invite carries no token")
            var roomKey: ByteArray? = null
            var hostKey: ByteArray? = null
            for ((name, value) in pairs(wire.substring(hash + 1), keepEmpty = true)) {
                if (name != "k" && name != "h") continue
                if ((if (name == "k") roomKey else hostKey) !=
                    null
                ) {
                    return Read.Refused("the invite names `$name` twice")
                }
                val key =
                    KeyCodec.decode(value)
                        ?: return Read.Refused("`$name` is not a 32-byte key in the fragment's encoding")
                if (name == "k") roomKey = key else hostKey = key
            }
            if (roomKey == null) return Read.Refused("the invite carries no room key (`k`)")
            if (hostKey == null) return Read.Refused("the invite carries no host key (`h`)")
            return Read.Ok(Invite(address, room, token, roomKey, hostKey))
        }

        /** The link a host hands on: the connection URL, and both keys in the fragment. */
        fun link(
            socketUrl: String,
            roomKey: ByteArray,
            hostKey: ByteArray,
        ): String = "$socketUrl#k=${KeyCodec.encode(roomKey)}&h=${KeyCodec.encode(hostKey)}"

        private fun pairs(
            text: String,
            keepEmpty: Boolean = false,
        ): List<Pair<String, String>> =
            text.split('&').filter { keepEmpty || it.isNotEmpty() }.map { pair ->
                val at = pair.indexOf('=')
                Urls.percentDecode(if (at == -1) pair else pair.substring(0, at)) to
                    Urls.percentDecode(if (at == -1) "" else pair.substring(at + 1))
            }
    }
}

/** The connection URL and `/meta` (`PROTOCOL.md` §2, §5.1): RFC 3986 percent-coding, `+` kept. */
object Urls {
    const val ENDPOINT_PATH = "/session"
    const val META_PATH = "/meta"

    private fun unreserved(b: Int) =
        b in 0x41..0x5a || b in 0x61..0x7a || b in 0x30..0x39 || b == 0x2d || b == 0x2e || b == 0x5f || b == 0x7e

    fun percentEncode(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xff
            if (unreserved(b)) out.append(b.toChar()) else out.append('%').append("%02X".format(b))
        }
        return out.toString()
    }

    fun percentDecode(text: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 3 <= text.length &&
                Character.digit(text[i + 1], 16) >= 0 && Character.digit(text[i + 2], 16) >= 0
            ) {
                out.write(text.substring(i + 1, i + 3).toInt(16))
                i += 3
                continue
            }
            val end = if (c.isHighSurrogate() && i + 1 < text.length) i + 2 else i + 1
            out.write(text.substring(i, end).toByteArray(Charsets.UTF_8))
            i = end
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * The session base, `ws(s)://host[:port][/prefix]`, from a server address in either scheme,
     * with a trailing `/session` stripped; null when it is not one.
     */
    fun sessionBase(text: String): String? {
        val uri =
            try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                return null
            }
        val scheme =
            when (uri.scheme) {
                "ws", "http" -> "ws"
                "wss", "https" -> "wss"
                else -> return null
            }
        if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            return null
        }
        var path = (uri.rawPath ?: "").trimEnd('/')
        if (path.endsWith(ENDPOINT_PATH)) path = path.removeSuffix(ENDPOINT_PATH).trimEnd('/')
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://${uri.host}$port$path"
    }

    fun sessionUrl(
        base: String,
        room: String? = null,
        token: String? = null,
    ): String {
        val query =
            listOf("room" to room, "token" to token)
                .filter { !it.second.isNullOrEmpty() }
                .joinToString("&") { "${it.first}=${percentEncode(it.second!!)}" }
        return "$base$ENDPOINT_PATH" + if (query.isEmpty()) "" else "?$query"
    }

    fun metaUrl(base: String): String = base.replaceFirst(Regex("^ws(s?)://"), "http$1://") + META_PATH

    /** The base of a connection URL, fragment and query stripped. */
    fun baseOf(socketUrl: String): String? {
        val address = socketUrl.substringBefore('#').substringBefore('?')
        if (!address.endsWith(ENDPOINT_PATH)) return null
        return sessionBase(address.removeSuffix(ENDPOINT_PATH))
    }

    /** A page link, `http(s)://host/prefix/?room=…&token=…#…`, read back as the connection URL. */
    fun wireInvite(link: String): String {
        val hash = link.indexOf('#')
        val address = if (hash == -1) link else link.substring(0, hash)
        val fragment = if (hash == -1) "" else link.substring(hash)
        if (address.substringBefore('?').endsWith(ENDPOINT_PATH)) return link
        val uri =
            try {
                URI(address)
            } catch (e: URISyntaxException) {
                return link
            }
        val scheme =
            when (uri.scheme) {
                "http" -> "ws"
                "https" -> "wss"
                else -> return link
            }
        val query = uri.rawQuery ?: return link
        val params = query.split('&').map { it.substringBefore('=') to percentDecode(it.substringAfter('=', "")) }
        val room = params.firstOrNull { it.first == "room" }?.second
        val token = params.firstOrNull { it.first == "token" }?.second
        if (room.isNullOrEmpty() || token.isNullOrEmpty()) return link
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val base = sessionBase("$scheme://${uri.host}$port${(uri.rawPath ?: "").trimEnd('/')}") ?: return link
        // The query is carried as it was written, so a `room` or `token` named twice is still
        // there for `Invite.parse` to refuse by name: rebuilding it from the two values read
        // above would collapse the repeat, and which room a link names must not depend on which
        // of two values a rewrite happened to take (§5.1).
        return "$base$ENDPOINT_PATH?$query$fragment"
    }
}
