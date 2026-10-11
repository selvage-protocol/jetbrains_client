package dev.dontblameme.selvage.crdt

/** A path's visible item identities, with editor replacements held at their actual UTF-16 offsets. */
class EditorText private constructor(
    private val owner: Doc,
    val path: String,
    private val observed: List<Piece>,
    private val pieces: List<Piece>,
    private val supported: Boolean,
) {
    internal data class Piece(
        val id: ID?,
        val text: String,
        val anchor: RelativePosition? = null,
    ) {
        fun slice(
            start: Int,
            end: Int,
        ): Piece = copy(id = id?.let { ID(it.client, it.clock + start) }, text = text.substring(start, end))
    }

    val text: String get() = pieces.joinToString("") { it.text }
    val changed: Boolean get() = pieces != observed

    /** No retained history, other roots, or encoded updates are copied into an editor basis. */
    val retainedLength: Int get() =
        observed.sumOf { it.text.length } +
            pieces.filter { it.id == null }.sumOf { it.text.length }

    fun replace(
        start: Int,
        end: Int,
        text: String,
    ): EditorText {
        val length = pieces.sumOf { it.text.length }
        require(start in 0..end && end <= length)
        if (start == end && text.isEmpty()) return this
        val anchor = anchorAt(start)
        val result = ArrayList<Piece>()
        var position = 0
        for (piece in pieces) {
            val next = position + piece.text.length
            if (position < start) result.add(piece.slice(0, minOf(start, next) - position))
            position = next
        }
        if (text.isNotEmpty()) result.add(Piece(null, text, anchor))
        position = 0
        for (piece in pieces) {
            val next = position + piece.text.length
            if (next > end) result.add(piece.slice(maxOf(end, position) - position, piece.text.length))
            position = next
        }
        return copy(coalesce(result))
    }

    private fun copy(result: List<Piece>): EditorText = EditorText(owner, path, observed, result, supported)

    private fun anchorAt(offset: Int): RelativePosition {
        var position = 0
        for (piece in pieces) {
            if (offset < position + piece.text.length) {
                return piece.anchor
                    ?: RelativePosition(null, path, ID(piece.id!!.client, piece.id.clock + offset - position))
            }
            position += piece.text.length
        }
        val last = pieces.lastOrNull()
        return last?.anchor ?: RelativePosition(null, path, null)
    }

    private fun wholeCharacters(): List<Piece> {
        val result = pieces.toMutableList()
        var index = 0
        while (index + 1 < result.size) {
            val left = result[index]
            val right = result[index + 1]
            if (left.text.last().isHighSurrogate() && right.text.first().isLowSurrogate()) {
                val pair = left.text.takeLast(1) + right.text.take(1)
                val anchor =
                    left.anchor
                        ?: RelativePosition(null, path, ID(left.id!!.client, left.id.clock + left.text.length - 1))
                result.removeAt(index + 1)
                result.removeAt(index)
                val replacements = ArrayList<Piece>()
                if (left.text.length > 1) replacements.add(left.slice(0, left.text.length - 1))
                replacements.add(Piece(null, pair, anchor))
                if (right.text.length > 1) replacements.add(right.slice(1, right.text.length))
                result.addAll(index, replacements)
                index += replacements.size - 1
            } else {
                index++
            }
        }
        return coalesce(result)
    }

    internal data class Edit(
        val start: Int,
        val end: Int,
        val text: String,
        val pieceIndex: Int? = null,
    )

    internal class Plan(
        private val editor: EditorText,
        private val normalized: List<Piece>,
        val edits: List<Edit>,
    ) {
        fun apply(doc: Doc): EditorText {
            val inserted = HashMap<Int, ID>()
            val handle = doc.getText(editor.path)
            for (edit in edits) {
                handle.delete(edit.start, edit.end - edit.start)
                if (edit.text.isNotEmpty()) {
                    handle.insert(edit.start, edit.text)
                    inserted[edit.pieceIndex!!] = RelativePosition.fromIndex(handle, edit.start).item!!
                }
            }
            val visible =
                normalized.mapIndexed { index, piece ->
                    inserted[index]?.let { Piece(it, piece.text) } ?: piece
                }
            return EditorText(doc, editor.path, visible, visible, editor.supported)
        }
    }

    internal fun plan(
        doc: Doc,
        expected: String,
        maxBytes: Int,
    ): Pair<EditorCommit?, Plan?> {
        if (owner !== doc || !supported) return EditorCommit.Refused to null
        val current = capture(doc, path)
        if (!current.supported) return EditorCommit.Refused to null
        if (current.text != expected) return EditorCommit.Moved to null
        if (hasLoneSurrogate(text)) return EditorCommit.Refused to null
        val normalized = wholeCharacters()
        val observedByClient = observed.filter { it.id != null }.groupBy { it.id!!.client }
        val survivors =
            normalized
                .filter { it.id != null }
                .groupBy { it.id!!.client }
                .mapValues { (_, pieces) -> pieces.sortedBy { it.id!!.clock } }
        val deletions = ArrayList<Edit>()
        var position = 0
        for (live in current.pieces) {
            val id = live.id!!
            val wanted = observedByClient[id.client].orEmpty()
            for (old in wanted) {
                val start = maxOf(id.clock, old.id!!.clock)
                val end = minOf(id.clock + live.text.length, old.id.clock + old.text.length)
                if (start >= end) continue
                var cursor = start
                // Both lists are partitions of the same per-client clock space, so a piece outside the
                // live range is skipped or ends the scan.
                for (keep in survivors[id.client].orEmpty()) {
                    val keepClock = keep.id!!.clock
                    if (keepClock >= end) break
                    if (keepClock + keep.text.length <= start) continue
                    val keepStart = maxOf(start, keepClock)
                    val keepEnd = minOf(end, keepClock + keep.text.length)
                    if (keepStart >= keepEnd) continue
                    if (cursor <
                        keepStart
                    ) {
                        deletions.add(
                            Edit(
                                position + (cursor - id.clock).toInt(),
                                position + (keepStart - id.clock).toInt(),
                                "",
                            ),
                        )
                    }
                    cursor = maxOf(cursor, keepEnd)
                }
                if (cursor <
                    end
                ) {
                    deletions.add(
                        Edit(position + (cursor - id.clock).toInt(), position + (end - id.clock).toInt(), ""),
                    )
                }
            }
            position += live.text.length
        }
        val edits = deletions.sortedByDescending { it.start }.toMutableList()
        val inserts =
            normalized.withIndex().filter { it.value.id == null }.map { (index, piece) ->
                val at = if (path in doc.rootNames) piece.anchor!!.resolve(doc, path) else 0
                if (at == null) return EditorCommit.Refused to null
                val mapped = at - deletions.sumOf { minOf(maxOf(at - it.start, 0), it.end - it.start) }
                Edit(mapped, mapped, piece.text, index)
            }
        edits.addAll(
            inserts
                .withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<Edit>> {
                        it.value.start
                    }.thenByDescending { it.index },
                ).map { it.value },
        )
        var merged = current.text
        for (edit in edits) merged = merged.substring(0, edit.start) + edit.text + merged.substring(edit.end)
        if (hasLoneSurrogate(merged)) return EditorCommit.Refused to null
        if (merged.length > maxBytes ||
            (merged.length * 3L > maxBytes && merged.toByteArray(Charsets.UTF_8).size > maxBytes)
        ) {
            return EditorCommit.OverBound to null
        }
        return null to Plan(this, normalized, edits)
    }

    /** The caller owns [doc]'s synchronization; an editor from another document is refused. */
    fun commit(
        doc: Doc,
        expected: String,
        maxBytes: Int,
    ): EditorCommit {
        val (refusal, plan) = plan(doc, expected, maxBytes)
        if (refusal != null) return refusal
        val visible = doc.transact { plan!!.apply(doc) }
        return EditorCommit.Applied(capture(doc, path), visible)
    }

    companion object {
        fun capture(
            doc: Doc,
            path: String,
        ): EditorText {
            val pieces = ArrayList<Piece>()
            var supported = true
            if (path in doc.rootNames) {
                var item = doc.getText(path).branch.start
                while (item != null) {
                    if (!item.deleted && item.countable) {
                        val content = item.content
                        if (content is ContentString) pieces.add(Piece(item.id, content.str)) else supported = false
                    }
                    item = item.right
                }
            }
            val observed = coalesce(pieces)
            return EditorText(doc, path, observed, observed, supported)
        }

        private fun coalesce(pieces: List<Piece>): List<Piece> {
            val result = ArrayList<Piece>()
            for (piece in pieces.filter { it.text.isNotEmpty() }) {
                val last = result.lastOrNull()
                val adjacent =
                    last?.id != null && piece.id != null && last.id.client == piece.id.client &&
                        last.id.clock + last.text.length == piece.id.clock
                if (last != null &&
                    (adjacent || (last.id == null && piece.id == null && last.anchor == piece.anchor))
                ) {
                    result[result.lastIndex] = last.copy(text = last.text + piece.text)
                } else {
                    result.add(piece)
                }
            }
            return result
        }

        private fun hasLoneSurrogate(text: String): Boolean {
            var index = 0
            while (index < text.length) {
                if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    index += 2
                } else {
                    if (text[index].isSurrogate()) return true
                    index++
                }
            }
            return false
        }
    }
}

sealed interface EditorCommit {
    data class Applied(
        val editor: EditorText,
        val visible: EditorText,
    ) : EditorCommit

    data object Moved : EditorCommit

    data object OverBound : EditorCommit

    data object Refused : EditorCommit
}
