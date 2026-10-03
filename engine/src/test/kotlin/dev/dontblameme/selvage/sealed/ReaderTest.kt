package dev.dontblameme.selvage.sealed

import dev.dontblameme.selvage.crdt.Doc
import dev.dontblameme.selvage.crdt.Sync
import dev.dontblameme.selvage.crdt.SyncMessage
import dev.dontblameme.selvage.crdt.Updates
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderTest {
    private val update = Updates.encodeStateAsUpdate(Doc(7).also { it.getText("README.md").insert(0, "viewer text") })
    private val anUpdate = Sync.encode(SyncMessage.Update(update))
    private val aStep2 = Sync.encode(SyncMessage.Step2(update))
    private val aStep1 = Sync.encode(SyncMessage.Step1(byteArrayOf(0)))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Step 10 reads content wherever §7's walk finds it, whatever stands in front of it. */
    @Test
    fun `content is found behind every message §7 defines`() {
        val carrying =
            mapOf(
                "auth status 1, then an Update" to bytes(2, 1) + anUpdate,
                "auth status 0 and a reason, then an Update" to Sync.encode(SyncMessage.Auth(0, "no")) + anUpdate,
                "auth status 0 and an empty reason, then a Step2" to Sync.encode(SyncMessage.Auth(0, "")) + aStep2,
                "two auth messages, then an Update" to bytes(2, 5, 2, 7) + anUpdate,
                "a two-byte auth status, then an Update" to Sync.encode(SyncMessage.Auth(300, null)) + anUpdate,
                "an awareness query, then a Step2" to bytes(3) + aStep2,
                "a Step1 and an auth, then an Update" to aStep1 + bytes(2, 1) + anUpdate,
                "an Update, then a message cut short" to anUpdate + bytes(0, 1, 5),
            )
        for ((shape, plaintext) in carrying) assertEquals(true, Reader.isContent(plaintext), shape)
    }

    @Test
    fun `a stream without content is not content, however it is spelled`() {
        val bare =
            mapOf(
                "auth status 1, then a Step1" to bytes(2, 1) + aStep1,
                "auth status 0 whose reason spells an Update" to Sync.encode(SyncMessage.Auth(0, "\u0000\u0002")),
                "an Update after a sync_type §7 does not define" to bytes(0, 3) + anUpdate,
                "an Update after a message_type §7 does not define" to bytes(4) + anUpdate,
                "a Step1, then an Update cut short" to aStep1 + anUpdate.copyOf(anUpdate.size - 1),
            )
        for ((shape, plaintext) in bare) assertEquals(false, Reader.isContent(plaintext), shape)
    }
}
