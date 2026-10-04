package dev.dontblameme.selvage.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A remote JSON text or lib0 Any is refused past [YAny.MAX_DEPTH], whichever container nests. */
class NestingTest {
    private val max = YAny.MAX_DEPTH

    private fun arrays(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

    private fun objects(depth: Int) = "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1)

    private fun mixed(depth: Int) =
        (0 until depth).joinToString("") { if (it % 2 == 0) "[" else "{\"a\":" } + "null" +
            (depth - 1 downTo 0).joinToString("") { if (it % 2 == 0) "]" else "}" }

    /** [depth] containers of lib0 Any, arrays or objects, around a null. */
    private fun any(
        depth: Int,
        objects: Boolean,
    ): ByteArray {
        val encoder = Lib0Encoder()
        repeat(depth) {
            if (objects) {
                encoder.writeUint8(118)
                encoder.writeVarUint(1)
                encoder.writeVarString("a")
            } else {
                encoder.writeUint8(117)
                encoder.writeVarUint(1)
            }
        }
        encoder.writeUint8(126)
        return encoder.toByteArray()
    }

    @Test
    fun json_reads_up_to_the_bound_and_refuses_past_it() {
        for (shape in listOf(::arrays, ::objects, ::mixed)) {
            val deepest = shape(max)
            assertEquals(deepest, Json.stringify(Json.parse(deepest)))
            assertFailsWith<JsonException> { Json.parse(shape(max + 1)) }
            assertFailsWith<JsonException> { Json.parse(shape(10_000)) }
        }
    }

    @Test
    fun any_reads_up_to_the_bound_and_refuses_past_it() {
        for (objects in listOf(false, true)) {
            val deepest = any(max, objects)
            val value = YAny.read(Lib0Decoder(deepest))
            assertEquals(deepest.toHex(), Lib0Encoder().also { YAny.write(it, value) }.toByteArray().toHex())
            assertFailsWith<DecodeException> { YAny.read(Lib0Decoder(any(max + 1, objects))) }
            assertFailsWith<DecodeException> { YAny.read(Lib0Decoder(any(10_000, objects))) }
        }
    }
}
