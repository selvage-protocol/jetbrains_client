package dev.dontblameme.selvage.crdt

import kotlin.random.Random

/** What a document emits after a transaction that changed it: the change as a v1 update. */
fun interface UpdateListener {
    fun onUpdate(
        update: ByteArray,
        origin: Any?,
        local: Boolean,
    )
}

/**
 * A replica: yjs's `Y.Doc`, holding root types by name. For Selvage a root type is a [YText]
 * named by its document's path.
 *
 * [clientID] is the identity every local edit is stamped with. yjs draws it as a uint32 and yrs
 * up to 2^53; any value in `[0, 2^53)` is accepted.
 */
class Doc(
    clientID: Long = randomClientId(Random.Default),
    /** Replace deleted content with its length when a transaction ends, as yjs does by default. */
    val gc: Boolean = true,
    private val random: Random = Random.Default,
) {
    var clientID: Long = clientID
        private set

    val store = StructStore()
    private val share = LinkedHashMap<String, Branch>()
    private var current: Transaction? = null
    private val cleanups = ArrayList<Transaction>()
    private val updateListeners = ArrayList<UpdateListener>()

    init {
        require(clientID in 0..MAX_SAFE_INTEGER) { "client id out of range: $clientID" }
    }

    /** The root type named [name], created empty when it is not there yet. */
    fun get(name: String): Branch =
        share.getOrPut(name) {
            Branch(null).also {
                it.rootName = name
                it.integrate(this, null)
            }
        }

    fun getText(name: String): YText = YText(get(name))

    /** The names of the root types, in the order they appeared. */
    val rootNames: Set<String> get() = share.keys

    fun onUpdate(listener: UpdateListener): () -> Unit {
        updateListeners.add(listener)
        return { updateListeners.remove(listener) }
    }

    /**
     * Runs [f] in a transaction, or in the one already open. Observers and update listeners run
     * once the outermost transaction ends.
     */
    fun <T> transact(
        origin: Any? = null,
        local: Boolean = true,
        f: (Transaction) -> T,
    ): T {
        var initialCall = false
        if (current == null) {
            initialCall = true
            val transaction = Transaction(this, origin, local)
            current = transaction
            cleanups.add(transaction)
        }
        try {
            return f(current!!)
        } finally {
            if (initialCall) {
                val finishCleanup = current === cleanups[0]
                current = null
                if (finishCleanup) cleanupTransactions()
            }
        }
    }

    private fun cleanupTransactions() {
        var failure: Throwable? = null
        var i = 0
        while (i < cleanups.size) {
            try {
                cleanup(cleanups[i])
            } catch (e: Throwable) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
            i++
        }
        cleanups.clear()
        failure?.let { throw it }
    }

    private fun cleanup(transaction: Transaction) {
        val ds = transaction.deleteSet
        var failure: Throwable? = null
        try {
            ds.sortAndMerge()
            transaction.afterState = store.stateVector()
            for ((type, _) in transaction.changed.entries.toList()) {
                if (type.observers.isEmpty() || (type.item != null && type.item!!.deleted)) continue
                val event = TextEvent(transaction, YText.delta(type, transaction))
                for (observer in type.observers.toList()) {
                    try {
                        observer(event)
                    } catch (e: Throwable) {
                        if (failure == null) failure = e else failure.addSuppressed(e)
                    }
                }
            }
        } finally {
            // Replace deleted content with its length, then merge what can be merged.
            if (gc) tryGcDeleteSet(ds)
            tryMergeDeleteSet(ds)
            for ((client, clock) in transaction.afterState) {
                val beforeClock = transaction.beforeState[client] ?: 0
                if (beforeClock != clock) {
                    val structs = store.clients.getValue(client)
                    val firstChangePos = maxOf(StructStore.findIndex(structs, beforeClock), 1)
                    var i = structs.size - 1
                    while (i >= firstChangePos) i -= 1 + tryToMergeWithLefts(structs, i)
                }
            }
            for (k in transaction.mergeStructs.indices.reversed()) {
                val id = transaction.mergeStructs[k].id
                val structs = store.clients.getValue(id.client)
                val replacedStructPos = StructStore.findIndex(structs, id.clock)
                if (replacedStructPos + 1 < structs.size) {
                    if (tryToMergeWithLefts(structs, replacedStructPos + 1) > 1) continue
                }
                if (replacedStructPos > 0) tryToMergeWithLefts(structs, replacedStructPos)
            }
            if (!transaction.local && transaction.afterState[clientID] != transaction.beforeState[clientID]) {
                // Another client seems to be using this id: take a new one.
                clientID = randomClientId(random)
            }
            if (updateListeners.isNotEmpty()) {
                val update = transaction.encodeUpdate()
                if (update != null) {
                    for (listener in updateListeners.toList()) {
                        listener.onUpdate(
                            update,
                            transaction.origin,
                            transaction.local,
                        )
                    }
                }
            }
        }
        failure?.let { throw it }
    }

    private fun tryGcDeleteSet(ds: DeleteSet) {
        for ((client, deleteItems) in ds.clients) {
            val structs = store.clients.getValue(client)
            for (di in deleteItems.indices.reversed()) {
                val deleteItem = deleteItems[di]
                val end = deleteItem.clock + deleteItem.len
                var si = StructStore.findIndex(structs, deleteItem.clock)
                while (si < structs.size && structs[si].id.clock < end) {
                    val struct = structs[si]
                    if (struct is Item && struct.deleted && !struct.keep) struct.gc(store, false)
                    si++
                }
            }
        }
    }

    private fun tryMergeDeleteSet(ds: DeleteSet) {
        for ((client, deleteItems) in ds.clients) {
            val structs = store.clients.getValue(client)
            for (di in deleteItems.indices.reversed()) {
                val deleteItem = deleteItems[di]
                // Start with merging the item next to the last deleted item.
                val mostRight =
                    minOf(
                        structs.size - 1,
                        1 + StructStore.findIndex(structs, deleteItem.clock + deleteItem.len - 1),
                    )
                var si = mostRight
                while (si > 0 && structs[si].id.clock >= deleteItem.clock) {
                    si -= 1 + tryToMergeWithLefts(structs, si)
                }
            }
        }
    }

    private fun tryToMergeWithLefts(
        structs: MutableList<Struct>,
        pos: Int,
    ): Int {
        var right = structs[pos]
        var i = pos
        while (i > 0) {
            val left = structs[i - 1]
            if (left.deleted == right.deleted && left::class == right::class && left.mergeWith(right)) {
                if (right is Item && right.parentSub != null && right.parent!!.map[right.parentSub!!] === right) {
                    right.parent!!.map[right.parentSub!!] = left as Item
                }
                right = left
                i--
                continue
            }
            break
        }
        val merged = pos - i
        if (merged > 0) structs.subList(pos + 1 - merged, pos + 1).clear()
        return merged
    }

    companion object {
        fun randomClientId(random: Random): Long = random.nextLong(0, 1L shl 32)
    }
}

/** A change to a document, open until the outermost [Doc.transact] returns. */
class Transaction internal constructor(
    val doc: Doc,
    val origin: Any?,
    /** False while a remote update is applied. */
    var local: Boolean,
) {
    val deleteSet = DeleteSet()
    val beforeState: Map<Long, Long> = doc.store.stateVector()
    var afterState: Map<Long, Long> = emptyMap()
        internal set
    internal val mergeStructs = ArrayList<Item>()
    internal val changed = LinkedHashMap<Branch, MutableSet<String?>>()

    internal fun addChangedType(
        type: Branch,
        parentSub: String?,
    ) {
        val item = type.item
        if (item == null || (item.id.clock < (beforeState[item.id.client] ?: 0) && !item.deleted)) {
            changed.getOrPut(type) { LinkedHashSet() }.add(parentSub)
        }
    }

    /** Whether [item] was created in this transaction. */
    fun adds(item: Item): Boolean = item.id.clock >= (beforeState[item.id.client] ?: 0)

    /** Whether [item] was deleted in this transaction. */
    fun deletes(item: Item): Boolean = deleteSet.isDeleted(item.id)

    /** The update this transaction makes, or null when it changed nothing. */
    internal fun encodeUpdate(): ByteArray? {
        if (deleteSet.clients.isEmpty() &&
            afterState.all { (client, clock) -> beforeState[client] == clock }
        ) {
            return null
        }
        deleteSet.sortAndMerge()
        val encoder = UpdateEncoder()
        Updates.writeClientsStructs(encoder, doc.store, beforeState)
        deleteSet.write(encoder)
        return encoder.toByteArray()
    }
}
