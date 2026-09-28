package dev.dontblameme.selvage.crdt

data class DeleteItem(
    val clock: Long,
    val len: Long,
)

/** Deleted clock ranges per client. After [sortAndMerge] each list is sorted and disjoint. */
class DeleteSet {
    val clients = LinkedHashMap<Long, MutableList<DeleteItem>>()

    fun add(
        client: Long,
        clock: Long,
        len: Long,
    ) {
        clients.getOrPut(client) { ArrayList() }.add(DeleteItem(clock, len))
    }

    fun isDeleted(id: ID): Boolean {
        val items = clients[id.client] ?: return false
        return findIndex(items, id.clock) != null
    }

    fun sortAndMerge() {
        for (dels in clients.values) {
            dels.sortBy { it.clock }
            var j = 1
            for (i in 1 until dels.size) {
                val left = dels[j - 1]
                val right = dels[i]
                if (left.clock + left.len >= right.clock) {
                    dels[j - 1] = DeleteItem(left.clock, maxOf(left.len, right.clock + right.len - left.clock))
                } else {
                    if (j < i) dels[j] = right
                    j++
                }
            }
            while (dels.size > j) dels.removeAt(dels.size - 1)
        }
    }

    fun write(encoder: UpdateEncoder) {
        encoder.rest.writeVarUint(clients.size)
        for ((client, items) in clients.entries.sortedByDescending { it.key }) {
            encoder.rest.writeVarUint(client)
            encoder.rest.writeVarUint(items.size)
            for (item in items) {
                encoder.writeDsClock(item.clock)
                encoder.writeDsLen(item.len)
            }
        }
    }

    companion object {
        fun findIndex(
            items: List<DeleteItem>,
            clock: Long,
        ): Int? {
            var left = 0
            var right = items.size - 1
            while (left <= right) {
                val midIndex = (left + right) / 2
                val mid = items[midIndex]
                if (mid.clock <= clock) {
                    if (clock < mid.clock + mid.len) return midIndex
                    left = midIndex + 1
                } else {
                    right = midIndex - 1
                }
            }
            return null
        }

        fun read(decoder: UpdateDecoder): DeleteSet {
            val ds = DeleteSet()
            val numClients = decoder.rest.readLength()
            repeat(numClients) {
                val client = decoder.rest.readVarUint()
                val count = decoder.rest.readLength()
                if (count > 0) {
                    val items = ds.clients.getOrPut(client) { ArrayList() }
                    repeat(count) { items.add(DeleteItem(decoder.readDsClock(), decoder.readDsLen())) }
                }
            }
            return ds
        }

        fun merge(dss: List<DeleteSet>): DeleteSet {
            val merged = DeleteSet()
            for ((i, ds) in dss.withIndex()) {
                for ((client, delsLeft) in ds.clients) {
                    if (!merged.clients.containsKey(client)) {
                        val dels = ArrayList(delsLeft)
                        for (k in i + 1 until dss.size) dss[k].clients[client]?.let { dels.addAll(it) }
                        merged.clients[client] = dels
                    }
                }
            }
            merged.sortAndMerge()
            return merged
        }

        /** The runs of deleted structs in [store]. */
        fun fromStore(store: StructStore): DeleteSet {
            val ds = DeleteSet()
            for ((client, structs) in store.clients) {
                val items = ArrayList<DeleteItem>()
                var i = 0
                while (i < structs.size) {
                    val struct = structs[i]
                    if (struct.deleted) {
                        val clock = struct.id.clock
                        var len = struct.length.toLong()
                        while (i + 1 < structs.size && structs[i + 1].deleted) {
                            len += structs[i + 1].length
                            i++
                        }
                        items.add(DeleteItem(clock, len))
                    }
                    i++
                }
                if (items.isNotEmpty()) ds.clients[client] = items
            }
            return ds
        }

        /**
         * Applies the delete set at the decoder, splitting items at its edges. Returns the part
         * that names clocks this document does not have yet, as an update with no structs.
         */
        fun readAndApply(
            decoder: UpdateDecoder,
            transaction: Transaction,
            store: StructStore,
        ): ByteArray? {
            val unapplied = DeleteSet()
            val numClients = decoder.rest.readLength()
            repeat(numClients) {
                val client = decoder.rest.readVarUint()
                val count = decoder.rest.readLength()
                val structs = store.clients[client] ?: ArrayList()
                val state = store.getState(client)
                repeat(count) {
                    val clock = decoder.readDsClock()
                    val clockEnd = clock + decoder.readDsLen()
                    if (clock < state) {
                        if (state < clockEnd) unapplied.add(client, state, clockEnd - state)
                        var index = StructStore.findIndex(structs, clock)
                        var struct = structs[index]
                        if (!struct.deleted && struct.id.clock < clock) {
                            structs.add(
                                index + 1,
                                splitItem(transaction, struct as Item, (clock - struct.id.clock).toInt()),
                            )
                            index++
                        }
                        while (index < structs.size) {
                            struct = structs[index++]
                            if (struct.id.clock < clockEnd) {
                                if (!struct.deleted) {
                                    if (clockEnd < struct.id.clock + struct.length) {
                                        structs.add(
                                            index,
                                            splitItem(
                                                transaction,
                                                struct as Item,
                                                (
                                                    clockEnd -
                                                        struct.id.clock
                                                ).toInt(),
                                            ),
                                        )
                                    }
                                    (struct as Item).delete(transaction)
                                }
                            } else {
                                break
                            }
                        }
                    } else {
                        unapplied.add(client, clock, clockEnd - clock)
                    }
                }
            }
            if (unapplied.clients.isEmpty()) return null
            val encoder = UpdateEncoder()
            encoder.rest.writeVarUint(0)
            unapplied.write(encoder)
            return encoder.toByteArray()
        }
    }
}
