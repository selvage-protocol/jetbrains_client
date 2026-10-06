package dev.dontblameme.selvage.intellij.bridge

import dev.dontblameme.selvage.engine.SessionException
import dev.dontblameme.selvage.sealed.Invite
import dev.dontblameme.selvage.sealed.KeyCodec
import dev.dontblameme.selvage.wire.Wire
import junit.framework.TestCase

/**
 * What a first connect that did not become a session says (`connectRefusal`), and what the join
 * box makes of a pasted link. The capacity faults are read by their code — §11's 1013 close, which
 * the engine reports as `try_again_later`, and §2.1's `x.server_full` for a refused room mint —
 * and the reference server's wording is only a fallback for a server that says it in a code this
 * client does not know.
 */
class InvitesTest : TestCase() {
    private val full = "The server is full. Try again in a few minutes."
    private val k = KeyCodec.encode(ByteArray(32) { it.toByte() })
    private val h = KeyCodec.encode(ByteArray(32) { (255 - it).toByte() })

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

    /**
     * §5.1: a page link has no server to refuse a repeated `room` or `token`, so the join box —
     * which runs before the join does — refuses it locally and names the parameter, rather than
     * accepting a link the rewrite would have joined at the first of two values.
     */
    fun testTheJoinBoxRefusesAPageLinkThatRepeatsAJoinKey() {
        assertEquals(
            "the invite names `room` twice",
            Invites.inviteLinkRefusal("https://example.org/?room=r-1&room=r-2&token=t#k=$k&h=$h"),
        )
        assertEquals(
            "the invite names `token` twice",
            Invites.inviteLinkRefusal("https://example.org/?room=r-1&token=t&token=u#k=$k&h=$h"),
        )
    }

    fun testTheJoinBoxRefusesARepeatedFragmentKeyByName() {
        val key = KeyCodec.encode(ByteArray(32))
        assertEquals(
            "the invite names `k` twice",
            Invites.inviteLinkRefusal("https://example.org/?room=r-1&token=t#k=$key&k=$key&h=$h"),
        )
    }

    /** The control: one of each still joins, and an unknown parameter is ignored, not refused. */
    fun testTheJoinBoxAcceptsAnOrdinaryPageLink() {
        assertNull(
            Invites.inviteLinkRefusal("https://example.org/?room=r-1&token=t&server=legacy#k=$k&h=$h"),
        )
    }

    /**
     * The join's own path: `wireInviteFor`, then the fragment as it arrived, handed to the engine
     * (`SelvageService.join`). A repeated page link is not rewritten — the rewrite would have to
     * choose a value — so the engine reads the link as it stands and names the repeat.
     */
    fun testTheJoinPathHandsARepeatedPageLinkToTheEngine() {
        val link = "https://example.org/?room=r-1&room=r-2&token=t#k=$k&h=$h"
        val wire = Invites.wireInviteFor(link)
        assertEquals(link, "$wire${Invites.fragmentOf(link)}")
        val read = Invite.parse("$wire${Invites.fragmentOf(link)}")
        assertEquals("the invite names `room` twice", (read as Invite.Read.Refused).reason)
    }
}
