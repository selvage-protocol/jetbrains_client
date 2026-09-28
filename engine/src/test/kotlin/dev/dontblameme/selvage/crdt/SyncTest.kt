package dev.dontblameme.selvage.crdt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncTest {
    @Test
    fun each_message_encodes_as_y_protocols_writes_it() {
        assertEquals("00000100", Sync.encode(SyncMessage.Step1(byteArrayOf(0))).toHex())
        assertEquals("0001020000", Sync.encode(SyncMessage.Step2("0000".hexToBytes())).toHex())
        assertEquals("000200", Sync.encode(SyncMessage.Update(ByteArray(0))).toHex())
        assertEquals("010100", Sync.encode(SyncMessage.Awareness(byteArrayOf(0))).toHex())
        assertEquals("0200026e6f", Sync.encode(SyncMessage.Auth(0, "no")).toHex())
        assertEquals("0201", Sync.encode(SyncMessage.Auth(1, null)).toHex())
        assertEquals("03", Sync.encode(SyncMessage.QueryAwareness).toHex())
    }

    @Test
    fun a_frame_holds_several_messages_in_order() {
        val frame = "00000100" + "0001020000" + "03" + "0200026e6f" + "010100" + "000200"
        val messages = Sync.decode(frame.hexToBytes())
        assertEquals(6, messages.size)
        assertContentEquals(byteArrayOf(0), assertIs<SyncMessage.Step1>(messages[0]).stateVector)
        assertContentEquals("0000".hexToBytes(), assertIs<SyncMessage.Step2>(messages[1]).update)
        assertEquals(SyncMessage.QueryAwareness, messages[2])
        assertEquals("no", assertIs<SyncMessage.Auth>(messages[3]).reason)
        assertIs<SyncMessage.Awareness>(messages[4])
        assertIs<SyncMessage.Update>(messages[5])
        assertEquals(frame, Sync.encode(messages).toHex())
        assertTrue(Sync.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun unknown_and_truncated_messages_are_refused() {
        for (frame in listOf("04", "7f", "0003", "0000", "000002", "0101", "0200", "000001" + "00" + "04")) {
            assertFailsWith<DecodeException>(frame) { Sync.decode(frame.hexToBytes()) }
        }
    }

    @Test
    fun a_sync_exchange_brings_two_replicas_level() {
        val a = Doc(1).also { it.getText("f").insert(0, "from a") }
        val b = Doc(2).also { it.getText("f").insert(0, "from b ") }
        // a -> b: step 1; b -> a: step 2 and b's own step 1; a -> b: step 2.
        val step1 = Sync.encode(SyncMessage.Step1(Updates.encodeStateVector(a)))
        val reply =
            Sync.decode(step1).flatMap { message ->
                val sv = assertIs<SyncMessage.Step1>(message).stateVector
                listOf(
                    SyncMessage.Step2(Updates.encodeStateAsUpdate(b, sv)),
                    SyncMessage.Step1(Updates.encodeStateVector(b)),
                )
            }
        val answer = ArrayList<SyncMessage>()
        for (message in Sync.decode(Sync.encode(reply))) {
            when (message) {
                is SyncMessage.Step2 -> {
                    Updates.applyUpdate(a, message.update, "remote")
                }

                is SyncMessage.Step1 -> {
                    answer.add(
                        SyncMessage.Step2(Updates.encodeStateAsUpdate(a, message.stateVector)),
                    )
                }

                else -> {
                    error("unexpected $message")
                }
            }
        }
        for (message in Sync.decode(Sync.encode(answer))) Updates.applyUpdate(b, (message as SyncMessage.Step2).update)
        assertEquals(a.getText("f").toString(), b.getText("f").toString())
        assertEquals("from afrom b ", a.getText("f").toString())
    }
}
