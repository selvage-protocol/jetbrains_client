package dev.dontblameme.selvage.intellij.bridge

import dev.dontblameme.selvage.peer.Ending
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The words every client says at the same moments, ported from `vscode_client/src/bridge/words.ts`
 * so this client says the same sentence as the others. `BridgeParityTest` runs the TypeScript source
 * beside this file and fails on any difference in output.
 */
object Words {
    fun hostingIdentity(folder: String): String = "Sharing “$folder”"

    const val SHARED_SESSION_IDENTITY = "In a shared session"

    fun guestIdentity(hostName: String?): String =
        if (hostName ==
            null
        ) {
            SHARED_SESSION_IDENTITY
        } else {
            "In $hostName’s session"
        }

    const val COPY_INVITE_LABEL = "Copy invite link"
    const val COPIED_LABEL = "Copied"
    const val COPIED_STAND_MS = 1800L

    const val RECONNECTING_NOTE = "Connection dropped. Reconnecting…"

    fun graceWording(graceMs: Double): String {
        val ms = max(0.0, graceMs)
        val seconds = floor(ms / 1000).toLong()
        if (seconds == 0L) return "a moment"
        if (seconds < 60) return "$seconds second${if (seconds == 1L) "" else "s"}"
        val minutes = floor(ms / 60_000).toLong()
        if (minutes < 60) return "$minutes minute${if (minutes == 1L) "" else "s"}"
        val hours = floor(ms / 3_600_000).toLong()
        return "$hours hour${if (hours == 1L) "" else "s"}"
    }

    fun disconnectingReading(
        graceMs: Double,
        remainingMs: Double,
    ): String {
        val left = max(0.0, remainingMs)
        if (graceMs >= 60_000) return graceWording(left)
        return "${ceil(left / 1000).toLong()}s"
    }

    fun hostLeftSentence(name: String): String {
        val who = if (name.jsTrim() == "") "The host" else name.jsTrim()
        return "$who left the session"
    }

    fun hostAwaySentence(
        name: String,
        graceMs: Double,
    ): String = "${hostLeftSentence(name)}. The room disconnects in ${windowWords(max(0.0, graceMs))}."

    fun hostBackSentence(name: String): String {
        val who = if (name.jsTrim() == "") "the host" else name.jsTrim()
        return "$who is back. The session continues."
    }

    private fun windowWords(graceMs: Double): String =
        if (graceMs >= 60_000) graceWording(graceMs) else graceWording(ceil(graceMs / 1000) * 1000)

    fun goToNotInFile(name: String): String = "$name is not in a file"

    fun goToCursorNotFound(name: String): String = "$name’s cursor could not be found in this file"

    fun followEndedByTyping(name: String): String = "Stopped following $name because you started typing."

    fun followEndedByMoving(name: String): String = "Stopped following $name because you moved your cursor."

    fun followEndedByLeaving(name: String): String = "$name left the room, so following stopped."

    fun followEndedByFileGone(name: String): String = "Stopped following $name because the file is gone."

    fun downloadCostSentence(path: String): String =
        "Downloading $path opens it in the room, so everyone there gets its text."

    const val DOWNLOAD_COST_MANY_SENTENCE =
        "Downloading these files opens them in the room, so everyone there gets their text."

    const val LEAVE_HOST_LABEL = "Leave and end the room"
    const val LEAVE_ASKING_LABEL = "Leave anyway"
    const val LEAVE_CANCEL_LABEL = "Cancel"

    const val HOST_LEAVE_CONSEQUENCE = "Leaving ends the room for everyone and stops the invite link."

    const val HOST_LEAVE_QUESTION = "$HOST_LEAVE_CONSEQUENCE Your last few keystrokes may not reach your folder."

    const val SESSION_ENDED_MESSAGE = "The session ended."

    fun roomGoneSentence(reason: String): String {
        val why = reason.jsTrim()
        if (why == endingReason(Ending.CLOSING)) return "The host ended the session."
        if (why == endingReason(Ending.HOST_AWAY)) return "The host was away too long, so the session ended."
        return if (why == "") SESSION_ENDED_MESSAGE else "The session ended ($why)."
    }

    /** `endingReason` in `vscode_client/src/engine/peer.ts`: the reason a room-gone report carries. */
    fun endingReason(ending: Ending): String = ending.sentence
}

/** JavaScript's `String.prototype.trim`: whitespace and line terminators as ECMAScript names them. */
internal fun String.jsTrim(): String = trim { isJsWhitespace(it) }

private val JS_WHITESPACE: Set<Int> =
    setOf(0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x20, 0xa0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff) +
        (0x2000..0x200a)

private fun isJsWhitespace(c: Char): Boolean = c.code in JS_WHITESPACE
