package dev.dontblameme.selvage.canonical

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * SJ-C/1 (`CANONICAL.md`): a strict reader, and the one writer.
 *
 * The reader takes any member order and insignificant whitespace (§4) and refuses what §5 and
 * §2.3 refuse: text that is not JSON, a repeated member name at any depth, a lone surrogate.
 * The writer produces the one form §2 fixes, so equal values are equal bytes.
 */
object CanonicalJson {
    const val MAX_DEPTH = 64

    fun parse(bytes: ByteArray): JsonValue {
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                throw MalformedJson("not UTF-8")
            }
        return parse(text)
    }

    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        reader.whitespace()
        val value = reader.value(0)
        reader.whitespace()
        if (reader.pos != text.length) throw MalformedJson("trailing characters at ${reader.pos}")
        return value
    }

    /** Parses [text] as one JSON object, the only thing a text frame may be (§5). */
    fun parseObject(text: String): JsonValue.Obj =
        parse(text) as? JsonValue.Obj ?: throw MalformedJson("not a JSON object")

    fun parseObject(bytes: ByteArray): JsonValue.Obj =
        parse(bytes) as? JsonValue.Obj ?: throw MalformedJson("not a JSON object")

    fun write(value: JsonValue): String = StringBuilder().also { write(it, value) }.toString()

    fun writeBytes(value: JsonValue): ByteArray = write(value).toByteArray(StandardCharsets.UTF_8)

    /** Member names in ascending code-point order (§2.1). */
    val codePointOrder: Comparator<String> =
        Comparator { a, b ->
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a.codePointAt(i)
                val cb = b.codePointAt(j)
                if (ca != cb) return@Comparator ca.compareTo(cb)
                i += Character.charCount(ca)
                j += Character.charCount(cb)
            }
            (a.length - i).compareTo(b.length - j)
        }

    private fun write(
        out: StringBuilder,
        value: JsonValue,
    ) {
        when (value) {
            JsonValue.Null -> {
                out.append("null")
            }

            is JsonValue.Bool -> {
                out.append(value.value)
            }

            is JsonValue.Number -> {
                out.append(value.literal)
            }

            is JsonValue.Str -> {
                quote(out, value.value)
            }

            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(out, item)
                }
                out.append(']')
            }

            is JsonValue.Obj -> {
                out.append('{')
                value.members.keys.sortedWith(codePointOrder).forEachIndexed { i, name ->
                    if (i > 0) out.append(',')
                    quote(out, name)
                    out.append(':')
                    write(out, value.members.getValue(name))
                }
                out.append('}')
            }
        }
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

                c == '\t' -> {
                    out.append("\\t")
                }

                c == '\n' -> {
                    out.append("\\n")
                }

                c == '\u000c' -> {
                    out.append("\\f")
                }

                c == '\r' -> {
                    out.append("\\r")
                }

                c < ' ' -> {
                    out.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                }

                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    out.append(c).append(s[i + 1])
                    i++
                }

                c.isSurrogate() -> {
                    throw MalformedJson("a lone surrogate has no UTF-8 form")
                }

                else -> {
                    out.append(c)
                }
            }
            i++
        }
        out.append('"')
    }

    private const val HEX = "0123456789abcdef"

    private class Reader(
        val s: String,
    ) {
        var pos = 0

        fun whitespace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        private fun fail(what: String): Nothing = throw MalformedJson("$what at $pos")

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (pos >= s.length) fail("unexpected end")
            return when (s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> number()
            }
        }

        private fun literal(
            word: String,
            value: JsonValue,
        ): JsonValue {
            if (!s.startsWith(word, pos)) fail("unexpected token")
            pos += word.length
            return value
        }

        private fun obj(depth: Int): JsonValue.Obj {
            pos++
            val members = LinkedHashMap<String, JsonValue>()
            whitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return JsonValue.Obj(members)
            }
            while (true) {
                whitespace()
                if (pos >= s.length || s[pos] != '"') fail("expected a member name")
                val name = string()
                if (members.containsKey(name)) fail("member `$name` repeated")
                whitespace()
                if (pos >= s.length || s[pos] != ':') fail("expected `:`")
                pos++
                whitespace()
                members[name] = value(depth + 1)
                whitespace()
                if (pos >= s.length) fail("unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return JsonValue.Obj(members)
                    else -> fail("expected `,` or `}`")
                }
            }
        }

        private fun arr(depth: Int): JsonValue.Arr {
            pos++
            val items = ArrayList<JsonValue>()
            whitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                whitespace()
                items.add(value(depth + 1))
                whitespace()
                if (pos >= s.length) fail("unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return JsonValue.Arr(items)
                    else -> fail("expected `,` or `]`")
                }
            }
        }

        private fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> {
                        break
                    }

                    c == '\\' -> {
                        if (pos >= s.length) fail("unterminated escape")
                        when (s[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> out.append(hex4())
                            else -> fail("bad escape")
                        }
                    }

                    c < ' ' -> {
                        fail("raw control character in a string")
                    }

                    else -> {
                        out.append(c)
                    }
                }
            }
            val text = out.toString()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    i += 2
                    continue
                }
                if (c.isSurrogate()) fail("lone surrogate")
                i++
            }
            return text
        }

        private fun hex4(): Char {
            if (pos + 4 > s.length) fail("short `\\u` escape")
            var n = 0
            repeat(4) {
                val d = Character.digit(s[pos++], 16)
                if (d < 0) fail("bad `\\u` escape")
                n = n * 16 + d
            }
            return n.toChar()
        }

        private fun number(): JsonValue.Number {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            if (pos >= s.length) fail("unexpected end")
            if (s[pos] == '0') {
                pos++
            } else if (s[pos] in '1'..'9') {
                while (pos < s.length && s[pos] in '0'..'9') pos++
            } else {
                fail("unexpected character")
            }
            if (pos < s.length && s[pos] == '.') {
                pos++
                digits()
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                digits()
            }
            return JsonValue.Number(s.substring(start, pos))
        }

        private fun digits() {
            val start = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == start) fail("expected a digit")
        }
    }
}
