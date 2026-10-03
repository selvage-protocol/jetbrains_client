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
import dev.dontblameme.selvage.crdt.TextDelta
import dev.dontblameme.selvage.intellij.bridge.Editing
import dev.dontblameme.selvage.intellij.bridge.Editing.TextChange

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

    fun hold(path: String)

    fun release(path: String)
}

/**
 * Keeps each bound IntelliJ [Document] and its replica text in step, both ways, with nothing that
 * loops. A `Document` is UTF-16 with LF line separators (the file's own separator is applied when it
 * is saved), so its offsets are the replica's and the EOL policy is the platform's.
 *
 * Each bound document keeps the replica text it last agreed with. A keystroke is published at once
 * when the replica still reads that text; when a remote edit has landed in between, the keystroke is
 * held and both are merged on the next sync, so neither side's change is put at a stale offset. A
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
    }

    private class Bound(
        val path: String,
        val document: Document,
        var shadow: String,
    ) {
        var applying = 0
        val pending = ArrayList<TextChange>()
        val remote = ArrayList<List<TextDelta>>()
        var syncQueued = false
    }

    private val bound = HashMap<String, Bound>()
    private val byDocument = HashMap<Document, Bound>()
    private val saveAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var disposed = false

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
        val entry = Bound(path, document, replica.text(path))
        bound[path] = entry
        byDocument[document] = entry
        if (seed && !replica.has(path)) {
            val text = document.text
            if (replica.replaceIf(path, "", 0, 0, text)) entry.shadow = text
        }
        replica.hold(path)
        reconcile(entry)
    }

    fun unbind(path: String) {
        val entry = bound.remove(path) ?: return
        byDocument.remove(entry.document)
        replica.release(path)
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

    /** Brings every bound document to the replica now, merging what was typed meanwhile. */
    fun syncAll() {
        bound.values.toList().forEach { sync(it) }
    }

    fun sync(path: String) {
        bound[path]?.let(::sync)
    }

    private fun changed(
        entry: Bound,
        event: DocumentEvent,
    ) {
        if (echoGuard && entry.applying > 0) return
        reports.localEdit(entry.path)
        val change = TextChange(event.offset, event.offset + event.oldLength, event.newFragment.toString())
        // §7: a client writes whole code points. An edit whose boundary falls inside a character is
        // left to the sync, whose diff widens it, and one that would write half a character is refused.
        val whole = !Editing.splitsCharacter(entry.shadow, change) && !Editing.hasLoneSurrogate(change.text)
        if (whole && entry.pending.isEmpty() && entry.remote.isEmpty() &&
            replica.replaceIf(entry.path, entry.shadow, change.start, change.end, change.text)
        ) {
            entry.shadow = Editing.apply(entry.shadow, change)
            return
        }
        entry.pending.add(change)
        scheduleSync(entry)
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

    /**
     * The three-way step: the document's change since the shadow is published over whatever the room
     * did meanwhile, and the document is brought to the result.
     */
    private fun sync(entry: Bound) {
        if (disposed || entry.applying > 0) return
        repeat(MAX_ATTEMPTS) {
            val document = entry.document.text
            val room = replica.text(entry.path)
            val local = Editing.diff(entry.shadow, document)
            val remote = Editing.diff(entry.shadow, room)
            if (local.isEmpty) {
                entry.pending.clear()
                applyRemote(entry, room)
                return
            }
            val rebased = rebase(local, remote)
            if (Editing.hasLoneSurrogate(rebased.text) || Editing.splitsCharacter(room, rebased)) return@repeat
            if (replica.replaceIf(entry.path, room, rebased.start, rebased.end, rebased.text)) {
                entry.pending.clear()
                entry.remote.clear()
                entry.shadow = Editing.apply(room, rebased)
                bring(entry, entry.shadow)
                return
            }
        }
        reports.divergence(entry.path)
        entry.pending.clear()
        entry.remote.clear()
        entry.shadow = replica.text(entry.path)
        bring(entry, entry.shadow)
    }

    private fun reconcile(entry: Bound) {
        if (entry.document.text != entry.shadow) bring(entry, entry.shadow)
    }

    /** The document holds what the shadow held; apply the room's text, by its own deltas where they fit. */
    private fun applyRemote(
        entry: Bound,
        room: String,
    ) {
        val deltas = ArrayList(entry.remote)
        entry.remote.clear()
        if (room == entry.shadow) {
            reconcile(entry)
            return
        }
        val changes = changesOf(entry.shadow, deltas, room)
        entry.shadow = room
        if (changes == null) {
            bring(entry, room)
        } else {
            edit(entry, changes)
        }
        if (autoSave()) scheduleSave(entry)
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
    ) {
        val change = Editing.diff(entry.document.text, target)
        if (!change.isEmpty) edit(entry, listOf(change))
    }

    private fun edit(
        entry: Bound,
        changes: List<TextChange>,
    ) {
        val document = entry.document
        if (!document.isWritable) {
            reports.applyRefused(entry.path)
            return
        }
        entry.applying += 1
        try {
            WriteCommandAction
                .writeCommandAction(project)
                .withName("Selvage")
                .run<RuntimeException> {
                    for (change in changes) document.replaceString(change.start, change.end, change.text)
                }
        } finally {
            entry.applying -= 1
        }
    }

    private fun scheduleSave(entry: Bound) {
        saveAlarm.cancelAllRequests()
        saveAlarm.addRequest({
            if (bound[entry.path] === entry) FileDocumentManager.getInstance().saveDocument(entry.document)
        }, saveSettleMs)
    }

    override fun dispose() {
        disposed = true
        bound.clear()
        byDocument.clear()
    }

    companion object {
        private const val MAX_ATTEMPTS = 4

        /**
         * [local] as it applies after [remote]: both are replacements of one common text. Text the
         * remote change wrote survives the local one unless the local change covered it whole.
         */
        fun rebase(
            local: TextChange,
            remote: TextChange,
        ): TextChange {
            if (remote.isEmpty) return local
            val shift = remote.text.length - (remote.end - remote.start)
            val remoteEnd = remote.start + remote.text.length
            return when {
                local.end <= remote.start -> {
                    local
                }

                local.start >= remote.end -> {
                    TextChange(local.start + shift, local.end + shift, local.text)
                }

                local.start < remote.start && local.end > remote.end -> {
                    TextChange(
                        local.start,
                        local.end + shift,
                        local.text,
                    )
                }

                local.start < remote.start -> {
                    TextChange(local.start, remote.start, local.text)
                }

                local.end > remote.end -> {
                    TextChange(remoteEnd, local.end + shift, local.text)
                }

                else -> {
                    TextChange(remoteEnd, remoteEnd, local.text)
                }
            }
        }
    }
}
