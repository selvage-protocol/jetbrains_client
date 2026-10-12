package dev.dontblameme.selvage.intellij.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import dev.dontblameme.selvage.crdt.EditorCommit
import dev.dontblameme.selvage.crdt.EditorText
import dev.dontblameme.selvage.crdt.TextDelta
import dev.dontblameme.selvage.intellij.bridge.Editing
import dev.dontblameme.selvage.intellij.bridge.Editing.TextChange
import dev.dontblameme.selvage.intellij.bridge.Grant

/** The room's replica of the documents, as the editor sees it. Offsets are UTF-16 code units of LF text. */
interface Replica {
    fun text(path: String): String

    fun has(path: String): Boolean

    /** Replaces `[start, end)` with [text] only while the replica reads [expected]. */
    fun replaceIf(
        path: String,
        expected: String,
        start: Int,
        end: Int,
        text: String,
    ): Boolean

    fun editorText(path: String): EditorText

    /** Commits only this path's observed-item deletes and anchored inserts, with atomic validation. */
    fun commitEditorIf(
        path: String,
        editor: EditorText,
        expected: String,
        maxBytes: Int,
    ): EditorCommit

    fun hold(path: String)

    fun release(path: String)
}

/**
 * Keeps each bound IntelliJ [Document] and its replica text in step, both ways, with nothing that
 * loops. A `Document` is UTF-16 with LF line separators (the file's own separator is applied when it
 * is saved), so its offsets are the replica's and the EOL policy is the platform's.
 *
 * Each bound document retains only its visible item identities. Editor operations name the items
 * seen and the insertion boundary, so unseen concurrent insertions survive queued deletes. A
 * remote edit is applied in a write command, which is on the undo stack as in VS Code: undoing a
 * peer's edit is a change like any other and is published.
 */
class DocumentSync(
    private val project: Project,
    private val replica: Replica,
    private val reports: Reports,
    parent: Disposable,
    private val autoSave: () -> Boolean = { true },
    private val saveSettleMs: Int = 500,
) : Disposable {
    /** Where a bound document's changes go besides the replica: the follow, the save policy. */
    interface Reports {
        fun localEdit(path: String) {}

        fun applyRefused(path: String) {}

        fun divergence(path: String) {}

        /** The document grew past what a session carries, so nothing of it is published until it shrinks. */
        fun overBound(path: String) {}

        /** The document's file could not be written; [why] when the editor said. */
        fun saveFailed(
            path: String,
            why: String?,
        ) {}
    }

    private class Bound(
        val path: String,
        val document: Document,
        var editor: EditorText,
    ) {
        var applying = 0
        var shadow = editor.text
        var malformed: Malformed? = null
        var invalid = false
        val remote = ArrayList<List<TextDelta>>()
        var syncQueued = false

        /** Made read-only by this sync because the session is a viewer's, and given back when unbound. */
        var locked = false

        /** The room text the editor last refused to take, said once until a write lands. */
        var refused: String? = null
    }

    /**
     * What a document falls back to while a lone surrogate sits in it, and the runs that lone surrogate
     * damaged. A run moves only with an event that touches it, so an edit between two damaged runs is
     * an edit like any other and survives the fallback.
     */
    private class Malformed(
        var backup: EditorText,
        val regions: MutableList<Region>,
    ) {
        /** A range ends before collapsed restored content; an insertion or range start follows it. */
        fun toBackup(
            position: Int,
            rangeEnd: Boolean = false,
        ): Int =
            position +
                regions
                    .filter { it.end <= position && !(rangeEnd && it.start == position && it.end == position) }
                    .sumOf { it.offset }

        fun shift(
            from: Int,
            by: Int,
            insertion: Boolean = false,
        ) {
            for (region in regions) {
                // An insertion at a collapsed run follows its restored content in the backup.
                if (insertion && region.start == from && region.end == from) continue
                if (region.start >= from) {
                    region.start += by
                    region.end += by
                }
            }
        }
    }

    /**
     * Merges every damaged run an event's own boundaries cover into one, and leaves the backup alone:
     * what the event damaged is what [sync] restores.
     */
    private fun merge(
        group: Malformed,
        before: String,
        change: TextChange,
        shift: Int,
    ) {
        val (start, end) = damagedSpan(before, change)
        var from = start
        var to = end
        var offset = 0
        val kept = ArrayList<Region>()
        for (region in group.regions) {
            if (start < region.end && end > region.start) {
                from = minOf(from, region.start)
                to = maxOf(to, region.end)
                offset += region.offset
            } else {
                kept.add(region)
            }
        }
        group.regions.clear()
        group.regions.addAll(kept)
        group.shift(end, shift)
        group.regions.add(Region(from, to + shift, offset - shift))
        group.regions.sortBy { it.start }
    }

    /** A run of the damaged text, and what [Malformed.backup] holds there: its length there less its length here. */
    private class Region(
        var start: Int,
        var end: Int,
        var offset: Int,
    )

    private val bound = HashMap<String, Bound>()
    private val byDocument = HashMap<Document, Bound>()
    private val saveAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val saves = HashMap<String, Runnable>()
    private var disposed = false

    /**
     * A viewer's session (§13.9): every bound document is read-only, the editor's own way of refusing
     * a keystroke, while the room's edits are still written into it.
     */
    var viewer = false
        set(value) {
            if (field == value) return
            field = value
            bound.values.forEach { lock(it, value) }
        }

    /** Off in a test that shows an unguarded editor would echo the room's edits back to it. */
    internal var echoGuard = true

    init {
        Disposer.register(parent, this)
        com.intellij.openapi.editor.EditorFactory
            .getInstance()
            .eventMulticaster
            .addDocumentListener(
                object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) {
                        val entry = byDocument[event.document] ?: return
                        changed(entry, event)
                    }
                },
                this,
            )
    }

    fun paths(): List<String> = bound.keys.sorted()

    /** True while the room's edit is being written into a document. */
    val applying: Boolean get() = bound.values.any { it.applying > 0 }

    fun isBound(path: String): Boolean = path in bound

    fun documentOf(path: String): Document? = bound[path]?.document

    fun pathOf(document: Document): String? = byDocument[document]?.path

    /**
     * Puts [document] in front of the room as [path]. The replica's text wins where it has any, and
     * the document is brought to it; a path the replica lacks takes the document's text when [seed]
     * says this window supplies it (a host), and waits for the room's otherwise.
     */
    fun bind(
        path: String,
        document: Document,
        seed: Boolean,
    ) {
        if (disposed || path in bound) return
        val entry = Bound(path, document, replica.editorText(path))
        bound[path] = entry
        byDocument[document] = entry
        if (viewer) lock(entry, true)
        if (seed && !viewer && !replica.has(path)) {
            val text = document.text
            if (replica.replaceIf(path, "", 0, 0, text)) {
                entry.editor = replica.editorText(entry.path)
                entry.shadow = entry.editor.text
            }
        }
        replica.hold(path)
        reconcile(entry)
    }

    fun unbind(path: String) {
        val entry = bound.remove(path) ?: return
        byDocument.remove(entry.document)
        cancelSave(path)
        lock(entry, false)
        replica.release(path)
    }

    private fun lock(
        entry: Bound,
        on: Boolean,
    ) {
        if (on == entry.locked) return
        if (on && !entry.document.isWritable) return
        entry.locked = on
        entry.document.setReadOnly(on)
    }

    /** The room changed [path]: the deltas are applied in order where they still describe the text. */
    fun remoteEdit(
        path: String,
        delta: List<TextDelta>,
    ) {
        val entry = bound[path] ?: return
        entry.remote.add(delta)
        scheduleSync(entry)
    }

    /**
     * Brings every bound document with something to settle to the replica now, merging what was typed
     * meanwhile: a keystroke held back, a room edit not yet applied, or one the editor refused.
     */
    fun syncAll() {
        bound.values
            .filter {
                it.editor.changed || it.malformed != null || it.invalid || it.remote.isNotEmpty() ||
                    it.refused != null || it.document.text != it.shadow
            }.forEach { sync(it) }
    }

    fun sync(path: String) {
        bound[path]?.let(::sync)
    }

    private fun changed(
        entry: Bound,
        event: DocumentEvent,
    ) {
        if (echoGuard && entry.applying > 0) return
        if (viewer) {
            scheduleSync(entry)
            return
        }
        reports.localEdit(entry.path)
        moveSave(entry)
        val after = entry.document.text
        val before =
            after.substring(0, event.offset) + event.oldFragment + after.substring(event.offset + event.newLength)
        if (echoGuard && entry.editor.text != before) {
            entry.invalid = true
            scheduleSync(entry)
            return
        }
        val change = TextChange(event.offset, event.offset + event.oldLength, event.newFragment.toString())
        record(entry, before, after, change)
        if (entry.malformed != null) {
            scheduleSync(entry)
            return
        }
        if (overBound(entry)) return
        if (entry.remote.isEmpty()) {
            when (
                val result =
                    replica.commitEditorIf(
                        entry.path,
                        entry.editor,
                        entry.shadow,
                        Grant.MAX_GRANT_FILE_BYTES,
                    )
            ) {
                is EditorCommit.Applied -> {
                    entry.editor = if (result.editor.text == after) result.editor else result.visible
                    entry.shadow = entry.editor.text
                    if (result.editor.text != after) {
                        entry.remote.add(emptyList())
                        scheduleSync(entry)
                    }
                    return
                }

                EditorCommit.OverBound -> {
                    reports.overBound(entry.path)
                    return
                }

                else -> {}
            }
        }
        scheduleSync(entry)
    }

    /**
     * Holds the events of one command while the document is malformed. An event that touches no damaged
     * run is a valid edit and goes into the backup at its own offset; one that damages text merges the
     * runs it covers. [sync] restores the backup, so only the damaged runs are refused.
     */
    private fun record(
        entry: Bound,
        before: String,
        after: String,
        change: TextChange,
    ) {
        val group = entry.malformed
        val shift = change.text.length - (change.end - change.start)
        if (group == null) {
            if (Editing.hasLoneSurrogate(after)) {
                val (start, end) = damagedSpan(before, change)
                entry.malformed = Malformed(entry.editor, mutableListOf(Region(start, end + shift, -shift)))
            }
        } else if (group.regions.none { change.start < it.end && change.end > it.start }) {
            val backup =
                group.backup.replace(
                    group.toBackup(change.start),
                    group.toBackup(change.end, rangeEnd = change.start < change.end),
                    change.text,
                )
            if (!Editing.hasLoneSurrogate(backup.text)) {
                group.backup = backup
                group.shift(change.end, shift, insertion = change.start == change.end)
            } else {
                merge(group, before, change, shift)
            }
        } else {
            merge(group, before, change, shift)
        }
        entry.editor = entry.editor.replace(change.start, change.end, change.text)
        if (!Editing.hasLoneSurrogate(after)) entry.malformed = null
    }

    /** The run of [before] an event's own boundaries damage: half a character it left without its partner. */
    private fun damagedSpan(
        before: String,
        change: TextChange,
    ): Pair<Int, Int> {
        val start =
            if (change.start > 0 && before[change.start - 1].isHighSurrogate()) {
                change.start - 1
            } else {
                change.start
            }
        val end =
            if (change.end < before.length && before[change.end].isLowSurrogate()) {
                change.end + 1
            } else {
                change.end
            }
        return start to end
    }

    private fun scheduleSync(entry: Bound) {
        if (entry.syncQueued) return
        entry.syncQueued = true
        ApplicationManager.getApplication().invokeLater(
            {
                entry.syncQueued = false
                if (bound[entry.path] === entry) sync(entry)
            },
            ModalityState.nonModal(),
        )
    }

    private fun sync(entry: Bound) {
        if (disposed || entry.applying > 0) return
        if (viewer || entry.invalid) {
            if (entry.invalid) reports.divergence(entry.path)
            entry.invalid = false
            entry.malformed = null
            entry.remote.clear()
            val room = replica.editorText(entry.path)
            if (land(entry, room.text, null)) entry.editor = room
            return
        }
        entry.malformed?.let {
            reports.divergence(entry.path)
            entry.editor = it.backup
            entry.malformed = null
        }
        if (!entry.editor.changed) {
            val room = replica.editorText(entry.path)
            if (applyRemote(entry, room.text)) entry.editor = room
            return
        }
        if (overBound(entry) && !Editing.hasLoneSurrogate(entry.document.text)) return
        repeat(MAX_ATTEMPTS) {
            val expected = replica.text(entry.path)
            when (val result = replica.commitEditorIf(entry.path, entry.editor, expected, Grant.MAX_GRANT_FILE_BYTES)) {
                is EditorCommit.Applied -> {
                    entry.remote.clear()
                    entry.editor = result.editor
                    if (!land(entry, result.editor.text, null)) entry.editor = result.visible
                    return
                }

                EditorCommit.OverBound -> {
                    reports.overBound(entry.path)
                    return
                }

                EditorCommit.Moved -> {}

                EditorCommit.Refused -> {
                    return@repeat
                }
            }
        }
        reports.divergence(entry.path)
        entry.remote.clear()
        val room = replica.editorText(entry.path)
        if (land(entry, room.text, null)) entry.editor = room
    }

    /**
     * The size gate a host's first share passes, applied to the document at every change, as VS Code
     * applies it: a document past the bound publishes nothing and is left as it is, rather than
     * brought back to the room's text, until it is under the bound again.
     */
    private fun overBound(entry: Bound): Boolean {
        val length = entry.document.textLength
        if (length * 3L <= Grant.MAX_GRANT_FILE_BYTES) return false
        if (!Grant.overFileBound(entry.document.text)) return false
        reports.overBound(entry.path)
        return true
    }

    private fun reconcile(entry: Bound) {
        if (entry.document.text != entry.shadow) land(entry, entry.shadow, null)
    }

    /**
     * Writes the room's [target] into the document, by [changes] when they are given. The shadow is
     * [target] while the write runs, and stays so only when the write landed. When the editor refuses
     * it, the shadow becomes what the document still holds, so the next sync neither publishes the
     * document's older text over the room's nor counts the room's edit as agreed: it tries again.
     */
    private fun land(
        entry: Bound,
        target: String,
        changes: List<TextChange>?,
    ): Boolean {
        entry.shadow = target
        val landed = if (changes == null) bring(entry, target) else edit(entry, changes)
        if (landed) {
            entry.refused = null
            return true
        }
        if (entry.refused != target) {
            entry.refused = target
            reports.applyRefused(entry.path)
        }
        entry.shadow = entry.document.text
        return false
    }

    /** The document holds what the shadow held; apply the room's text, by its own deltas where they fit. */
    private fun applyRemote(
        entry: Bound,
        room: String,
    ): Boolean {
        val deltas = ArrayList(entry.remote)
        entry.remote.clear()
        if (room == entry.shadow) {
            reconcile(entry)
            return entry.document.text == room
        }
        val changes = if (entry.document.text == entry.shadow) changesOf(entry.shadow, deltas, room) else null
        val landed = land(entry, room, changes)
        if (landed && autoSave()) scheduleSave(entry)
        return landed
    }

    /** The deltas as changes in sequence, when they turn [from] into [to]; null when they do not. */
    private fun changesOf(
        from: String,
        deltas: List<List<TextDelta>>,
        to: String,
    ): List<TextChange>? {
        var text = from
        val changes = ArrayList<TextChange>()
        for (delta in deltas) {
            var position = 0
            for (op in delta) {
                when (op) {
                    is TextDelta.Retain -> {
                        position += op.length
                    }

                    is TextDelta.Insert -> {
                        if (position > text.length) return null
                        val change = TextChange(position, position, op.text)
                        text = Editing.apply(text, change)
                        changes.add(change)
                        position += op.text.length
                    }

                    is TextDelta.Delete -> {
                        if (position + op.length > text.length) return null
                        val change = TextChange(position, position + op.length, "")
                        text = Editing.apply(text, change)
                        changes.add(change)
                    }

                    is TextDelta.InsertEmbed -> {
                        return null
                    }
                }
            }
        }
        return if (text == to) changes else null
    }

    private fun bring(
        entry: Bound,
        target: String,
    ): Boolean {
        val change = Editing.diff(entry.document.text, target)
        return change.isEmpty || edit(entry, listOf(change))
    }

    /** False when the document refuses the write: it is read-only, and not by this sync's own lock. */
    private fun edit(
        entry: Bound,
        changes: List<TextChange>,
    ): Boolean {
        val document = entry.document
        if (!document.isWritable && !entry.locked) return false
        entry.applying += 1
        // The viewer's lock refuses the person's keystrokes, not the room's own text.
        if (entry.locked) document.setReadOnly(false)
        try {
            WriteCommandAction
                .writeCommandAction(project)
                .withName("Selvage")
                .run<RuntimeException> {
                    for (change in changes) document.replaceString(change.start, change.end, change.text)
                }
        } finally {
            if (entry.locked) document.setReadOnly(true)
            entry.applying -= 1
        }
        return true
    }

    /** Each document is written once the room's edits to it settle, on its own clock. */
    private fun scheduleSave(entry: Bound) {
        cancelSave(entry.path)
        val request =
            Runnable {
                saves.remove(entry.path)
                if (bound[entry.path] === entry) save(entry)
            }
        saves[entry.path] = request
        saveAlarm.addRequest(request, saveSettleMs)
    }

    /** A keystroke inside the settle window moves the write rather than racing it. */
    private fun moveSave(entry: Bound) {
        if (entry.path in saves) scheduleSave(entry)
    }

    private fun cancelSave(path: String) {
        saves.remove(path)?.let { saveAlarm.cancelRequest(it) }
    }

    /**
     * Writes the document. The IDE says a failed write in its own dialog and keeps the document
     * unsaved, so the room's sentence is said when it is still unsaved afterwards; the cause is
     * known only when the write refuses outright or the IDE hands the failure back.
     */
    private fun save(entry: Bound) {
        val manager = FileDocumentManager.getInstance()
        val file = manager.getFile(entry.document)
        if (file != null && !file.isWritable) {
            reports.saveFailed(entry.path, "the file is read-only")
            return
        }
        try {
            // As it is: a save the room asked for must not add a final line break nobody typed,
            // which every other participant would receive as an edit.
            manager.saveDocumentAsIs(entry.document)
        } catch (e: RuntimeException) {
            reports.saveFailed(entry.path, (e.cause ?: e).message ?: e.toString())
            return
        }
        if (manager.isDocumentUnsaved(entry.document)) reports.saveFailed(entry.path, null)
    }

    override fun dispose() {
        disposed = true
        bound.values.forEach { lock(it, false) }
        bound.clear()
        byDocument.clear()
        saves.clear()
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
    }
}
