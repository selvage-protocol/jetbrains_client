package dev.dontblameme.selvage.crdt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class Lib0Test {
    private fun bytes(write: Lib0Encoder.() -> Unit): String = Lib0Encoder().apply(write).toByteArray().toHex()

    @Test
    fun var_uints_are_leb128_up_to_the_largest_safe_integer() {
        assertEquals("00", bytes { writeVarUint(0) })
        assertEquals("7f", bytes { writeVarUint(127) })
        assertEquals("8001", bytes { writeVarUint(128) })
        assertEquals("ac02", bytes { writeVarUint(300) })
        assertEquals("ffffffffffffff0f", bytes { writeVarUint(MAX_SAFE_INTEGER) })
        for (value in listOf(0L, 1L, 127L, 128L, 300L, 1L shl 32, MAX_SAFE_INTEGER)) {
            assertEquals(value, Lib0Decoder(bytes { writeVarUint(value) }.hexToBytes()).readVarUint())
        }
        assertFailsWith<IllegalArgumentException> { Lib0Encoder().writeVarUint(MAX_SAFE_INTEGER + 1) }
        assertFailsWith<IllegalArgumentException> { Lib0Encoder().writeVarUint(-1L) }
        assertFailsWith<DecodeException> { Lib0Decoder("8080808080808010".hexToBytes()).readVarUint() }
        assertFailsWith<DecodeException> { Lib0Decoder("ffffffffffffffffff01".hexToBytes()).readVarUint() }
    }

    @Test
    fun var_ints_carry_a_sign_bit_and_negative_zero() {
        assertEquals("00", bytes { writeVarInt(0) })
        assertEquals("40", bytes { writeVarInt(0, negativeZero = true) })
        assertEquals("01", bytes { writeVarInt(1) })
        assertEquals("41", bytes { writeVarInt(-1) })
        assertEquals("3f", bytes { writeVarInt(63) })
        assertEquals("8001", bytes { writeVarInt(64) })
        assertEquals("c001", bytes { writeVarInt(-64) })
        assertEquals(0L to true, Lib0Decoder("40".hexToBytes()).readVarIntWithSign())
        assertEquals(0L to false, Lib0Decoder("00".hexToBytes()).readVarIntWithSign())
        for (value in listOf(-MAX_SAFE_INTEGER, -65L, -1L, 5L, 1L shl 40, MAX_SAFE_INTEGER)) {
            assertEquals(value, Lib0Decoder(bytes { writeVarInt(value) }.hexToBytes()).readVarInt())
        }
    }

    @Test
    fun strings_are_utf8_with_lone_surrogates_written_as_the_replacement_character() {
        assertEquals("0161", bytes { writeVarString("a") })
        assertEquals("06f09f9880c3a9", bytes { writeVarString("😀é") })
        assertEquals("0561efbfbd62", bytes { writeVarString("a\uD800b") })
        assertEquals("0561efbfbd62", bytes { writeVarString("a\uDC00b") })
        assertEquals("03efbfbd", bytes { writeVarString("\uD83D") })
        assertEquals("😀é", Lib0Decoder("06f09f9880c3a9".hexToBytes()).readVarString())
        // A byte-order mark is content, and invalid UTF-8 is refused.
        assertEquals("﻿a", Lib0Decoder("04efbbbf61".hexToBytes()).readVarString())
        assertFailsWith<DecodeException> { Lib0Decoder("01ff".hexToBytes()).readVarString() }
        assertFailsWith<DecodeException> { Lib0Decoder("03eda080".hexToBytes()).readVarString() }
    }

    @Test
    fun every_read_past_the_end_throws() {
        assertFailsWith<DecodeException> { Lib0Decoder(ByteArray(0)).readUint8() }
        assertFailsWith<DecodeException> { Lib0Decoder("80".hexToBytes()).readVarUint() }
        assertFailsWith<DecodeException> { Lib0Decoder("0361".hexToBytes()).readVarUint8Array() }
        assertFailsWith<DecodeException> { Lib0Decoder("3ff0".hexToBytes()).readFloat64() }
        val decoder = Lib0Decoder("0100".hexToBytes())
        decoder.readVarUint8Array()
        assertFalse(decoder.hasContent())
    }

    @Test
    fun floats_are_big_endian() {
        assertEquals("3ff8000000000000", bytes { writeFloat64(1.5) })
        assertEquals("3f000000", bytes { writeFloat32(0.5f) })
        assertContentEquals("0000000000000001".hexToBytes(), Lib0Encoder().apply { writeBigInt64(1) }.toByteArray())
    }
}
