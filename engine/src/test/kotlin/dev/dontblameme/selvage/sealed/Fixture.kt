package dev.dontblameme.selvage.sealed

import dev.dontblameme.selvage.canonical.CanonicalJson
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.crdt.TestPaths
import java.io.File
import kotlin.test.fail

/** The specification's peer vectors and their key fixture. */
object PeerVectors {
    val dir: File by lazy { File(TestPaths.specification, "vectors/peer") }

    fun load(): List<JsonValue.Obj> {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) fail("no peer vectors in $dir")
        return files.map { CanonicalJson.parseObject(it.readText()) }
    }

    val fixture: Fixture by lazy {
        Fixture(CanonicalJson.parseObject(File(dir.parentFile, "fixture/keys.json").readText()))
    }
}

class Fixture(
    json: JsonValue.Obj,
) {
    private val room = json.obj("room")!!
    val roomId: String = room.string("id")!!
    val roomKey: ByteArray = Bytes.fromHex(room.string("key")!!)
    val keys: Map<String, SessionKey> =
        json.obj("keys")!!.members.mapValues { (_, v) ->
            SessionKey.fromSeed(Bytes.fromHex((v as JsonValue.Obj).string("private")!!))
        }
    val hostKeyName: String = room.string("host")!!
    val host: SessionKey get() = keys.getValue(hostKeyName)
    val frameKey: ByteArray get() = Frames.frameKey(roomId, roomKey)

    fun key(name: String): SessionKey = keys[name] ?: fail("the fixture has no key `$name`")

    fun reader(): Reader = Reader(roomId, roomKey, host.public)
}

fun JsonValue?.str(): String = (this as? JsonValue.Str)?.value ?: fail("expected a string, got $this")

fun JsonValue?.long(): Long = (this as? JsonValue.Number)?.integer() ?: fail("expected an integer, got $this")

fun JsonValue?.obj(): JsonValue.Obj = this as? JsonValue.Obj ?: fail("expected an object, got $this")

fun JsonValue?.strings(): List<String> =
    (this as? JsonValue.Arr)?.items?.map { it.str() } ?: fail("expected an array, got $this")
