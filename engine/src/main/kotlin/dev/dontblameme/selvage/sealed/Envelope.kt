package dev.dontblameme.selvage.sealed

/** One `selvage/2` binary frame, field by field, as §6.1's table writes it. */
class Envelope(
    val keyId: ByteArray,
    val kind: Long,
    val epoch: Long,
    val counter: Long,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val signature: ByteArray,
) {
    fun encode(): ByteArray =
        Bytes.concat(
            keyId,
            Bytes.varUint(kind),
            Bytes.varUint(epoch),
            Bytes.varUint(counter),
            nonce,
            Bytes.varUint8Array(ciphertext),
            signature,
        )

    companion object {
        /** The layout, or null when a field runs out or a byte is left over (`bad_envelope`). */
        fun parse(raw: ByteArray): Envelope? {
            if (raw.size < 8) return null
            val keyId = raw.copyOfRange(0, 8)
            val (kind, afterKind) = Bytes.readVarUint(raw, 8) ?: return null
            val (epoch, afterEpoch) = Bytes.readVarUint(raw, afterKind) ?: return null
            val (counter, afterCounter) = Bytes.readVarUint(raw, afterEpoch) ?: return null
            if (afterCounter + 12 > raw.size) return null
            val nonce = raw.copyOfRange(afterCounter, afterCounter + 12)
            val (size, at) = Bytes.readVarUint(raw, afterCounter + 12) ?: return null
            if (size > raw.size - at) return null
            val end = at + size.toInt()
            if (raw.size - end != 64) return null
            return Envelope(
                keyId,
                kind,
                epoch,
                counter,
                nonce,
                raw.copyOfRange(at, end),
                raw.copyOfRange(end, raw.size),
            )
        }
    }
}

/** The frame key, the associated data, the signature input, and sealing and opening under them. */
object Frames {
    private val VERSION = "selvage/2".toByteArray(Charsets.UTF_8)
    private val FRAME_INFO = "selvage/2 frame".toByteArray(Charsets.UTF_8)

    fun frameKey(
        roomId: String,
        roomKey: ByteArray,
    ): ByteArray = FrameCrypto.hkdfSha256(roomKey, roomId.toByteArray(Charsets.UTF_8), FRAME_INFO, 32)

    fun associatedData(
        roomId: String,
        kind: Long,
        epoch: Long,
        keyId: ByteArray,
    ): ByteArray =
        Bytes.concat(
            Bytes.varUint8Array(VERSION),
            Bytes.varUint8Array(roomId.toByteArray(Charsets.UTF_8)),
            Bytes.varUint(kind),
            Bytes.varUint(epoch),
            Bytes.varUint8Array(keyId),
        )

    fun signingInput(
        aad: ByteArray,
        counter: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray =
        Bytes.concat(aad, Bytes.varUint(counter), Bytes.varUint8Array(nonce), Bytes.varUint8Array(ciphertext))

    /** Seals [plaintext] under the frame key and signs it with [signer]: the bytes handed to the relay. */
    fun seal(
        roomId: String,
        frameKey: ByteArray,
        kind: Long,
        counter: Long,
        signer: SessionKey,
        plaintext: ByteArray,
        nonce: ByteArray = FrameCrypto.randomBytes(12),
        epoch: Long = 0,
    ): ByteArray {
        require(nonce.size == 12 && frameKey.size == 32) { "a nonce is 12 bytes and a frame key 32" }
        val aad = associatedData(roomId, kind, epoch, signer.id)
        val ciphertext = FrameCrypto.aesGcmSeal(frameKey, nonce, plaintext, aad)
        val signature = signer.sign(signingInput(aad, counter, nonce, ciphertext))
        return Envelope(signer.id, kind, epoch, counter, nonce, ciphertext, signature).encode()
    }

    fun opens(
        roomId: String,
        frameKey: ByteArray,
        envelope: Envelope,
    ): ByteArray? =
        FrameCrypto.aesGcmOpen(
            frameKey,
            envelope.nonce,
            envelope.ciphertext,
            associatedData(roomId, envelope.kind, envelope.epoch, envelope.keyId),
        )

    fun authentic(
        roomId: String,
        envelope: Envelope,
        publicKey: ByteArray,
    ): Boolean {
        val aad = associatedData(roomId, envelope.kind, envelope.epoch, envelope.keyId)
        return FrameCrypto.ed25519Verify(
            publicKey,
            signingInput(aad, envelope.counter, envelope.nonce, envelope.ciphertext),
            envelope.signature,
        )
    }
}
