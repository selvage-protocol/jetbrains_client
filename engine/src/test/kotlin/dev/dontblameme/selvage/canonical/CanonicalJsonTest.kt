package dev.dontblameme.selvage.canonical

import dev.dontblameme.selvage.sealed.Payload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CanonicalJsonTest {
    private fun canon(text: String) = CanonicalJson.write(CanonicalJson.parse(text))

    @Test
    fun `writes members in code point order with no whitespace`() {
        assertEquals(
            """{"a":1,"b":{"c":[1,2],"d":true}}""",
            canon(""" { "b" : { "d" : true , "c" : [ 1 , 2 ] } , "a" : 1 } """),
        )
    }

    @Test
    fun `orders astral names by code point, not by UTF-16 unit`() {
        // U+FF61 sorts before U+1F600 by code point, after it by UTF-16 unit.
        assertEquals("{\"\uFF61\":1,\"\uD83D\uDE00\":2}", canon("{\"\uD83D\uDE00\":2,\"\uFF61\":1}"))
    }

    @Test
    fun `escapes exactly the three kinds and writes the rest raw`() {
        val value = JsonValue.Str("\"\\\b\t\n\u000c\r\u0001\u001f/\u007f\u00e9\uD83D\uDE00'")
        assertEquals("\"\\\"\\\\\\b\\t\\n\\f\\r\\u0001\\u001f/\u007f\u00e9\uD83D\uDE00'\"", CanonicalJson.write(value))
        assertEquals("\"\\u001f\"", canon("\"\\u001F\""))
        assertEquals("\"/\"", canon("\"\\/\""))
    }

    @Test
    fun `refuses repeated names at any depth`() {
        assertFailsWith<MalformedJson> { CanonicalJson.parse("""{"a":1,"a":1}""") }
        assertFailsWith<MalformedJson> { CanonicalJson.parse("""{"a":{"b":1,"b":2}}""") }
        assertFailsWith<MalformedJson> { CanonicalJson.parse("""[{"b":1,"b":2}]""") }
    }

    @Test
    fun `refuses lone surrogates, invalid UTF-8 and non-JSON`() {
        for (bad in listOf(
            "\"\\ud800\"",
            "\"\\udc00x\"",
            "\"\\ud800\\u0041\"",
            "",
            "{",
            "{}x",
            "[1,]",
            "{\"a\":1,}",
            "01",
            "1.",
            "-",
            "+1",
            "'a'",
            "\"\t\"",
            "nul",
            "[1 2]",
        )) {
            assertFailsWith<MalformedJson>(bad) { CanonicalJson.parse(bad) }
        }
        assertFailsWith<MalformedJson> {
            CanonicalJson.parse(
                byteArrayOf('"'.code.toByte(), 0xc3.toByte(), '"'.code.toByte()),
            )
        }
        assertFailsWith<MalformedJson> {
            CanonicalJson.parse(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), '1'.code.toByte()))
        }
        assertFailsWith<MalformedJson> { CanonicalJson.write(JsonValue.Str("\uD800")) }
        assertFailsWith<MalformedJson> { CanonicalJson.parseObject("[1]") }
    }

    /** serde_json's verdicts: 127 containers inside one another read, a 128th does not. */
    @Test
    fun `bounds nesting where the reference reader does`() {
        fun arrays(n: Int) = "[".repeat(n) + "]".repeat(n)

        fun objects(n: Int) = "{\"x\":".repeat(n - 1) + "{\"x\":1}" + "}".repeat(n - 1)
        for (shape in listOf(::arrays, ::objects)) {
            CanonicalJson.parse(shape(127))
            assertFailsWith<MalformedJson> { CanonicalJson.parse(shape(128)) }
        }
    }

    @Test
    fun `a sealed payload's unknown member is read at the reference reader's depth`() {
        val deep = "{\"x\":".repeat(125) + "[1]" + "}".repeat(125)
        val holds = Payload.read(3, """{"holds":["a.txt"],"later":$deep}""".toByteArray())
        assertEquals(listOf("a.txt"), (holds as? Payload.Holds)?.holds)
    }

    @Test
    fun `a count is a plain non-negative decimal no larger than 2^53 - 1`() {
        assertEquals(0L, JsonValue.Number("0").count())
        assertEquals(9007199254740991L, JsonValue.Number("9007199254740991").count())
        for (bad in listOf("9007199254740992", "1.0", "1e2", "-0", "-1", "99999999999999999999999")) {
            assertNull(JsonValue.Number(bad).count(), bad)
        }
        assertEquals(-1L, JsonValue.Number("-1").integer())
        assertEquals("1e2", canon("1e2"))
    }

    @Test
    fun `round trips a frame byte for byte`() {
        val frame = """{"id":1,"method":"session.hello","params":{"display_name":"Ada"},"v":"selvage/2"}"""
        assertEquals(frame, canon(frame))
    }
}
