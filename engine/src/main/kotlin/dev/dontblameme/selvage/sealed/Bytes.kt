package dev.dontblameme.selvage.sealed

import java.io.ByteArrayOutputStream

/** The byte helpers §6.1's layout is written in: lib0's `varUint` and `varUint8Array`, and hex. */
object Bytes {
    private const val MAX_SAFE = 9_007_199_254_740_991L

    fun varUint(value: Long): ByteArray {
        require(value in 0..MAX_SAFE) { "a varUint is a non-negative count, and this is $value" }
        val out = ByteArrayOutputStream()
        var rest = value
        while (true) {
            val byte = (rest and 0x7f).toInt()
            rest = rest ushr 7
            if (rest == 0L) {
                out.write(byte)
                return out.toByteArray()
            }
            out.write(byte or 0x80)
        }
    }

    fun varUint8Array(raw: ByteArray): ByteArray = concat(varUint(raw.size.toLong()), raw)

    /** Reads a `varUint` at [at]: the value and the index after it, or null when it runs out or passes 2^53 - 1. */
    fun readVarUint(
        data: ByteArray,
        at: Int,
    ): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var cursor = at
        while (true) {
            if (cursor >= data.size || shift > 49) return null
            val byte = data[cursor++].toInt() and 0xff
            val part = (byte and 0x7f).toLong() shl shift
            value += part
            if (value > MAX_SAFE) return null
            if (byte and 0x80 == 0) return value to cursor
            shift += 7
        }
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun fromHex(digits: String): ByteArray {
        val compact = digits.filterNot { it.isWhitespace() }
        require(compact.length % 2 == 0 && compact.all { Character.digit(it, 16) >= 0 }) { "not hex" }
        return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
