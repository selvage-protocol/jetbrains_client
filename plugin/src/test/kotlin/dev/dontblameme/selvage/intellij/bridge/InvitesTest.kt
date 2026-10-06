package dev.dontblameme.selvage.intellij.bridge

import dev.dontblameme.selvage.engine.SessionException
import dev.dontblameme.selvage.wire.Wire
import junit.framework.TestCase

/**
 * What a first connect that did not become a session says (`connectRefusal`). The capacity faults
 * are read by their code — §11's 1013 close, which the engine reports as `try_again_later`, and
 * §2.1's `x.server_full` for a refused room mint — and the reference server's wording is only a
 * fallback for a server that says it in a code this client does not know.
 */
class InvitesTest : TestCase() {
    private val full = "The server is full. Try again in a few minutes."

    fun testACapacityCloseIsAServerFullSentence() {
        assertEquals(
            "a 1013 close whose reason is not the reference server's",
            full,
            Invites.connectRefusal(
                SessionException(
                    Wire.TRY_AGAIN_LATER,
                    "the socket closed before the session was seated: 1013 capacity reached",
                ),
                Invites.JOIN_CHECK,
            ),
        )
    }

    fun testARefusedRoomMintIsAServerFullSentence() {
        assertEquals(
            full,
            Invites.connectRefusal(
                SessionException("x.server_full", "the server holds at most 1024 rooms"),
                Invites.JOIN_CHECK,
            ),
        )
    }

    fun testTheReferenceServersWordingIsStillAFallback() {
        assertEquals(
            full,
            Invites.connectRefusal(
                SessionException(
                    "closed",
                    "the socket closed before the session was seated: 1013 server full, try again later",
                ),
                Invites.JOIN_CHECK,
            ),
        )
    }
}
