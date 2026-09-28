package dev.dontblameme.selvage.crdt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    private val path = "src/main.rs"

    private class Recorder(
        doc: Doc,
    ) {
        val updates = ArrayList<ByteArray>()
        val origins = ArrayList<Any?>()
        val locals = ArrayList<Boolean>()

        init {
            doc.onUpdate { update, origin, local ->
                updates.add(update)
                origins.add(origin)
                locals.add(local)
            }
        }
    }

    @Test
    fun a_state_update_rebuilds_the_replica() {
        val doc = Doc(7)
        doc.getText(path).insert(0, "hello world")
        doc.getText(path).delete(0, 6)
        doc.getText("other").insert(0, "x")
        val copy = Doc(8)
        Updates.applyUpdate(copy, Updates.encodeStateAsUpdate(doc))
        assertEquals("world", copy.getText(path).toString())
        assertEquals("x", copy.getText("other").toString())
        assertEquals(doc.store.stateVector(), copy.store.stateVector())
        assertContentEquals(Updates.encodeStateAsUpdate(doc), Updates.encodeStateAsUpdate(copy))
    }

    @Test
    fun the_fixture_update_decodes_to_its_parts() {
        val update = "0101050004010b7372632f6d61696e2e72730361626300".hexToBytes()
        val decoded = Updates.decodeUpdate(update)
        assertEquals(1, decoded.structs.size)
        assertEquals(ID(5, 0), decoded.structs[0].id)
        assertEquals(3, decoded.structs[0].length)
        assertTrue(decoded.deleteSet.clients.isEmpty())
        assertEquals(mapOf(5L to 3L), Updates.decodeStateVector(Updates.encodeStateVector(mapOf(5L to 3L))))
    }

    @Test
    fun update_listeners_get_one_update_per_transaction_with_its_origin() {
        val doc = Doc(1)
        val recorder = Recorder(doc)
        doc.getText(path).insert(0, "ab", origin = "me")
        doc.transact("both") {
            doc.getText(path).insert(2, "c")
            doc.getText(path).delete(0, 1)
        }
        assertEquals(2, recorder.updates.size)
        assertEquals(listOf<Any?>("me", "both"), recorder.origins)
        assertEquals(listOf(true, true), recorder.locals)

        val peer = Doc(2)
        val peerRecorder = Recorder(peer)
        recorder.updates.forEach { Updates.applyUpdate(peer, it, "remote") }
        assertEquals("bc", peer.getText(path).toString())
        assertEquals(listOf(false, false), peerRecorder.locals)
        // Re-applying what the replica already has changes nothing and emits nothing.
        recorder.updates.forEach { Updates.applyUpdate(peer, it, "remote") }
        assertEquals(2, peerRecorder.updates.size)
    }

    @Test
    fun an_update_that_arrives_early_waits_for_the_one_before_it() {
        val doc = Doc(1)
        val recorder = Recorder(doc)
        doc.getText(path).insert(0, "a")
        doc.getText(path).insert(1, "b")
        doc.getText(path).insert(2, "c")
        val (u1, u2, u3) = recorder.updates

        val peer = Doc(2)
        Updates.applyUpdate(peer, u3)
        Updates.applyUpdate(peer, u2)
        assertEquals("", peer.getText(path).toString())
        assertNotNull(peer.store.pendingStructs)
        assertEquals(emptyMap<Long, Long>(), peer.store.stateVector())
        Updates.applyUpdate(peer, u1)
        assertEquals("abc", peer.getText(path).toString())
        assertNull(peer.store.pendingStructs)
    }

    @Test
    fun a_delete_of_unknown_content_waits_for_the_content() {
        val doc = Doc(1)
        val recorder = Recorder(doc)
        doc.getText(path).insert(0, "abc")
        doc.getText(path).delete(1, 1)
        val (insert, delete) = recorder.updates

        val peer = Doc(2)
        Updates.applyUpdate(peer, delete)
        assertNotNull(peer.store.pendingDs)
        Updates.applyUpdate(peer, insert)
        assertEquals("ac", peer.getText(path).toString())
        assertNull(peer.store.pendingDs)
    }

    @Test
    fun a_state_update_against_a_state_vector_carries_only_what_is_missing() {
        val doc = Doc(1)
        doc.getText(path).insert(0, "abc")
        val peer = Doc(2)
        Updates.applyUpdate(peer, Updates.encodeStateAsUpdate(doc))
        val sv = Updates.encodeStateVector(peer)
        doc.getText(path).insert(3, "d")
        peer.getText(path).insert(0, ">")
        val missing = Updates.encodeStateAsUpdate(doc, sv)
        assertEquals(listOf(ID(1, 3)), Updates.decodeUpdate(missing).structs.map { it.id })
        Updates.applyUpdate(peer, missing)
        assertEquals(">abcd", peer.getText(path).toString())
        assertContentEquals(missing, Updates.diffUpdate(Updates.encodeStateAsUpdate(doc), sv))
    }

    @Test
    fun merged_updates_apply_as_their_parts() {
        val doc = Doc(1)
        val recorder = Recorder(doc)
        doc.getText(path).insert(0, "abc")
        doc.getText(path).delete(0, 1)
        doc.getText(path).insert(2, "d")
        val merged = Updates.mergeUpdates(recorder.updates)
        val peer = Doc(2)
        Updates.applyUpdate(peer, merged)
        assertEquals("bcd", peer.getText(path).toString())
        assertContentEquals(Updates.mergeUpdates(listOf(merged)), merged)
    }

    @Test
    fun malformed_updates_are_refused() {
        val doc = Doc(1)
        for (bad in listOf("", "01", "0101", "01010500", "0101050004010b7372632f6d61696e2e7273036162")) {
            assertFailsWith<DecodeException>(bad) { Updates.applyUpdate(doc, bad.hexToBytes()) }
        }
        assertEquals("", doc.getText(path).toString())
        assertEquals(emptyMap<Long, Long>(), doc.store.stateVector())
    }

    /**
     * Client 9's "ok" at the root, then client 5's item 5:0 whose [dependency] names its own client
     * at its own clock or later. yjs integrates client 9 first and then throws a TypeError, with
     * "ok" left applied.
     */
    private fun ownClientDependency(dependency: String): ByteArray =
        Lib0Encoder()
            .apply {
                writeVarUint(2)
                writeVarUint(1)
                writeVarUint(9)
                writeVarUint(0)
                writeUint8(Content.STRING)
                writeVarUint(1)
                writeVarString("t")
                writeVarString("ok")
                writeVarUint(1)
                writeVarUint(5)
                writeVarUint(0)
                when (dependency) {
                    "origin" -> {
                        writeUint8(Struct.BIT8 or Content.STRING)
                        writeVarUint(5)
                        writeVarUint(3)
                    }

                    "rightOrigin" -> {
                        writeUint8(Struct.BIT7 or Content.STRING)
                        writeVarUint(5)
                        writeVarUint(0)
                    }

                    else -> {
                        writeUint8(Content.STRING)
                        writeVarUint(0)
                        writeVarUint(5)
                        writeVarUint(0)
                    }
                }
                writeVarString("x")
                writeVarUint(0)
            }.toByteArray()

    @Test
    fun a_dependency_on_the_items_own_client_at_or_after_it_is_refused_before_anything_integrates() {
        for (dependency in listOf("origin", "rightOrigin", "parentID")) {
            val doc = Doc(1)
            val recorder = Recorder(doc)
            assertFailsWith<DecodeException>(dependency) { Updates.applyUpdate(doc, ownClientDependency(dependency)) }
            assertEquals("", doc.getText("t").toString(), dependency)
            assertEquals(emptyMap<Long, Long>(), doc.store.stateVector(), dependency)
            assertTrue(recorder.updates.isEmpty(), dependency)
        }
    }

    @Test
    fun the_delete_set_merges_adjacent_and_overlapping_ranges() {
        val ds = DeleteSet()
        ds.add(1, 5, 2)
        ds.add(1, 0, 2)
        ds.add(1, 2, 3)
        ds.add(1, 10, 1)
        ds.add(2, 0, 1)
        ds.sortAndMerge()
        assertEquals(listOf(DeleteItem(0L, 7L), DeleteItem(10L, 1L)), ds.clients.getValue(1L).toList())
        assertTrue(ds.isDeleted(ID(1, 6)))
        assertFalse(ds.isDeleted(ID(1, 7)))
        assertTrue(ds.isDeleted(ID(1, 10)))
        assertFalse(ds.isDeleted(ID(3, 0)))
    }
}
