package dev.dontblameme.selvage.crdt

/** Structs that wait for others: which clock each client is missing, and the structs as an update. */
class PendingStructs(
    val missing: MutableMap<Long, Long>,
    var update: ByteArray,
)

/** Every struct of a document, per client, in clock order; plus what could not be applied yet. */
class StructStore {
    val clients = HashMap<Long, MutableList<Struct>>()
    var pendingStructs: PendingStructs? = null
    var pendingDs: ByteArray? = null

    fun stateVector(): Map<Long, Long> =
        clients.mapValues { (_, structs) ->
            structs.last().let {
                it.id.clock +
                    it.length
            }
        }

    fun getState(client: Long): Long {
        val structs = clients[client] ?: return 0
        val last = structs.last()
        return last.id.clock + last.length
    }

    fun addStruct(struct: Struct) {
        val structs = clients.getOrPut(struct.id.client) { ArrayList() }
        if (structs.isNotEmpty()) {
            val last = structs.last()
            check(last.id.clock + last.length == struct.id.clock) { "a struct out of clock order: ${struct.id}" }
        }
        structs.add(struct)
    }

    fun find(id: ID): Struct {
        val structs = clients[id.client] ?: throw IllegalStateException("no structs for client ${id.client}")
        return structs[findIndex(structs, id.clock)]
    }

    fun replaceStruct(
        struct: Struct,
        newStruct: Struct,
    ) {
        val structs = clients.getValue(struct.id.client)
        structs[findIndex(structs, struct.id.clock)] = newStruct
    }

    /** The first struct from [id], splitting the item that holds it so that it starts there. */
    fun getItemCleanStart(
        transaction: Transaction,
        id: ID,
    ): Struct {
        val structs = clients.getValue(id.client)
        return structs[findIndexCleanStart(transaction, structs, id.clock)]
    }

    /** The struct that ends at [id], splitting the item that holds it; a [GC] is not split. */
    fun getItemCleanEnd(
        transaction: Transaction,
        id: ID,
    ): Struct {
        val structs = clients.getValue(id.client)
        val index = findIndex(structs, id.clock)
        val struct = structs[index]
        if (id.clock != struct.id.clock + struct.length - 1 && struct !is GC) {
            structs.add(index + 1, splitItem(transaction, struct as Item, (id.clock - struct.id.clock + 1).toInt()))
        }
        return struct
    }

    /** Calls [f] on every struct in `[clockStart, clockStart + len)`, split to exactly that range. */
    fun iterateStructs(
        transaction: Transaction,
        structs: MutableList<Struct>,
        clockStart: Long,
        len: Long,
        f: (Struct) -> Unit,
    ) {
        if (len == 0L) return
        val clockEnd = clockStart + len
        var index = findIndexCleanStart(transaction, structs, clockStart)
        do {
            val struct = structs[index++]
            if (clockEnd < struct.id.clock + struct.length) findIndexCleanStart(transaction, structs, clockEnd)
            f(struct)
        } while (index < structs.size && structs[index].id.clock < clockEnd)
    }

    companion object {
        /** The index of the struct that holds [clock], by yjs's pivoting binary search. */
        fun findIndex(
            structs: List<Struct>,
            clock: Long,
        ): Int {
            var left = 0
            var right = structs.size - 1
            var mid = structs[right]
            var midClock = mid.id.clock
            if (midClock == clock) return right
            var midIndex =
                ((clock.toDouble() / (midClock + mid.length - 1)) * right)
                    .toLong()
                    .coerceIn(
                        0,
                        right.toLong(),
                    ).toInt()
            while (left <= right) {
                mid = structs[midIndex]
                midClock = mid.id.clock
                if (midClock <= clock) {
                    if (clock < midClock + mid.length) return midIndex
                    left = midIndex + 1
                } else {
                    right = midIndex - 1
                }
                midIndex = (left + right) / 2
            }
            throw IllegalStateException("no struct holds clock $clock")
        }

        fun findIndexCleanStart(
            transaction: Transaction,
            structs: MutableList<Struct>,
            clock: Long,
        ): Int {
            val index = findIndex(structs, clock)
            val struct = structs[index]
            if (struct.id.clock < clock && struct is Item) {
                structs.add(index + 1, splitItem(transaction, struct, (clock - struct.id.clock).toInt()))
                return index + 1
            }
            return index
        }
    }
}
