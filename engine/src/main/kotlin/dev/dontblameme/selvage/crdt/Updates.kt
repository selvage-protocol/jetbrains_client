package dev.dontblameme.selvage.crdt

/**
 * The v1 update format: applying one to a document, encoding a document's state and state
 * vector, and merging and diffing updates without a document. Each follows yjs 13.x.
 */
object Updates {
    /** Applies [update]; what cannot be integrated yet is kept pending until its dependencies arrive. */
    fun applyUpdate(
        doc: Doc,
        update: ByteArray,
        origin: Any? = null,
    ) {
        doc.transact(origin, local = false) { transaction ->
            readUpdate(UpdateDecoder(update), transaction)
        }
    }

    private fun readUpdate(
        decoder: UpdateDecoder,
        transaction: Transaction,
    ) {
        transaction.local = false
        val doc = transaction.doc
        val store = doc.store
        var retry = false
        val refs = readClientsStructRefs(decoder, doc)
        val restStructs = integrateStructs(transaction, store, refs)
        val pending = store.pendingStructs
        if (pending != null) {
            // A pending struct whose missing clock is now here can be retried.
            for ((client, clock) in pending.missing) {
                if (clock < store.getState(client)) {
                    retry = true
                    break
                }
            }
            if (restStructs != null) {
                for ((client, clock) in restStructs.missing) {
                    val mclock = pending.missing[client]
                    if (mclock == null || mclock > clock) pending.missing[client] = clock
                }
                pending.update = mergeUpdates(listOf(pending.update, restStructs.update))
            }
        } else {
            store.pendingStructs = restStructs
        }
        val dsRest = DeleteSet.readAndApply(decoder, transaction, store)
        val pendingDs = store.pendingDs
        if (pendingDs != null) {
            val pendingDecoder = UpdateDecoder(pendingDs)
            pendingDecoder.rest.readVarUint() // no structs: a pending delete set is deletes only
            val dsRest2 = DeleteSet.readAndApply(pendingDecoder, transaction, store)
            store.pendingDs =
                if (dsRest != null && dsRest2 != null) mergeUpdates(listOf(dsRest, dsRest2)) else dsRest ?: dsRest2
        } else {
            store.pendingDs = dsRest
        }
        if (retry) {
            val retryUpdate = store.pendingStructs!!.update
            store.pendingStructs = null
            readUpdate(UpdateDecoder(retryUpdate), transaction)
        }
    }

    private class ClientRefs(
        var i: Int,
        var refs: List<Struct>,
    )

    private fun readClientsStructRefs(
        decoder: UpdateDecoder,
        doc: Doc,
    ): MutableMap<Long, ClientRefs> {
        val clientRefs = HashMap<Long, ClientRefs>()
        val numOfStateUpdates = decoder.rest.readLength()
        repeat(numOfStateUpdates) {
            val numberOfStructs = decoder.rest.readLength()
            val client = decoder.readClient()
            var clock = decoder.rest.readVarUint()
            val refs = ArrayList<Struct>(minOf(numberOfStructs, 1024))
            clientRefs[client] = ClientRefs(0, refs)
            repeat(numberOfStructs) {
                val struct = readStruct(decoder, client, clock, doc::get)
                refs.add(struct)
                clock += struct.length
            }
        }
        return clientRefs
    }

    /**
     * Integrates the structs of every client, highest client first, following each struct's
     * missing dependency to its client's structs. What cannot be integrated is returned as an
     * update, with the lowest missing clock per client.
     */
    private fun integrateStructs(
        transaction: Transaction,
        store: StructStore,
        clientsStructRefs: MutableMap<Long, ClientRefs>,
    ): PendingStructs? {
        val stack = ArrayList<Struct>()
        var ids = clientsStructRefs.keys.sorted().toMutableList()
        if (ids.isEmpty()) return null

        fun nextTarget(): ClientRefs? {
            if (ids.isEmpty()) return null
            var target = clientsStructRefs.getValue(ids.last())
            while (target.refs.size == target.i) {
                ids.removeAt(ids.size - 1)
                if (ids.isNotEmpty()) target = clientsStructRefs.getValue(ids.last()) else return null
            }
            return target
        }
        var current = nextTarget() ?: return null
        val restStructs = StructStore()
        val missingSV = HashMap<Long, Long>()

        fun updateMissingSv(
            client: Long,
            clock: Long,
        ) {
            val mclock = missingSV[client]
            if (mclock == null || mclock > clock) missingSV[client] = clock
        }
        var stackHead: Struct = current.refs[current.i++]
        val state = HashMap<Long, Long>()

        fun addStackToRestSS() {
            for (item in stack) {
                val client = item.id.client
                val inapplicable = clientsStructRefs[client]
                if (inapplicable != null) {
                    // The rest of this client's structs cannot be applied either.
                    inapplicable.i--
                    restStructs.clients[client] =
                        inapplicable.refs.subList(inapplicable.i, inapplicable.refs.size).toMutableList()
                    clientsStructRefs.remove(client)
                    inapplicable.i = 0
                    inapplicable.refs = emptyList()
                } else {
                    restStructs.clients[client] = mutableListOf(item)
                }
                ids = ids.filter { it != client }.toMutableList()
            }
            stack.clear()
        }
        var currentTarget: ClientRefs? = current
        while (true) {
            if (stackHead !is Skip) {
                val head = stackHead
                val localClock = state.getOrPut(head.id.client) { store.getState(head.id.client) }
                val offset = localClock - head.id.clock
                if (offset < 0) {
                    // A gap before this struct: it waits for the clock before it.
                    stack.add(head)
                    updateMissingSv(head.id.client, head.id.clock - 1)
                    addStackToRestSS()
                } else {
                    val missing = head.getMissing(transaction, store)
                    if (missing != null) {
                        stack.add(head)
                        // Integrate the missing client's structs first, if this update has them.
                        val structRefs = clientsStructRefs[missing] ?: ClientRefs(0, emptyList())
                        if (structRefs.refs.size == structRefs.i) {
                            updateMissingSv(missing, store.getState(missing))
                            addStackToRestSS()
                        } else {
                            stackHead = structRefs.refs[structRefs.i++]
                            continue
                        }
                    } else if (offset == 0L || offset < head.length) {
                        head.integrate(transaction, offset.toInt())
                        state[head.id.client] = head.id.clock + head.length
                    }
                }
            }
            if (stack.isNotEmpty()) {
                stackHead = stack.removeAt(stack.size - 1)
            } else if (currentTarget != null && currentTarget.i < currentTarget.refs.size) {
                stackHead = currentTarget.refs[currentTarget.i++]
            } else {
                currentTarget = nextTarget()
                if (currentTarget == null) break
                stackHead = currentTarget.refs[currentTarget.i++]
            }
        }
        if (restStructs.clients.isEmpty()) return null
        val encoder = UpdateEncoder()
        writeClientsStructs(encoder, restStructs, emptyMap())
        encoder.rest.writeVarUint(0) // no deletes
        return PendingStructs(missingSV, encoder.toByteArray())
    }

    private fun writeStructs(
        encoder: UpdateEncoder,
        structs: List<Struct>,
        client: Long,
        fromClock: Long,
    ) {
        // Make sure the first id exists.
        val clock = maxOf(fromClock, structs[0].id.clock)
        val start = StructStore.findIndex(structs, clock)
        encoder.rest.writeVarUint(structs.size - start)
        encoder.writeClient(client)
        encoder.rest.writeVarUint(clock)
        val first = structs[start]
        first.write(encoder, (clock - first.id.clock).toInt())
        for (i in start + 1 until structs.size) structs[i].write(encoder, 0)
    }

    /** Every struct of [store] from [targetState] on, clients in descending order. */
    internal fun writeClientsStructs(
        encoder: UpdateEncoder,
        store: StructStore,
        targetState: Map<Long, Long>,
    ) {
        val sm = HashMap<Long, Long>()
        for ((client, clock) in targetState) {
            if (store.getState(client) > clock) sm[client] = clock
        }
        for (client in store.clients.keys) {
            if (!targetState.containsKey(client)) sm[client] = 0
        }
        encoder.rest.writeVarUint(sm.size)
        for ((client, clock) in sm.entries.sortedByDescending { it.key }) {
            writeStructs(encoder, store.clients.getValue(client), client, clock)
        }
    }

    /** The document's state from [targetStateVector] on, pending structs and deletes included. */
    fun encodeStateAsUpdate(
        doc: Doc,
        targetStateVector: ByteArray = byteArrayOf(0),
    ): ByteArray {
        val target = decodeStateVector(targetStateVector)
        val encoder = UpdateEncoder()
        writeClientsStructs(encoder, doc.store, target)
        DeleteSet.fromStore(doc.store).write(encoder)
        val updates = arrayListOf(encoder.toByteArray())
        doc.store.pendingDs?.let { updates.add(it) }
        doc.store.pendingStructs?.let { updates.add(diffUpdate(it.update, targetStateVector)) }
        return if (updates.size > 1) mergeUpdates(updates) else updates[0]
    }

    fun encodeStateVector(doc: Doc): ByteArray = encodeStateVector(doc.store.stateVector())

    fun encodeStateVector(sv: Map<Long, Long>): ByteArray {
        val encoder = Lib0Encoder()
        encoder.writeVarUint(sv.size)
        for ((client, clock) in sv.entries.sortedByDescending { it.key }) {
            encoder.writeVarUint(client)
            encoder.writeVarUint(clock)
        }
        return encoder.toByteArray()
    }

    fun decodeStateVector(bytes: ByteArray): Map<Long, Long> {
        val decoder = Lib0Decoder(bytes)
        val sv = LinkedHashMap<Long, Long>()
        repeat(decoder.readLength()) {
            val client = decoder.readVarUint()
            sv[client] = decoder.readVarUint()
        }
        return sv
    }

    /** The structs and delete set of an update, as written. */
    class Decoded(
        val structs: List<Struct>,
        val deleteSet: DeleteSet,
    )

    fun decodeUpdate(update: ByteArray): Decoded {
        val decoder = UpdateDecoder(update)
        val reader = LazyStructReader(decoder, filterSkips = false)
        val structs = ArrayList<Struct>()
        var curr = reader.curr
        while (curr != null) {
            structs.add(curr)
            curr = reader.next()
        }
        return Decoded(structs, DeleteSet.read(decoder))
    }

    /** Re-encodes an update struct by struct: the bytes are the input's when it is canonical. */
    fun reencodeUpdate(update: ByteArray): ByteArray {
        val decoded = decodeUpdate(update)
        val encoder = UpdateEncoder()
        val writer = LazyStructWriter(encoder)
        for (struct in decoded.structs) writer.write(struct, 0)
        writer.finish()
        decoded.deleteSet.write(encoder)
        return encoder.toByteArray()
    }

    private class LazyStructReader(
        decoder: UpdateDecoder,
        val filterSkips: Boolean,
    ) {
        private val iterator = structs(decoder).iterator()
        var curr: Struct? = null

        init {
            next()
        }

        fun next(): Struct? {
            do {
                curr = if (iterator.hasNext()) iterator.next() else null
            } while (filterSkips && curr is Skip)
            return curr
        }

        private fun structs(decoder: UpdateDecoder): Sequence<Struct> =
            sequence {
                val numOfStateUpdates = decoder.rest.readLength()
                repeat(numOfStateUpdates) {
                    val numberOfStructs = decoder.rest.readLength()
                    val client = decoder.readClient()
                    var clock = decoder.rest.readVarUint()
                    repeat(numberOfStructs) {
                        val struct = readStruct(decoder, client, clock, null)
                        clock += struct.length
                        yield(struct)
                    }
                }
            }
    }

    private class LazyStructWriter(
        val encoder: UpdateEncoder,
    ) {
        private var currClient = 0L
        private var written = 0
        private var part = UpdateEncoder()
        private val parts = ArrayList<Pair<Int, ByteArray>>()

        private fun flush() {
            if (written > 0) {
                parts.add(written to part.toByteArray())
                part = UpdateEncoder()
                written = 0
            }
        }

        fun write(
            struct: Struct,
            offset: Int,
        ) {
            if (written > 0 && currClient != struct.id.client) flush()
            if (written == 0) {
                currClient = struct.id.client
                part.writeClient(struct.id.client)
                part.rest.writeVarUint(struct.id.clock + offset)
            }
            struct.write(part, offset)
            written++
        }

        fun finish() {
            flush()
            encoder.rest.writeVarUint(parts.size)
            for ((count, bytes) in parts) {
                encoder.rest.writeVarUint(count)
                encoder.rest.writeBytes(bytes)
            }
        }
    }

    /** The right part of [left] from [diff], without a document: yjs's `sliceStruct`. */
    private fun sliceStruct(
        left: Struct,
        diff: Int,
    ): Struct =
        when (left) {
            is GC -> {
                GC(ID(left.id.client, left.id.clock + diff), left.length - diff)
            }

            is Skip -> {
                Skip(ID(left.id.client, left.id.clock + diff), left.length - diff)
            }

            is Item -> {
                Item(
                    ID(left.id.client, left.id.clock + diff),
                    null,
                    ID(left.id.client, left.id.clock + diff - 1),
                    null,
                    left.rightOrigin,
                    left.parent,
                    left.parentSub,
                    left.content.splice(diff),
                    left.parentYKey,
                    left.parentID,
                )
            }
        }

    /** One update holding everything [updates] hold: yjs's `mergeUpdates`. */
    fun mergeUpdates(updates: List<ByteArray>): ByteArray {
        if (updates.size == 1) return updates[0]
        val updateDecoders = updates.map { UpdateDecoder(it) }
        var readers = updateDecoders.map { LazyStructReader(it, filterSkips = true) }
        var currWrite: Pair<Struct, Int>? = null
        val updateEncoder = UpdateEncoder()
        val writer = LazyStructWriter(updateEncoder)
        val order =
            Comparator<LazyStructReader> { a, b ->
                val s1 = a.curr!!
                val s2 = b.curr!!
                if (s1.id.client == s2.id.client) {
                    val clockDiff = s1.id.clock.compareTo(s2.id.clock)
                    if (clockDiff == 0) {
                        if (s1::class == s2::class) {
                            0
                        } else if (s1 is Skip) {
                            1
                        } else {
                            -1
                        }
                    } else {
                        clockDiff
                    }
                } else {
                    s2.id.client.compareTo(s1.id.client)
                }
            }
        while (true) {
            readers = readers.filter { it.curr != null }.sortedWith(order)
            if (readers.isEmpty()) break
            val currDecoder = readers[0]
            val firstClient = currDecoder.curr!!.id.client
            if (currWrite != null) {
                var curr: Struct? = currDecoder.curr
                var iterated = false
                // Skip what the struct being written already covers.
                val written = currWrite.first
                while (curr != null && curr.id.clock + curr.length <= written.id.clock + written.length &&
                    curr.id.client >= written.id.client
                ) {
                    curr = currDecoder.next()
                    iterated = true
                }
                if (curr == null ||
                    curr.id.client != firstClient ||
                    (iterated && curr.id.clock > written.id.clock + written.length)
                ) {
                    continue
                }
                if (firstClient != written.id.client) {
                    writer.write(written, currWrite.second)
                    currWrite = curr to 0
                    currDecoder.next()
                } else if (written.id.clock + written.length < curr.id.clock) {
                    // A gap: fill it with a skip.
                    if (written is Skip) {
                        written.length = (curr.id.clock + curr.length - written.id.clock).toInt()
                    } else {
                        writer.write(written, currWrite.second)
                        val diff = curr.id.clock - written.id.clock - written.length
                        currWrite = Skip(ID(firstClient, written.id.clock + written.length), diff.toInt()) to 0
                    }
                } else {
                    val diff = (written.id.clock + written.length - curr.id.clock).toInt()
                    if (diff > 0) {
                        if (written is Skip) {
                            written.length -= diff
                        } else {
                            curr = sliceStruct(curr, diff)
                        }
                    }
                    if (!written.mergeWith(curr)) {
                        writer.write(written, currWrite.second)
                        currWrite = curr to 0
                        currDecoder.next()
                    }
                }
            } else {
                currWrite = currDecoder.curr!! to 0
                currDecoder.next()
            }
            var next = currDecoder.curr
            while (next != null &&
                next.id.client == firstClient &&
                next.id.clock == currWrite!!.first.id.clock + currWrite.first.length &&
                next !is Skip
            ) {
                writer.write(currWrite.first, currWrite.second)
                currWrite = next to 0
                next = currDecoder.next()
            }
        }
        currWrite?.let { writer.write(it.first, it.second) }
        writer.finish()
        val ds = DeleteSet.merge(updateDecoders.map { DeleteSet.read(it) })
        ds.write(updateEncoder)
        return updateEncoder.toByteArray()
    }

    /** What [update] holds beyond [stateVector]: yjs's `diffUpdate`. */
    fun diffUpdate(
        update: ByteArray,
        stateVector: ByteArray,
    ): ByteArray {
        val state = decodeStateVector(stateVector)
        val encoder = UpdateEncoder()
        val writer = LazyStructWriter(encoder)
        val decoder = UpdateDecoder(update)
        val reader = LazyStructReader(decoder, filterSkips = false)
        while (reader.curr != null) {
            val curr = reader.curr!!
            val currClient = curr.id.client
            val svClock = state[currClient] ?: 0
            if (curr is Skip) {
                reader.next()
                continue
            }
            if (curr.id.clock + curr.length > svClock) {
                writer.write(curr, maxOf(svClock - curr.id.clock, 0).toInt())
                reader.next()
                while (reader.curr != null && reader.curr!!.id.client == currClient) {
                    writer.write(reader.curr!!, 0)
                    reader.next()
                }
            } else {
                while (reader.curr != null && reader.curr!!.id.client == currClient &&
                    reader.curr!!.id.clock + reader.curr!!.length <= svClock
                ) {
                    reader.next()
                }
            }
        }
        writer.finish()
        DeleteSet.read(decoder).write(encoder)
        return encoder.toByteArray()
    }
}
