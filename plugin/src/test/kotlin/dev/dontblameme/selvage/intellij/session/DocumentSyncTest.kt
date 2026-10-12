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
import dev.dontblameme.selvage.crdt.Doc
import dev.dontblameme.selvage.crdt.EditorCommit
import dev.dontblameme.selvage.crdt.EditorText
import dev.dontblameme.selvage.crdt.TextDelta
import dev.dontblameme.selvage.crdt.Updates
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
        val doc = Doc(clientID = 1)
        var snapshotCalls = 0

        private fun ensureTexts() {
            for ((path, value) in texts) {
                val text = doc.getText(path)
                if (text.toString() == value) continue
                text.delete(0, text.length)
                text.insert(0, value)
            }
        }

        fun snapshot(): ByteArray {
            snapshotCalls += 1
            ensureTexts()
            return Updates.encodeStateAsUpdate(doc)
        }

        var beforeCommit: (() -> Unit)? = null

        override fun editorText(path: String): EditorText {
            ensureTexts()
            return EditorText.capture(doc, path)
        }

        override fun commitEditorIf(
            path: String,
            editor: EditorText,
            expected: String,
            maxBytes: Int,
        ): EditorCommit {
            beforeCommit?.also { beforeCommit = null }?.invoke()
            ensureTexts()
            if (editor.path != path) return EditorCommit.Refused
            val result = editor.commit(doc, expected, maxBytes)
            if (result is EditorCommit.Applied) {
                val target = result.editor.text
                assertFalse("half a character was published", Editing.hasLoneSurrogate(target))
                texts[path] = target
                published.add(Editing.diff(expected, target))
            }
            return result
        }

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
            ensureTexts()
            val handle = doc.getText(path)
            handle.delete(start, end - start)
            handle.insert(start, text)
            texts[path] = handle.toString()
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
            ensureTexts()
            val handle = doc.getText(path)
            handle.delete(at, delete)
            handle.insert(at, insert)
            texts[path] = handle.toString()
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
        replica.published.clear()
        sync.remoteEdit("a.txt", replica.remote("a.txt", 0, 0, "> "))
        settle()
        assertTrue("the room's edit was published back", replica.published.isNotEmpty())
        assertTrue("the echo changed the room again", replica.text("a.txt") != "> hello\n")
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

    fun testQueuedDisjointLocalInsertsPreserveARemoteInsert() {
        val document = document("abcdefghij")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        val delta = replica.remote("a.txt", 5, 0, "R")
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(1, "!")
            document.insertString(10, "?")
            assertEquals("a!bcdefghi?j", document.text)
        }
        sync.remoteEdit("a.txt", delta)
        sync.syncAll()
        assertEquals("a!bcdeRfghi?j", replica.text("a.txt"))
        assertEquals("a!bcdeRfghi?j", document.text)
    }

    fun testRepeatedTextInsertionKeepsTheActualEventBoundary() {
        val document = document("aaa")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 1, 0, "R"))
        type(document, 0, "a")
        sync.syncAll()
        assertEquals("aaRaa", replica.text("a.txt"))
        assertEquals("aaRaa", document.text)
    }

    fun testRepeatedTextDeletionNamesTheFirstOriginalItem() {
        val document = document("aaa")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 0, 1, ""))
        type(document, 0, "", delete = 1)
        sync.syncAll()
        assertEquals("deleting the same observed item twice is idempotent", "aa", replica.text("a.txt"))
        assertEquals("aa", document.text)
    }

    fun testRepairedSurrogateCommandKeepsIndependentQueuedEvents() {
        val document = document("a😀b")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 4, 0, "R"))
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "!")
            document.deleteString(3, 4)
            assertTrue("the command is temporarily malformed", Editing.hasLoneSurrogate(document.text))
            assertEquals("no intermediate event was published", 0, replica.published.size)
            document.insertString(3, "\uDE01")
            document.insertString(5, "?")
        }
        sync.syncAll()
        assertEquals("!a😁bR?", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testStillMalformedGroupDoesNotDiscardIndependentQueuedEvents() {
        val document = document("a😀b")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 4, 0, "R"))
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "!")
            document.deleteString(3, 4)
            document.insertString(4, "?")
            assertEquals("no malformed intermediate event was published", 0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("!a😀bR?", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
        assertFalse(Editing.hasLoneSurrogate(document.text))
    }

    fun testStillMalformedGroupKeepsInsertAtItsEndBoundary() {
        val document = document("a😀b")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 4, 0, "R"))
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.insertString(2, "!")
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("a😀!bR", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testStillMalformedGroupKeepsDeleteEndingAtItsStartBoundary() {
        val document = document("a😀b")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 4, 0, "R"))
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.deleteString(0, 1)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("😀bR", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testAnEditBetweenTwoMalformedRegionsSurvivesTheFallback() {
        val document = document("a😀b😀c")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.deleteString(4, 5)
            document.insertString(2, "X")
            document.insertString(2, "\uDE00")
            assertEquals("only the repaired region is valid", "a😀Xb\uD83Dc", document.text)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("the edit between the two damaged runs is not the second run's", "a😀Xb😀c", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testAnEditAtAMalformedRegionBoundarySurvivesTheFallback() {
        val document = document("a😀b😀c")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.deleteString(4, 5)
            document.insertString(3, "Y")
            document.insertString(2, "\uDE00")
            document.insertString(7, "Z")
            assertEquals("only the repaired region is valid", "a😀bY\uD83DcZ", document.text)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("an edit at the second run's own boundary is its own", "a😀bY😀cZ", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testADeletionBetweenTwoMalformedRegionsSurvivesTheFallback() {
        val document = document("a😀b😀c")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.deleteString(4, 5)
            document.deleteString(2, 3)
            assertEquals("both lone surrogates are still there", "a\uD83D\uD83Dc", document.text)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("the deletion between the two damaged runs is kept", "a😀😀c", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testAnEditAfterARunMergedIntoItsNeighbourKeepsItsPlace() {
        val document = document("a😀b😀c")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.insertString(2, "\uD83D")
            document.insertString(7, "Z")
            assertEquals("the two lone high surrogates are one run", "a\uD83D\uD83Db😀cZ", document.text)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("the damaged run is refused, the edit past it is not", "a😀b😀cZ", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testCollapsedMalformedRunKeepsBoundaryReplacementAndAppend() {
        val document = document("a😀b😀c")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.deleteString(4, 5)
            document.deleteString(1, 2)
            assertEquals("ab\uD83Dc", document.text)
            document.insertString(1, "X")
            document.replaceString(1, 2, "Q")
            document.insertString(5, "Z")
            assertEquals("aQb\uD83DcZ", document.text)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("a😀Qb😀cZ", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testCollapsedMalformedRunKeepsIndependentReplacement() {
        val document = document("a😀b😀c")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.deleteString(4, 5)
            document.deleteString(1, 2)
            document.insertString(1, "X")
            document.replaceString(1, 2, "Q")
            assertEquals("aQb\uD83Dc", document.text)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("replacing X must not delete the restored emoji", "a😀Qb😀c", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testCollapsedMalformedRunKeepsValidAppend() {
        val document = document("a😀b😀c")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.deleteString(4, 5)
            document.deleteString(1, 2)
            document.insertString(1, "X")
            document.insertString(5, "Z")
            assertEquals("aXb\uD83DcZ", document.text)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("a😀Xb😀cZ", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testCollapsedMalformedRunKeepsPartialRepairAndIndependentEdits() {
        val document = document("a😀b😀c😀d")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.deleteString(4, 5)
            document.deleteString(6, 7)
            document.deleteString(1, 2)
            document.insertString(1, "X")
            document.replaceString(1, 2, "Q")
            document.insertString(4, "\uDE00")
            document.insertString(8, "Z")
            assertEquals("aQb😀c\uD83DdZ", document.text)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("a😀Qb😀c😀dZ", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testCollapsedMalformedRunFromSpanningDeletionKeepsBoundaryEdits() {
        val document = document("a😀b😀c😀d")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.deleteString(4, 5)
            document.deleteString(6, 7)
            document.deleteString(1, 4)
            assertEquals("ac\uD83Dd", document.text)
            document.insertString(1, "X")
            document.replaceString(1, 2, "Q")
            document.insertString(5, "Z")
            assertEquals("aQc\uD83DdZ", document.text)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("a😀b😀Qc😀dZ", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testCollapsedMalformedRunMovesWithDeletionEndingAtItsBoundary() {
        val document = document("a😀b😀c")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(2, 3)
            document.deleteString(4, 5)
            document.deleteString(1, 2)
            document.insertString(0, "P")
            document.replaceString(0, 2, "Long")
            document.deleteString(0, 4)
            assertEquals("b\uD83Dc", document.text)
            document.insertString(0, "X")
            document.replaceString(0, 1, "Q")
            document.insertString(4, "Z")
            assertEquals("Qb\uD83DcZ", document.text)
            assertEquals(0, replica.published.size)
        }
        sync.syncAll()
        assertEquals("😀Qb😀cZ", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testTypingDoesNotSerializeTheRoomEvenWithUnrelatedBoundDocuments() {
        replica.texts["unrelated.txt"] = "u".repeat(900 * 1024)
        val unrelated =
            com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(
                replica.text("unrelated.txt"),
            )
        sync.bind("unrelated.txt", unrelated, seed = false)
        val document = document("aaa")
        sync.bind("a.txt", document, seed = true)
        val snapshotsAtBind = replica.snapshotCalls
        repeat(10) { type(document, 0, "a") }
        assertEquals("ordinary edits must not request or encode room snapshots", snapshotsAtBind, replica.snapshotCalls)
        assertEquals("a".repeat(13), replica.text("a.txt"))
        assertEquals("u".repeat(900 * 1024), unrelated.text)
    }

    private fun sameTextIdentityRaceAtBound(queued: Boolean) {
        val text = "A".repeat(700 * 1024)
        val document = document(text)
        val reports = Recorded()
        val guarded = DocumentSync(project, replica, reports, testRootDisposable, autoSave = { false })
        guarded.bind("a.txt", document, seed = true)
        replica.published.clear()
        if (queued) guarded.remoteEdit("a.txt", emptyList())
        replica.beforeCommit = { replica.remote("a.txt", 0, text.length, text) }
        type(document, 0, "B".repeat(text.length), delete = text.length)
        guarded.syncAll()
        assertEquals("the rejected merge cannot mutate or publish an oversized replica", text, replica.text("a.txt"))
        assertEquals(emptyList<Editing.TextChange>(), replica.published)
        assertTrue("the merged bound is reported", "overBound a.txt" in reports.said)
        assertEquals("the local buffer stays available until it shrinks", "B".repeat(text.length), document.text)
    }

    fun testImmediateSameVisibleTextIdentityRaceValidatesTheActualMergedBound() = sameTextIdentityRaceAtBound(false)

    fun testQueuedSameVisibleTextIdentityRaceValidatesTheActualMergedBound() = sameTextIdentityRaceAtBound(true)

    fun testSeparateQueuedCommandsAndRemoteReplacementsKeepTheirOperations() {
        val document = document("abcdefghij")
        sync.bind("a.txt", document, seed = true)
        val first = replica.remote("a.txt", 1, 1, "B")
        type(document, 4, "!")
        val second = replica.remote("a.txt", 8, 1, "I")
        type(document, 5, "?", delete = 1)
        // The replica has moved, but the EDT has not received either remote event yet.
        sync.syncAll()
        assertEquals("aBcd!?fghIj", document.text)
        assertEquals(document.text, replica.text("a.txt"))
        val published = replica.published.size
        sync.remoteEdit("a.txt", first)
        sync.remoteEdit("a.txt", second)
        sync.syncAll()
        assertEquals("late remote events are not echoed", published, replica.published.size)
        assertEquals("aBcd!?fghIj", document.text)
    }

    fun testQueuedEditsCanReplaceTextInsertedByAnEarlierQueuedEdit() {
        val document = document("abcdefghij")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 5, 0, "R"))
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(1, "!!")
            document.replaceString(2, 3, "?")
            document.insertString(11, "*")
        }
        sync.syncAll()
        assertEquals("a!?bcdeRfghi*j", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testAnExpectedTextRaceRetriesWithoutLosingEitherRemoteEdit() {
        val document = document("abcdefghij")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 5, 0, "R"))
        type(document, 1, "!")
        replica.beforeCommit = { replica.remote("a.txt", 8, 0, "S") }
        sync.syncAll()
        assertEquals("a!bcdeRfgShij", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testACommitReturnsTheActualCrdtStateEvenWhenTheVisibleTextDidNotChange() {
        val document = document("abcdef")
        sync.bind("a.txt", document, seed = true)
        replica.beforeCommit = { replica.remote("a.txt", 1, 1, "b") }
        type(document, 1, "", delete = 1)
        sync.syncAll()
        assertEquals("the unseen replacement b survives the delete of the original b", "abcdef", replica.text("a.txt"))
        assertEquals(
            "the editor uses the atomic commit state, not its predicted result",
            replica.text("a.txt"),
            document.text,
        )
    }

    fun testTypingAfterARefusedRemoteEditUsesTheEditorsOriginalCrdtState() {
        val document = document("abcdefghij")
        sync.bind("a.txt", document, seed = true)
        document.setReadOnly(true)
        try {
            sync.remoteEdit("a.txt", replica.remote("a.txt", 5, 0, "R"))
            sync.syncAll()
            assertEquals("abcdefghij", document.text)
        } finally {
            document.setReadOnly(false)
        }
        type(document, 1, "!")
        sync.syncAll()
        assertEquals("a!bcdeRfghij", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
    }

    fun testMergedRemoteItemsKeepTheirIdentityForAConcurrentPeersDelete() {
        val document = document("abcdefghij")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 5, 0, "R"))
        val peer = Doc(clientID = 2)
        Updates.applyUpdate(peer, replica.snapshot())
        val peerVector = Updates.encodeStateVector(peer)
        peer.getText("a.txt").delete(5, 1)
        type(document, 1, "!")
        type(document, 10, "?")
        sync.syncAll()
        Updates.applyUpdate(replica.doc, Updates.encodeStateAsUpdate(peer, peerVector))
        replica.texts["a.txt"] = replica.doc.getText("a.txt").toString()
        sync.sync("a.txt")
        assertEquals("the peer deletes the original R, not an overwritten copy", "a!bcdefghi?j", document.text)
        assertEquals(document.text, replica.text("a.txt"))
    }

    fun testOverlappingDeletesKeepUnseenRemoteInsertions() {
        val document = document("0123456789")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 2, 4, "ab"))
        type(document, 1, "", delete = 6)
        sync.syncAll()
        assertEquals("0ab789", replica.text("a.txt"))
        assertEquals("0ab789", document.text)
        type(document, 1, "", delete = 2)
        assertEquals("a subsequent delete can remove the now-visible remote insertion", "0789", replica.text("a.txt"))
    }

    fun testQueuedWholeCharacterEditsKeepLfAndRemoteEmojiBoundaries() {
        val document = document("a😀bcdef\nghij")
        sync.bind("a.txt", document, seed = true)
        sync.remoteEdit("a.txt", replica.remote("a.txt", 5, 0, "🦀"))
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(1, 3, "😁")
            document.insertString(12, "?\n")
        }
        sync.syncAll()
        assertEquals("a😁bc🦀def\nghi?\nj", replica.text("a.txt"))
        assertEquals(replica.text("a.txt"), document.text)
        assertFalse(Editing.hasLoneSurrogate(document.text))
    }

    fun testBecomingAViewerDiscardsQueuedLocalOperations() {
        val document = document("abcdefghij")
        sync.bind("a.txt", document, seed = true)
        replica.published.clear()
        sync.remoteEdit("a.txt", replica.remote("a.txt", 5, 0, "R"))
        type(document, 1, "!")
        sync.viewer = true
        sync.syncAll()
        assertEquals("abcdeRfghij", document.text)
        assertEquals(document.text, replica.text("a.txt"))
        assertFalse(document.isWritable)
        assertEquals(emptyList<Editing.TextChange>(), replica.published)
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

        override fun overBound(path: String) {
            said.add("overBound $path")
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

    fun testARefusedRemoteEditIsNeitherAgreedNorPublishedBack() {
        val reports = Recorded()
        val guarded = DocumentSync(project, replica, reports, testRootDisposable, autoSave = { false })
        val document = document("hello\n")
        guarded.bind("a.txt", document, seed = true)
        replica.published.clear()
        document.setReadOnly(true)
        try {
            guarded.remoteEdit("a.txt", replica.remote("a.txt", 0, 0, "> "))
            settle()
            assertEquals("the editor refused the room's edit", "hello\n", document.text)
            repeat(3) {
                guarded.syncAll()
                settle()
            }
            assertEquals("the peer's edit stands in the room", "> hello\n", replica.text("a.txt"))
            assertEquals("nothing was published back", emptyList<Editing.TextChange>(), replica.published)
            assertEquals("the refusal is said once", listOf("applyRefused a.txt"), reports.said)
        } finally {
            document.setReadOnly(false)
        }
        guarded.syncAll()
        assertEquals("once the document is writable the room's edit lands", "> hello\n", document.text)
        assertEquals(emptyList<Editing.TextChange>(), replica.published)
    }

    fun testAReadOnlyDocumentBoundToTheRoomDoesNotOverwriteIt() {
        val guarded = DocumentSync(project, replica, Recorded(), testRootDisposable, autoSave = { false })
        replica.texts["a.txt"] = "room\n"
        val document = document("mine\n")
        document.setReadOnly(true)
        try {
            guarded.bind("a.txt", document, seed = false)
            guarded.syncAll()
            settle()
            assertEquals("room\n", replica.text("a.txt"))
            assertEquals(emptyList<Editing.TextChange>(), replica.published)
        } finally {
            document.setReadOnly(false)
        }
    }

    fun testTypingBeforeARefusedInitialBindingSettlesDoesNotUseAnUnrelatedFork() {
        replica.texts["a.txt"] = "room"
        val document = document("mine")
        document.setReadOnly(true)
        try {
            sync.bind("a.txt", document, seed = false)
        } finally {
            document.setReadOnly(false)
        }
        type(document, 4, "!")
        sync.syncAll()
        assertEquals("room", replica.text("a.txt"))
        assertEquals("room", document.text)
        type(document, 4, "?")
        assertEquals("room?", replica.text("a.txt"))
    }

    fun testADocumentTypedPastTheSizeBoundPublishesNothingUntilItShrinks() {
        val reports = Recorded()
        val bounded = DocumentSync(project, replica, reports, testRootDisposable, autoSave = { false })
        val document = document("hello\n")
        bounded.bind("a.txt", document, seed = true)
        replica.published.clear()
        val big = "x".repeat(dev.dontblameme.selvage.intellij.bridge.Grant.MAX_GRANT_FILE_BYTES)
        type(document, 0, big)
        settle()
        assertEquals("hello\n", replica.text("a.txt"))
        bounded.remoteEdit("a.txt", replica.remote("a.txt", 5, 0, "!"))
        type(document, 0, "y")
        bounded.syncAll()
        settle()
        assertEquals("nothing was published", emptyList<Editing.TextChange>(), replica.published)
        assertEquals("the document is left as it is", "y" + big + "hello\n", document.text)
        assertFalse(reports.said.isEmpty())
        assertTrue(reports.said.toString(), reports.said.all { it == "overBound a.txt" })
        type(document, 0, "small ", delete = big.length + 1)
        settle()
        assertEquals("under the bound again, the change is published", "small hello!\n", replica.text("a.txt"))
        assertEquals("small hello!\n", document.text)
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

    fun testASaveTheRoomAsksForAddsNoLineBreakNobodyTyped() {
        val settings =
            com.intellij.openapi.editor.ex.EditorSettingsExternalizable
                .getInstance()
        val before = settings.isEnsureNewLineAtEOF
        settings.isEnsureNewLineAtEOF = true
        try {
            val saving =
                DocumentSync(project, replica, Recorded(), testRootDisposable, autoSave = { true }, saveSettleMs = 50)
            val (path, document) = onDisk("no-break.txt", "no break")
            saving.bind("no-break.txt", document, seed = true)
            replica.published.clear()
            saving.remoteEdit("no-break.txt", replica.remote("no-break.txt", 0, 0, "still "))
            settle()
            eventually("the room's edit is saved") { !FileDocumentManager.getInstance().isDocumentUnsaved(document) }
            settle()
            assertEquals("the document holds what the room holds", "still no break", document.text)
            assertEquals("and nothing was published", emptyList<Editing.TextChange>(), replica.published)
            eventually("the file holds it too") { Files.readString(path) == "still no break" }
        } finally {
            settings.isEnsureNewLineAtEOF = before
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
