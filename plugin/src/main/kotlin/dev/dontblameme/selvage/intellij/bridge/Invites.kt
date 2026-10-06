package dev.dontblameme.selvage.intellij.bridge

import dev.dontblameme.selvage.engine.SessionException
import dev.dontblameme.selvage.sealed.Urls
import dev.dontblameme.selvage.wire.Wire
import java.net.URI
import java.net.URISyntaxException

/**
 * Server addresses and invite links as the VS Code client reads them (`normaliseServerUrl`,
 * `serverAddressRefusal`, `inviteLinkRefusal`, `buildPageLink` and their helpers in
 * `vscode_client/src/adapter/extension.ts`), so a link copied from one client joins from the other.
 */
object Invites {
    /** The demo server a host is offered when nothing was set or remembered. */
    const val DEFAULT_SERVER_URL = "selvage-demo.dontblameme.dev"

    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

    /** A bare host means `wss://<host>`; a trailing slash and a trailing `/session` are dropped. */
    fun normaliseServerUrl(text: String): String {
        val trimmed = text.jsTrim()
        if (trimmed == "") return ""
        val addressed = if (SCHEME.containsMatchIn(trimmed)) trimmed else "wss://$trimmed"
        return addressed.replace(Regex("/+$"), "").replace(Regex("/session$"), "")
    }

    fun serverAddressRefusal(value: String): String? {
        val text = value.jsTrim()
        if (text == "") return "Enter the address the server printed when it started."
        if (Urls.sessionBase(normaliseServerUrl(text)) != null) return null
        return if (text.contains('?') || text.contains('#')) {
            "that is an invite link, not a server address. Paste the address the server " +
                "printed when it started, not the link you send to your guest."
        } else {
            "that does not look like a server address. Paste the address the server printed " +
                "when it started, e.g. selvage.example or ws://127.0.0.1:8080."
        }
    }

    const val INVITE_LINK_HINT =
        "that does not look like a Selvage invite link. Paste the whole link the host " +
            "sent you — it looks like https://…/?room=…&token=…. A " +
            "ws://host:8080/session?room=…&token=… link still joins."

    fun fragmentOf(invite: String): String {
        val hash = invite.indexOf('#')
        return if (hash == -1) "" else invite.substring(hash)
    }

    fun fragmentKeys(fragment: String): Pair<String?, String?> {
        val text = fragment.removePrefix("#")
        var roomKey: String? = null
        var hostKey: String? = null
        for (part in text.split('&')) {
            val at = part.indexOf('=')
            val name = if (at == -1) part else part.substring(0, at)
            val value = if (at == -1) "" else part.substring(at + 1)
            if (name == "k" && roomKey == null) {
                roomKey = value
            } else if (name == "h" && hostKey == null) {
                hostKey = value
            }
        }
        return roomKey to hostKey
    }

    fun fragmentKeyRefusal(invite: String): String? {
        val (roomKey, hostKey) = fragmentKeys(fragmentOf(invite))
        if (roomKey == null && hostKey == null) return null
        if (roomKey.isNullOrEmpty()) return "the invite carries no room key (`k`)"
        if (hostKey.isNullOrEmpty()) return "the invite carries no host key (`h`)"
        return null
    }

    data class PageLink(
        val room: String,
        val token: String,
        val origin: String,
    )

    fun parsePageLink(text: String): PageLink? {
        val uri =
            try {
                URI(text.jsTrim())
            } catch (e: URISyntaxException) {
                return null
            }
        if (uri.scheme != "http" && uri.scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        val query = queryPairs(uri.rawQuery ?: "")
        val room = query.firstOrNull { it.first == "room" }?.second
        val token = query.firstOrNull { it.first == "token" }?.second
        if (room.isNullOrEmpty() || token.isNullOrEmpty()) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return PageLink(room, token, "${uri.scheme}://${uri.host}$port${(uri.rawPath ?: "").replace(Regex("/+$"), "")}")
    }

    private fun queryPairs(query: String): List<Pair<String, String>> =
        query.split('&').filter { it.isNotEmpty() }.map { pair ->
            val at = pair.indexOf('=')
            Urls.percentDecode(if (at == -1) pair else pair.substring(0, at)) to
                Urls.percentDecode(if (at == -1) "" else pair.substring(at + 1))
        }

    fun pageOriginOf(serverBase: String): String {
        val wanted = serverBase.jsTrim().replace(Regex("/+$"), "")
        if (wanted.startsWith("wss://")) return "https://${wanted.removePrefix("wss://")}"
        if (wanted.startsWith("ws://")) return "http://${wanted.removePrefix("ws://")}"
        return wanted
    }

    fun serverBaseOf(page: String): String {
        val wanted = page.jsTrim().replace(Regex("/+$"), "")
        if (wanted.startsWith("https://")) return "wss://${wanted.removePrefix("https://")}"
        if (wanted.startsWith("http://")) return "ws://${wanted.removePrefix("http://")}"
        return wanted
    }

    data class WireUrl(
        val base: String,
        val room: String?,
        val token: String?,
    )

    /** `parseSessionUrl`: the base and the join query of a `…/session?room=…&token=…` URL. */
    fun parseSessionUrl(url: String): WireUrl? {
        val at = url.indexOf('?')
        val endpoint = if (at == -1) url else url.substring(0, at)
        val query = if (at == -1) "" else url.substring(at + 1)
        if (!endpoint.endsWith(Urls.ENDPOINT_PATH)) return null
        val base = Urls.sessionBase(endpoint.removeSuffix(Urls.ENDPOINT_PATH)) ?: return null
        val pairs = queryPairs(query)
        return WireUrl(
            base,
            pairs
                .firstOrNull {
                    it.first == "room"
                }?.second,
            pairs.firstOrNull { it.first == "token" }?.second,
        )
    }

    /** The wire URL an invite joins on, fragment stripped: a page link is read back as its server's socket. */
    fun wireInviteFor(invite: String): String {
        val hash = invite.indexOf('#')
        val address = if (hash == -1) invite else invite.substring(0, hash)
        val page = parsePageLink(address) ?: return address
        val server = Urls.sessionBase(serverBaseOf(page.origin)) ?: return address
        return Urls.sessionUrl(server, page.room, page.token)
    }

    private fun isSessionBase(value: String): Boolean =
        try {
            val scheme = URI(value).scheme
            scheme == "ws" || scheme == "wss"
        } catch (e: URISyntaxException) {
            false
        }

    /** Why the join box refuses [value], or null when it is an invite this client can dial. */
    fun inviteLinkRefusal(value: String): String? {
        val invite = value.jsTrim()
        fragmentKeyRefusal(invite)?.let { return it }
        if (parsePageLink(invite.substringBefore('#')) != null) return null
        val wire = wireInviteFor(invite)
        if (!isSessionBase(wire)) return INVITE_LINK_HINT
        val parsed = parseSessionUrl(wire)
        if (parsed == null || parsed.room.isNullOrEmpty() || parsed.token.isNullOrEmpty()) return INVITE_LINK_HINT
        val dialled = URI(Urls.sessionUrl(parsed.base, parsed.room, parsed.token))
        val pasted =
            try {
                URI(wire)
            } catch (e: URISyntaxException) {
                return INVITE_LINK_HINT
            }
        if (pasted.rawPath != dialled.rawPath || pasted.rawQuery != dialled.rawQuery) return INVITE_LINK_HINT
        return null
    }

    /** The guest link for a room: the page its server serves, with the room, the token and the fragment. */
    fun buildPageLink(
        serverBase: String,
        room: String,
        token: String,
        fragment: String,
    ): String =
        "${pageOriginOf(serverBase)}/?room=${Urls.percentEncode(room)}&token=${Urls.percentEncode(token)}$fragment"

    /** The page link for an engine's wire invite, fragment kept byte for byte; null when it carries no token. */
    fun pageInviteFor(wireInvite: String?): String? {
        if (wireInvite == null) return null
        val parsed = parseSessionUrl(wireInviteFor(wireInvite)) ?: return null
        val room = parsed.room
        val token = parsed.token
        if (room.isNullOrEmpty() || token.isNullOrEmpty()) return null
        return buildPageLink(parsed.base, room, token, fragmentOf(wireInvite))
    }

    /** The address a connect notice names: the base, never the URL that carries the token. */
    fun sessionAddress(wire: String): String = parseSessionUrl(wire)?.base ?: "the address in the invite"

    const val HOST_CHECK = "check the address is the one the server printed, and that the server is running."
    const val JOIN_CHECK = "check the invite is complete, and that the server is running at the address it names."

    /** §2.1's code for a room mint the server's cap refused. */
    const val SERVER_FULL = "x.server_full"

    /** The reference server's own wording for that fault, kept only as a fallback for a code this client does not know. */
    private val SERVER_FULL_WORDS = Regex("^server full\\b")

    /** The codes a capacity fault arrives under: the refused mint, and §11's 1013 close at the cap. */
    private val CAPACITY_CODES = setOf(SERVER_FULL, Wire.TRY_AGAIN_LATER)

    /** What a first connect that did not become a session says (`connectRefusal`). */
    fun connectRefusal(
        error: Throwable,
        check: String,
    ): String {
        val refusal = error as? SessionException ?: return "No server answered — $check"
        val message = refusal.message ?: ""
        if (refusal.code in CAPACITY_CODES ||
            SERVER_FULL_WORDS.containsMatchIn(message) ||
            (refusal.code == "closed" && message.contains(" server full"))
        ) {
            return "The server is full. Try again in a few minutes."
        }
        return when (refusal.code) {
            "room_unknown" -> "That invite names a room the server does not have. Ask the host for a fresh invite."
            "token_invalid" -> "That invite is no longer valid. Ask the host for a fresh invite."
            ROOM_FULL -> "The room is full — it seats no more people."
            "room_gone" -> "That room is gone."
            "hello_required", "timeout", "connect", "closed" -> "No server answered — $check"
            else -> message
        }
    }

    const val ROOM_FULL = "x.room_full"

    /** A fault the room reported to a seated session: the server's words, made a sentence. */
    fun sessionErrorSentence(
        message: String,
        code: String,
    ): String {
        if (code == ROOM_FULL) return "Selvage: the room is full — it seats no more people."
        val words = message.jsTrim()
        val sentence = if (Regex("[.!?]$").containsMatchIn(words)) words else "$words."
        return "Selvage: $sentence"
    }

    /** `displayNameRefusal` in `vscode_client/src/adapter/display-name.ts`. */
    fun displayNameRefusal(name: String): String? {
        val trimmed = name.jsTrim()
        if (trimmed == "") return "a name is needed."
        val units = trimmed.length
        if (units > Wire.MAX_DISPLAY_NAME_UNITS) {
            return "this name is $units UTF-16 code units and the limit is " +
                "${Wire.MAX_DISPLAY_NAME_UNITS}; a name is refused rather than shortened."
        }
        return null
    }
}
