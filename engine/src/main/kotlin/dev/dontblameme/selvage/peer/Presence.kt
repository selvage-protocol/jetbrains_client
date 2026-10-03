package dev.dontblameme.selvage.peer

import dev.dontblameme.selvage.crdt.Doc
import dev.dontblameme.selvage.crdt.RelativePosition
import dev.dontblameme.selvage.crdt.YAny

/** A caret or selection in UTF-16 offsets; a caret has [anchor] equal to [head]. */
data class Selection(
    val anchor: Int,
    val head: Int,
)

/** One awareness state as a document position (`PROTOCOL.md` §8.1): where a client is, resolved. */
data class Cursor(
    val clientId: Long,
    val path: String?,
    val selection: Selection?,
)

/** The §8.1 awareness state `{path, selection: {anchor, head}}` with anchors as relative positions. */
internal object AwarenessState {
    fun of(
        path: String,
        anchor: RelativePosition?,
        head: RelativePosition?,
    ): YAny.Obj {
        if (anchor == null || head == null) return YAny.Obj.of("path" to YAny.Str(path))
        return YAny.Obj.of(
            "path" to YAny.Str(path),
            "selection" to YAny.Obj.of("anchor" to anchor.toJson(), "head" to head.toJson()),
        )
    }

    /** The path and the offsets [state] names in [doc], or what of it resolves. */
    fun resolve(
        clientId: Long,
        state: YAny,
        doc: Doc,
    ): Cursor {
        val obj = state as? YAny.Obj ?: return Cursor(clientId, null, null)
        val path = (obj["path"] as? YAny.Str)?.value ?: return Cursor(clientId, null, null)
        val selection = obj["selection"] as? YAny.Obj
        val anchor = selection?.get("anchor")?.let(::anchorOf)?.resolve(doc, path)
        val head = selection?.get("head")?.let(::anchorOf)?.resolve(doc, path)
        val resolved = if (anchor != null && head != null && path in doc.rootNames) Selection(anchor, head) else null
        return Cursor(clientId, path, resolved)
    }

    /** §8.1.1: an anchor names an element or a root scope, never a nested type and a scope both. */
    private fun anchorOf(raw: YAny): RelativePosition? {
        val position = RelativePosition.fromJson(raw) ?: return null
        if (position.item == null && position.tname == null && position.type == null) return null
        if (position.tname != null && position.type != null) return null
        return position
    }
}
