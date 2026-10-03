package dev.dontblameme.selvage.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RelativePositionTest {
    private val path = "src/main.rs"

    private fun anchor(json: String) = RelativePosition.fromJson(Json.parse(json))

    private fun doc(text: String): Doc = Doc(5).also { it.getText(path).insert(0, text) }

    @Test
    fun anchors_are_read_leniently_and_refused_when_unreadable() {
        assertEquals(
            RelativePosition(null, path, ID(5, 1), 0),
            anchor("""{"tname":"src/main.rs","item":{"client":5,"clock":1},"assoc":0}"""),
        )
        assertEquals(RelativePosition(null, null, ID(5, 1), 0), anchor("""{"item":{"client":5,"clock":1}}"""))
        assertEquals(
            RelativePosition(null, path, null, -1),
            anchor("""{"tname":"src/main.rs","assoc":-7,"extra":[1]}"""),
        )
        assertEquals(0, anchor("""{"tname":"src/main.rs","assoc":3}""")!!.assoc)
        assertEquals(RelativePosition(ID(1, 2), null, null, 0), anchor("""{"type":{"client":1,"clock":2}}"""))
        val refused =
            listOf(
                "[]",
                "1",
                "{}",
                """{"assoc":0}""",
                """{"type":{"client":1,"clock":2},"tname":"a"}""",
                """{"tname":5}""",
                """{"item":{"client":-1,"clock":0}}""",
                """{"item":{"client":1.5,"clock":0}}""",
                """{"item":{"client":1,"clock":9007199254740992}}""",
                """{"item":{"client":1}}""",
                """{"item":[1,2]}""",
                """{"tname":"a","assoc":"0"}""",
            )
        for (json in refused) assertNull(anchor(json), json)
    }

    @Test
    fun anchors_write_their_members_in_yjs_order() {
        val position = RelativePosition(null, path, ID(5, 1), -1)
        assertEquals(
            """{"tname":"src/main.rs","item":{"client":5,"clock":1},"assoc":-1}""",
            Json.stringify(position.toJson()),
        )
        assertEquals("""{"item":{"client":5,"clock":1},"assoc":-1}""", Json.stringify(position.toYrsForm().toJson()))
        val end = RelativePosition(null, path, null, 0)
        assertEquals(end, end.toYrsForm())
        assertEquals(
            """{"type":{"client":1,"clock":2},"assoc":0}""",
            Json.stringify(RelativePosition(ID(1, 2), null, null).toJson()),
        )
    }

    @Test
    fun positions_from_an_index_resolve_back_to_it() {
        val doc = doc("ab")
        doc.getText(path).insert(2, "cd")
        doc.getText(path).insert(0, "😀")
        val text = doc.getText(path)
        for (assoc in listOf(0, -1)) {
            for (index in 0..text.length) {
                val position = RelativePosition.fromIndex(text, index, assoc)
                assertEquals(index, position.resolve(doc, path), "index $index assoc $assoc")
                assertEquals(index, position.toYrsForm().resolve(doc, path), "yrs form, index $index assoc $assoc")
            }
        }
        assertEquals(RelativePosition(null, path, null, 0), RelativePosition.fromIndex(text, text.length))
        assertEquals(RelativePosition(null, path, null, -1), RelativePosition.fromIndex(text, 0, -1))
    }

    @Test
    fun a_position_follows_its_element_through_edits() {
        val doc = doc("abc")
        val text = doc.getText(path)
        val atB = RelativePosition.fromIndex(text, 1)
        val end = RelativePosition.fromIndex(text, 3)
        text.insert(0, ">>")
        assertEquals(3, atB.resolve(doc, path))
        assertEquals(5, end.resolve(doc, path))
        // With its element deleted a position falls to where the element was.
        text.delete(3, 1)
        assertEquals(3, atB.resolve(doc, path))
    }

    /** §8.1.1: what a receiver does not resolve, and what a scope alone denotes. */
    @Test
    fun resolution_follows_the_protocol_rules() {
        val doc = doc("abc")
        doc.getText("src/other.rs").insert(0, "zz")
        val other = RelativePosition.fromIndex(doc.getText("src/other.rs"), 1)
        // A nested type is never a document's text.
        assertNull(RelativePosition(ID(5, 0), null, null).resolve(doc, path))
        // The scope must name the path, and the element must live in the text of the path.
        assertNull(RelativePosition(null, "src/other.rs", ID(5, 1)).resolve(doc, path))
        assertNull(other.resolve(doc, path))
        assertNull(other.toYrsForm().resolve(doc, path))
        assertEquals(1, other.resolve(doc, "src/other.rs"))
        // An element the replica has not seen does not resolve yet.
        assertNull(RelativePosition(null, path, ID(5, 99)).resolve(doc, path))
        assertNull(RelativePosition(null, path, ID(77, 0)).resolve(doc, path))
        // A scope alone is an end of the text.
        assertEquals(3, RelativePosition(null, path, null, 0).resolve(doc, path))
        assertEquals(0, RelativePosition(null, path, null, -1).resolve(doc, path))
        // §8.1.1: a replica with no text at the path resolves no anchor, a scope alone included,
        // and does not create the text to try.
        for (assoc in listOf(0, -1)) {
            assertNull(RelativePosition(null, "src/new.rs", null, assoc).resolve(doc, "src/new.rs"))
        }
        assertFalse("src/new.rs" in doc.rootNames)
    }
}
