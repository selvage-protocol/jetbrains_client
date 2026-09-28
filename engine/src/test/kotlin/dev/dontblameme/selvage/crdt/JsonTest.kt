package dev.dontblameme.selvage.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonTest {
    private fun num(d: Double) = YAny.Num(d)

    @Test
    fun objects_keep_javascript_key_order() {
        val obj =
            YAny.Obj.of(
                "b" to num(1.0),
                "10" to YAny.Str("ten"),
                "a" to num(2.0),
                "2" to YAny.Str("two"),
                "b" to num(3.0),
            )
        // Array-index keys first in ascending order, then the rest by first assignment.
        assertEquals("""{"2":"two","10":"ten","b":3,"a":2}""", Json.stringify(obj))
        assertEquals(obj, Json.parse("""{"b":1,"10":"ten","a":2,"2":"two","b":3}"""))
    }

    @Test
    fun numbers_format_as_javascript_does() {
        val cases =
            mapOf(
                0.0 to "0",
                -0.0 to "0",
                1.0 to "1",
                -2.5 to "-2.5",
                0.1 to "0.1",
                1e21 to "1e+21",
                1e20 to "100000000000000000000",
                1e-7 to "1e-7",
                0.000001 to "0.000001",
                1.5e-10 to "1.5e-10",
                9007199254740991.0 to "9007199254740991",
            )
        for ((d, text) in cases) assertEquals(text, Json.numberToString(d), "$d")
    }

    @Test
    fun strings_escape_as_javascript_does() {
        assertEquals("\"a\\\"\\\\\\n\\u0001\"", Json.stringify(YAny.Str("a\"\\\n\u0001")))
        // A pair is written as is, a lone surrogate escaped (well-formed JSON.stringify).
        assertEquals("\"\uD83D\uDE00\\ud800\"", Json.stringify(YAny.Str("\uD83D\uDE00\uD800")))
        assertEquals(YAny.Str("\uD800x"), Json.parse("\"\\ud800x\""))
    }

    @Test
    fun undefined_stringifies_to_nothing_and_to_null_inside_arrays() {
        assertNull(Json.stringify(YAny.Undefined))
        assertEquals("[null]", Json.stringify(YAny.Arr(listOf(YAny.Undefined))))
        assertEquals("{}", Json.stringify(YAny.Obj.of("u" to YAny.Undefined)))
    }

    @Test
    fun malformed_text_is_refused() {
        for (text in listOf("", "{", "[1,]", "01", "{\"a\" 1}", "nul", "1 2", "\"\\x\"")) {
            assertFailsWith<JsonException>(text) { Json.parse(text) }
        }
    }

    @Test
    fun any_values_encode_as_lib0_writes_them() {
        fun hex(value: YAny) = Lib0Encoder().also { YAny.write(it, value) }.toByteArray().toHex()
        assertEquals("7d07", hex(num(7.0)))
        assertEquals("7d41", hex(num(-1.0)))
        assertEquals("7d40", hex(num(-0.0)))
        assertEquals("7c3f000000", hex(num(0.5)))
        assertEquals("7b3ff199999999999a", hex(num(1.1)))
        assertEquals("7c53800000", hex(num(2.0 * (1L shl 39))))
        assertEquals("7e", hex(YAny.Null))
        assertEquals("7f", hex(YAny.Undefined))
        assertEquals("78", hex(YAny.Bool(true)))
        assertEquals("79", hex(YAny.Bool(false)))
        assertEquals("770161", hex(YAny.Str("a")))
        assertEquals("750278740101", hex(YAny.Arr(listOf(YAny.Bool(true), YAny.Bytes(byteArrayOf(1))))))
        assertEquals("76010161770162", hex(YAny.Obj.of("a" to YAny.Str("b"))))
        assertEquals("7a0000000000000005", hex(YAny.BigInt(5)))
        val value =
            YAny.Obj.of(
                "n" to num(-3.25),
                "l" to YAny.Arr(listOf(YAny.Null, YAny.Str("\uD83D\uDE00"), YAny.Bytes(byteArrayOf(0, -1)))),
            )
        assertEquals(value, YAny.read(Lib0Decoder(hex(value).hexToBytes())))
        assertFailsWith<DecodeException> { YAny.read(Lib0Decoder("73".hexToBytes())) }
    }
}
