package dev.dontblameme.selvage.crdt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.fail

/** The specification's vectors that fix CRDT bytes: the anchors crossing fixture and the §7/§8 frames. */
class SpecificationVectorsTest {
    private fun vector(relative: String): YAny.Obj {
        val file = File(TestPaths.specification, "vectors/$relative")
        if (!file.isFile) fail("the specification vector $file is missing")
        return Json.parse(file.readText()) as YAny.Obj
    }

    private fun YAny?.str(): String = (this as? YAny.Str)?.value ?: fail("expected a string, got $this")

    private fun YAny?.int(): Int = (this as? YAny.Num)?.value?.toInt() ?: fail("expected a number, got $this")

    private fun YAny?.obj(): YAny.Obj = this as? YAny.Obj ?: fail("expected an object, got $this")

    private fun spacedHex(text: String): ByteArray = text.replace(" ", "").hexToBytes()

    @Test
    fun the_anchors_crossing_fixture_resolves_both_forms_and_rebuilds_both() {
        val fixture = vector("anchors/relative-position.json")
        val path = fixture["path"].str()
        val document = fixture["document"].obj()
        val update = document["update"].str().hexToBytes()

        val doc = Doc(document["client"].int().toLong())
        Updates.applyUpdate(doc, update)
        assertEquals(document["text"].str(), doc.getText(path).toString())

        // The same document written locally encodes to the fixture's bytes.
        val local = Doc(document["client"].int().toLong())
        local.getText(path).insert(0, document["text"].str())
        assertEquals(document["update"].str(), Updates.encodeStateAsUpdate(local).toHex())

        for (library in listOf("yjs", "yrs")) {
            val half = fixture[library].obj()
            val anchor = assertNotNull(RelativePosition.fromJson(half["anchor"]!!), "$library anchor")
            assertEquals(half["offset"].int(), anchor.resolve(doc, path), "$library anchor resolves")
            assertEquals(half["offset"].int(), anchor.resolve(local, path), "$library anchor resolves locally")
            // Another path never resolves it, whichever form it is in.
            assertEquals(null, anchor.resolve(doc, "src/other.rs"), "$library anchor under another path")
        }

        val rebuilt = RelativePosition.fromIndex(doc.getText(path), fixture["yjs"].obj()["offset"].int())
        assertEquals(Json.stringify(fixture["yjs"].obj()["anchor"]!!), Json.stringify(rebuilt.toJson()))
        assertEquals(Json.stringify(fixture["yrs"].obj()["anchor"]!!), Json.stringify(rebuilt.toYrsForm().toJson()))
    }

    @Test
    fun the_anchors_crossing_fixture_agrees_with_real_yjs() {
        val fixture = vector("anchors/relative-position.json")
        val path = fixture["path"].str()
        val document = fixture["document"].obj()
        YjsDriver().use { yjs ->
            yjs.call("new", "doc" to "a", "clientID" to document["client"].int())
            yjs.call("insert", "doc" to "a", "path" to path, "index" to 0, "text" to document["text"].str())
            assertEquals(document["update"].str(), (yjs.call("state", "doc" to "a")["update"]).str())
            for (library in listOf("yjs", "yrs")) {
                val half = fixture[library].obj()
                val ours = RelativePosition.fromJson(half["anchor"]!!)!!.toJson()
                assertEquals(half["offset"].int(), yjs.call("resolve", "doc" to "a", "rpos" to ours)["index"].int())
            }
            val rpos = yjs.call("relpos", "doc" to "a", "path" to path, "index" to 1)["rpos"]!!
            assertEquals(Json.stringify(fixture["yjs"].obj()["anchor"]!!), Json.stringify(rpos))
        }
    }

    /** Vector 009: each connection's replica, fed the frames marked `apply`, holds the text it names. */
    @Test
    fun the_document_sync_vector_replays_on_two_replicas() {
        val steps = (vector("009-document-sync.json")["steps"] as YAny.Arr).items.map { it.obj() }
        val replicas = HashMap<String, Doc>()
        var checked = 0
        for (step in steps) {
            val conn = (step["conn"] as? YAny.Str)?.value
            when (step["op"].str()) {
                "sendBinary", "expectBinary" -> {
                    val frame = spacedHex(step["hex"].str())
                    val messages = Sync.decode(frame)
                    assertContentEquals(frame, Sync.encode(messages), "frame re-encodes byte for byte")
                    if (step["apply"] == YAny.Bool(true)) {
                        val doc = replicas.getOrPut(conn!!) { Doc(1000L + replicas.size) }
                        for (message in messages) {
                            when (message) {
                                is SyncMessage.Step2 -> Updates.applyUpdate(doc, message.update, "remote")
                                is SyncMessage.Update -> Updates.applyUpdate(doc, message.update, "remote")
                                else -> fail("an applied frame holds $message")
                            }
                        }
                    }
                }

                "expectDoc" -> {
                    val doc = replicas[conn] ?: fail("no replica for $conn")
                    assertEquals(step["text"].str(), doc.getText(step["path"].str()).toString(), "replica of $conn")
                    checked++
                }
            }
        }
        assertEquals(4, checked)
    }

    /** Vector 010: every awareness frame decodes, and applies with the states the notes attribute. */
    @Test
    fun the_awareness_vector_decodes_and_applies() {
        val steps = (vector("010-awareness.json")["steps"] as YAny.Arr).items.map { it.obj() }
        val frames = steps.filter { it["op"].str() == "sendBinary" }.map { spacedHex(it["hex"].str()) }
        assertEquals(3, frames.size)
        val awareness = Awareness(0, 15_000, 30_000) { 0L }
        for (frame in frames) {
            val messages = Sync.decode(frame)
            assertContentEquals(frame, Sync.encode(messages))
            val message = assertIs<SyncMessage.Awareness>(messages.single())
            awareness.applyUpdate(message.update, "remote")
        }
        val host = awareness.states[5] ?: fail("no state for the host's awareness client 5")
        assertEquals(YAny.Str("src/main.rs"), host.obj()["path"])
        val anchor = host.obj()["selection"].obj()["anchor"]!!
        assertNotNull(RelativePosition.fromJson(anchor))
        assertNotNull(awareness.states[9], "no state for the guest's awareness client 9")
    }

    /** Vector 008's frame is a sync step 2 cut short: a strict decoder refuses it. */
    @Test
    fun the_binary_first_frame_is_not_a_complete_message() {
        val step =
            (vector("008-binary-first-frame.json")["steps"] as YAny.Arr)
                .items
                .map { it.obj() }
                .single { it["op"].str() == "sendBinary" }
        assertFailsWith<DecodeException> { Sync.decode(spacedHex(step["hex"].str())) }
    }
}
