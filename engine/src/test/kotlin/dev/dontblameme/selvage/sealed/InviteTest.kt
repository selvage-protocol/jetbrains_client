package dev.dontblameme.selvage.sealed

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class InviteTest {
    private val roomKey = ByteArray(32) { it.toByte() }
    private val hostKey = ByteArray(32) { (255 - it).toByte() }
    private val k = KeyCodec.encode(roomKey)
    private val h = KeyCodec.encode(hostKey)

    private fun refused(link: String) = assertIs<Invite.Read.Refused>(Invite.parse(link)).reason

    @Test
    fun `the key encoding is canonical and 43 characters`() {
        assertEquals(43, k.length)
        assertContentEquals(roomKey, KeyCodec.decode(k))
        assertNull(KeyCodec.decode(k.dropLast(1) + "B"), "a final character with low bits set spells no key")
        assertNull(KeyCodec.decode("$k="))
        assertNull(KeyCodec.decode(k.replaceRange(0, 1, "+")))
    }

    @Test
    fun `reads the connection URL form and strips the fragment`() {
        val link = "ws://127.0.0.1:9/session?room=R%2Bx+&token=t%20k#k=$k&h=$h"
        val invite = assertIs<Invite.Read.Ok>(Invite.parse(link)).invite
        assertEquals("ws://127.0.0.1:9/session?room=R%2Bx+&token=t%20k", invite.socketUrl)
        assertEquals("R+x+", invite.room)
        assertEquals("t k", invite.token)
        assertContentEquals(roomKey, invite.roomKey)
        assertContentEquals(hostKey, invite.hostKey)
        assertEquals(false, invite.toString().contains(k))
    }

    @Test
    fun `reads the page link form as the connection URL`() {
        val invite = assertIs<Invite.Read.Ok>(Invite.parse("https://example.org/p/?room=R1&token=T1#k=$k&h=$h")).invite
        assertEquals("wss://example.org/p/session?room=R1&token=T1", invite.socketUrl)
    }

    /**
     * §5.1: a page link has no server to refuse a repeat, so the receiver refuses it locally, and
     * the refusal names the parameter — the rewrite may not decide which of two values wins.
     */
    @Test
    fun `refuses a page link that repeats a join key by name`() {
        assertEquals(
            "the invite names `room` twice",
            refused("https://example.org/?room=R&room=S&token=T#k=$k&h=$h"),
        )
        assertEquals(
            "the invite names `token` twice",
            refused("https://example.org/?room=R&token=T&token=U#k=$k&h=$h"),
        )
    }

    /**
     * The control: the same page link with one of each still reads, and an unknown parameter is
     * carried rather than dropped or refused (§5.1 ignores it).
     */
    @Test
    fun `reads a page link that carries an unknown parameter`() {
        val invite =
            assertIs<Invite.Read.Ok>(
                Invite.parse("https://example.org/p/?room=R1&token=T1&server=legacy#k=$k&h=$h"),
            ).invite
        assertEquals("wss://example.org/p/session?room=R1&token=T1&server=legacy", invite.socketUrl)
        assertEquals("R1", invite.room)
        assertEquals("T1", invite.token)
    }

    @Test
    fun `refuses by name`() {
        assertEquals(Invite.MISSING_FRAGMENT, refused("ws://h/session?room=R&token=T"))
        assertEquals("the invite names `room` twice", refused("ws://h/session?room=R&room=S&token=T#k=$k&h=$h"))
        assertEquals("the invite names no room", refused("ws://h/session?token=T#k=$k&h=$h"))
        assertEquals("the invite carries no token", refused("ws://h/session?room=R#k=$k&h=$h"))
        assertEquals("the invite carries no room key (`k`)", refused("ws://h/session?room=R&token=T#h=$h"))
        assertEquals("the invite carries no host key (`h`)", refused("ws://h/session?room=R&token=T#k=$k"))
        assertEquals(
            "`k` is not a 32-byte key in the fragment's encoding",
            refused("ws://h/session?room=R&token=T#k=abc&h=$h"),
        )
        assertEquals("the invite names `h` twice", refused("ws://h/session?room=R&token=T#k=$k&h=$h&h=$h"))
    }

    @Test
    fun `builds the connection and meta URLs`() {
        assertEquals("ws://127.0.0.1:8080", Urls.sessionBase("http://127.0.0.1:8080/"))
        assertEquals("wss://x.org/pre", Urls.sessionBase("wss://x.org/pre/session"))
        assertNull(Urls.sessionBase("ftp://x.org"))
        assertEquals("ws://h:1/session", Urls.sessionUrl("ws://h:1"))
        assertEquals("ws://h:1/session?room=a%2Bb&token=c~d", Urls.sessionUrl("ws://h:1", "a+b", "c~d"))
        assertEquals("https://x.org/pre/meta", Urls.metaUrl("wss://x.org/pre"))
        assertEquals("ws://h:1", Urls.baseOf("ws://h:1/session?room=r&token=t"))
        assertEquals("a+b c", Urls.percentDecode("a+b%20c"))
    }
}
