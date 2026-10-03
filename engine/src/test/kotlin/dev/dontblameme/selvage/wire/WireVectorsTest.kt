package dev.dontblameme.selvage.wire

import dev.dontblameme.selvage.canonical.CanonicalJson
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.crdt.TestPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The wire vectors, read from the client's side: what it sends, and what it reads. */
class WireVectorsTest {
    private val vectors: Map<String, JsonValue.Obj> by lazy {
        val dir = File(TestPaths.specification, "vectors")
        val files = dir.listFiles { f -> f.name.matches(Regex("0\\d\\d-.*\\.json")) }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) fail("no wire vectors in $dir")
        files.associate { it.name.take(3) to CanonicalJson.parseObject(it.readText()) }
    }

    private fun steps(
        id: String,
        op: String,
        conn: String? = null,
    ): List<String> =
        (vectors.getValue(id).arr("steps")!!.items)
            .map { it as JsonValue.Obj }
            .filter { it.string("op") == op && (conn == null || it.string("conn") == conn) }
            .mapNotNull { it.string("text") }

    @Test
    fun `every server frame a vector expects is an envelope this client reads`() {
        var read = 0
        for ((id, _) in vectors) {
            for (text in steps(id, "expect")) {
                val message = ServerMessage.parse(text) ?: fail("$id: $text is not read")
                assertTrue(message.event != null || message.id != null, "$id: $text")
                read += 1
            }
        }
        assertTrue(read > 100, "only $read frames were read")
    }

    @Test
    fun `the handshake replies are read as seatings`() {
        val created = ServerMessage.parse(steps("002", "expect", "host").single())!!
        val seating = assertNotNull(Seating.of(created.params))
        assertEquals("\$room", seating.roomId)
        assertEquals("\$token", seating.token)
        assertEquals(77L, seating.self.awarenessClientId)
        assertEquals(15_000L, seating.keepalive.awarenessRenewMs)

        val joined = ServerMessage.parse(steps("020", "expect", "guest").first())!!
        val guest = assertNotNull(Seating.of(joined.params))
        assertNull(guest.token)
        assertEquals(listOf(WirePeer("\$host_peer", "Ada")), guest.peers)
    }

    @Test
    fun `hello and rename are byte for byte the frames the vectors send`() {
        assertEquals(steps("002", "send", "host").single(), Wire.hello("Ada", 77, listOf("y-protocols/1"), "probe/1"))
        assertEquals(steps("020", "send", "host").single(), Wire.hello("Ada", null, null))
        val renames = steps("020", "send", "guest").drop(1)
        assertEquals(renames[0], Wire.rename(2, "Robert"))
        assertEquals(renames[2], Wire.rename(4, "Rob"))
        assertEquals(
            """{"id":1,"method":"session.hello","params":{"awareness_client_id":4294967295,"capabilities":["y-protocols/1","awareness"],"display_name":"Ada"},"v":"selvage/2"}""",
            Wire.hello("Ada", 4294967295),
        )
    }

    @Test
    fun `frames the server refuses as not one object are not envelopes here either`() {
        val refused =
            steps("030", "send").filter { !it.contains("session.") } +
                steps("031", "send").filter {
                    "\"id\":1,\"id\"" in it ||
                        Regex("\"(method|display_name)\":[^,]*,\"\\1\"").containsMatchIn(it)
                }
        assertEquals(6, refused.size, refused.toString())
        for (text in refused) assertNull(ServerMessage.parse(text), text)
        val lone = steps("036", "send").single { "\\ud800" in it }
        assertNull(ServerMessage.parse(lone))
    }

    @Test
    fun `an envelope at any other version is not read`() {
        for (v in listOf("selvage/1", "selvage/2.0", "selvage/2.1", "salvage/2", "")) {
            assertNull(ServerMessage.parse("""{"event":"room.gone","params":{},"v":"$v"}"""), v)
        }
        assertNull(ServerMessage.parse("""{"event":"room.gone","params":{}}"""))
        assertNotNull(ServerMessage.parse("""{"event":"room.gone","params":{},"v":"selvage/2","x":1}"""))
    }

    @Test
    fun `a display name is judged as the server judges it`() {
        val hellos = steps("019", "send") + steps("017", "send") + steps("029", "send")
        val names =
            hellos.mapNotNull {
                (CanonicalJson.parseObject(it).obj("params")?.get("display_name") as? JsonValue.Str)?.value
            }
        val astral = "\uD834\uDD1E"
        assertTrue("a".repeat(31) + astral in names)
        assertTrue("a".repeat(30) + astral in names)
        assertNotNull(Wire.displayNameProblem("a".repeat(31) + astral))
        assertNull(Wire.displayNameProblem("a".repeat(30) + astral))
        assertNotNull(Wire.displayNameProblem("  "))
        assertNotNull(Wire.displayNameProblem("A\u0001da"))
        assertNotNull(Wire.displayNameProblem("A\tda"))
        assertNotNull(Wire.displayNameProblem("A\u009fda"))
        assertNull(Wire.displayNameProblem("Ada"))
        assertNull(Wire.displayNameProblem(" Ada "))
    }

    @Test
    fun `meta is read, with the room's grace`() {
        val body =
            steps("001", "expectBody").single().ifEmpty { fail() }
        val meta = assertNotNull(Meta.parse(body))
        assertEquals(listOf(Wire.VERSION), meta.wireVersions)
        assertEquals(Wire.CAPABILITIES, meta.capabilities)
        assertEquals(30_000L, meta.roomGraceMs)
        assertEquals(15_000L, meta.keepalive?.awarenessRenewMs)
        assertNull(Meta.parse("[]"))
    }

    @Test
    fun `the retry budget spans the room's grace and is bounded`() {
        val policy = ReconnectPolicy()
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 10_000L), (0..5).map(policy::delay))
        assertEquals(7, policy.attemptsForGrace(30_000))
        assertEquals(0, policy.attemptsForGrace(0))
        assertEquals(ReconnectPolicy.MAX_GRACE_ATTEMPTS, policy.attemptsForGrace(Long.MAX_VALUE))
        assertTrue(Wire.isTerminal("room_unknown"))
        assertTrue(Wire.isTerminal("x.capacity"))
        assertTrue(!Wire.isTerminal("bad_params"))
    }
}
