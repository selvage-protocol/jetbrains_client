package dev.dontblameme.selvage.crdt

/**
 * yjs's `RelativePosition`: a place in a type named by the element beside it rather than by an
 * offset. [type] names a nested type and [tname] a root type (the scope); [item] names the
 * element; [assoc] is `>= 0` for the element after the place and `< 0` for the one before.
 */
data class RelativePosition(
    val type: ID?,
    val tname: String?,
    val item: ID?,
    val assoc: Int = 0,
) {
    /** yjs's `relativePositionToJSON`: members in the order `type`, `tname`, `item`, `assoc`. */
    fun toJson(): YAny.Obj {
        val entries = ArrayList<Pair<String, YAny>>()
        type?.let { entries.add("type" to it.toJson()) }
        if (!tname.isNullOrEmpty()) entries.add("tname" to YAny.Str(tname))
        item?.let { entries.add("item" to it.toJson()) }
        entries.add("assoc" to YAny.Num(assoc.toDouble()))
        return YAny.Obj.of(entries)
    }

    /** The form `yrs` writes for a root type: the element alone, or the scope when there is none. */
    fun toYrsForm(): RelativePosition = if (item != null) copy(type = null, tname = null) else this

    /**
     * The UTF-16 offset this position denotes in the text named [path] of [doc], by the rules of
     * the protocol's §8.1.1; null when it does not resolve there, which includes a [doc] holding no
     * text at [path] (the text is not created to resolve one).
     */
    fun resolve(
        doc: Doc,
        path: String,
    ): Int? {
        if (type != null) return null
        if (tname != null && tname != path) return null
        if (path !in doc.rootNames) return null
        val store = doc.store
        if (item != null) {
            if (store.getState(item.client) <= item.clock) return null
            val structs = store.clients.getValue(item.client)
            val right = structs[StructStore.findIndex(structs, item.clock)] as? Item ?: return null
            val parent = right.parent ?: return null
            if (parent.rootName != path || parent.doc !== doc) return null
            var index =
                if (right.deleted ||
                    !right.countable
                ) {
                    0
                } else {
                    (item.clock - right.id.clock).toInt() + (if (assoc >= 0) 0 else 1)
                }
            var n = right.left
            while (n != null) {
                if (!n.deleted && n.countable) index += n.length
                n = n.left
            }
            return index
        }
        if (tname == null) return null
        // A scope alone names an end of the text.
        if (assoc < 0) return 0
        return doc.get(path).length
    }

    companion object {
        /** yjs's `createRelativePositionFromTypeIndex` on a root text: the form a yjs peer publishes. */
        fun fromIndex(
            text: YText,
            index: Int,
            assoc: Int = 0,
        ): RelativePosition {
            val branch = text.branch
            if (index < 0 ||
                index > branch.length
            ) {
                throw IndexOutOfBoundsException("position $index in a text of length ${branch.length}")
            }
            val tname = branch.rootName ?: throw IllegalArgumentException("not a root type")
            var i = index
            if (assoc < 0) {
                if (i == 0) return RelativePosition(null, tname, null, assoc)
                i--
            }
            var t = branch.start
            while (t != null) {
                if (!t.deleted && t.countable) {
                    if (t.length > i) return RelativePosition(null, tname, ID(t.id.client, t.id.clock + i), assoc)
                    i -= t.length
                }
                if (t.right == null && assoc < 0) return RelativePosition(null, tname, t.lastId, assoc)
                t = t.right
            }
            return RelativePosition(null, tname, null, assoc)
        }

        /**
         * Reads an anchor object. Unknown members are ignored and `assoc` is normalised to `0` or
         * `-1` by its sign, defaulting to `0`. Null when a member is not readable, or when none of
         * `type`, `tname` and `item` is present, or when both scopes are.
         */
        fun fromJson(json: YAny): RelativePosition? {
            if (json !is YAny.Obj) return null
            val type =
                when (val t = json["type"]) {
                    null, YAny.Undefined, YAny.Null -> null
                    else -> readId(t) ?: return null
                }
            val tname =
                when (val t = json["tname"]) {
                    null, YAny.Undefined, YAny.Null -> null
                    is YAny.Str -> t.value
                    else -> return null
                }
            val item =
                when (val t = json["item"]) {
                    null, YAny.Undefined, YAny.Null -> null
                    else -> readId(t) ?: return null
                }
            val assoc =
                when (val a = json["assoc"]) {
                    null, YAny.Undefined, YAny.Null -> {
                        0
                    }

                    is YAny.Num -> {
                        if (a.value < 0) {
                            -1
                        } else if (a.value.isNaN()) {
                            return null
                        } else {
                            0
                        }
                    }

                    else -> {
                        return null
                    }
                }
            if (type == null && tname == null && item == null) return null
            if (type != null && tname != null) return null
            return RelativePosition(type, tname, item, assoc)
        }

        private fun readId(json: YAny): ID? {
            if (json !is YAny.Obj) return null
            val client = readUint(json["client"]) ?: return null
            val clock = readUint(json["clock"]) ?: return null
            return ID(client, clock)
        }

        private fun readUint(json: YAny?): Long? {
            val d = (json as? YAny.Num)?.value ?: return null
            if (d < 0 || d > MAX_SAFE_INTEGER.toDouble() || d != Math.floor(d)) return null
            return d.toLong()
        }

        private fun ID.toJson(): YAny.Obj =
            YAny.Obj.of(listOf("client" to YAny.Num(client.toDouble()), "clock" to YAny.Num(clock.toDouble())))
    }
}
