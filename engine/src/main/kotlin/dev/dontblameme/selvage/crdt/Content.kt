package dev.dontblameme.selvage.crdt

/** The content of an [Item]: yjs's content classes, one for each content ref of an update. */
sealed class Content {
    abstract val ref: Int
    abstract val length: Int
    abstract val countable: Boolean

    /** Splits this at [offset] and returns the right half; this keeps the left half. */
    abstract fun splice(offset: Int): Content

    /** Appends [right] to this when both are of the same kind and mergeable. */
    abstract fun mergeWith(right: Content): Boolean

    open fun integrate(
        transaction: Transaction,
        item: Item,
    ) {}

    open fun delete(transaction: Transaction) {}

    open fun gc(store: StructStore) {}

    abstract fun write(
        encoder: UpdateEncoder,
        offset: Int,
    )

    companion object {
        const val DELETED = 1
        const val JSON = 2
        const val BINARY = 3
        const val STRING = 4
        const val EMBED = 5
        const val FORMAT = 6
        const val TYPE = 7
        const val ANY = 8
        const val DOC = 9

        fun read(
            decoder: UpdateDecoder,
            ref: Int,
        ): Content =
            when (ref) {
                DELETED -> {
                    ContentDeleted(decoder.readLen())
                }

                JSON -> {
                    ContentJson(
                        List(decoder.readLen()) {
                            decoder.readString().let {
                                if (it ==
                                    "undefined"
                                ) {
                                    YAny.Undefined
                                } else {
                                    Json.parse(it)
                                }
                            }
                        },
                    )
                }

                BINARY -> {
                    ContentBinary(decoder.readBuf())
                }

                STRING -> {
                    ContentString(decoder.readString())
                }

                EMBED -> {
                    ContentEmbed(decoder.readJson())
                }

                FORMAT -> {
                    ContentFormat(decoder.readKey(), decoder.readJson())
                }

                TYPE -> {
                    ContentType(Branch.read(decoder))
                }

                ANY -> {
                    ContentAny(List(decoder.readLen()) { decoder.readAny() })
                }

                DOC -> {
                    ContentDoc.read(decoder.readString(), decoder.readAny())
                }

                else -> {
                    throw DecodeException("unknown content ref $ref")
                }
            }
    }
}

class ContentDeleted(
    var len: Int,
) : Content() {
    override val ref get() = DELETED
    override val length get() = len
    override val countable get() = false

    override fun splice(offset: Int): Content {
        val right = ContentDeleted(len - offset)
        len = offset
        return right
    }

    override fun mergeWith(right: Content): Boolean {
        len += (right as ContentDeleted).len
        return true
    }

    override fun integrate(
        transaction: Transaction,
        item: Item,
    ) {
        transaction.deleteSet.add(item.id.client, item.id.clock, len.toLong())
        item.deleted = true
    }

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) = encoder.writeLen(len - offset)
}

/** A text run, in UTF-16 code units as yjs counts them. */
class ContentString(
    var str: String,
) : Content() {
    override val ref get() = STRING
    override val length get() = str.length
    override val countable get() = true

    override fun splice(offset: Int): Content {
        val right = ContentString(str.substring(offset))
        str = str.substring(0, offset)
        // yjs does not split a surrogate pair: both halves of a split pair become U+FFFD.
        if (str[offset - 1].isHighSurrogate()) {
            str = str.substring(0, offset - 1) + '�'
            right.str = '�' + right.str.substring(1)
        }
        return right
    }

    override fun mergeWith(right: Content): Boolean {
        str += (right as ContentString).str
        return true
    }

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) = encoder.writeString(if (offset == 0) str else str.substring(offset))
}

class ContentJson(
    var items: List<YAny>,
) : Content() {
    override val ref get() = JSON
    override val length get() = items.size
    override val countable get() = true

    override fun splice(offset: Int): Content {
        val right = ContentJson(items.subList(offset, items.size).toList())
        items = items.subList(0, offset).toList()
        return right
    }

    override fun mergeWith(right: Content): Boolean {
        items = items + (right as ContentJson).items
        return true
    }

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) {
        encoder.writeLen(items.size - offset)
        for (i in offset until items.size) encoder.writeString(Json.stringify(items[i]) ?: "undefined")
    }
}

class ContentAny(
    var items: List<YAny>,
) : Content() {
    override val ref get() = ANY
    override val length get() = items.size
    override val countable get() = true

    override fun splice(offset: Int): Content {
        val right = ContentAny(items.subList(offset, items.size).toList())
        items = items.subList(0, offset).toList()
        return right
    }

    override fun mergeWith(right: Content): Boolean {
        items = items + (right as ContentAny).items
        return true
    }

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) {
        encoder.writeLen(items.size - offset)
        for (i in offset until items.size) encoder.writeAny(items[i])
    }
}

class ContentBinary(
    val bytes: ByteArray,
) : Content() {
    override val ref get() = BINARY
    override val length get() = 1
    override val countable get() = true

    override fun splice(offset: Int): Content = throw UnsupportedOperationException("binary content does not split")

    override fun mergeWith(right: Content) = false

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) = encoder.writeBuf(bytes)
}

class ContentEmbed(
    val embed: YAny,
) : Content() {
    override val ref get() = EMBED
    override val length get() = 1
    override val countable get() = true

    override fun splice(offset: Int): Content = throw UnsupportedOperationException("an embed does not split")

    override fun mergeWith(right: Content) = false

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) = encoder.writeJson(embed)
}

/** A formatting attribute; it takes a clock but no index. */
class ContentFormat(
    val key: String,
    val value: YAny,
) : Content() {
    override val ref get() = FORMAT
    override val length get() = 1
    override val countable get() = false

    override fun splice(offset: Int): Content = throw UnsupportedOperationException("a format does not split")

    override fun mergeWith(right: Content) = false

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) {
        encoder.writeKey(key)
        encoder.writeJson(value)
    }
}

/** A nested shared type. */
class ContentType(
    val type: Branch,
) : Content() {
    override val ref get() = TYPE
    override val length get() = 1
    override val countable get() = true

    override fun splice(offset: Int): Content = throw UnsupportedOperationException("a type does not split")

    override fun mergeWith(right: Content) = false

    override fun integrate(
        transaction: Transaction,
        item: Item,
    ) {
        type.integrate(transaction.doc, item)
    }

    override fun delete(transaction: Transaction) {
        var item = type.start
        while (item != null) {
            if (!item.deleted) {
                item.delete(transaction)
            } else if (item.id.clock < (transaction.beforeState[item.id.client] ?: 0)) {
                // Deleted before this transaction, so not in its delete set: merge it later.
                transaction.mergeStructs.add(item)
            }
            item = item.right
        }
        for (entry in type.map.values) {
            if (!entry.deleted) {
                entry.delete(transaction)
            } else if (entry.id.clock < (transaction.beforeState[entry.id.client] ?: 0)) {
                transaction.mergeStructs.add(entry)
            }
        }
        transaction.changed.remove(type)
    }

    override fun gc(store: StructStore) {
        var item = type.start
        while (item != null) {
            item.gc(store, true)
            item = item.right
        }
        type.start = null
        for (last in type.map.values) {
            var entry: Item? = last
            while (entry != null) {
                entry.gc(store, true)
                entry = entry.left
            }
        }
        type.map.clear()
    }

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) = type.writeTypeRef(encoder)
}

/**
 * A subdocument, kept as its guid and options. yjs rebuilds the options from the document it
 * creates, so they are normalised here the same way: `gc: false`, `autoLoad: true` and `meta`,
 * in that order, each only when it applies.
 */
class ContentDoc(
    val guid: String,
    val opts: YAny.Obj,
) : Content() {
    override val ref get() = DOC
    override val length get() = 1
    override val countable get() = true

    override fun splice(offset: Int): Content = throw UnsupportedOperationException("a subdocument does not split")

    override fun mergeWith(right: Content) = false

    override fun write(
        encoder: UpdateEncoder,
        offset: Int,
    ) {
        encoder.writeString(guid)
        encoder.writeAny(opts)
    }

    companion object {
        fun read(
            guid: String,
            raw: YAny,
        ): ContentDoc {
            val source = raw as? YAny.Obj ?: YAny.Obj.of()
            val entries = ArrayList<Pair<String, YAny>>()
            val gc = source["gc"]
            if (gc != null && gc != YAny.Undefined && !truthy(gc)) entries.add("gc" to YAny.Bool(false))
            if (source["autoLoad"]?.let(::truthy) == true) entries.add("autoLoad" to YAny.Bool(true))
            val meta = source["meta"]
            if (meta != null && meta != YAny.Undefined && meta != YAny.Null) entries.add("meta" to meta)
            val ownGuid = (source["guid"] as? YAny.Str)?.value ?: guid
            return ContentDoc(ownGuid, YAny.Obj.of(entries))
        }

        private fun truthy(value: YAny): Boolean =
            when (value) {
                YAny.Undefined, YAny.Null -> false
                is YAny.Bool -> value.value
                is YAny.Num -> value.value != 0.0 && !value.value.isNaN()
                is YAny.BigInt -> value.value != 0L
                is YAny.Str -> value.value.isNotEmpty()
                else -> true
            }
    }
}
