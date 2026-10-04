package dev.dontblameme.selvage.crdt

/**
 * A JavaScript value as lib0's `writeAny`/`readAny` and `JSON.stringify`/`JSON.parse` see it.
 *
 * Numbers are doubles, as they are in JavaScript, so a value decoded from yjs re-encodes to the
 * bytes yjs writes for it. [Obj] keeps a JavaScript object's key order: integer-like keys first,
 * in ascending order, then the rest in insertion order.
 */
sealed interface YAny {
    data object Undefined : YAny

    data object Null : YAny

    data class Bool(
        val value: Boolean,
    ) : YAny

    class Num(
        val value: Double,
    ) : YAny {
        override fun equals(other: Any?): Boolean = other is Num && other.value == value

        override fun hashCode(): Int = if (value == 0.0) 0 else value.hashCode()

        override fun toString(): String = "Num(${Json.numberToString(value)})"
    }

    data class BigInt(
        val value: Long,
    ) : YAny

    data class Str(
        val value: String,
    ) : YAny

    data class Arr(
        val items: List<YAny>,
    ) : YAny

    class Obj private constructor(
        val entries: List<Pair<String, YAny>>,
    ) : YAny {
        operator fun get(key: String): YAny? = entries.firstOrNull { it.first == key }?.second

        val keys: List<String> get() = entries.map { it.first }

        override fun equals(other: Any?): Boolean = other is Obj && other.entries.toSet() == entries.toSet()

        override fun hashCode(): Int = entries.toSet().hashCode()

        override fun toString(): String = "Obj($entries)"

        companion object {
            /** Builds an object the way assigning `obj[key] = value` in order would. */
            fun of(vararg entries: Pair<String, YAny>): Obj = of(entries.asList())

            fun of(entries: List<Pair<String, YAny>>): Obj {
                val map = LinkedHashMap<String, YAny>()
                for ((k, v) in entries) map[k] = v
                val (indices, names) = map.entries.partition { arrayIndex(it.key) != null }
                val ordered = indices.sortedBy { arrayIndex(it.key) } + names
                return Obj(ordered.map { it.key to it.value })
            }

            /** A canonical array index, the kind of key a JavaScript object orders first. */
            private fun arrayIndex(key: String): Long? {
                if (key.isEmpty() || key.length > 10 || !key.all { it in '0'..'9' }) return null
                if (key.length > 1 && key[0] == '0') return null
                val n = key.toLong()
                return if (n <= 4294967294L) n else null
            }
        }
    }

    class Bytes(
        val value: ByteArray,
    ) : YAny {
        override fun equals(other: Any?): Boolean = other is Bytes && other.value.contentEquals(value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Bytes(${value.toHex()})"
    }

    companion object {
        /**
         * The most arrays and objects a remote value may open inside one another. Reading is
         * recursive, so past this a frame is refused as malformed rather than left to overflow the
         * stack; a cursor or an embed is a few levels deep.
         */
        const val MAX_DEPTH = 256

        fun of(value: String): YAny = Str(value)

        fun of(value: Number): YAny = Num(value.toDouble())

        fun of(value: Boolean): YAny = Bool(value)

        fun read(
            decoder: Lib0Decoder,
            depth: Int = 0,
        ): YAny =
            when (val type = decoder.readUint8()) {
                127 -> {
                    Undefined
                }

                126 -> {
                    Null
                }

                125 -> {
                    val (value, negativeZero) = decoder.readVarIntWithSign()
                    Num(if (negativeZero) -0.0 else value.toDouble())
                }

                124 -> {
                    Num(decoder.readFloat32().toDouble())
                }

                123 -> {
                    Num(decoder.readFloat64())
                }

                122 -> {
                    BigInt(decoder.readBigInt64())
                }

                121 -> {
                    Bool(false)
                }

                120 -> {
                    Bool(true)
                }

                119 -> {
                    Str(decoder.readVarString())
                }

                118 -> {
                    val count = decoder.readCount()
                    nested(depth)
                    Obj.of(List(count) { decoder.readVarString() to read(decoder, depth + 1) })
                }

                117 -> {
                    val count = decoder.readCount()
                    nested(depth)
                    Arr(List(count) { read(decoder, depth + 1) })
                }

                116 -> {
                    Bytes(decoder.readVarUint8Array())
                }

                else -> {
                    throw DecodeException("unknown Any type $type")
                }
            }

        private fun nested(depth: Int) {
            if (depth >= MAX_DEPTH) throw DecodeException("an Any nested deeper than $MAX_DEPTH")
        }

        fun write(
            encoder: Lib0Encoder,
            value: YAny,
        ) {
            when (value) {
                is Str -> {
                    encoder.writeUint8(119)
                    encoder.writeVarString(value.value)
                }

                is Num -> {
                    val d = value.value
                    if (!d.isNaN() && !d.isInfinite() && d == Math.floor(d) && Math.abs(d) <= Int.MAX_VALUE) {
                        encoder.writeUint8(125)
                        encoder.writeVarInt(d.toLong(), negativeZero = d == 0.0 && 1.0 / d < 0)
                    } else if (d.toFloat().toDouble() == d) {
                        encoder.writeUint8(124)
                        encoder.writeFloat32(d.toFloat())
                    } else {
                        encoder.writeUint8(123)
                        encoder.writeFloat64(d)
                    }
                }

                is BigInt -> {
                    encoder.writeUint8(122)
                    encoder.writeBigInt64(value.value)
                }

                Null -> {
                    encoder.writeUint8(126)
                }

                is Arr -> {
                    encoder.writeUint8(117)
                    encoder.writeVarUint(value.items.size)
                    value.items.forEach { write(encoder, it) }
                }

                is Bytes -> {
                    encoder.writeUint8(116)
                    encoder.writeVarUint8Array(value.value)
                }

                is Obj -> {
                    encoder.writeUint8(118)
                    encoder.writeVarUint(value.entries.size)
                    for ((k, v) in value.entries) {
                        encoder.writeVarString(k)
                        write(encoder, v)
                    }
                }

                is Bool -> {
                    encoder.writeUint8(if (value.value) 120 else 121)
                }

                Undefined -> {
                    encoder.writeUint8(127)
                }
            }
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
