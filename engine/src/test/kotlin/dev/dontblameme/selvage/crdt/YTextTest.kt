package dev.dontblameme.selvage.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YTextTest {
    private val path = "src/main.rs"

    /** Every update [from] has that [to] lacks, as one state update. */
    private fun sync(
        from: Doc,
        to: Doc,
    ) = Updates.applyUpdate(to, Updates.encodeStateAsUpdate(from, Updates.encodeStateVector(to)), "remote")

    private fun Doc.text() = getText(path).toString()

    @Test
    fun inserts_and_deletes_count_utf16_code_units() {
        val doc = Doc(1)
        val text = doc.getText(path)
        text.insert(0, "a😀c")
        assertEquals(4, text.length)
        text.insert(3, "b")
        assertEquals("a😀bc", text.toString())
        text.delete(1, 2)
        assertEquals("abc", text.toString())
        text.delete(0, 3)
        assertEquals("", text.toString())
        assertEquals(0, text.length)
    }

    @Test
    fun out_of_range_edits_throw_and_empty_ones_change_nothing() {
        val doc = Doc(1)
        val text = doc.getText(path)
        text.insert(0, "ab")
        val updates = ArrayList<ByteArray>()
        doc.onUpdate { update, _, _ -> updates.add(update) }
        assertFailsWith<IndexOutOfBoundsException> { text.insert(3, "x") }
        assertFailsWith<IndexOutOfBoundsException> { text.insert(-1, "x") }
        assertFailsWith<IndexOutOfBoundsException> { text.delete(1, 2) }
        assertFailsWith<IndexOutOfBoundsException> { text.delete(-1, 1) }
        assertFailsWith<IndexOutOfBoundsException> { text.delete(0, -1) }
        text.insert(1, "")
        text.delete(1, 0)
        assertEquals("ab", text.toString())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun splitting_a_surrogate_pair_replaces_both_halves_as_yjs_does() {
        val doc = Doc(1)
        val text = doc.getText(path)
        text.insert(0, "a😀b")
        text.delete(1, 1)
        assertEquals("a�b", text.toString())
        val peer = Doc(2)
        sync(doc, peer)
        assertEquals("a�b", peer.text())
    }

    @Test
    fun observers_see_the_delta_origin_and_locality_of_each_transaction() {
        val doc = Doc(1)
        val text = doc.getText(path)
        text.insert(0, "hello")
        val events = ArrayList<TextEvent>()
        val unobserve = text.observe { events.add(it) }
        text.insert(2, "XY", origin = "typing")
        text.delete(1, 3)
        doc.transact("both") {
            text.insert(0, ">")
            text.delete(2, 1)
        }
        assertEquals(listOf(TextDelta.Retain(2), TextDelta.Insert("XY")), events[0].delta)
        assertEquals("typing", events[0].origin)
        assertTrue(events[0].local)
        assertEquals(listOf(TextDelta.Retain(1), TextDelta.Delete(3)), events[1].delta)
        assertEquals(listOf(TextDelta.Insert(">"), TextDelta.Retain(1), TextDelta.Delete(1)), events[2].delta)

        val peer = Doc(2)
        val remote = ArrayList<TextEvent>()
        peer.getText(path).observe { remote.add(it) }
        sync(doc, peer)
        assertEquals(listOf(TextDelta.Insert(">hlo")), remote.single().delta)
        assertFalse(remote.single().local)
        assertEquals("remote", remote.single().origin)

        unobserve()
        text.insert(0, "z")
        assertEquals(3, events.size)
    }

    /** YATA's first rule: concurrent inserts with one left origin order by client id, lowest first. */
    @Test
    fun concurrent_inserts_at_one_place_order_by_client_id() {
        val docs = listOf(Doc(3), Doc(1), Doc(2))
        docs.forEach { it.getText(path).insert(0, "<${it.clientID}>") }
        for (a in docs) for (b in docs) if (a !== b) sync(a, b)
        for (doc in docs) assertEquals("<1><2><3>", doc.text())
    }

    /**
     * YATA's second rule: an insert whose origin lies inside a concurrent run stays inside it.
     * Client 2 types `b` after client 1's `a` while client 3, having seen neither, types `z` at
     * the start: `b` follows `a` on every replica, whatever order they arrive in.
     */
    @Test
    fun a_run_typed_after_a_concurrent_insert_converges() {
        val one = Doc(1)
        val two = Doc(2)
        val three = Doc(3)
        one.getText(path).insert(0, "a")
        sync(one, two)
        two.getText(path).insert(1, "b")
        three.getText(path).insert(0, "z")
        sync(three, two)
        sync(two, three)
        sync(two, one)
        assertEquals("abz", three.text())
        assertEquals("abz", two.text())
        assertEquals("abz", one.text())
    }

    @Test
    fun an_insert_goes_after_deleted_items_at_its_place() {
        val one = Doc(1)
        one.getText(path).insert(0, "abc")
        one.getText(path).delete(1, 1)
        one.getText(path).insert(1, "X")
        val two = Doc(2)
        sync(one, two)
        assertEquals("aXc", two.text())
        two.getText(path).insert(2, "Y")
        sync(two, one)
        assertEquals("aXYc", one.text())
    }
}
