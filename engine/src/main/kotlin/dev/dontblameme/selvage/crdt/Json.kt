package dev.dontblameme.selvage.crdt

import java.math.BigDecimal

/** Malformed JSON text, or a value `JSON.stringify` cannot write. */
class JsonException(
    message: String,
) : RuntimeException(message)

/**
 * `JSON.parse` and `JSON.stringify` as JavaScript runs them, over [YAny].
 *
 * yjs writes the JSON, Embed and Format contents and y-protocols the awareness states with
 * `JSON.stringify`, so these follow it to the byte: number formatting, string escaping and a
 * JavaScript object's key order.
 */
object Json {
    fun parse(text: String): YAny {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.value()
        parser.skipWhitespace()
        if (parser.pos != text.length) throw JsonException("unexpected character at ${parser.pos}")
        return value
    }

    /** `JSON.stringify(value)`, or null where JavaScript returns `undefined`. */
    fun stringify(value: YAny): String? {
        val out = StringBuilder()
        return if (write(out, value)) out.toString() else null
    }

    private fun write(
        out: StringBuilder,
        value: YAny,
    ): Boolean {
        when (value) {
            YAny.Undefined -> {
                return false
            }

            YAny.Null -> {
                out.append("null")
            }

            is YAny.Bool -> {
                out.append(value.value)
            }

            is YAny.Num -> {
                out.append(if (value.value.isNaN() || value.value.isInfinite()) "null" else numberToString(value.value))
            }

            is YAny.BigInt -> {
                throw JsonException("Do not know how to serialize a BigInt")
            }

            is YAny.Str -> {
                quote(out, value.value)
            }

            is YAny.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    if (!write(out, item)) out.append("null")
                }
                out.append(']')
            }

            is YAny.Obj -> {
                out.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    val mark = out.length
                    if (!first) out.append(',')
                    quote(out, k)
                    out.append(':')
                    if (write(out, v)) first = false else out.setLength(mark)
                }
                out.append('}')
            }

            is YAny.Bytes -> {
                // A Uint8Array stringifies as an object keyed by index.
                write(
                    out,
                    YAny.Obj.of(
                        value.value.mapIndexed { i, b ->
                            i.toString() to
                                YAny.Num((b.toInt() and 0xff).toDouble())
                        },
                    ),
                )
            }
        }
        return true
    }

    private fun quote(
        out: StringBuilder,
        s: String,
    ) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> {
                    out.append("\\\"")
                }

                c == '\\' -> {
                    out.append("\\\\")
                }

                c == '\b' -> {
                    out.append("\\b")
                }

                c == '\u000c' -> {
                    out.append("\\f")
                }

                c == '\n' -> {
                    out.append("\\n")
                }

                c == '\r' -> {
                    out.append("\\r")
                }

                c == '\t' -> {
                    out.append("\\t")
                }

                c.code < 0x20 -> {
                    out.append("\\u%04x".format(c.code))
                }

                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    out.append(c).append(s[i + 1])
                    i++
                }

                c.isSurrogate() -> {
                    out.append("\\u%04x".format(c.code))
                }

                else -> {
                    out.append(c)
                }
            }
            i++
        }
        out.append('"')
    }

    /** `Number.prototype.toString()` for a finite double. */
    fun numberToString(d: Double): String {
        if (d == 0.0) return "0"
        if (d < 0) return "-" + numberToString(-d)
        // Double.toString gives the shortest digits that round-trip, as ECMAScript's algorithm does.
        val decimal = BigDecimal(d.toString()).stripTrailingZeros()
        val digits = decimal.unscaledValue().toString()
        val k = digits.length
        val n = k - decimal.scale()
        return when {
            n in k..21 -> {
                digits + "0".repeat(n - k)
            }

            n in 1..21 -> {
                digits.substring(0, n) + "." + digits.substring(n)
            }

            n in -5..0 -> {
                "0." + "0".repeat(-n) + digits
            }

            else -> {
                val e = n - 1
                val sign = if (e < 0) "-" else "+"
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + sign + Math.abs(e)
            }
        }
    }

    private class Parser(
        val text: String,
    ) {
        var pos = 0
        private var depth = 0

        fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        private fun fail(what: String): Nothing = throw JsonException("$what at $pos")

        fun value(): YAny {
            if (pos >= text.length) fail("unexpected end")
            return when (text[pos]) {
                '{' -> nested { obj() }
                '[' -> nested { arr() }
                '"' -> YAny.Str(string())
                't' -> literal("true", YAny.Bool(true))
                'f' -> literal("false", YAny.Bool(false))
                'n' -> literal("null", YAny.Null)
                else -> number()
            }
        }

        /** A remote value is read recursively, so its nesting is bounded before the stack is. */
        private inline fun nested(read: () -> YAny): YAny {
            if (depth >= YAny.MAX_DEPTH) fail("nested deeper than ${YAny.MAX_DEPTH}")
            depth++
            return read().also { depth-- }
        }

        private fun literal(
            word: String,
            value: YAny,
        ): YAny {
            if (!text.startsWith(word, pos)) fail("unexpected token")
            pos += word.length
            return value
        }

        private fun obj(): YAny {
            pos++
            val entries = ArrayList<Pair<String, YAny>>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return YAny.Obj.of(entries)
            }
            while (true) {
                skipWhitespace()
                if (pos >= text.length || text[pos] != '"') fail("expected a key")
                val key = string()
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') fail("expected ':'")
                pos++
                skipWhitespace()
                entries.add(key to value())
                skipWhitespace()
                if (pos >= text.length) fail("unexpected end")
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return YAny.Obj.of(entries)
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(): YAny {
            pos++
            val items = ArrayList<YAny>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return YAny.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items.add(value())
                skipWhitespace()
                if (pos >= text.length) fail("unexpected end")
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return YAny.Arr(items)
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> {
                        return out.toString()
                    }

                    c.code < 0x20 -> {
                        fail("control character in string")
                    }

                    c == '\\' -> {
                        if (pos >= text.length) fail("unterminated escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> {
                                out.append(e)
                            }

                            'b' -> {
                                out.append('\b')
                            }

                            'f' -> {
                                out.append('\u000c')
                            }

                            'n' -> {
                                out.append('\n')
                            }

                            'r' -> {
                                out.append('\r')
                            }

                            't' -> {
                                out.append('\t')
                            }

                            'u' -> {
                                if (pos + 4 > text.length) fail("bad unicode escape")
                                val hex = text.substring(pos, pos + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                    fail(
                                        "bad unicode escape",
                                    )
                                }
                                out.append(hex.toInt(16).toChar())
                                pos += 4
                            }

                            else -> {
                                fail("bad escape")
                            }
                        }
                    }

                    else -> {
                        out.append(c)
                    }
                }
            }
        }

        private fun number(): YAny {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            if (pos >= text.length) fail("bad number")
            if (text[pos] == '0') {
                pos++
            } else if (text[pos] in '1'..'9') {
                while (pos < text.length && text[pos].isAsciiDigit()) pos++
            } else {
                fail("unexpected token")
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                if (pos >= text.length || !text[pos].isAsciiDigit()) fail("bad number")
                while (pos < text.length && text[pos].isAsciiDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || !text[pos].isAsciiDigit()) fail("bad number")
                while (pos < text.length && text[pos].isAsciiDigit()) pos++
            }
            return YAny.Num(text.substring(start, pos).toDouble())
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}
