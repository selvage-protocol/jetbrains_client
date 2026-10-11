package dev.dontblameme.selvage.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EditorTextTest {
    private val path = "a.txt"

    private fun doc(
        text: String,
        gc: Boolean = true,
    ): Doc = Doc(1, gc).also { it.getText(path).insert(0, text) }

    @Test
    fun `observed deletes survive item splits and leave unseen insertions`() {
        for (gc in listOf(false, true)) {
            val doc = doc("abcdef", gc)
            val editor = EditorText.capture(doc, path).replace(1, 5, "!")
            doc.getText(path).insert(3, "R")
            doc.getText(path).delete(1, 1)
            val applied = assertIs<EditorCommit.Applied>(editor.commit(doc, "acRdef", 1024))
            assertEquals("a!Rf", applied.editor.text)
            assertEquals("a!f", applied.visible.text)
            assertEquals("a!Rf", doc.getText(path).toString())
        }
    }

    @Test
    fun `an insertion anchor still resolves after its content is collected and merged`() {
        val doc = doc("abcdef")
        val editor = EditorText.capture(doc, path).replace(3, 3, "!")
        doc.getText(path).delete(1, 4)
        assertTrue(
            doc.store.clients
                .getValue(1)
                .any { it is Item && it.content is ContentDeleted },
        )
        val applied = assertIs<EditorCommit.Applied>(editor.commit(doc, "af", 1024))
        assertEquals("a!f", applied.editor.text)
    }

    @Test
    fun `a basis from another replica cannot mutate matching client clocks`() {
        val first = doc("aaa")
        val second = doc("aaa")
        var publications = 0
        second.onUpdate { _, _, _ -> publications++ }
        val editor = EditorText.capture(first, path).replace(0, 1, "wrong")
        assertEquals(EditorCommit.Refused, editor.commit(second, "aaa", 1024))
        assertEquals("aaa", second.getText(path).toString())
        assertEquals(0, publications)
    }

    @Test
    fun `same text with new identities cannot exceed the merged byte bound`() {
        val doc = doc("AAAA")
        val editor = EditorText.capture(doc, path).replace(0, 4, "BBBB")
        doc.getText(path).delete(0, 4)
        doc.getText(path).insert(0, "AAAA")
        var publications = 0
        doc.onUpdate { _, _, _ -> publications++ }
        assertEquals(EditorCommit.OverBound, editor.commit(doc, "AAAA", 4))
        assertEquals("AAAA", doc.getText(path).toString())
        assertEquals(0, publications)
        val applied = assertIs<EditorCommit.Applied>(editor.commit(doc, "AAAA", 8))
        assertEquals("BBBBAAAA", applied.editor.text)
        assertEquals(1, publications)
    }

    @Test
    fun `the merged bound counts UTF-8 bytes not only UTF-16 units`() {
        val doc = doc("a")
        val editor = EditorText.capture(doc, path).replace(1, 1, "😀")
        assertEquals(EditorCommit.OverBound, editor.commit(doc, "a", 4))
        assertEquals("a", doc.getText(path).toString())
        assertEquals("a😀", assertIs<EditorCommit.Applied>(editor.commit(doc, "a", 5)).editor.text)
    }

    @Test
    fun `a repaired surrogate replacement publishes only whole characters`() {
        val doc = doc("a😀b")
        val editor = EditorText.capture(doc, path).replace(2, 3, "")
        assertEquals(EditorCommit.Refused, editor.commit(doc, "a😀b", 1024))
        val repaired = editor.replace(2, 2, "\uDE01")
        val updates = ArrayList<ByteArray>()
        doc.onUpdate { update, _, _ -> updates.add(update) }
        val applied = assertIs<EditorCommit.Applied>(repaired.commit(doc, "a😀b", 1024))
        assertEquals("a😁b", applied.editor.text)
        val basis = doc("a😀b")
        val replay = Doc(3)
        Updates.applyUpdate(replay, Updates.encodeStateAsUpdate(basis))
        updates.forEach { Updates.applyUpdate(replay, it) }
        assertEquals("a😁b", replay.getText(path).toString())
    }

    @Test
    fun `editor state retains only its path and live local insertions`() {
        val doc = doc("aaa")
        doc.getText("unrelated.txt").insert(0, "u".repeat(900 * 1024))
        val editor = EditorText.capture(doc, path).replace(1, 1, "x".repeat(10000)).replace(1, 10001, "!")
        assertEquals(4, editor.retainedLength)
        assertEquals("a!aa", assertIs<EditorCommit.Applied>(editor.commit(doc, "aaa", 1024)).editor.text)
        assertEquals("u".repeat(900 * 1024), doc.getText("unrelated.txt").toString())
    }

    @Test
    fun `capturing an absent path does not create a root`() {
        val doc = Doc(1)
        val editor = EditorText.capture(doc, path)
        assertFalse(path in doc.rootNames)
        assertEquals("", editor.text)
        assertEquals(
            "new",
            assertIs<EditorCommit.Applied>(editor.replace(0, 0, "new").commit(doc, "", 1024)).editor.text,
        )
    }
}
