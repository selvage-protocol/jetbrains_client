package dev.dontblameme.selvage.intellij.bridge

/**
 * The document policy of `vscode_client/src/bridge/editing.ts`: LF in the replica, the document's
 * own line endings on disk, and the smallest change between two texts. An IntelliJ `Document` holds
 * LF already and keeps the file's separator for the save, so the conversions here are for text
 * read from or written to disk directly.
 */
object Editing {
    data class TextChange(
        val start: Int,
        val end: Int,
        val text: String,
    ) {
        val isEmpty: Boolean get() = start == end && text.isEmpty()
    }

    fun toCrdt(bufferText: String): String = bufferText.replace("\r\n", "\n")

    fun render(
        crdtText: String,
        eol: String,
    ): String = if (eol == "\n") crdtText else crdtText.replace("\n", eol)

    private fun splitsPair(
        text: String,
        offset: Int,
    ): Boolean {
        if (offset <= 0 || offset >= text.length) return false
        return text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()
    }

    /** The smallest replacement turning [from] into [to], never splitting a surrogate pair. */
    fun diff(
        from: String,
        to: String,
    ): TextChange {
        if (from == to) return TextChange(0, 0, "")
        var start = 0
        val shortest = minOf(from.length, to.length)
        while (start < shortest && from[start] == to[start]) start += 1
        var endFrom = from.length
        var endTo = to.length
        while (endFrom > start && endTo > start && from[endFrom - 1] == to[endTo - 1]) {
            endFrom -= 1
            endTo -= 1
        }
        if (splitsPair(from, start) || splitsPair(to, start)) start -= 1
        if (splitsPair(from, endFrom) || splitsPair(to, endTo)) {
            endFrom += 1
            endTo += 1
        }
        return TextChange(start, endFrom, to.substring(start, endTo))
    }

    /** Whether [change] of [text] puts a boundary between the two halves of one character. */
    fun splitsCharacter(
        text: String,
        change: TextChange,
    ): Boolean = splitsPair(text, change.start) || splitsPair(text, change.end)

    /** Whether [text] holds half a character: a surrogate without its partner (`PROTOCOL.md` §7). */
    fun hasLoneSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                index += 2
                continue
            }
            if (c.isSurrogate()) return true
            index += 1
        }
        return false
    }

    fun apply(
        text: String,
        change: TextChange,
    ): String = text.substring(0, change.start) + change.text + text.substring(maxOf(change.end, change.start))

    /**
     * Where [offset] of a text lands after [change] was applied to it: before the change it stays, after
     * it shifts, inside a replaced range it moves to the range's end.
     */
    fun mapOffset(
        offset: Int,
        change: TextChange,
    ): Int =
        when {
            offset <= change.start -> offset
            offset >= change.end -> offset + change.text.length - (change.end - change.start)
            else -> change.start + change.text.length
        }

    fun matchesReplica(
        bufferText: CharSequence,
        crdtText: String,
    ): Boolean = toCrdt(bufferText.toString()) == crdtText
}
