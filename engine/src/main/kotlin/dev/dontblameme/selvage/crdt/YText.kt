package dev.dontblameme.selvage.crdt

/** One operation of a text change, in UTF-16 code units: yjs's delta format without attributes. */
sealed interface TextDelta {
    data class Insert(
        val text: String,
    ) : TextDelta

    /** An inserted embed or nested type, one index long. */
    data class InsertEmbed(
        val content: Content,
    ) : TextDelta

    data class Retain(
        val length: Int,
    ) : TextDelta

    data class Delete(
        val length: Int,
    ) : TextDelta
}

/** A change to a text, seen once the transaction that made it ends. */
class TextEvent(
    val transaction: Transaction,
    val delta: List<TextDelta>,
) {
    val origin: Any? get() = transaction.origin

    /** False when the change came from a remote update. */
    val local: Boolean get() = transaction.local
}

/**
 * yjs's `Y.Text` over a [Branch], indexed in UTF-16 code units. Formatting is carried but not
 * edited: inserts take the attributes in effect at their position, as yjs's do.
 */
class YText internal constructor(
    val branch: Branch,
) {
    private val doc: Doc get() = branch.doc ?: throw IllegalStateException("the text is not in a document")

    val length: Int get() = branch.length

    override fun toString(): String {
        val sb = StringBuilder()
        var n = branch.start
        while (n != null) {
            val content = n.content
            if (!n.deleted && content is ContentString) sb.append(content.str)
            n = n.right
        }
        return sb.toString()
    }

    fun insert(
        index: Int,
        text: String,
        origin: Any? = null,
    ) {
        if (index < 0 || index > length) throw IndexOutOfBoundsException("insert at $index in a text of length $length")
        if (text.isEmpty()) return
        doc.transact(origin) { transaction ->
            val pos = findPosition(transaction, index)
            val attributes = HashMap(pos.currentAttributes)
            minimizeAttributeChanges(pos, attributes)
            val store = doc.store
            val clientID = doc.clientID
            val left = pos.left
            val right = pos.right
            Item(
                ID(clientID, store.getState(clientID)),
                left,
                left?.lastId,
                right,
                right?.id,
                branch,
                null,
                ContentString(text),
            ).integrate(transaction, 0)
        }
    }

    fun delete(
        index: Int,
        length: Int,
        origin: Any? = null,
    ) {
        if (index < 0 || length < 0 || index + length > this.length) {
            throw IndexOutOfBoundsException("delete $length at $index in a text of length ${this.length}")
        }
        if (length == 0) return
        doc.transact(origin) { transaction ->
            val pos = findPosition(transaction, index)
            var remaining = length
            while (remaining > 0 && pos.right != null) {
                val right = pos.right!!
                if (!right.deleted) {
                    when (right.content) {
                        is ContentType, is ContentEmbed, is ContentString -> {
                            if (remaining < right.length) {
                                doc.store.getItemCleanStart(
                                    transaction,
                                    ID(right.id.client, right.id.clock + remaining),
                                )
                            }
                            remaining -= right.length
                            right.delete(transaction)
                        }

                        else -> {}
                    }
                }
                pos.forward()
            }
        }
    }

    /** Calls [observer] after every transaction that changed this text; returns the unsubscriber. */
    fun observe(observer: (TextEvent) -> Unit): () -> Unit {
        branch.observers.add(observer)
        return { branch.observers.remove(observer) }
    }

    private class Position(
        var left: Item?,
        var right: Item?,
        var index: Int,
        val currentAttributes: MutableMap<String, YAny>,
    ) {
        fun forward() {
            val right = this.right ?: throw IllegalStateException("unexpected end of text")
            if (!right.deleted) {
                when (val content = right.content) {
                    is ContentFormat -> updateCurrentAttributes(currentAttributes, content)
                    else -> if (right.countable) index += right.length
                }
            }
            left = right
            this.right = right.right
        }
    }

    private fun findPosition(
        transaction: Transaction,
        index: Int,
    ): Position {
        val pos = Position(null, branch.start, 0, HashMap())
        var count = index
        while (pos.right != null && count > 0) {
            val right = pos.right!!
            val content = right.content
            if (content is ContentFormat) {
                if (!right.deleted) updateCurrentAttributes(pos.currentAttributes, content)
            } else if (!right.deleted) {
                if (count < right.length) {
                    // Split so that the position falls between two items.
                    doc.store.getItemCleanStart(transaction, ID(right.id.client, right.id.clock + count))
                }
                pos.index += right.length
                count -= right.length
            }
            pos.left = right
            pos.right = right.right
        }
        return pos
    }

    /** Moves past deleted items and formats that change nothing, as yjs does before inserting. */
    private fun minimizeAttributeChanges(
        pos: Position,
        attributes: Map<String, YAny>,
    ) {
        while (true) {
            val right = pos.right ?: break
            val content = right.content
            if (right.deleted ||
                (content is ContentFormat && (attributes[content.key] ?: YAny.Null) == content.value)
            ) {
                pos.forward()
            } else {
                break
            }
        }
    }

    companion object {
        private fun updateCurrentAttributes(
            attributes: MutableMap<String, YAny>,
            format: ContentFormat,
        ) {
            if (format.value == YAny.Null) attributes.remove(format.key) else attributes[format.key] = format.value
        }

        /** What [transaction] changed in [branch], as yjs's `YTextEvent.delta` without attributes. */
        fun delta(
            branch: Branch,
            transaction: Transaction,
        ): List<TextDelta> {
            val delta = ArrayList<TextDelta>()
            var action: Char? = null
            val insert = StringBuilder()
            var retain = 0
            var deleteLen = 0

            fun addOp() {
                when (action) {
                    'd' -> if (deleteLen > 0) delta.add(TextDelta.Delete(deleteLen))
                    'i' -> if (insert.isNotEmpty()) delta.add(TextDelta.Insert(insert.toString()))
                    'r' -> if (retain > 0) delta.add(TextDelta.Retain(retain))
                }
                deleteLen = 0
                insert.setLength(0)
                retain = 0
                action = null
            }

            fun switchTo(a: Char) {
                if (action != a) {
                    addOp()
                    action = a
                }
            }
            var item = branch.start
            while (item != null) {
                val content = item.content
                when (content) {
                    is ContentType, is ContentEmbed -> {
                        if (transaction.adds(item)) {
                            if (!transaction.deletes(item)) {
                                addOp()
                                delta.add(TextDelta.InsertEmbed(content))
                            }
                        } else if (transaction.deletes(item)) {
                            switchTo('d')
                            deleteLen += 1
                        } else if (!item.deleted) {
                            switchTo('r')
                            retain += 1
                        }
                    }

                    is ContentString -> {
                        if (transaction.adds(item)) {
                            if (!transaction.deletes(item)) {
                                switchTo('i')
                                insert.append(content.str)
                            }
                        } else if (transaction.deletes(item)) {
                            switchTo('d')
                            deleteLen += item.length
                        } else if (!item.deleted) {
                            switchTo('r')
                            retain += item.length
                        }
                    }

                    else -> {}
                }
                item = item.right
            }
            addOp()
            while (delta.isNotEmpty() && delta.last() is TextDelta.Retain) delta.removeAt(delta.size - 1)
            return delta
        }
    }
}
