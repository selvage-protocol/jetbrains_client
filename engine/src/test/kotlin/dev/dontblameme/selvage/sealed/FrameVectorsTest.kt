package dev.dontblameme.selvage.sealed

import dev.dontblameme.selvage.canonical.CanonicalJson
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.crdt.Doc
import dev.dontblameme.selvage.crdt.Sync
import dev.dontblameme.selvage.crdt.SyncMessage
import dev.dontblameme.selvage.crdt.Updates
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays the specification's frame vectors (`vectors/peer/1[01]*.json`, kind `frame`): every
 * `seal` reproduces the vector's bytes, and the reader reaches every verdict it claims.
 */
class FrameVectorsTest {
    private val fixture = PeerVectors.fixture
    private val frameVectors = PeerVectors.load().filter { it.string("kind") == "frame" }

    private class Failure(
        message: String,
    ) : AssertionError(message)

    private fun replay(
        vector: JsonValue.Obj,
        guards: Set<ReaderGuard>,
    ): Int {
        val reader = fixture.reader()
        reader.guards += guards
        val replica = Doc(1)
        val frames = HashMap<String, ByteArray>()
        var assertions = 0
        for ((index, stepValue) in vector.arr("steps")!!.items.withIndex()) {
            val step = stepValue.obj()
            val where = "${vector.string("id")} step $index (${step.string("op")})"

            fun frame(member: String = "frame") =
                frames[step.string(member)] ?: fail("$where: no frame ${step.string(member)}")
            when (step.string("op")) {
                "seal" -> {
                    val recipe = step.obj("recipe")!!
                    val plaintext =
                        recipe.string("plaintext")?.let(Bytes::fromHex)
                            ?: CanonicalJson.writeBytes(recipe.obj("payload") ?: fail("$where: no plaintext"))
                    val raw =
                        Frames.seal(
                            fixture.roomId,
                            fixture.frameKey,
                            recipe["kind"].long(),
                            recipe["counter"].long(),
                            fixture.key(recipe.string("sign")!!),
                            plaintext,
                            Bytes.fromHex(recipe.string("nonce")!!),
                            recipe["epoch"]?.long() ?: 0,
                        )
                    assertEquals(step.string("hex")!!.replace(" ", ""), Bytes.hex(raw), "$where: sealed bytes")
                    frames[step.string("frame")!!] = raw
                }

                "corrupt" -> {
                    val source = frame()
                    val raw =
                        when {
                            step["xor"] != null -> {
                                source.copyOf().also {
                                    val at = step["at"].long().toInt()
                                    it[at] = (it[at].toInt() xor step["xor"].long().toInt()).toByte()
                                }
                            }

                            step["truncate"] != null -> {
                                source.copyOf(source.size - step["truncate"].long().toInt())
                            }

                            else -> {
                                source + Bytes.fromHex(step.string("append")!!)
                            }
                        }
                    assertEquals(step.string("hex")!!.replace(" ", ""), Bytes.hex(raw), "$where: corrupted bytes")
                    frames[step.string("as")!!] = raw
                }

                "expectVerify" -> {
                    assertions++
                    val verdict = reader.read(frame())
                    if (!verdict.ok) throw Failure("$where: refused `${verdict.reason?.wire}`")
                    if (verdict.payload == Payload.Content) apply(replica, verdict.plaintext)
                }

                "expectReject" -> {
                    assertions++
                    val verdict = reader.read(frame())
                    if (verdict.ok) throw Failure("$where: applied, and the vector says `${step.string("reason")}`")
                    if (verdict.reason?.wire != step.string("reason")) {
                        throw Failure(
                            "$where: refused `${verdict.reason?.wire}`, and the vector says `${step.string("reason")}`",
                        )
                    }
                }

                "expectPlaintext" -> {
                    assertions++
                    val envelope = Envelope.parse(frame()) ?: fail("$where: not an envelope")
                    val plaintext =
                        Frames.opens(fixture.roomId, fixture.frameKey, envelope) ?: fail("$where: does not open")
                    step.string("signed_by")?.let {
                        assertTrue(
                            Frames.authentic(fixture.roomId, envelope, fixture.key(it).public),
                            "$where: signature",
                        )
                    }
                    step.string("plaintext")?.let { assertContentEquals(Bytes.fromHex(it), plaintext, where) }
                    step.obj("payload")?.let { assertEquals(it, CanonicalJson.parse(plaintext), where) }
                }

                "expectListing" -> {
                    assertions++
                    if (reader.listing != step["listing"].strings()) throw Failure("$where: listing ${reader.listing}")
                }

                "expectHolds" -> {
                    assertions++
                    val held = reader.holds[fixture.key(step.string("sign")!!).idHex].orEmpty()
                    if (held != step["holds"].strings()) throw Failure("$where: holds $held")
                }

                "expectDoc" -> {
                    assertions++
                    val text = replica.getText(step.string("path")!!).toString()
                    if (text != step.string("text")) throw Failure("$where: document holds \"$text\"")
                }

                else -> {
                    fail("$where: an op this replay does not know")
                }
            }
        }
        return assertions
    }

    private fun apply(
        doc: Doc,
        plaintext: ByteArray,
    ) {
        for (message in Sync.decode(plaintext)) {
            when (message) {
                is SyncMessage.Step2 -> {
                    Updates.applyUpdate(doc, message.update)
                }

                is SyncMessage.Update -> {
                    Updates.applyUpdate(doc, message.update)
                }

                else -> {}
            }
        }
    }

    @Test
    fun every_frame_vector_replays() {
        assertTrue(frameVectors.size >= 19, "expected the 19 frame vectors, found ${frameVectors.size}")
        val assertions = frameVectors.sumOf { replay(it, emptySet()) }
        assertTrue(assertions > 0)
    }

    @Test
    fun the_fixture_keys_derive_their_public_halves_and_ids() {
        val raw = CanonicalJson.parseObject(java.io.File(PeerVectors.dir.parentFile, "fixture/keys.json").readText())
        for ((name, entry) in raw.obj("keys")!!.members) {
            val key = fixture.key(name)
            assertEquals(entry.obj().string("public"), Bytes.hex(key.public), name)
            assertEquals(entry.obj().string("key_id"), key.idHex, name)
        }
    }

    /** Each guard the reader has is one some vector catches: without it, the corpus goes red. */
    @Test
    fun each_reader_guard_is_caught_by_a_vector() {
        for (guard in ReaderGuard.entries) {
            val caught =
                frameVectors.filter { vector ->
                    try {
                        replay(vector, setOf(guard))
                        false
                    } catch (e: Failure) {
                        true
                    }
                }
            assertTrue(caught.isNotEmpty(), "no frame vector catches the reader without `${guard.wire}`")
        }
    }
}
