package dev.dontblameme.selvage.crdt

/**
 * A shared type: yjs's `AbstractType`. A root type is named by [rootName]; a nested one is held
 * by [item]. Only [YText] is given an API; every other type is carried so that nothing a peer
 * sends is lost.
 */
class Branch internal constructor(
    /** yjs's type ref for a nested type (`YArray` 0 … `YXmlText` 6), or null for a root type. */
    val typeRef: Int?,
    /** `YXmlElement`'s node name or `YXmlHook`'s hook name. */
    val typeKey: String? = null,
) {
    var doc: Doc? = null
        internal set
    var item: Item? = null
        internal set
    var rootName: String? = null
        internal set
    var start: Item? = null
    val map = LinkedHashMap<String, Item>()
    var length: Int = 0

    internal val observers = ArrayList<(TextEvent) -> Unit>()

    internal fun integrate(
        doc: Doc,
        item: Item?,
    ) {
        this.doc = doc
        this.item = item
    }

    internal fun writeTypeRef(encoder: UpdateEncoder) {
        val ref = typeRef ?: throw IllegalStateException("a root type is not written as content")
        encoder.writeTypeRef(ref)
        if (ref == YXML_ELEMENT || ref == YXML_HOOK) encoder.writeKey(typeKey!!)
    }

    companion object {
        const val YARRAY = 0
        const val YMAP = 1
        const val YTEXT = 2
        const val YXML_ELEMENT = 3
        const val YXML_FRAGMENT = 4
        const val YXML_HOOK = 5
        const val YXML_TEXT = 6

        internal fun read(decoder: UpdateDecoder): Branch =
            when (val ref = decoder.readTypeRef()) {
                YARRAY, YMAP, YTEXT, YXML_FRAGMENT, YXML_TEXT -> Branch(ref)
                YXML_ELEMENT, YXML_HOOK -> Branch(ref, decoder.readKey())
                else -> throw DecodeException("unknown type ref $ref")
            }
    }
}
