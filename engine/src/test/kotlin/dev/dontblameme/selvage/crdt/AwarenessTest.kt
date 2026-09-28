package dev.dontblameme.selvage.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwarenessTest {
    private var clock = 1_000L
    private val renew = 15_000L
    private val expire = 30_000L

    private fun awareness(client: Long) = Awareness(client, renew, expire) { clock }

    private data class Seen(
        val change: AwarenessChange,
        val origin: Any?,
    )

    private fun Awareness.changes(): MutableList<Seen> =
        ArrayList<Seen>().also { list -> onChange { c, o -> list.add(Seen(c, o)) } }

    private fun Awareness.updates(): MutableList<Seen> =
        ArrayList<Seen>().also { list -> onUpdate { c, o -> list.add(Seen(c, o)) } }

    private fun state(path: String) = YAny.Obj.of("path" to YAny.Str(path))

    @Test
    fun a_new_awareness_publishes_an_empty_state_at_clock_zero() {
        val a = awareness(5)
        assertEquals(YAny.Obj.of(), a.localState)
        assertEquals(Awareness.Meta(0, clock), a.meta[5])
        assertEquals("01050002" + "7b7d", a.encodeUpdate().toHex())
    }

    @Test
    fun local_changes_bump_the_clock_and_report_only_real_changes() {
        val a = awareness(5)
        val changes = a.changes()
        val updates = a.updates()
        a.setLocalState(state("a"))
        a.setLocalState(state("a"))
        a.setLocalState(null)
        assertEquals(3L, a.meta[5]!!.clock)
        assertNull(a.localState)
        assertEquals(
            listOf(
                Seen(AwarenessChange(emptyList(), listOf(5), emptyList()), Awareness.LOCAL),
                Seen(AwarenessChange(emptyList(), emptyList(), listOf(5)), Awareness.LOCAL),
            ),
            changes,
        )
        assertEquals(3, updates.size)
    }

    @Test
    fun remote_states_apply_only_on_a_newer_clock_or_a_removal_at_the_same_one() {
        val remote = awareness(9)
        remote.setLocalState(state("r"))
        val a = awareness(5)
        val changes = a.changes()
        val stale = remote.encodeUpdate()
        remote.setLocalState(state("s"))
        a.applyUpdate(remote.encodeUpdate(), "remote")
        a.applyUpdate(stale, "remote")
        assertEquals(state("s"), a.states[9])
        assertEquals(2L, a.meta[9]!!.clock)

        // null at the same clock removes the state.
        val removal =
            Lib0Encoder()
                .apply {
                    writeVarUint(1)
                    writeVarUint(9)
                    writeVarUint(2)
                    writeVarString("null")
                }.toByteArray()
        a.applyUpdate(removal, "remote")
        assertNull(a.states[9])
        assertEquals(
            listOf(
                AwarenessChange(listOf(9), emptyList(), emptyList()),
                AwarenessChange(emptyList(), emptyList(), listOf(9)),
            ),
            changes.map { it.change },
        )
        assertTrue(changes.all { it.origin == "remote" })
    }

    @Test
    fun a_remote_removal_of_the_local_state_is_answered_with_a_newer_clock() {
        val a = awareness(5)
        val removal =
            Lib0Encoder()
                .apply {
                    writeVarUint(1)
                    writeVarUint(5)
                    writeVarUint(4)
                    writeVarString("null")
                }.toByteArray()
        a.applyUpdate(removal, "remote")
        assertEquals(YAny.Obj.of(), a.localState)
        assertEquals(5L, a.meta[5]!!.clock)
    }

    @Test
    fun tick_renews_the_local_state_and_expires_silent_peers() {
        val a = awareness(5)
        val remote = awareness(9)
        // As in y-protocols, a state at clock 0 from an unknown client is not newer than nothing.
        a.applyUpdate(remote.encodeUpdate(), "remote")
        assertNull(a.states[9])
        remote.setLocalState(YAny.Obj.of())
        a.applyUpdate(remote.encodeUpdate(), "remote")
        val changes = a.changes()

        clock += renew - 1
        a.tick()
        assertEquals(0L, a.meta[5]!!.clock)
        clock += 1
        a.tick()
        assertEquals(1L, a.meta[5]!!.clock)
        assertEquals(clock, a.meta[5]!!.lastUpdated)
        assertTrue(changes.isEmpty(), "a renewal with the same state is no change")

        clock += expire - renew - 1
        a.tick()
        assertEquals(YAny.Obj.of(), a.states[9])
        clock += 1
        a.tick()
        assertNull(a.states[9])
        assertEquals(listOf(Seen(AwarenessChange(emptyList(), emptyList(), listOf(9)), Awareness.TIMEOUT)), changes)
        assertEquals(YAny.Obj.of(), a.localState, "the local state never expires")
    }

    @Test
    fun falsy_states_are_written_as_null_and_updates_round_trip() {
        val a = awareness(5)
        a.setLocalState(YAny.Bool(false))
        assertEquals("0105010" + "46e756c6c", a.encodeUpdate().toHex())
        a.setLocalState(YAny.Num(0.0))
        assertTrue(a.encodeUpdate().toHex().endsWith("046e756c6c"))
        a.setLocalState(YAny.Str(""))
        assertTrue(a.encodeUpdate().toHex().endsWith("046e756c6c"))

        a.setLocalState(state("src/😀.rs"))
        val b = awareness(9)
        b.applyUpdate(a.encodeUpdate())
        assertEquals(state("src/😀.rs"), b.states[5])
        assertFailsWith<IllegalArgumentException> { a.encodeUpdate(listOf(77L)) }
        assertFailsWith<DecodeException> { b.applyUpdate("0105".hexToBytes()) }
        assertFailsWith<JsonException> { b.applyUpdate("010906027b7b".hexToBytes()) }
    }

    @Test
    fun removing_the_local_state_bumps_its_clock() {
        val a = awareness(5)
        a.removeStates(listOf(5, 42), "gone")
        assertNull(a.localState)
        assertEquals(1L, a.meta[5]!!.clock)
    }
}
