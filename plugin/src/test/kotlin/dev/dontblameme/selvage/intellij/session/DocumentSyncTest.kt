package dev.dontblameme.selvage.intellij.session

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dontblameme.selvage.crdt.TextDelta
import dev.dontblameme.selvage.intellij.bridge.Editing
import java.nio.file.Files

/**
 * A bound IntelliJ `Document` and the replica, both ways: a keystroke reaches the replica, a remote
 * edit reaches the document and is not published back, a keystroke made while a remote edit was in
 * flight is merged rather than put at a stale offset, a file's own line endings survive a remote edit,
 * an undo of a remote edit is published like any change, and half a character is never written.
 */
class DocumentSyncTest : BasePlatformTestCase() {
    /** A replica held as strings; [remote] is a peer's edit, which the real engine reports as deltas. */
    class FakeReplica : Replica {
        val texts = HashMap<String, String>()
        val held = HashSet<String>()
        val published = ArrayList<Editing.TextChange>()

        override fun text(path: String): String = texts[path] ?: ""

        override fun has(path: String): Boolean = path in texts

        override fun replaceIf(
            path: String,
            expected: String,
            start: Int,
            end: Int,
            text: String,
        ): Boolean {
            if (text(path) != expected) return false
            val change = Editing.TextChange(start, end, text)
            assertFalse("half a character was published: $change", Editing.hasLoneSurrogate(text))
            texts[path] = Editing.apply(expected, change)
            published.add(change)
            return true
        }

        override fun hold(path: String) {
            held.add(path)
        }

        override fun release(path: String) {
            held.remove(path)
        }

        /** A peer's edit: the replica moves now, and its delta is what the engine would report. */
        fun remote(
            path: String,
            at: Int,
            delete: Int,
            insert: String,
        ): List<TextDelta> {
            texts[path] = Editing.apply(text(path), Editing.TextChange(at, at + delete, insert))
            return listOfNotNull(
                TextDelta.Retain(at).takeIf { at > 0 },
                TextDelta.Delete(delete).takeIf { delete > 0 },
                TextDelta.Insert(insert).takeIf { insert.isNotEmpty() },
            )
        }
    }

    private lateinit var replica: FakeReplica
    private lateinit var sync: DocumentSync

    private var tolerated: com.intellij.openapi.application.AccessToken? = null

    override fun setUp() {
        tolerated =
            dev.dontblameme.selvage.intellij.TestIde
                .tolerateProductExtensions()
        super.setUp()
        replica = FakeReplica()
        sync =
            DocumentSync(project, replica, object : DocumentSync.Reports {}, testRootDisposable, autoSave = { false })
    }

    private fun document(text: String): Document {
        myFixture.configureByText("a.txt", text)
        return myFixture.editor.document
    }

    private fun type(
        document: Document,
        at: Int,
        text: String,
        delete: Int = 0,
    ) = WriteCommandAction.runWriteCommandAction(project) { document.replaceString(at, at + delete, text) }

    private fun settle() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    fun testAHostSeedsTheRoomAndHoldsTheDocument() {
        val document = document("hello\n")
        sync.bind("a.txt", document, seed = true)
        assertEquals("hello\n", replica.text("a.txt"))
        assertTrue("a.txt" in replica.held)
    }

    fun testTheRoomsTextWinsOverAGuestsBuffer() {
        replica.texts["a.txt"] = "room\n"
        val document = document("")
        sync.bind("a.txt", document, seed = false)
        assertEquals("room\n", document.text)
    }

    fun testAKeystrokeReachesTheReplica() {
        val document = document("hello\n")
        sync.bind("a.txt", document, seed = true)
        type(document, 5, ", world")
        assertEquals("hello, world\n", replica.text("a.txt"))
    }

    fun testARemoteEditReachesTheDocumentAndIsNotEchoed() {
        val document = document("hello\n")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        sync.remoteEdit("a.txt", replica.remote("a.txt", 0, 0, "> "))
        settle()
        assertEquals("> hello\n", document.text)
        assertEquals("> hello\n", replica.text("a.txt"))
        assertEquals("nothing was published back", emptyList<Editing.TextChange>(), replica.published)
    }

    fun testWithoutTheGuardARemoteEditIsEchoedBack() {
        val document = document("hello\n")
        sync.bind("a.txt", document, seed = true)
        sync.echoGuard = false
        sync.remoteEdit("a.txt", replica.remote("a.txt", 0, 0, "> "))
        settle()
        assertEquals("the room's edit came back as a second one", "> > hello\n", replica.text("a.txt"))
    }

    fun testAKeystrokeDuringARemoteEditIsMergedNotMisplaced() {
        val document = document("abc\n")
        sync.bind("a.txt", document, seed = true)
        val delta = replica.remote("a.txt", 0, 0, "XYZ")
        type(document, 3, "!")
        sync.remoteEdit("a.txt", delta)
        settle()
        assertEquals("XYZabc!\n", replica.text("a.txt"))
        assertEquals("XYZabc!\n", document.text)
    }

    fun testAKeystrokeInsideARemoteReplacementKeepsTheRoomsText() {
        val document = document("0123456789\n")
        sync.bind("a.txt", document, seed = true)
        val delta = replica.remote("a.txt", 2, 4, "ab")
        type(document, 4, "!")
        sync.remoteEdit("a.txt", delta)
        settle()
        assertEquals(document.text, replica.text("a.txt"))
        assertTrue(replica.text("a.txt").contains("ab"))
        assertTrue(replica.text("a.txt").contains("!"))
    }

    fun testAnUndoOfARemoteEditIsPublished() {
        val document = document("hello\n")
        sync.bind("a.txt", document, seed = true)
        val editor = FileEditorManager.getInstance(project).selectedEditor as TextEditor
        sync.remoteEdit("a.txt", replica.remote("a.txt", 5, 0, "!"))
        settle()
        assertEquals("hello!\n", document.text)
        val undo = UndoManager.getInstance(project)
        assertTrue("the room's edit is on the undo stack", undo.isUndoAvailable(editor))
        CommandProcessor.getInstance().executeCommand(project, { undo.undo(editor) }, "undo", null)
        settle()
        assertEquals("hello\n", document.text)
        assertEquals("the undo reached the room", "hello\n", replica.text("a.txt"))
    }

    fun testHalfACharacterIsNeverPublished() {
        val document = document("a😀b\n")
        sync.bind("a.txt", document, seed = true)
        type(document, 1, "😁", delete = 2)
        settle()
        assertEquals(
            "the editor's change split the pair; the published one is widened",
            listOf(Editing.TextChange(1, 3, "😁")),
            replica.published.drop(1),
        )
        assertEquals("a😁b\n", replica.text("a.txt"))
        val split = 2
        type(document, split, "x")
        settle()
        assertFalse(Editing.hasLoneSurrogate(replica.text("a.txt")))
        assertEquals(document.text, replica.text("a.txt"))
    }

    fun testACrlfFileKeepsItsLineEndingsWhenTheRoomEditsIt() {
        val dir = Files.createTempDirectory("crlf")
        val path = dir.resolve("crlf.txt")
        Files.writeString(path, "one\r\ntwo\r\n")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        assertEquals("the Document holds LF", "one\ntwo\n", document.text)
        sync.bind("crlf.txt", document, seed = true)
        assertEquals("the replica holds LF", "one\ntwo\n", replica.text("crlf.txt"))
        sync.remoteEdit("crlf.txt", replica.remote("crlf.txt", 4, 0, "middle\n"))
        settle()
        assertEquals("one\nmiddle\ntwo\n", document.text)
        assertTrue(
            "the room's edit left the document unsaved",
            FileDocumentManager.getInstance().isDocumentUnsaved(document),
        )
        WriteCommandAction.runWriteCommandAction(project) { FileDocumentManager.getInstance().saveDocument(document) }
        assertFalse("saved", FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertEquals("one\r\nmiddle\r\ntwo\r\n", String(file.contentsToByteArray()))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (Files.readString(path) != "one\r\nmiddle\r\ntwo\r\n" && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport
                .parkNanos(10_000_000L)
        }
        assertEquals("the file on disk keeps CRLF", "one\r\nmiddle\r\ntwo\r\n", Files.readString(path))
    }

    /** What a sync reported, in order. */
    private class Recorded : DocumentSync.Reports {
        val said = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun applyRefused(path: String) {
            said.add("applyRefused $path")
        }

        override fun saveFailed(
            path: String,
            why: String?,
        ) {
            said.add("saveFailed $path ${why ?: "-"}")
        }
    }

    private fun onDisk(
        name: String,
        text: String,
    ): Pair<java.nio.file.Path, Document> {
        val dir = Files.createTempDirectory("saves")
        val path = dir.resolve(name)
        Files.writeString(path, text)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        return path to FileDocumentManager.getInstance().getDocument(file)!!
    }

    private fun eventually(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("not within 5 s: $what")
            settle()
            java.util.concurrent.locks.LockSupport
                .parkNanos(10_000_000L)
        }
    }

    fun testAViewersDocumentIsReadOnlyAndTheRoomStillWritesIt() {
        val reports = Recorded()
        val viewing = DocumentSync(project, replica, reports, testRootDisposable, autoSave = { false })
        replica.texts["a.txt"] = "hello\n"
        val document = document("")
        viewing.viewer = true
        viewing.bind("a.txt", document, seed = false)
        assertEquals("hello\n", document.text)
        assertFalse("a viewer's document refuses a keystroke", document.isWritable)
        viewing.remoteEdit("a.txt", replica.remote("a.txt", 5, 0, "!"))
        settle()
        assertEquals("the room's edit is written all the same", "hello!\n", document.text)
        assertFalse("and the document stays read-only", document.isWritable)
        assertEquals(emptyList<String>(), reports.said)
        viewing.unbind("a.txt")
        assertTrue("the document is given back writable", document.isWritable)
    }

    fun testARoleThatBecomesViewerLocksTheBoundDocuments() {
        val viewing = DocumentSync(project, replica, Recorded(), testRootDisposable, autoSave = { false })
        val document = document("hello\n")
        viewing.bind("a.txt", document, seed = true)
        assertTrue(document.isWritable)
        viewing.viewer = true
        assertFalse(document.isWritable)
        viewing.viewer = false
        assertTrue(document.isWritable)
    }

    fun testEachDocumentIsSavedOnItsOwnClock() {
        val saving =
            DocumentSync(project, replica, Recorded(), testRootDisposable, autoSave = { true }, saveSettleMs = 50)
        val (_, first) = onDisk("first.txt", "one\n")
        val (_, second) = onDisk("second.txt", "two\n")
        saving.bind("first.txt", first, seed = true)
        saving.bind("second.txt", second, seed = true)
        saving.remoteEdit("first.txt", replica.remote("first.txt", 0, 0, "1 "))
        settle()
        saving.remoteEdit("second.txt", replica.remote("second.txt", 0, 0, "2 "))
        settle()
        val manager = FileDocumentManager.getInstance()
        eventually("both documents are written") {
            !manager.isDocumentUnsaved(first) &&
                !manager.isDocumentUnsaved(second)
        }
    }

    fun testASaveToAFileThatTurnedReadOnlyIsSaidWithItsCause() {
        val reports = Recorded()
        val saving =
            DocumentSync(project, replica, reports, testRootDisposable, autoSave = { true }, saveSettleMs = 500)
        val (_, document) = onDisk("locked.txt", "text\n")
        saving.bind("locked.txt", document, seed = true)
        saving.remoteEdit("locked.txt", replica.remote("locked.txt", 0, 0, "more "))
        settle()
        assertEquals("more text\n", document.text)
        val file = FileDocumentManager.getInstance().getFile(document)!!
        WriteCommandAction.runWriteCommandAction(project) { file.isWritable = false }
        try {
            eventually("the failed save is said: ${reports.said}") { reports.said.isNotEmpty() }
            assertEquals(listOf("saveFailed locked.txt the file is read-only"), reports.said)
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { file.isWritable = true }
        }
    }

    fun testASaveTheIdeHoldsBackIsSaid() {
        val reports = Recorded()
        val saving = DocumentSync(project, replica, reports, testRootDisposable, autoSave = { true }, saveSettleMs = 50)
        val (_, document) = onDisk("vetoed.txt", "text\n")
        com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer.EP_NAME.point.registerExtension(
            object : com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer() {
                override fun maySaveDocument(
                    document: Document,
                    isSaveExplicit: Boolean,
                ): Boolean = false
            },
            testRootDisposable,
        )
        saving.bind("vetoed.txt", document, seed = true)
        saving.remoteEdit("vetoed.txt", replica.remote("vetoed.txt", 0, 0, "more "))
        settle()
        eventually("the held-back save is said: ${reports.said}") { reports.said.isNotEmpty() }
        assertEquals(listOf("saveFailed vetoed.txt -"), reports.said)
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            tolerated?.close()
        }
    }
}
