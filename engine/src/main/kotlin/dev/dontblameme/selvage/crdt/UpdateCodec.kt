package dev.dontblameme.selvage.crdt

/** An element's identity: the client that created it and that client's clock at the time. */
data class ID(
    val client: Long,
    val clock: Long,
) {
    override fun toString() = "$client:$clock"
}

/** yjs's `UpdateEncoderV1`: the v1 update format, which is lib0 primitives in order. */
class UpdateEncoder {
    val rest = Lib0Encoder()

    fun toByteArray(): ByteArray = rest.toByteArray()

    fun writeLeftID(id: ID) {
        rest.writeVarUint(id.client)
        rest.writeVarUint(id.clock)
    }

    fun writeRightID(id: ID) = writeLeftID(id)

    fun writeClient(client: Long) = rest.writeVarUint(client)

    fun writeInfo(info: Int) = rest.writeUint8(info)

    fun writeString(s: String) = rest.writeVarString(s)

    fun writeParentInfo(isYKey: Boolean) = rest.writeVarUint(if (isYKey) 1L else 0L)

    fun writeTypeRef(ref: Int) = rest.writeVarUint(ref.toLong())

    fun writeLen(len: Int) = rest.writeVarUint(len.toLong())

    fun writeAny(value: YAny) = YAny.write(rest, value)

    fun writeBuf(bytes: ByteArray) = rest.writeVarUint8Array(bytes)

    fun writeJson(value: YAny) =
        rest.writeVarString(Json.stringify(value) ?: throw JsonException("undefined is not JSON"))

    fun writeKey(key: String) = rest.writeVarString(key)

    fun writeDsClock(clock: Long) = rest.writeVarUint(clock)

    fun writeDsLen(len: Long) = rest.writeVarUint(len)
}

/** yjs's `UpdateDecoderV1`. */
class UpdateDecoder(
    val rest: Lib0Decoder,
) {
    constructor(bytes: ByteArray) : this(Lib0Decoder(bytes))

    fun readLeftID() = ID(rest.readVarUint(), rest.readVarUint())

    fun readRightID() = readLeftID()

    fun readClient() = rest.readVarUint()

    fun readInfo() = rest.readUint8()

    fun readString() = rest.readVarString()

    fun readParentInfo() = rest.readVarUint() == 1L

    fun readTypeRef() = rest.readLength()

    fun readLen() = rest.readLength()

    /** A [readLen] that counts elements: see [Lib0Decoder.readCount]. */
    fun readCount() = rest.readCount()

    fun readAny() = YAny.read(rest)

    fun readBuf() = rest.readVarUint8Array()

    fun readJson(): YAny = Json.parse(rest.readVarString())

    fun readKey() = rest.readVarString()

    fun readDsClock() = rest.readVarUint()

    fun readDsLen() = rest.readVarUint()
}
