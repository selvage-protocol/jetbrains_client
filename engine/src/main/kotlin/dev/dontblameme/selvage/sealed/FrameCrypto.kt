package dev.dontblameme.selvage.sealed

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.security.spec.EdECPoint
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** §6.1's primitives, from the JDK alone: HKDF-SHA256, AES-256-GCM, SHA-256 and Ed25519. */
object FrameCrypto {
    val random: SecureRandom = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - written)
            previous.copyInto(out, written, 0, take)
            written += take
            counter++
        }
        return out
    }

    fun aesGcmSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** The plaintext, or null when the tag does not verify. */
    fun aesGcmOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray? =
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            null
        }

    /** RFC 8032's public key for a 32-byte seed, in its 32-byte encoding. */
    fun ed25519PublicFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "an Ed25519 seed is 32 bytes" }
        val generator = KeyPairGenerator.getInstance("Ed25519")
        generator.initialize(NamedParameterSpec.ED25519, FixedRandom(seed))
        val point = (generator.generateKeyPair().public as EdECPublicKey).point
        val y = point.y.toByteArray()
        val out = ByteArray(32)
        for (i in y.indices) {
            val at = y.size - 1 - i
            if (i < 32) out[i] = y[at]
        }
        if (point.isXOdd) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    fun ed25519Sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray {
        val key =
            KeyFactory
                .getInstance("Ed25519")
                .generatePrivate(EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(key)
        signer.update(message)
        return signer.sign()
    }

    fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val littleEndian = publicKey.copyOf()
            val xOdd = littleEndian[31].toInt() and 0x80 != 0
            littleEndian[31] = (littleEndian[31].toInt() and 0x7f).toByte()
            val y = BigInteger(1, littleEndian.reversedArray())
            val key =
                KeyFactory
                    .getInstance("Ed25519")
                    .generatePublic(EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint(xOdd, y)))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update(message)
            verifier.verify(signature)
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** Hands the key generator the seed as its "random" bytes, which is how the JDK takes a seed. */
    private class FixedRandom(
        private val seed: ByteArray,
    ) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            require(bytes.size == seed.size) { "the generator asked for ${bytes.size} bytes" }
            seed.copyInto(bytes)
        }
    }
}
