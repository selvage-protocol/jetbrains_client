package dev.dontblameme.selvage.spec

import dev.dontblameme.selvage.canonical.CanonicalJson
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.crdt.TestPaths
import dev.dontblameme.selvage.peer.HostProducer
import dev.dontblameme.selvage.sealed.Payload
import dev.dontblameme.selvage.wire.Wire
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The numeric bounds the specification owns, read from `schema/limits.json` rather than copied, so
 * a bound that moves in the spec and not here — or here and not in the spec — is a red run.
 *
 * Only the entries this engine owns are read. Its `/meta` body cap and its inbox cap are client-only
 * and the protocol has no such bound (`specification/NOTES.md` §A.11), and `PROTOCOL.md` §2.1's
 * table is the reference server's own policy.
 */
class SpecificationLimitsTest {
    /** One registry entry: the value the file writes, the unit it writes it in, and the section that owns it. */
    private data class Limit(
        val value: Long,
        val unit: String,
        val section: String,
    )

    private val limits: Map<String, Limit> by lazy {
        val file = File(TestPaths.specification, "schema/limits.json")
        if (!file.isFile) {
            fail("the specification's $file is missing: there is nothing to pin the engine's bounds against")
        }
        val entries = CanonicalJson.parseObject(file.readText()).arr("limits")?.items
        if (entries.isNullOrEmpty()) fail("$file names no limits")
        entries.associate { entry ->
            val obj = entry as? JsonValue.Obj ?: fail("$file: $entry is not an object")
            val name = obj.string("name") ?: fail("$file: an entry names no limit")
            name to
                Limit(
                    value = obj.count("value") ?: fail("$file: $name carries no integer value"),
                    unit = obj.string("unit") ?: fail("$file: $name names no unit"),
                    section = obj.string("section") ?: fail("$file: $name names no section"),
                )
        }
    }

    /** The entry, or a failure naming it: a pin that reaches no entry reports a clean tree. */
    private fun limit(name: String): Limit = limits[name] ?: fail("schema/limits.json names no `$name`")

    /** Asserts an engine constant against an entry whose value is a plain count in [unit]. */
    private fun assertBound(
        name: String,
        unit: String,
        constant: String,
        value: Long,
    ) {
        val limit = limit(name)
        assertEquals(unit, limit.unit, "limits.json writes $name in `${limit.unit}`, not in $unit (${limit.section})")
        assertEquals(limit.value, value, "$constant against limits.json's $name (${limit.section})")
    }

    /** Asserts an engine constant in bytes against an entry the file writes in bytes or MiB. */
    private fun assertBytesBound(
        name: String,
        constant: String,
        value: Long,
    ) {
        val limit = limit(name)
        val scale =
            when (limit.unit) {
                "bytes" -> 1L
                "MiB" -> 1024L * 1024L
                else -> fail("limits.json writes $name in `${limit.unit}`, which this test cannot read as bytes")
            }
        assertEquals(limit.value * scale, value, "$constant against limits.json's $name (${limit.section})")
    }

    @Test
    fun `the canonical reader's nesting depth is the specification's`() {
        assertBound(
            "nesting_depth",
            "objects and arrays",
            "CanonicalJson.MAX_DEPTH",
            CanonicalJson.MAX_DEPTH.toLong(),
        )
    }

    @Test
    fun `a listing path's byte bound is the specification's`() {
        assertBytesBound("listing_path_bytes", "Payload.MAX_PATH_BYTES", Payload.MAX_PATH_BYTES.toLong())
    }

    @Test
    fun `the host's listing ceilings are the specification's`() {
        assertBound(
            "listing_paths",
            "paths",
            "HostProducer.MAX_LISTING_PATHS",
            HostProducer.MAX_LISTING_PATHS.toLong(),
        )
        assertBytesBound(
            "listing_path_bytes_total",
            "HostProducer.MAX_LISTING_BYTES",
            HostProducer.MAX_LISTING_BYTES,
        )
    }

    @Test
    fun `a display name's bound is the specification's`() {
        assertBound(
            "display_name_length",
            "UTF-16 code units",
            "Wire.MAX_DISPLAY_NAME_UNITS",
            Wire.MAX_DISPLAY_NAME_UNITS.toLong(),
        )
    }

    @Test
    fun `the close vocabulary fills the range the specification names`() {
        assertBound("close_code_min", "close code", "Wire.CLOSE_PROTOCOL_ERROR", Wire.CLOSE_PROTOCOL_ERROR.toLong())
        assertBound("close_code_max", "close code", "Wire.CLOSE_ROOM_GONE", Wire.CLOSE_ROOM_GONE.toLong())
        assertEquals(
            (Wire.CLOSE_PROTOCOL_ERROR..Wire.CLOSE_ROOM_GONE).toList(),
            listOf(
                Wire.CLOSE_PROTOCOL_ERROR,
                Wire.CLOSE_ROOM_UNKNOWN,
                Wire.CLOSE_TOKEN_INVALID,
                Wire.CLOSE_ROOM_GONE,
            ),
            "the closes of PROTOCOL.md §11 against the range limits.json bounds",
        )
    }
}
