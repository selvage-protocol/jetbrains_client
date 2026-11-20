package dev.dontblameme.selvage.crdt

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Bytes that are not a valid lib0 encoding: truncated, out of range, or of an unknown shape. */
class DecodeException(
    message: String,
) : RuntimeException(message)

/** The largest integer a lib0 varUint may carry: JavaScript's `Number.MAX_SAFE_INTEGER`. */
const val MAX_SAFE_INTEGER: Long = (1L shl 53) - 1

/** lib0's `encoding`: the primitives yjs and y-protocols write. */
internal class GrowingBytes(
    initialCapacity: Int,
) {
    private var bytes = ByteArray(maxOf(1, initialCapacity))
    var size: Int = 0
        private set

    fun ensure(extra: Int) {
        if (extra <= bytes.size - size) return
        val required = size.toLong() + extra
        var capacity = bytes.size.toLong()
        while (capacity < required) capacity *= 2
        if (capacity > Int.MAX_VALUE) throw OutOfMemoryError("lib0 output too large")
        bytes = bytes.copyOf(capacity.toInt())
    }

    fun write(value: Int) {
        ensure(1)
        bytes[size++] = value.toByte()
    }

    fun writeAll(value: ByteArray) {
        ensure(value.size)
        value.copyInto(bytes, size)
        size += value.size
    }

    fun finish(): ByteArray = bytes.copyOf(size)
}

class Lib0Encoder {
    private val out = GrowingBytes(32)

    val size: Int get() = out.size

    fun toByteArray(): ByteArray = out.finish()

    fun writeUint8(value: Int) = out.write(value and 0xff)

    fun writeBytes(bytes: ByteArray) = out.writeAll(bytes)

    /** LEB128. */
    fun writeVarUint(value: Long) {
        require(value in 0..MAX_SAFE_INTEGER) { "varUint out of range: $value" }
        var num = value
        while (num > 0x7f) {
            out.write(0x80 or (num and 0x7f).toInt())
            num = num ushr 7
        }
        out.write(num.toInt())
    }

    fun writeVarUint(value: Int) = writeVarUint(value.toLong())

    /**
     * The first byte carries a continuation bit, a sign bit and six bits of magnitude; later bytes
     * seven bits each. `negativeZero` writes lib0's `-0`, which is a sign bit on a zero magnitude.
     */
    fun writeVarInt(
        value: Long,
        negativeZero: Boolean = false,
    ) {
        val negative = value < 0 || (value == 0L && negativeZero)
        require(value != Long.MIN_VALUE) { "varInt out of range" }
        var num = if (value < 0) -value else value
        out.write((if (num > 0x3f) 0x80 else 0) or (if (negative) 0x40 else 0) or (num and 0x3f).toInt())
        num = num ushr 6
        while (num > 0) {
            out.write((if (num > 0x7f) 0x80 else 0) or (num and 0x7f).toInt())
            num = num ushr 7
        }
    }

    fun writeVarUint8Array(bytes: ByteArray) {
        writeVarUint(bytes.size.toLong())
        writeBytes(bytes)
    }

    /** UTF-8 as `TextEncoder` writes it: a lone surrogate becomes U+FFFD. */
    fun writeVarString(value: String) = writeVarUint8Array(Utf8.encode(value))

    fun writeFloat32(value: Float) {
        val bits = java.lang.Float.floatToRawIntBits(value)
        out.write(bits ushr 24)
        out.write(bits ushr 16)
        out.write(bits ushr 8)
        out.write(bits)
    }

    fun writeFloat64(value: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(value)
        var shift = 56
        while (shift >= 0) {
            out.write((bits ushr shift).toInt())
            shift -= 8
        }
    }

    fun writeBigInt64(value: Long) {
        var shift = 56
        while (shift >= 0) {
            out.write((value ushr shift).toInt())
            shift -= 8
        }
    }
}

/** lib0's `decoding`, strict: every read that runs past the end throws [DecodeException]. */
class Lib0Decoder(
    private val bytes: ByteArray,
    start: Int = 0,
) {
    var pos: Int = start
        private set

    fun hasContent(): Boolean = pos < bytes.size

    fun readUint8(): Int {
        if (pos >= bytes.size) throw DecodeException("unexpected end of array")
        return bytes[pos++].toInt() and 0xff
    }

    fun readBytes(length: Int): ByteArray {
        if (length < 0 || length > bytes.size - pos) throw DecodeException("unexpected end of array")
        val result = bytes.copyOfRange(pos, pos + length)
        pos += length
        return result
    }

    fun readVarUint(): Long {
        var num = 0L
        var shift = 0
        while (true) {
            val r = readUint8()
            num = num or ((r and 0x7f).toLong() shl shift)
            if (r < 0x80) break
            shift += 7
            if (shift > 56) throw DecodeException("integer out of range")
        }
        if (num > MAX_SAFE_INTEGER) throw DecodeException("integer out of range")
        return num
    }

    /** A varUint that must fit an `Int`: a length or a count. */
    fun readLength(): Int {
        val value = readVarUint()
        if (value > Int.MAX_VALUE) throw DecodeException("length out of range: $value")
        return value.toInt()
    }

    /**
     * A count of elements that take a byte each at least: one past the bytes left cannot be
     * read, so it is refused before anything is allocated for it.
     */
    fun readCount(): Int {
        val count = readLength()
        if (count > bytes.size - pos) throw DecodeException("unexpected end of array: $count elements")
        return count
    }

    /** Returns the value, and whether it was lib0's `-0` (a sign bit on a zero magnitude). */
    fun readVarIntWithSign(): Pair<Long, Boolean> {
        var r = readUint8()
        var num = (r and 0x3f).toLong()
        var shift = 6
        val negative = (r and 0x40) != 0
        while ((r and 0x80) != 0) {
            r = readUint8()
            num = num or ((r and 0x7f).toLong() shl shift)
            shift += 7
            if (shift > 55 && (r and 0x80) != 0) throw DecodeException("integer out of range")
        }
        if (num > MAX_SAFE_INTEGER) throw DecodeException("integer out of range")
        return (if (negative) -num else num) to (negative && num == 0L)
    }

    fun readVarInt(): Long = readVarIntWithSign().first

    fun readVarUint8Array(): ByteArray = readBytes(readLength())

    /** UTF-8 as lib0 reads it: `fatal`, so invalid UTF-8 throws, and `ignoreBOM`, so a BOM is kept. */
    fun readVarString(): String = Utf8.decode(readVarUint8Array())

    fun readFloat32(): Float = ByteBuffer.wrap(readBytes(4)).float

    fun readFloat64(): Double = ByteBuffer.wrap(readBytes(8)).double

    fun readBigInt64(): Long = ByteBuffer.wrap(readBytes(8)).long
}

internal object Utf8 {
    fun encode(value: String): ByteArray {
        val out = GrowingBytes(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            var cp = c.code
            if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                cp = Character.toCodePoint(c, value[i + 1])
                i++
            } else if (c.isSurrogate()) {
                cp = 0xFFFD
            }
            when {
                cp < 0x80 -> {
                    out.write(cp)
                }

                cp < 0x800 -> {
                    out.write(0xC0 or (cp shr 6))
                    out.write(0x80 or (cp and 0x3f))
                }

                cp < 0x10000 -> {
                    out.write(0xE0 or (cp shr 12))
                    out.write(0x80 or ((cp shr 6) and 0x3f))
                    out.write(0x80 or (cp and 0x3f))
                }

                else -> {
                    out.write(0xF0 or (cp shr 18))
                    out.write(0x80 or ((cp shr 12) and 0x3f))
                    out.write(0x80 or ((cp shr 6) and 0x3f))
                    out.write(0x80 or (cp and 0x3f))
                }
            }
            i++
        }
        return out.finish()
    }

    fun decode(bytes: ByteArray): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw DecodeException("invalid UTF-8: ${e.message}")
        }
    }
}
