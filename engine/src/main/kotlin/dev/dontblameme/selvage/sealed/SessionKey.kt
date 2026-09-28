package dev.dontblameme.selvage.sealed

/** An Ed25519 keypair: a connection's session key, or the room's host key. */
class SessionKey private constructor(
    val seed: ByteArray,
    val public: ByteArray,
) {
    /** `key_id`: the first 8 bytes of SHA-256 over the public key. */
    val id: ByteArray = keyId(public)
    val idHex: String = Bytes.hex(id)

    /** The key as the fragment and the state's `peers` spell it. */
    val spelling: String = KeyCodec.encode(public)

    fun sign(message: ByteArray): ByteArray = FrameCrypto.ed25519Sign(seed, message)

    companion object {
        fun fromSeed(seed: ByteArray): SessionKey {
            require(seed.size == 32) { "a seed is 32 bytes" }
            return SessionKey(seed.copyOf(), FrameCrypto.ed25519PublicFromSeed(seed))
        }

        fun mint(): SessionKey = fromSeed(FrameCrypto.randomBytes(32))

        fun keyId(public: ByteArray): ByteArray = FrameCrypto.sha256(public).copyOf(8)
    }
}
