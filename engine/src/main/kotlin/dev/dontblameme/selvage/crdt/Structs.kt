package dev.dontblameme.selvage.crdt

/** A run of clocks from one client: an [Item], a garbage-collected [GC], or an update's [Skip]. */
sealed class Struct(
    var id: ID,
    var length: Int,
) {
    abstract val deleted: Boolean

    abstract fun mergeWith(right: Struct): Boolean

    abstract fun write(
        encoder: UpdateEncoder,
        offset: Int,
    )

    abstract fun integrate(
        transaction: Transaction,
        offset: Int,
    )

    /** The client whose structs this one waits for, or null when it can be integrated. */
    abstract fun getMissing(
        transaction: Transaction,
        store: StructStore,
    ): Long?

    val lastId: ID get() = if (length == 1) id else ID(id.client, id.clock + length - 1)

    companion object {
        const val GC_REF = 0
        const val SKIP_REF = 10
        const val BIT6 = 0x20
        const val BIT7 = 0x40
        const val BIT8 = 0x80
        const val BITS5 = 0x1f
    }
}

/** Deleted content whose items are gone: only the clocks remain. */
class GC(
    id: ID,
    length: Int,
) : Struct(id, length) {
    override val deleted get() = true

    override fun mergeWith(right: Struct): Boolean {
        if (right !is GC) return false
        length += right.length
        return true
    }

    override fun integrate(
        transaction: Transaction,
        offset: Int,
    ) {
        if (offset > 0) {
            id = ID(id.client, id.clock + offset)
            length -= offset
        }
        transaction.doc.store.addStruct(this)
    }

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) {
        encoder.writeInfo(GC_REF)
        encoder.writeLen(length - offset)
    }

    override fun getMissing(
        transaction: Transaction,
        store: StructStore,
    ): Long? = null

    override fun toString() = "GC($id, $length)"
}

/** A gap in a merged update: clocks it does not carry. It is never integrated. */
class Skip(
    id: ID,
    length: Int,
) : Struct(id, length) {
    override val deleted get() = true

    override fun mergeWith(right: Struct): Boolean {
        if (right !is Skip) return false
        length += right.length
        return true
    }

    override fun integrate(
        transaction: Transaction,
        offset: Int,
    ): Unit = throw IllegalStateException("a skip cannot be integrated")

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) {
        encoder.writeInfo(SKIP_REF)
        // A skip's length is a varUint whatever the encoder, as yjs writes it.
        encoder.rest.writeVarUint((length - offset).toLong())
    }

    override fun getMissing(
        transaction: Transaction,
        store: StructStore,
    ): Long? = null

    override fun toString() = "Skip($id, $length)"
}

/**
 * An element of a shared type, placed by YATA between [origin] and [rightOrigin].
 *
 * Before integration the parent may still be a root name ([parentYKey]) or the ID of the item
 * holding the parent type ([parentID]); [getMissing] resolves it to a [Branch].
 */
class Item(
    id: ID,
    var left: Item?,
    var origin: ID?,
    var right: Item?,
    var rightOrigin: ID?,
    var parent: Branch?,
    var parentSub: String?,
    var content: Content,
    var parentYKey: String? = null,
    var parentID: ID? = null,
) : Struct(id, content.length) {
    override var deleted: Boolean = false
    var keep: Boolean = false
    var redone: ID? = null

    val countable: Boolean get() = content.countable

    /**
     * Refuses an origin, right origin or parent on this item's own client at its clock or later:
     * such a struct cannot exist yet, and yjs throws on it in the middle of integration.
     */
    internal fun requireEarlierOwnDependencies() {
        for (dependency in listOfNotNull(origin, rightOrigin, parentID)) {
            if (dependency.client == id.client && dependency.clock >= id.clock) {
                throw DecodeException("item $id depends on $dependency, which is not before it")
            }
        }
    }

    fun getMissingInternal(store: StructStore): Long? {
        origin?.let { if (it.client != id.client && it.clock >= store.getState(it.client)) return it.client }
        rightOrigin?.let { if (it.client != id.client && it.clock >= store.getState(it.client)) return it.client }
        parentID?.let { if (it.client != id.client && it.clock >= store.getState(it.client)) return it.client }
        return null
    }

    override fun getMissing(
        transaction: Transaction,
        store: StructStore,
    ): Long? {
        getMissingInternal(store)?.let { return it }
        // Every dependency is here: find the neighbours, splitting them where the origins point.
        var leftStruct: Struct? = null
        var rightStruct: Struct? = null
        origin?.let {
            leftStruct = store.getItemCleanEnd(transaction, it)
            origin = leftStruct.lastId
        }
        rightOrigin?.let {
            rightStruct = store.getItemCleanStart(transaction, it)
            rightOrigin = rightStruct.id
        }
        left = leftStruct as? Item
        right = rightStruct as? Item
        if (leftStruct is GC || rightStruct is GC) {
            parent = null
            parentYKey = null
            parentID = null
        } else if (parent == null && parentID == null) {
            // Only take a parent from the neighbours when this is not about to be collected.
            val neighbour = (leftStruct as? Item) ?: (rightStruct as? Item)
            if (neighbour != null) {
                parent = neighbour.parent
                parentSub = neighbour.parentSub
            }
        } else if (parentID != null) {
            val parentItem = store.find(parentID!!)
            parent = if (parentItem is Item) (parentItem.content as? ContentType)?.type else null
            parentID = null
        }
        return null
    }

    override fun integrate(
        transaction: Transaction,
        offset: Int,
    ) {
        val store = transaction.doc.store
        if (offset > 0) {
            id = ID(id.client, id.clock + offset)
            val leftStruct = store.getItemCleanEnd(transaction, ID(id.client, id.clock - 1))
            origin = leftStruct.lastId
            content = content.splice(offset)
            length -= offset
            if (leftStruct is Item) left = leftStruct else parent = null
        }
        val parent = this.parent
        if (parent == null) {
            // No parent: the item is integrated as garbage instead.
            GC(id, length).integrate(transaction, 0)
            return
        }
        if ((left == null && (right == null || right!!.left != null)) || (left != null && left!!.right !== right)) {
            var left = this.left
            // The first item that conflicts with this one.
            var o: Item? =
                when {
                    left != null -> left.right
                    parentSub != null -> parent.map[parentSub!!]?.let { leftmost(it) }
                    else -> parent.start
                }
            val conflictingItems = HashSet<Item>()
            val itemsBeforeOrigin = HashSet<Item>()
            // Let c in conflictingItems, b in itemsBeforeOrigin:
            // ***{origin}bbbb{this}{c,b}{c,b}{o}***
            while (o != null && o !== right) {
                itemsBeforeOrigin.add(o)
                conflictingItems.add(o)
                if (origin == o.origin) {
                    // Case 1: the same left origin, so the lower client goes first.
                    if (o.id.client < id.client) {
                        left = o
                        conflictingItems.clear()
                    } else if (rightOrigin == o.rightOrigin) {
                        // Both point at the same integration points and the ID decides: this goes
                        // left of o, so stop.
                        break
                    }
                } else if (o.origin != null &&
                    (store.find(o.origin!!) as? Item).let { it != null && itemsBeforeOrigin.contains(it) }
                ) {
                    // Case 2: o's origin is between this one's origin and o.
                    if (!conflictingItems.contains(store.find(o.origin!!) as Item)) {
                        left = o
                        conflictingItems.clear()
                    }
                } else {
                    break
                }
                o = o.right
            }
            this.left = left
        }
        // Link this between left and right, and update the parent's start or map.
        val l = left
        if (l != null) {
            right = l.right
            l.right = this
        } else {
            val r: Item?
            if (parentSub != null) {
                r = parent.map[parentSub!!]?.let { leftmost(it) }
            } else {
                r = parent.start
                parent.start = this
            }
            right = r
        }
        val r = right
        if (r != null) {
            r.left = this
        } else if (parentSub != null) {
            // This is the current value of the key: the one before it is overwritten.
            parent.map[parentSub!!] = this
            left?.delete(transaction)
        }
        if (parentSub == null && countable && !deleted) parent.length += length
        store.addStruct(this)
        content.integrate(transaction, this)
        transaction.addChangedType(parent, parentSub)
        if ((parent.item != null && parent.item!!.deleted) || (parentSub != null && right != null)) {
            // The parent is deleted, or this is not the current value of its key.
            delete(transaction)
        }
    }

    private fun leftmost(item: Item): Item {
        var o = item
        while (o.left != null) o = o.left!!
        return o
    }

    /** The next item to the right that is not deleted. */
    val next: Item?
        get() {
            var n = right
            while (n != null && n.deleted) n = n.right
            return n
        }

    override fun mergeWith(right: Struct): Boolean {
        if (right !is Item) return false
        if (right.origin == lastId &&
            this.right === right &&
            rightOrigin == right.rightOrigin &&
            id.client == right.id.client &&
            id.clock + length == right.id.clock &&
            deleted == right.deleted &&
            redone == null &&
            right.redone == null &&
            content::class == right.content::class &&
            content.mergeWith(right.content)
        ) {
            if (right.keep) keep = true
            this.right = right.right
            this.right?.left = this
            length += right.length
            return true
        }
        return false
    }

    fun delete(transaction: Transaction) {
        if (deleted) return
        val parent = this.parent!!
        if (countable && parentSub == null) parent.length -= length
        deleted = true
        transaction.deleteSet.add(id.client, id.clock, length.toLong())
        transaction.addChangedType(parent, parentSub)
        content.delete(transaction)
    }

    /** Drops a deleted item's content; with [parentGCd] the whole item becomes a [GC]. */
    fun gc(
        store: StructStore,
        parentGCd: Boolean,
    ) {
        check(deleted) { "only a deleted item is collected" }
        content.gc(store)
        if (parentGCd) {
            store.replaceStruct(this, GC(id, length))
        } else {
            content = ContentDeleted(length)
        }
    }

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) {
        val origin = if (offset > 0) ID(id.client, id.clock + offset - 1) else this.origin
        val rightOrigin = this.rightOrigin
        val parentSub = this.parentSub
        val info =
            (content.ref and BITS5) or
                (if (origin == null) 0 else BIT8) or
                (if (rightOrigin == null) 0 else BIT7) or
                (if (parentSub == null) 0 else BIT6)
        encoder.writeInfo(info)
        if (origin != null) encoder.writeLeftID(origin)
        if (rightOrigin != null) encoder.writeRightID(rightOrigin)
        if (origin == null && rightOrigin == null) {
            val parent = this.parent
            when {
                parent != null -> {
                    val parentItem = parent.item
                    if (parentItem == null) {
                        encoder.writeParentInfo(true)
                        encoder.writeString(
                            parent.rootName ?: throw IllegalStateException("a root type without a name"),
                        )
                    } else {
                        encoder.writeParentInfo(false)
                        encoder.writeLeftID(parentItem.id)
                    }
                }

                parentYKey != null -> {
                    encoder.writeParentInfo(true)
                    encoder.writeString(parentYKey!!)
                }

                parentID != null -> {
                    encoder.writeParentInfo(false)
                    encoder.writeLeftID(parentID!!)
                }

                else -> {
                    throw IllegalStateException("an item with neither origin nor parent")
                }
            }
            if (parentSub != null) encoder.writeString(parentSub)
        }
        content.write(encoder, offset)
    }

    override fun toString() =
        "Item($id, $length, origin=$origin, rightOrigin=$rightOrigin, deleted=$deleted, ${content::class.simpleName})"
}

/** Splits [leftItem] at [diff] and returns the right half, as yjs's `splitItem` does. */
internal fun splitItem(
    transaction: Transaction,
    leftItem: Item,
    diff: Int,
): Item {
    val client = leftItem.id.client
    val clock = leftItem.id.clock
    val rightItem =
        Item(
            ID(client, clock + diff),
            leftItem,
            ID(client, clock + diff - 1),
            leftItem.right,
            leftItem.rightOrigin,
            leftItem.parent,
            leftItem.parentSub,
            leftItem.content.splice(diff),
        )
    if (leftItem.deleted) rightItem.deleted = true
    if (leftItem.keep) rightItem.keep = true
    leftItem.redone?.let { rightItem.redone = ID(it.client, it.clock + diff) }
    // The left half keeps its right origin: changing it would break syncing.
    leftItem.right = rightItem
    rightItem.right?.left = rightItem
    transaction.mergeStructs.add(rightItem)
    if (rightItem.parentSub != null && rightItem.right == null) {
        rightItem.parent!!.map[rightItem.parentSub!!] = rightItem
    }
    leftItem.length = diff
    return rightItem
}

/**
 * Reads one struct of an update, with its parent unresolved (a root name or an item ID).
 *
 * A struct must end by clock `Int.MAX_VALUE`, so every length taken from a difference of clocks
 * (a merge's gap, a split, merged runs) fits a [Struct.length]. yjs allows clocks up to 2^53 and
 * yrs up to 2^32; a client that has written two thousand million clocks is refused here.
 */
internal fun readStruct(
    decoder: UpdateDecoder,
    client: Long,
    clock: Long,
    resolveRoot: ((String) -> Branch)?,
): Struct {
    val struct = readStructAt(decoder, client, clock, resolveRoot)
    if (clock + struct.length > Int.MAX_VALUE) {
        throw DecodeException("struct $client:$clock of length ${struct.length} ends past clock ${Int.MAX_VALUE}")
    }
    return struct
}

private fun readStructAt(
    decoder: UpdateDecoder,
    client: Long,
    clock: Long,
    resolveRoot: ((String) -> Branch)?,
): Struct {
    val info = decoder.readInfo()
    return when (info and Struct.BITS5) {
        Struct.GC_REF -> {
            GC(ID(client, clock), decoder.readLen())
        }

        Struct.SKIP_REF -> {
            val len = decoder.rest.readVarUint()
            if (len > Int.MAX_VALUE) throw DecodeException("skip length out of range")
            Skip(ID(client, clock), len.toInt())
        }

        else -> {
            val cantCopyParentInfo = (info and (Struct.BIT7 or Struct.BIT8)) == 0
            val origin = if ((info and Struct.BIT8) != 0) decoder.readLeftID() else null
            val rightOrigin = if ((info and Struct.BIT7) != 0) decoder.readRightID() else null
            var parent: Branch? = null
            var parentYKey: String? = null
            var parentID: ID? = null
            if (cantCopyParentInfo) {
                if (decoder.readParentInfo()) {
                    val key = decoder.readString()
                    if (resolveRoot != null) parent = resolveRoot(key) else parentYKey = key
                } else {
                    parentID = decoder.readLeftID()
                }
            }
            val parentSub = if (cantCopyParentInfo && (info and Struct.BIT6) != 0) decoder.readString() else null
            val content = Content.read(decoder, info and Struct.BITS5)
            val item =
                Item(
                    ID(client, clock),
                    null,
                    origin,
                    null,
                    rightOrigin,
                    parent,
                    parentSub,
                    content,
                    parentYKey,
                    parentID,
                )
            if (item.length == 0) throw DecodeException("an item of length 0")
            item
        }
    }
}
