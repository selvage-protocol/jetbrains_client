package dev.dontblameme.selvage.sealed

/**
 * The fragment's key encoding (`CANONICAL.md` §6.1): unpadded base64url over exactly 32 bytes, 43
 * characters whose last carries two zero bits. A spelling the encoder cannot produce is no key.
 */
object KeyCodec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val FINAL = "AEIMQUYcgkosw048"

    fun encode(raw: ByteArray): String {
        val out = StringBuilder()
        var at = 0
        while (at < raw.size) {
            val b0 = raw[at].toInt() and 0xff
            val b1 = if (at + 1 < raw.size) raw[at + 1].toInt() and 0xff else 0
            val b2 = if (at + 2 < raw.size) raw[at + 2].toInt() and 0xff else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            for (shift in intArrayOf(18, 12, 6, 0)) out.append(ALPHABET[(triple shr shift) and 0x3f])
            at += 3
        }
        return out.substring(0, (raw.size * 8 + 5) / 6)
    }

    fun decode(spelling: String): ByteArray? {
        if (spelling.length != 43 || spelling[42] !in FINAL) return null
        val out = ByteArray(32)
        var written = 0
        var acc = 0
        var bits = 0
        for (c in spelling) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            acc = (acc shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[written++] = (acc ushr bits).toByte()
            }
        }
        return if (written == 32) out else null
    }
}
