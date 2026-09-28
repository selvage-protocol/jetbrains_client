package dev.dontblameme.selvage.crdt

/** One message of a §7 binary frame. */
sealed interface SyncMessage {
    /** SyncStep1: the sender's state vector. */
    class Step1(
        val stateVector: ByteArray,
    ) : SyncMessage

    /** SyncStep2: what the receiver of a step 1 is missing, as a v1 update. */
    class Step2(
        val update: ByteArray,
    ) : SyncMessage

    /** A local change, as a v1 update. */
    class Update(
        val update: ByteArray,
    ) : SyncMessage

    class Awareness(
        val update: ByteArray,
    ) : SyncMessage

    /** y-protocols' auth message: a status, and a reason when the status is 0 (permission denied). */
    class Auth(
        val status: Long,
        val reason: String?,
    ) : SyncMessage

    data object QueryAwareness : SyncMessage
}

/**
 * The §7 frame codec: `varUint(message_type)` and a body per message, several messages per frame
 * with no count and no terminator.
 */
object Sync {
    const val MESSAGE_SYNC = 0L
    const val MESSAGE_AWARENESS = 1L
    const val MESSAGE_AUTH = 2L
    const val MESSAGE_QUERY_AWARENESS = 3L

    const val SYNC_STEP1 = 0L
    const val SYNC_STEP2 = 1L
    const val SYNC_UPDATE = 2L

    fun encode(vararg messages: SyncMessage): ByteArray = encode(messages.asList())

    fun encode(messages: List<SyncMessage>): ByteArray {
        val encoder = Lib0Encoder()
        for (message in messages) {
            when (message) {
                is SyncMessage.Step1 -> {
                    sync(encoder, SYNC_STEP1, message.stateVector)
                }

                is SyncMessage.Step2 -> {
                    sync(encoder, SYNC_STEP2, message.update)
                }

                is SyncMessage.Update -> {
                    sync(encoder, SYNC_UPDATE, message.update)
                }

                is SyncMessage.Awareness -> {
                    encoder.writeVarUint(MESSAGE_AWARENESS)
                    encoder.writeVarUint8Array(message.update)
                }

                is SyncMessage.Auth -> {
                    encoder.writeVarUint(MESSAGE_AUTH)
                    encoder.writeVarUint(message.status)
                    if (message.status == 0L) encoder.writeVarString(message.reason ?: "")
                }

                SyncMessage.QueryAwareness -> {
                    encoder.writeVarUint(MESSAGE_QUERY_AWARENESS)
                }
            }
        }
        return encoder.toByteArray()
    }

    private fun sync(
        encoder: Lib0Encoder,
        syncType: Long,
        payload: ByteArray,
    ) {
        encoder.writeVarUint(MESSAGE_SYNC)
        encoder.writeVarUint(syncType)
        encoder.writeVarUint8Array(payload)
    }

    /** Every message of [frame]; throws [DecodeException] on an unknown or truncated one. */
    fun decode(frame: ByteArray): List<SyncMessage> {
        val decoder = Lib0Decoder(frame)
        val messages = ArrayList<SyncMessage>()
        while (decoder.hasContent()) {
            messages +=
                when (val type = decoder.readVarUint()) {
                    MESSAGE_SYNC -> {
                        when (val syncType = decoder.readVarUint()) {
                            SYNC_STEP1 -> SyncMessage.Step1(decoder.readVarUint8Array())
                            SYNC_STEP2 -> SyncMessage.Step2(decoder.readVarUint8Array())
                            SYNC_UPDATE -> SyncMessage.Update(decoder.readVarUint8Array())
                            else -> throw DecodeException("unknown sync type $syncType")
                        }
                    }

                    MESSAGE_AWARENESS -> {
                        SyncMessage.Awareness(decoder.readVarUint8Array())
                    }

                    MESSAGE_AUTH -> {
                        val status = decoder.readVarUint()
                        SyncMessage.Auth(status, if (status == 0L) decoder.readVarString() else null)
                    }

                    MESSAGE_QUERY_AWARENESS -> {
                        SyncMessage.QueryAwareness
                    }

                    else -> {
                        throw DecodeException("unknown message type $type")
                    }
                }
        }
        return messages
    }
}
