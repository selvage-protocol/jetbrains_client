package dev.dontblameme.selvage.crdt

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine against real yjs, both ways, over seeded random scripts.
 *
 * - **Mirrored:** every peer is a Kotlin document and a yjs document with one client id, fed the
 *   same edits and the same remote updates in the same order. Each step must emit byte-identical
 *   updates and observer deltas, and leave byte-identical state, state vectors and pending
 *   structures.
 * - **Mixed:** Kotlin and yjs peers edit concurrently and exchange updates in shuffled order; all
 *   converge to one text and one state vector, and each decodes the other's full state.
 */
class DifferentialTest {
    private lateinit var yjs: YjsDriver

    @BeforeTest
    fun start() {
        yjs = YjsDriver()
    }

    @AfterTest
    fun stop() {
        yjs.close()
    }

    /** A Kotlin replica and what it has emitted. */
    private class KPeer(
        clientID: Long,
        gc: Boolean = true,
    ) {
        val doc = Doc(clientID, gc)
        val updates = ArrayList<ByteArray>()
        val events = ArrayList<String>()

        init {
            doc.onUpdate { update, _, _ -> updates.add(update) }
        }

        fun observe(path: String) {
            doc.getText(path).observe { e -> events.add(renderEvent(path, e.local, e.delta.map(::renderOp))) }
        }

        fun drainUpdates(): List<ByteArray> = updates.toList().also { updates.clear() }

        fun drainEvents(): List<String> = events.toList().also { events.clear() }
    }

    /** One logical peer: a Kotlin document and its yjs twin. */
    private inner class Twin(
        val name: String,
        clientID: Long,
        val paths: List<String>,
        gc: Boolean = true,
    ) {
        val k = KPeer(clientID, gc)

        init {
            yjs.call("new", "doc" to name, "clientID" to clientID, "gc" to gc)
            for (path in paths) {
                k.observe(path)
                yjs.call("observe", "doc" to name, "path" to path)
            }
        }

        fun text(path: String) = k.doc.getText(path).toString()

        fun insert(
            path: String,
            index: Int,
            text: String,
        ): List<ByteArray> {
            k.doc.getText(path).insert(index, text)
            yjs.call("insert", "doc" to name, "path" to path, "index" to index, "text" to text)
            return compareStep("insert $index ${Json.stringify(YAny.Str(text))} into $path")
        }

        fun delete(
            path: String,
            index: Int,
            length: Int,
        ): List<ByteArray> {
            k.doc.getText(path).delete(index, length)
            yjs.call("delete", "doc" to name, "path" to path, "index" to index, "length" to length)
            return compareStep("delete $length at $index from $path")
        }

        fun apply(update: ByteArray): List<ByteArray> {
            Updates.applyUpdate(k.doc, update, "remote")
            yjs.call("apply", "doc" to name, "update" to update)
            return compareStep("apply ${update.toHex()}")
        }

        /** Both sides emitted the same updates and events, and hold the same state. */
        fun compareStep(what: String): List<ByteArray> {
            val kUpdates = k.drainUpdates().map { it.toHex() }
            val yUpdates =
                (
                    yjs.call(
                        "updates",
                        "doc" to name,
                    )["updates"] as YAny.Arr
                ).items.map { (it as YAny.Str).value }
            assertEquals(yUpdates, kUpdates, "$name: updates emitted by $what")
            val yEvents =
                (yjs.call("events", "doc" to name)["events"] as YAny.Arr).items.map {
                    val e = it as YAny.Obj
                    renderEvent(
                        (e["path"] as YAny.Str).value,
                        e["local"] == YAny.Bool(true),
                        (e["delta"] as YAny.Arr).items,
                    )
                }
            assertEquals(yEvents, k.drainEvents(), "$name: observer deltas after $what")
            compareState(what)
            return kUpdates.map { it.hexToBytes() }
        }

        fun compareState(what: String) {
            for (path in paths) {
                assertEquals(
                    (yjs.call("text", "doc" to name, "path" to path)["text"] as YAny.Str).value,
                    text(path),
                    "$name: $path after $what",
                )
            }
            assertEquals(
                yjs.hex("sv", "sv", "doc" to name).toHex(),
                Updates.encodeStateVector(k.doc).toHex(),
                "$name: state vector after $what",
            )
            val pending = yjs.call("pending", "doc" to name)
            assertEquals(
                pending["structs"] == YAny.Bool(true),
                k.doc.store.pendingStructs != null,
                "$name: pending structs after $what",
            )
            assertEquals(
                pending["ds"] == YAny.Bool(true),
                k.doc.store.pendingDs != null,
                "$name: pending deletes after $what",
            )
            assertEquals(
                yjs.hex("state", "update", "doc" to name).toHex(),
                Updates.encodeStateAsUpdate(k.doc).toHex(),
                "$name: state after $what",
            )
        }
    }

    private class Message(
        val to: Int,
        val update: ByteArray,
    )

    /** Short runs of ASCII, BMP and astral text; lone surrogates only when [lone] is set. */
    private fun randomText(
        random: Random,
        lone: Boolean = false,
    ): String {
        val pieces =
            listOf(
                "a",
                "b",
                "c",
                "xyz",
                " ",
                "\n",
                "é",
                "π",
                "\uD83D\uDE00",
                "\uD83D\uDC69\u200D\uD83D\uDCBB",
                "𝄞",
                "\uD83D",
                "\uDE00",
            )
        val sb = StringBuilder()
        repeat(1 + random.nextInt(4)) {
            var piece = pieces[random.nextInt(pieces.size)]
            if (piece.length == 1 && piece[0].isSurrogate() && (!lone || random.nextInt(4) != 0)) piece = "s"
            sb.append(piece)
        }
        return sb.toString()
    }

    private fun clientIds(
        random: Random,
        n: Int,
    ): List<Long> {
        val ids = LinkedHashSet<Long>()
        while (ids.size < n) {
            ids.add(
                when (random.nextInt(4)) {
                    0 -> random.nextLong(0, 16)

                    // small ids: ties in client order are common
                    1 -> random.nextLong(1L shl 32, MAX_SAFE_INTEGER)

                    // yrs draws up to 2^53
                    else -> random.nextLong(0, 1L shl 32)
                },
            )
        }
        return ids.toList()
    }

    /**
     * A seeded script over mirrored peers: edits biased to the same few positions, messages
     * delivered in random order (so structs and deletes wait as pending), and a full flush.
     *
     * With [lone] set, edits may type lone surrogates. yjs keeps one in the replica that typed it
     * and writes it as U+FFFD, and splitting after a lone high surrogate replaces the character
     * after it as well, locally only: the peers' texts then differ for good. The script still
     * holds each peer to its yjs twin, and all peers to one state vector.
     */
    private fun mirroredScript(
        seed: Int,
        peers: Int,
        steps: Int,
        gc: Boolean,
        lone: Boolean = false,
    ) {
        val random = Random(seed)
        val paths = listOf("src/main.rs", "README.md", "docs/ü.txt").take(1 + random.nextInt(3))
        val ids = clientIds(random, peers)
        val twins = ids.mapIndexed { i, id -> Twin("s$seed-p$i", id, paths, gc) }
        val inFlight = ArrayList<Message>()

        fun broadcast(
            from: Int,
            updates: List<ByteArray>,
        ) {
            for (update in updates) for (to in twins.indices) if (to != from) inFlight.add(Message(to, update))
        }
        repeat(steps) {
            val action = random.nextInt(10)
            if (action < 4 || inFlight.isEmpty()) {
                val from = random.nextInt(twins.size)
                val twin = twins[from]
                val path = paths[random.nextInt(paths.size)]
                val len =
                    twin.k.doc
                        .getText(path)
                        .length
                if (len > 0 && random.nextInt(3) == 0) {
                    val index = random.nextInt(len)
                    val length = 1 + random.nextInt(minOf(len - index, 6))
                    broadcast(from, twin.delete(path, index, length))
                } else {
                    // Same-position inserts: the start, the end, or one of the first few.
                    val index =
                        when (random.nextInt(3)) {
                            0 -> 0
                            1 -> len
                            else -> random.nextInt(minOf(len, 3) + 1)
                        }
                    broadcast(from, twin.insert(path, index, randomText(random, lone)))
                }
            } else {
                val message = inFlight.removeAt(random.nextInt(inFlight.size))
                broadcast(message.to, twins[message.to].apply(message.update))
            }
        }
        while (inFlight.isNotEmpty()) {
            val message = inFlight.removeAt(random.nextInt(inFlight.size))
            broadcast(message.to, twins[message.to].apply(message.update))
        }
        val first = twins[0]
        for (twin in twins) {
            if (!lone) {
                for (path in paths) {
                    assertEquals(
                        first.text(path),
                        twin.text(path),
                        "seed $seed: ${twin.name} converged on $path",
                    )
                }
            }
            assertEquals(
                first.k.doc.store
                    .stateVector(),
                twin.k.doc.store
                    .stateVector(),
                "seed $seed: ${twin.name} state vector",
            )
            assertTrue(
                twin.k.doc.store.pendingStructs == null && twin.k.doc.store.pendingDs == null,
                "seed $seed: nothing pending",
            )
        }
    }

    @Test
    fun mirrored_peers_match_yjs_byte_for_byte() {
        for (seed in 1..40) mirroredScript(seed, peers = 2 + seed % 3, steps = 60, gc = true)
    }

    @Test
    fun mirrored_peers_match_yjs_with_lone_surrogates() {
        for (seed in 51..70) mirroredScript(seed, peers = 3, steps = 50, gc = true, lone = true)
    }

    @Test
    fun a_lone_high_surrogate_diverges_the_typing_replica_as_in_yjs() {
        val a = Twin("lone-a", 1, listOf("f"))
        val b = Twin("lone-b", 2, listOf("f"))
        for (u in a.insert("f", 0, "\uD83D\u00E9")) b.apply(u)
        for (u in a.insert("f", 1, "x")) b.apply(u)
        assertEquals("\uFFFDx\uFFFD", a.text("f"))
        assertEquals("\uFFFDx\u00E9", b.text("f"))
        assertEquals(
            a.k.doc.store
                .stateVector(),
            b.k.doc.store
                .stateVector(),
        )
    }

    @Test
    fun mirrored_peers_match_yjs_without_gc() {
        for (seed in 101..115) mirroredScript(seed, peers = 3, steps = 60, gc = false)
    }

    @Test
    fun concurrent_inserts_at_one_position_order_like_yjs() {
        // Every peer inserts at 0 and at the end before seeing any other: pure YATA conflicts.
        for (seed in 201..220) {
            val random = Random(seed)
            val ids = clientIds(random, 4)
            val twins = ids.mapIndexed { i, id -> Twin("c$seed-p$i", id, listOf("f")) }
            val sent = ArrayList<Pair<Int, ByteArray>>()
            for ((i, twin) in twins.withIndex()) {
                twin.insert("f", 0, "<$i>").forEach { sent.add(i to it) }
                twin.insert("f", twin.text("f").length, "[$i]").forEach { sent.add(i to it) }
                twin.insert("f", 1, "\uD83D\uDE00").forEach { sent.add(i to it) }
            }
            sent.shuffle(random)
            for ((from, update) in sent) {
                for ((to, twin) in twins.withIndex()) if (to != from) twin.apply(update)
            }
            for (twin in twins) assertEquals(twins[0].text("f"), twin.text("f"), "seed $seed")
        }
    }

    @Test
    fun deletes_across_items_and_surrogate_splits_match_yjs() {
        val a = Twin("d-a", 7, listOf("f"))
        val b = Twin("d-b", 3, listOf("f"))
        val updates = ArrayList<ByteArray>()
        updates += a.insert("f", 0, "ab\uD83D\uDE00cd")
        updates += a.insert("f", 2, "XY")
        updates += a.insert("f", 8, "\uD83D\uDC69\u200D\uD83D\uDCBB")
        for (u in updates) b.apply(u)
        // Deleting from the middle of one pair to the middle of another splits both.
        val del = b.delete("f", 5, 4)
        for (u in del) a.apply(u)
        // An insert between the halves of a pair splits it; yjs writes both halves as U+FFFD.
        val ins = a.insert("f", 3, "|")
        for (u in ins) b.apply(u)
        assertEquals(a.text("f"), b.text("f"))
        // One delete spanning items from two clients.
        val span = a.delete("f", 1, a.text("f").length - 2)
        for (u in span) b.apply(u)
        assertEquals(a.text("f"), b.text("f"))
    }

    @Test
    fun mixed_peers_converge_and_cross_decode() {
        for (seed in 301..330) {
            val random = Random(seed)
            val paths = listOf("a.txt", "b/c.kt")
            val ids = clientIds(random, 4)
            // Even peers are Kotlin, odd ones yjs.
            val kotlin = HashMap<Int, KPeer>()
            val names = ids.indices.map { "m$seed-p$it" }
            for ((i, id) in ids.withIndex()) {
                if (i % 2 == 0) kotlin[i] = KPeer(id) else yjs.call("new", "doc" to names[i], "clientID" to id)
            }

            fun text(
                i: Int,
                path: String,
            ): String =
                kotlin[i]?.doc?.getText(path)?.toString()
                    ?: (yjs.call("text", "doc" to names[i], "path" to path)["text"] as YAny.Str).value

            fun drain(i: Int): List<ByteArray> =
                kotlin[i]?.drainUpdates()
                    ?: (
                        yjs.call(
                            "updates",
                            "doc" to names[i],
                        )["updates"] as YAny.Arr
                    ).items.map { (it as YAny.Str).value.hexToBytes() }

            fun apply(
                i: Int,
                update: ByteArray,
            ) {
                val k = kotlin[i]
                if (k !=
                    null
                ) {
                    Updates.applyUpdate(k.doc, update, "remote")
                } else {
                    yjs.call(
                        "apply",
                        "doc" to names[i],
                        "update" to update,
                    )
                }
            }
            val inFlight = ArrayList<Message>()

            fun flushFrom(i: Int) {
                for (u in drain(i)) for (to in ids.indices) if (to != i) inFlight.add(Message(to, u))
            }
            repeat(80) {
                if (random.nextInt(10) < 5 || inFlight.isEmpty()) {
                    val i = random.nextInt(ids.size)
                    val path = paths[random.nextInt(paths.size)]
                    val len = text(i, path).length
                    val k = kotlin[i]
                    if (len > 0 && random.nextInt(3) == 0) {
                        val index = random.nextInt(len)
                        val length = 1 + random.nextInt(minOf(len - index, 5))
                        if (k != null) {
                            k.doc.getText(path).delete(index, length)
                        } else {
                            yjs.call("delete", "doc" to names[i], "path" to path, "index" to index, "length" to length)
                        }
                    } else {
                        val index = if (random.nextBoolean()) 0 else random.nextInt(len + 1)
                        val t = randomText(random)
                        if (k != null) {
                            k.doc.getText(path).insert(index, t)
                        } else {
                            yjs.call("insert", "doc" to names[i], "path" to path, "index" to index, "text" to t)
                        }
                    }
                    flushFrom(i)
                } else {
                    val m = inFlight.removeAt(random.nextInt(inFlight.size))
                    apply(m.to, m.update)
                    flushFrom(m.to)
                }
            }
            while (inFlight.isNotEmpty()) {
                val m = inFlight.removeAt(random.nextInt(inFlight.size))
                apply(m.to, m.update)
                flushFrom(m.to)
            }
            val kRef = kotlin.getValue(0)
            val kSv = Updates.encodeStateVector(kRef.doc)
            for (i in ids.indices) {
                for (path in paths) assertEquals(text(0, path), text(i, path), "seed $seed: peer $i on $path")
                val sv = kotlin[i]?.let { Updates.encodeStateVector(it.doc) } ?: yjs.hex("sv", "sv", "doc" to names[i])
                assertContentEquals(kSv, sv, "seed $seed: peer $i state vector")
            }
            // Cross-decoding: a fresh replica of each kind rebuilt from the other's full state.
            val yState = yjs.hex("state", "update", "doc" to names[1])
            val rebuilt = Doc(999_999)
            Updates.applyUpdate(rebuilt, yState)
            for (path in paths) {
                assertEquals(
                    text(1, path),
                    rebuilt.getText(path).toString(),
                    "seed $seed: Kotlin from yjs state",
                )
            }
            assertContentEquals(kSv, Updates.encodeStateVector(rebuilt))
            assertEquals(
                yState.toHex(),
                Updates.encodeStateAsUpdate(rebuilt).toHex(),
                "seed $seed: re-encoded yjs state",
            )
            yjs.call("new", "doc" to "m$seed-fresh", "clientID" to 999_999)
            yjs.call("apply", "doc" to "m$seed-fresh", "update" to Updates.encodeStateAsUpdate(kRef.doc))
            for (path in paths) {
                assertEquals(
                    text(0, path),
                    (yjs.call("text", "doc" to "m$seed-fresh", "path" to path)["text"] as YAny.Str).value,
                )
            }
            assertContentEquals(kSv, yjs.hex("sv", "sv", "doc" to "m$seed-fresh"))
            // The diff against a peer's old state is what yjs computes for it.
            val half =
                Updates.encodeStateVector(
                    kotlin
                        .getValue(2)
                        .doc.store
                        .stateVector()
                        .mapValues { it.value / 2 },
                )
            assertEquals(
                yjs.hex("state", "update", "doc" to names[1], "sv" to half).toHex(),
                Updates.encodeStateAsUpdate(rebuilt, half).toHex(),
                "seed $seed: state since a half state vector",
            )
        }
    }

    @Test
    fun merge_and_diff_match_yjs() {
        for (seed in 401..420) {
            val random = Random(seed)
            val a = KPeer(random.nextLong(0, 1L shl 32))
            val b = KPeer(random.nextLong(0, 1L shl 32))
            val all = ArrayList<ByteArray>()
            repeat(30) {
                val p = if (random.nextBoolean()) a else b
                val t = p.doc.getText("f")
                if (t.length > 2 &&
                    random.nextInt(3) == 0
                ) {
                    t.delete(random.nextInt(t.length - 1), 1)
                } else {
                    t.insert(
                        random.nextInt(t.length + 1),
                        randomText(random),
                    )
                }
                val us = p.drainUpdates()
                all += us
                val other = if (p === a) b else a
                for (u in us) Updates.applyUpdate(other.doc, u)
                other.drainUpdates()
            }
            // Gaps between the merged updates are written as skips.
            val subset = all.filterIndexed { i, _ -> i % 3 != 1 }.shuffled(random)
            for (updates in listOf(all, subset, all.shuffled(random).take(5))) {
                val merged = Updates.mergeUpdates(updates)
                assertEquals(
                    yjs.hex("merge", "update", "updates" to updates).toHex(),
                    merged.toHex(),
                    "seed $seed: merge",
                )
                val sv = Updates.encodeStateVector(mapOf(a.doc.clientID to 2L, b.doc.clientID to 5L))
                assertEquals(
                    yjs.hex("diff", "update", "update" to merged, "sv" to sv).toHex(),
                    Updates.diffUpdate(merged, sv).toHex(),
                    "seed $seed: diff",
                )
                val doc = Doc(3)
                Updates.applyUpdate(doc, merged)
                yjs.call("new", "doc" to "g$seed-${updates.size}", "clientID" to 3)
                yjs.call("apply", "doc" to "g$seed-${updates.size}", "update" to merged)
                assertEquals(
                    yjs.hex("state", "update", "doc" to "g$seed-${updates.size}").toHex(),
                    Updates.encodeStateAsUpdate(doc).toHex(),
                    "seed $seed: state with pending",
                )
            }
        }
    }

    @Test
    fun contents_the_engine_does_not_edit_survive_a_round_trip() {
        for (kind in listOf("embed", "format", "map", "array", "nested", "xml", "subdoc")) {
            val name = "rich-$kind"
            yjs.call("new", "doc" to name, "clientID" to 5)
            yjs.call("insert", "doc" to name, "path" to "p", "index" to 0, "text" to "hello")
            yjs.call("rich", "doc" to name, "path" to "p", "kind" to kind)
            val state = yjs.hex("state", "update", "doc" to name)
            assertEquals(
                yjs.hex("reencode", "update", "update" to state).toHex(),
                Updates.reencodeUpdate(state).toHex(),
                "$kind: struct re-encoding",
            )
            val doc = Doc(9)
            Updates.applyUpdate(doc, state)
            assertEquals(state.toHex(), Updates.encodeStateAsUpdate(doc).toHex(), "$kind: state kept byte for byte")
            assertEquals(
                (yjs.call("text", "doc" to name, "path" to "p")["text"] as YAny.Str).value,
                doc.getText("p").toString(),
                kind,
            )
            // An edit on top travels back, and both sides agree on the result.
            val updates = ArrayList<ByteArray>()
            doc.onUpdate { u, _, _ -> updates.add(u) }
            doc.getText("p").insert(1, "\uD83D\uDE00")
            doc.getText("p").delete(0, 1)
            for (u in updates) yjs.call("apply", "doc" to name, "update" to u)
            assertEquals(
                (yjs.call("text", "doc" to name, "path" to "p")["text"] as YAny.Str).value,
                doc.getText("p").toString(),
                kind,
            )
            assertEquals(
                yjs.hex("state", "update", "doc" to name).toHex(),
                Updates.encodeStateAsUpdate(doc).toHex(),
                "$kind: after an edit",
            )
        }
    }

    @Test
    fun relative_positions_match_yjs() {
        for (seed in 501..510) {
            val random = Random(seed)
            val twin = Twin("r$seed", random.nextLong(0, 1L shl 32), listOf("src/lib.rs"))
            repeat(12) {
                val len = twin.text("src/lib.rs").length
                if (len > 3 && random.nextInt(3) == 0) {
                    twin.delete("src/lib.rs", random.nextInt(len - 2), 2)
                } else {
                    twin.insert("src/lib.rs", random.nextInt(len + 1), randomText(random))
                }
            }
            val len = twin.text("src/lib.rs").length
            for (index in 0..len) {
                for (assoc in listOf(0, -1)) {
                    val y =
                        yjs.call(
                            "relpos",
                            "doc" to twin.name,
                            "path" to "src/lib.rs",
                            "index" to index,
                            "assoc" to assoc,
                        )["rpos"]!!
                    val k = RelativePosition.fromIndex(twin.k.doc.getText("src/lib.rs"), index, assoc)
                    assertEquals(Json.stringify(y), Json.stringify(k.toJson()), "seed $seed: position $index/$assoc")
                    for (form in listOf(k, k.toYrsForm())) {
                        val yIndex = yjs.call("resolve", "doc" to twin.name, "rpos" to form.toJson())["index"]
                        assertEquals(
                            yIndex,
                            form.resolve(twin.k.doc, "src/lib.rs")?.let {
                                YAny.Num(it.toDouble())
                            },
                            "seed $seed: resolve $index/$assoc",
                        )
                    }
                }
            }
            // Deleting the element an anchor names leaves it at the surviving boundary, as in yjs.
            val anchors = (0..len).map { RelativePosition.fromIndex(twin.k.doc.getText("src/lib.rs"), it) }
            if (len > 2) twin.delete("src/lib.rs", 1, len - 2)
            for (a in anchors) {
                val yIndex = yjs.call("resolve", "doc" to twin.name, "rpos" to a.toJson())["index"]
                assertEquals(
                    yIndex,
                    a.resolve(twin.k.doc, "src/lib.rs")?.let {
                        YAny.Num(it.toDouble())
                    },
                    "seed $seed: resolve after delete",
                )
            }
        }
    }

    @Test
    fun awareness_updates_cross_decode() {
        val state =
            Json.parse(
                """{"path":"src/main.rs","selection":{"anchor":{"tname":"src/main.rs","item":{"client":5,"clock":1},"assoc":0},"head":{"item":{"client":5,"clock":2},"assoc":-1}},"z":[1.5,"\ud83d"]}""",
            )
        var t = 0L
        val k = Awareness(42, renewMs = 100, expireMs = 300) { t }
        k.setLocalState(state)
        val fromYjs = yjs.hex("awarenessEncode", "update", "clientID" to 42, "states" to listOf(state))
        assertEquals(fromYjs.toHex(), k.encodeUpdate(listOf(42)).toHex())
        val applied = yjs.call("awarenessApply", "updates" to listOf(k.encodeUpdate(listOf(42))))
        val states = (applied["states"] as YAny.Arr).items.map { it as YAny.Obj }
        val remote = states.single { it["client"] == YAny.Num(42.0) }
        assertEquals(Json.stringify(state), Json.stringify(remote["state"]!!))
        assertEquals(YAny.Num(1.0), remote["clock"])
        // Clearing the state reaches yjs as a removal.
        k.setLocalState(null)
        val removed = yjs.call("awarenessApply", "updates" to listOf(fromYjs, k.encodeUpdate(listOf(42))))
        assertTrue((removed["states"] as YAny.Arr).items.none { (it as YAny.Obj)["client"] == YAny.Num(42.0) })
    }

    companion object {
        fun renderOp(op: TextDelta): YAny =
            when (op) {
                is TextDelta.Insert -> YAny.Obj.of("insert" to YAny.Str(op.text))
                is TextDelta.InsertEmbed -> YAny.Obj.of("embed" to YAny.Bool(true))
                is TextDelta.Retain -> YAny.Obj.of("retain" to YAny.Num(op.length.toDouble()))
                is TextDelta.Delete -> YAny.Obj.of("delete" to YAny.Num(op.length.toDouble()))
            }

        fun renderEvent(
            path: String,
            local: Boolean,
            delta: List<YAny>,
        ): String =
            Json.stringify(
                YAny.Obj.of(
                    "path" to YAny.Str(path),
                    "local" to YAny.Bool(local),
                    "delta" to YAny.Arr(delta),
                ),
            )!!
    }
}
