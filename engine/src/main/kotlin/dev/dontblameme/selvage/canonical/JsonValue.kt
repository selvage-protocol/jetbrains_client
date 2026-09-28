package dev.dontblameme.selvage.canonical

import java.math.BigDecimal

/** A frame that is not one well-formed JSON text, or a value of the wrong shape: `bad_message`. */
class MalformedJson(
    message: String,
) : RuntimeException(message)

/**
 * A JSON value as SJ-C/1 reads it (`CANONICAL.md` §2): an object is a set of member names, and a
 * number keeps the digits it was written with, so a count can be told from `1.0` or `1e2`.
 */
sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(
        val value: Boolean,
    ) : JsonValue

    data class Number(
        val literal: String,
    ) : JsonValue {
        /** The value as a plain non-negative decimal no larger than [max], or null (§2.4). */
        fun count(max: Long = MAX_SAFE_INTEGER): Long? {
            if (!COUNT.matches(literal) || literal.length > 19) return null
            val n = literal.toLongOrNull() ?: return null
            return if (n <= max) n else null
        }

        /** The value as an integer of any sign that fits a double exactly, or null. */
        fun integer(): Long? {
            val d = runCatching { BigDecimal(literal) }.getOrNull() ?: return null
            val n = runCatching { d.toBigIntegerExact() }.getOrNull() ?: return null
            if (n.bitLength() > 53) return null
            return n.toLong()
        }

        companion object {
            fun of(n: Long): Number = Number(n.toString())
        }
    }

    data class Str(
        val value: String,
    ) : JsonValue

    data class Arr(
        val items: List<JsonValue>,
    ) : JsonValue

    data class Obj(
        val members: Map<String, JsonValue>,
    ) : JsonValue {
        operator fun get(name: String): JsonValue? = members[name]

        fun string(name: String): String? = (members[name] as? Str)?.value

        fun obj(name: String): Obj? = members[name] as? Obj

        fun arr(name: String): Arr? = members[name] as? Arr

        fun count(name: String): Long? = (members[name] as? Number)?.count()

        companion object {
            fun of(vararg members: Pair<String, JsonValue?>): Obj {
                val map = LinkedHashMap<String, JsonValue>()
                for ((k, v) in members) if (v != null) map[k] = v
                return Obj(map)
            }
        }
    }

    companion object {
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private val COUNT = Regex("0|[1-9][0-9]*")
    }
}

fun String.json(): JsonValue.Str = JsonValue.Str(this)

fun Long.json(): JsonValue.Number = JsonValue.Number.of(this)

fun Int.json(): JsonValue.Number = JsonValue.Number.of(toLong())

fun Boolean.json(): JsonValue.Bool = JsonValue.Bool(this)
