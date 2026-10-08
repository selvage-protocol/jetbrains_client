package dev.dontblameme.selvage.intellij.session

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.dontblameme.selvage.engine.HostContent
import dev.dontblameme.selvage.engine.SelvageSession
import dev.dontblameme.selvage.engine.SessionOptions
import dev.dontblameme.selvage.intellij.TestIde
import dev.dontblameme.selvage.intellij.bridge.Mirror
import dev.dontblameme.selvage.intellij.bridge.People
import dev.dontblameme.selvage.intellij.bridge.Say
import dev.dontblameme.selvage.intellij.bridge.Words
import dev.dontblameme.selvage.intellij.settings.SelvageSettings
import dev.dontblameme.selvage.intellij.ui.Notifier
import dev.dontblameme.selvage.intellij.ui.Prompts
import dev.dontblameme.selvage.peer.Keepalive
import dev.dontblameme.selvage.peer.Selection
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.wire.JdkTransport
import dev.dontblameme.selvage.wire.SocketListener
import dev.dontblameme.selvage.wire.Transport
import dev.dontblameme.selvage.wire.WireSocket
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * The adapter in a test IDE against a real `selvaged` (SELVAGE_SELVAGED): the IDE hosts from its
 * project while a second participant joins with the engine directly, and the IDE joins a room the
 * engine hosts. Open, edits both ways, carets both ways, a rename, follow and its endings, the invite
 * control, leave, and the host going away. Every wait polls a predicate against a deadline.
 */
class LiveSessionTest : HeavyPlatformTestCase() {
    private val renew = 300L
    private val expire = 1_500L

    /**
     * What a wait whose predicate a peer's frame has to satisfy is given: the peer's engine →
     * `selvaged` → this window's engine, and back. That is milliseconds on an idle machine, and
     * the room's own clock is what a contended runner stretches: §8.2 renews awareness every 15 s
     * in a real session and §13.1's step 4 re-announces a key on the same window, so a frame only
     * a renewal republishes lands inside this wait and outside a shorter one. A predicate local to
     * this window keeps [eventually]'s default.
     */
    private val roundTripMs = 30_000L
    private var tolerated: com.intellij.openapi.application.AccessToken? = null
    private lateinit var server: Process
    private lateinit var base: String
    private lateinit var scratch: Path
    private lateinit var prompts: ScriptedPrompts
    private lateinit var said: Said
    private val engines = ArrayList<SelvageSession>()

    private fun options(
        name: String,
        transport: Transport = JdkTransport(),
    ) = SessionOptions(
        name,
        transport = transport,
        handshakeTimeout = Duration.ofSeconds(5),
        metaTimeout = Duration.ofSeconds(2),
        requestTimeout = Duration.ofSeconds(5),
        keepalive = Keepalive(30_000, renew, expire),
        client = SelvageService.CLIENT,
    )

    override fun setUp() {
        tolerated =
            dev.dontblameme.selvage.intellij.TestIde
                .tolerateProductExtensions()
        super.setUp()
        val binary =
            System.getProperty("selvage.selvaged")?.let(::File)
                ?: throw AssertionError("SELVAGE_SELVAGED is not set: the live test spawns a real selvaged")
        assertTrue("SELVAGE_SELVAGED is $binary, which is not an executable file", binary.canExecute())
        scratch = Files.createTempDirectory("selvage-live")
        server =
            ProcessBuilder(binary.path, "--listen", "127.0.0.1:0", "--room-grace-ms", "5000")
                .redirectError(scratch.resolve("selvaged.err").toFile())
                .start()
        val lines = LinkedBlockingQueue<String>()
        Thread({ server.inputStream.bufferedReader().forEachLine { lines.add(it) } }, "selvaged-out")
            .apply { isDaemon = true }
            .start()
        val first = lines.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("selvaged said nothing within 10 s")
        base = Regex("ws://\\S+/session").find(first)?.value?.removeSuffix("/session")
            ?: throw AssertionError("selvaged's first line names no URL: $first")
        prompts = ScriptedPrompts()
        Prompts.current = prompts
        said = Said()
        SelvageSettings.get().loadState(SelvageSettings.Options())
        SelvageSettings.get().update {
            serverUrl = base
            displayName = "Ada"
        }
        val service = SelvageService.get()
        service.sessionOptions = { options(it) }
        service.storage = scratch.resolve("storage")
        service.openMirror = { project }
    }

    override fun tearDown() {
        try {
            SelvageService.get().current?.end()
            val editors = FileEditorManager.getInstance(project)
            editors.openFiles.forEach { editors.closeFile(it) }
            engines.forEach { it.leave() }
            said.close()
            Prompts.current = Prompts.Ide
            server.destroy()
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            // A saved document is written by the VFS on a thread of its own, and the scratch those
            // writes land in is deleted next: let them finish first, or one lands after the delete
            // and is logged against whichever test is running when it reports.
            val vfs =
                LocalFileSystem.getInstance() as? com.intellij.openapi.vfs.newvfs.AsyncableFileSystem
                    ?: throw AssertionError("the local file system is not async")
            try {
                vfs.fsync()
            } catch (e: java.io.IOException) {
                // A write the test already reported; the scratch still goes. Said rather than
                // swallowed, because a flush that failed leaves the late write this drain exists
                // to stop, and the next test is what would report it.
                System.err.println("selvage-live: the file system's flush before the scratch was deleted failed: $e")
            }
            scratch.toFile().deleteRecursively()
        } finally {
            try {
                super.tearDown()
            } finally {
                tolerated?.close()
            }
        }
    }

    private fun eventually(
        what: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            if (System.nanoTime() > deadline) fail("not within $timeoutMs ms: $what; said: ${said.sentences()}")
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
    }

    private fun openInEditor(path: Path): Editor {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        return FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)!!
    }

    /** A host IDE whose folder holds one file, with one engine guest seated and attributed in the room. */
    private fun hostWithOneFile(): Pair<RoomSession, SelvageSession> {
        val root = Files.createDirectories(Path.of(project.basePath!!))
        Files.writeString(root.resolve("README.md"), "hello\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
        val service = SelvageService.get()
        service.host(project)
        val session = service.current ?: throw AssertionError("no session after hosting; said: ${said.sentences()}")
        val guest = SelvageSession.join(session.invite()!!, options("Bob")).also { engines.add(it) }
        eventually("the guest is committed", timeoutMs = roundTripMs) { guest.ownRole() == Role.GUEST }
        eventually("the guest has the listing", timeoutMs = roundTripMs) { guest.listing() == listOf("README.md") }
        eventually("the host has attributed the guest", timeoutMs = roundTripMs) { session.participants().size == 1 }
        return session to guest
    }

    /**
     * The mirror of [hostWithOneFile]: a peer engine hosts a room with one file, and this IDE joins
     * it as a guest. [transport] is what the window's session dials with, when a case has to hold the
     * room's frames back; [read] is what the host serves for the file.
     */
    private fun guestWithOneFile(
        transport: Transport? = null,
        read: (String) -> String? = { "hello\n" },
    ): Pair<SelvageSession, RoomSession> {
        val service = SelvageService.get()
        if (transport != null) service.sessionOptions = { options(it, transport) }
        val host =
            SelvageSession
                .host(base, HostContent({ listOf("README.md") }, read), options("Grace"))
                .also { engines.add(it) }
        service.join(project, host.invite!!)
        val session = service.current ?: throw AssertionError("no session after joining; said: ${said.sentences()}")
        eventually("the guest is committed", timeoutMs = roundTripMs) { session.engine.ownRole() == Role.GUEST }
        eventually("the room's listing is in", timeoutMs = roundTripMs) { session.listed() == listOf("README.md") }
        eventually("the mirror holds the file") { Files.isRegularFile(session.mirror!!.root.resolve("README.md")) }
        return host to session
    }

    /**
     * The reported shape, measured: the peer holds and selects the file while this window has no
     * document bound for it, and the window then opens the file. Binding raises no event — the path is
     * already in the open set because the peer holds it, and its text was served when the peer took
     * that hold — so the bind itself has to draw the selection the peer has already made.
     */
    fun testASelectionThatArrivesBeforeTheDocumentBindsIsDrawnWhenItBinds() {
        val (session, guest) = hostWithOneFile()
        guest.open("README.md")
        // The host serves a path a guest holds even though no editor of this window holds it.
        eventually("the host's replica holds the text the guest's hold was served", timeoutMs = roundTripMs) {
            session.engine.text("README.md") == "hello\n"
        }
        // The host's replica holds the text before the guest's does, by exactly the hop that
        // carries the content to it, and a selection is anchored to positions in the sender's own
        // replica: set in a replica that does not hold the document yet, it is published as the
        // path with no selection at all (§8.1), which §8.2's renewal republishes as it stands. The
        // wait below could then never pass, however long it were given, so the cursor is set in a
        // replica that holds the text.
        eventually("the guest's replica holds the text", timeoutMs = roundTripMs) {
            guest.text("README.md") == "hello\n"
        }
        assertFalse("the file is not bound in this window yet", session.sync.isBound("README.md"))

        guest.setCursor("README.md", Selection(1, 4))
        eventually("the guest's selection resolves in the host's engine", timeoutMs = roundTripMs) {
            session.engine.cursors().any {
                it.clientId == guest.awarenessClientId() && it.selection == Selection(1, 4)
            }
        }
        assertTrue(
            "nothing is drawn while the document is unbound",
            session.presence.lastDrawn["README.md"].isNullOrEmpty(),
        )

        openInEditor(Path.of(project.basePath!!).resolve("README.md"))
        eventually("the open document is bound") { session.sync.isBound("README.md") }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(
            "the selection is drawn the moment its document binds, before the peer moves again",
            listOf(1 to 4),
            session.presence
                .lastDrawn["README.md"]
                .orEmpty()
                .map { it.anchor to it.head },
        )
    }

    /** The healthy order: the document is bound before the peer's selection arrives, so the presence event draws it. */
    fun testASelectionThatArrivesAfterTheDocumentBindsIsDrawnOnTheFirstPresence() {
        val (session, guest) = hostWithOneFile()
        openInEditor(Path.of(project.basePath!!).resolve("README.md"))
        eventually("the open document is bound") { session.sync.isBound("README.md") }
        eventually("the host's replica holds the text of its own file") {
            session.engine.text("README.md") == "hello\n"
        }

        guest.open("README.md")
        eventually("the guest holds the text", timeoutMs = roundTripMs) { guest.text("README.md") == "hello\n" }
        guest.setCursor("README.md", Selection(1, 4))
        eventually("the first presence draws the selection", timeoutMs = roundTripMs) {
            session.presence.lastDrawn["README.md"]?.any { it.anchor == 1 && it.head == 4 } == true
        }
    }

    /** The literal report shape: the file is already open and bound, and the peer opens it and selects. */
    fun testASelectionInAnAlreadyBoundDocumentIsDrawnOnTheFirstPresence() {
        val (session, guest) = hostWithOneFile()
        openInEditor(Path.of(project.basePath!!).resolve("README.md"))
        eventually("the open document is bound") { session.sync.isBound("README.md") }

        guest.open("README.md")
        eventually("the guest holds the text", timeoutMs = roundTripMs) { guest.text("README.md") == "hello\n" }
        guest.setCursor("README.md", Selection(1, 4))
        eventually("the first presence draws the selection", timeoutMs = roundTripMs) {
            session.presence.lastDrawn["README.md"]?.any { it.anchor == 1 && it.head == 4 } == true
        }
        // A linewise range over one line is the shape the report pressed `shift+v` for.
        guest.setCursor("README.md", Selection(0, 5))
        eventually("a later move is still drawn", timeoutMs = roundTripMs) {
            session.presence.lastDrawn["README.md"]?.any { it.anchor == 0 && it.head == 5 } == true
        }
    }

    /**
     * The other side of the same gap, in the role only a guest reaches: the room's text for a document
     * this window shows arrives as a later message, and a caret published before it is one the sender
     * cannot anchor. §8.1 carries the path with no selection, and §8.2's renewal republishes those
     * same anchors, so the text's arrival is the one moment left to publish it again. The peer host
     * must hold the caret as soon as the text lands, with no further move in this window.
     */
    fun testACaretInARoomDocumentReachesTheHostWhenTheRoomsTextArrives() {
        val held = HeldFrames()
        val (host, session) = guestWithOneFile(held)
        val guest = session.engine
        // From here the window's replica does not get the room's frames, so the text for a document it
        // opens cannot arrive until this is let go.
        held.hold()

        // A user opens the room's document. Its mirror copy is empty, so the window binds it at once
        // and holds the path; the room's text is a later frame.
        val editor = session.openRoomPath("README.md") ?: throw AssertionError("the room's document did not open")
        eventually("the document is bound") { session.sync.isBound("README.md") }
        assertFalse("the replica holds no text for it yet", guest.has("README.md"))

        // The caret is where the user leaves it, published while the replica cannot anchor it.
        editor.caretModel.moveToOffset(0)
        eventually("the host holds the path with no selection", timeoutMs = roundTripMs) {
            host.cursors().any {
                it.clientId == guest.awarenessClientId() && it.path == "README.md" && it.selection == null
            }
        }

        // The room's text arrives.
        held.release()

        eventually("the host holds the caret when the text arrives", timeoutMs = roundTripMs) {
            host.cursors().any {
                it.clientId == guest.awarenessClientId() && it.path == "README.md" && it.selection == Selection(0, 0)
            }
        }
    }

    fun testTheIdeHostsAndAnEngineGuestEditsWithIt() {
        val root = Files.createDirectories(Path.of(project.basePath!!))
        Files.writeString(root.resolve("README.md"), "hello\n")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/main.kt"), "fun main() {}\n")
        Files.writeString(root.resolve(".env"), "TOKEN=1\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)

        val service = SelvageService.get()
        service.host(project)
        val session = service.current ?: throw AssertionError("no session after hosting; said: ${said.sentences()}")
        assertTrue(said.sentences().toString(), said.sentences().any { it.startsWith("Selvage: the room is open") })
        assertEquals(Words.hostingIdentity(root.fileName.toString()), session.statusText())
        val invite = session.invite() ?: throw AssertionError("the host holds no invite")
        assertTrue(
            invite,
            invite.startsWith("http://127.0.0.1:") && invite.contains("/?room=") && invite.contains("#k="),
        )

        val guest = SelvageSession.join(invite, options("Bob")).also { engines.add(it) }
        eventually("the guest is committed") { guest.ownRole() == Role.GUEST }
        eventually("the guest has the listing") { guest.listing() == listOf("README.md", "src/main.kt") }

        guest.open("README.md")
        eventually("the host serves README.md from its folder") { guest.text("README.md") == "hello\n" }

        val editor = openInEditor(root.resolve("README.md"))
        eventually("the open document is bound") { session.sync.isBound("README.md") }
        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(5, ", world") }
        eventually("the host's keystroke reaches the guest") { guest.text("README.md") == "hello, world\n" }

        guest.insert("README.md", 0, "> ")
        eventually("the guest's edit reaches the host's editor") { editor.document.text == "> hello, world\n" }
        eventually("and is not echoed back") { guest.text("README.md") == "> hello, world\n" }

        guest.setCursor("README.md", Selection(2, 7))
        eventually("the guest's selection is drawn in the host's editor") {
            session.presence.lastDrawn["README.md"]?.any { it.anchor == 2 && it.head == 7 && it.label == "Bob" } == true
        }
        editor.caretModel.moveToOffset(4)
        session.flushPresence()
        eventually("the host's caret reaches the guest") {
            guest.cursors().any { it.path == "README.md" && it.selection == Selection(4, 4) }
        }

        guest.rename("Robert").get(5, TimeUnit.SECONDS)
        eventually("the rename reaches the people list") {
            session.rows().any {
                it.label == "Robert" &&
                    it.path == "README.md"
            }
        }
        val guestId = guest.seat!!
        assertEquals(listOf(true, false), session.rows().map { it.self })

        session.follow(guestId)
        assertEquals(Say.following("Robert"), session.followLabel())
        guest.setCursor("README.md", Selection(9, 9))
        eventually("the follow moves the host's caret with the guest") { editor.caretModel.offset == 9 }
        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "#") }
        eventually("typing ends the follow") { said.sentences().contains(Words.followEndedByTyping("Robert")) }
        assertNull(session.followLabel())
        eventually("the keystroke still reaches the guest") { guest.text("README.md") == "#> hello, world\n" }

        session.follow(guestId)
        eventually("the follow lands") { editor.caretModel.offset == 9 || editor.caretModel.offset == 10 }
        editor.caretModel.moveToOffset(1)
        eventually("moving ends the follow") { said.sentences().contains(Words.followEndedByMoving("Robert")) }

        service.copyInvite(project)
        if (said
                .last()
                .sentence
                .startsWith("Selvage: the invite link could not be copied")
                .not()
        ) {
            assertEquals(Words.COPIED_LABEL, session.inviteLabel())
            assertEquals(
                Words.COPY_INVITE_LABEL,
                session.inviteLabel(System.currentTimeMillis() + Words.COPIED_STAND_MS),
            )
        }

        session.follow(guestId)
        guest.leave()
        eventually(
            "the guest leaving ends the follow",
        ) { said.sentences().contains(Words.followEndedByLeaving("Robert")) }
        eventually("the guest is gone from the list") { session.rows().size == 1 }

        prompts.confirms.add(true)
        service.leave(project)
        assertEquals(Say.leftSession(), said.last().sentence)
        assertTrue(prompts.asked.contains("confirm: ${Say.HOST_LEAVE_ASKING}"))
        assertNull(service.current)
        assertFalse("the .env file was never offered", guest.listing().contains(".env"))
    }

    fun testTheIdeJoinsAndTheHostGoesAway() {
        val files = mapOf("README.md" to "hello\n", "src/main.kt" to "fun main() {}\n")
        val host =
            SelvageSession
                .host(
                    base,
                    HostContent({
                        files.keys.toList()
                    }, { files[it] }),
                    options("Grace"),
                ).also { engines.add(it) }
        val service = SelvageService.get()
        service.join(project, host.invite!!)
        val session = service.current ?: throw AssertionError("no session after joining; said: ${said.sentences()}")
        assertFalse(session.isHost)
        val mirror = session.mirror!!
        eventually("the joined notice") { said.sentences().any { it.startsWith("Selvage: joined the room") } }
        eventually("the mirror holds the listing") { Files.isRegularFile(mirror.root.resolve("src/main.kt")) }
        eventually("the host is named") { session.statusText() == Words.guestIdentity("Grace") }

        prompts.choices.add(0)
        service.openDocument(project)
        eventually("the room's text fills the opened mirror file") {
            FileEditorManager
                .getInstance(project)
                .selectedTextEditor
                ?.document
                ?.text == "hello\n"
        }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "# ") }
        eventually("the guest's keystroke reaches the host") { host.text("README.md") == "# hello\n" }

        host.leave()
        eventually("the host's absence is said") {
            said.sentences().any { it.startsWith("Grace left the session. The room disconnects in ") }
        }
        assertTrue(session.statusText(), session.statusText().startsWith("Grace left the session · Disconnecting in "))
        eventually("the window closes the session", timeoutMs = expire + 8_000) { service.current == null }
        val sentence = Words.roomGoneSentence(Words.endingReason(dev.dontblameme.selvage.peer.Ending.HOST_AWAY))
        assertEquals(Say.copyKept(sentence, mirror.root.toString()), said.last().sentence)
        assertTrue("the copy is kept", Files.isRegularFile(mirror.root.resolve("README.md")))
    }

    fun testAHostFollowingAGuestReadsNoFileAndSaysARefusalOnce() {
        val root = Files.createDirectories(Path.of(project.basePath!!))
        val readme = Files.writeString(root.resolve("README.md"), "hello, world\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
        val service = SelvageService.get()
        service.host(project)
        val session = service.current ?: throw AssertionError("no session; said: ${said.sentences()}")
        val bob = SelvageSession.join(session.invite()!!, options("Bob")).also { engines.add(it) }
        val carol = SelvageSession.join(session.invite()!!, options("Carol")).also { engines.add(it) }
        eventually("both guests are in") { session.participants().size == 2 }
        bob.open("README.md")
        carol.open("README.md")
        eventually("both have the text") {
            bob.text("README.md") == "hello, world\n" && carol.text("README.md") == "hello, world\n"
        }
        val editor = openInEditor(readme)
        eventually("the open document is bound") { session.sync.isBound("README.md") }
        bob.setCursor("README.md", Selection(1, 1))
        session.follow(bob.seat!!)
        eventually("the follow lands") { editor.caretModel.offset == 1 }

        // The file can no longer be read: a follow that read it to decide would refuse to land.
        Files.setPosixFilePermissions(readme, emptySet())
        try {
            bob.setCursor("README.md", Selection(5, 5))
            eventually("the follow moves with the guest") { editor.caretModel.offset == 5 }
        } finally {
            Files.setPosixFilePermissions(
                readme,
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-r--r--"),
            )
        }
        assertFalse(said.sentences().toString(), said.sentences().any { it.startsWith("Selvage: could not open") })

        val badge =
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<List<String>> {
                com.intellij.openapi.application.ReadAction.compute<List<String>, RuntimeException> {
                    session.peopleIn(FileDocumentManager.getInstance().getFile(editor.document)!!).map { it.label }
                }
            }
        eventually("the badge is read off the event thread") { badge.isDone }
        assertEquals("the badge names who is in the file", listOf("Bob"), badge.get())

        val refusal = Say.couldNotOpen("missing.txt", "the path is not one this window shares")
        bob.setCursor("missing.txt")
        eventually("the refusal is said") { said.sentences().contains(refusal) }
        for (offset in 1..3) {
            carol.setCursor("README.md", Selection(offset, offset))
            eventually("the other guest's caret reaches the host") {
                session.engine.cursors().any {
                    it.clientId == carol.awarenessClientId() && it.selection == Selection(offset, offset)
                }
            }
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("said once while the follow stays there", 1, said.sentences().count { it == refusal })
        assertEquals(Say.following("Bob"), session.followLabel())
    }

    private fun hostEngine(
        name: String,
        listing: AtomicReference<List<String>>,
        texts: Map<String, String>,
    ): SelvageSession =
        SelvageSession.host(base, HostContent({ listing.get() }, { texts[it] }), options(name)).also { engines.add(it) }

    fun testAListingThatMovesWhileTheSessionIsSetUpReachesTheWindow() {
        val listing = AtomicReference(listOf("README.md"))
        val host = hostEngine("Grace", listing, mapOf("README.md" to "hello\n", "b.txt" to ""))
        val guest = SelvageSession.join(host.invite!!, options("Bob")).also { engines.add(it) }
        eventually("the guest has the listing") { guest.listing() == listOf("README.md") }
        val moved = listOf("README.md", "b.txt")
        val heard = CopyOnWriteArrayList<List<String>>()
        guest.addListener { if (it is dev.dontblameme.selvage.engine.SessionEvent.Listing) heard.add(it.paths) }
        // The listing moves, and the engine has said so, after the session read the room and before
        // the constructor is done.
        RoomSession.stateRead = {
            listing.set(moved)
            host.listingChanged()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (moved !in heard) {
                if (System.nanoTime() > deadline) throw AssertionError("the guest never heard the moved listing")
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
            }
        }
        val mirror = Mirror.mint(scratch.resolve("guest"), "room")
        val session =
            try {
                RoomSession(guest, project, null, mirror, joinedWith = host.invite)
            } finally {
                RoomSession.stateRead = null
            }
        try {
            eventually("the moved listing reaches the window") { session.listed() == moved }
            eventually("and the mirror") { Files.isRegularFile(mirror.root.resolve("b.txt")) }
        } finally {
            session.end()
        }
    }

    fun testAViewersDocumentsAreReadOnlyAndTheRoomStillReachesThem() {
        val host = hostEngine("Grace", AtomicReference(listOf("README.md")), mapOf("README.md" to "hello\n"))
        val viewer = SelvageSession.join(host.invite!!, options("Vic"), Role.VIEWER)
        eventually("the viewer is committed") { viewer.ownRole() == Role.VIEWER }
        eventually("the viewer has the listing") { viewer.listing() == listOf("README.md") }
        val mirror = Mirror.mint(scratch.resolve("viewer"), "room").also { it.materialise(viewer.listing()) }
        val session = RoomSession(viewer, project, null, mirror, joinedWith = host.invite)
        try {
            eventually("the role is said once, at warning") {
                said.all.any { it.level == Notifier.Level.WARNING && it.sentence == Say.viewerReadOnly() }
            }
            val editor = session.openRoomPath("README.md")!!
            eventually("the room's text fills the viewer's document") { editor.document.text == "hello\n" }
            assertFalse("a viewer's document refuses a keystroke", editor.document.isWritable)
            host.insert("README.md", 5, "!")
            eventually("the host's edit reaches the read-only document") { editor.document.text == "hello!\n" }
            assertFalse("and it stays read-only", editor.document.isWritable)
            assertEquals(1, said.sentences().count { it == Say.viewerReadOnly() })
            assertFalse(said.sentences().toString(), said.sentences().any { it.contains("would not apply") })
            assertEquals("Viewer in this session", session.statusTooltip().lines().first())
        } finally {
            session.end()
        }
    }

    fun testAGuestHearsWhatTheMirrorAndTheFetchCouldNotDo() {
        val listing = AtomicReference(listOf("README.md", "gone.txt", "x", "x/y.txt"))
        val host = hostEngine("Grace", listing, mapOf("README.md" to "hello\n", "gone.txt" to "", "x" to ""))
        val progress = CopyOnWriteArrayList<String>()
        val recordProgress: (String) -> Unit = { progress.add(it) }
        Notifier.progressListeners.add(recordProgress)
        try {
            val service = SelvageService.get()
            service.join(project, host.invite!!)
            val session = service.current ?: throw AssertionError("no session; said: ${said.sentences()}")
            val mirror = session.mirror!!
            eventually("the one file the mirror could not write is said") {
                said.sentences().contains(Say.mirrorWriteFailedOne("x/y.txt"))
            }

            service.fetch(project, "README.md")
            eventually("the fetch lands") { said.sentences().contains(Say.fetched()) }
            assertEquals("the wait was shown while it lasted", listOf(Say.fetching("README.md")), progress)
            eventually(
                "the fetched text is on disk",
            ) { Files.readString(mirror.root.resolve("README.md")) == "hello\n" }

            listing.set(listOf("README.md", "x", "x/y.txt", "z", "z/w.txt"))
            host.listingChanged()
            eventually("the guest's listing loses gone.txt") { !session.listed().contains("gone.txt") }
            eventually("both refused files are counted") {
                said.sentences().contains(Say.mirrorWriteFailed("2", "x/y.txt"))
            }
            service.fetch(project, "gone.txt")
            assertEquals(Notifier.Level.ERROR, said.last().level)
            assertEquals(Say.couldNotFetch("gone.txt", Say.leftListingNotice("gone.txt")), said.last().sentence)

            val extra = mirror.root.resolve("extra.txt")
            Files.writeString(extra, "mine\n")
            val editor = openInEditor(extra)
            eventually("opening a file the room does not list is said") {
                said.sentences().contains(Say.unlistedOpened("extra.txt"))
            }
            repeat(2) { round ->
                WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "$round") }
                FileDocumentManager.getInstance().saveDocument(editor.document)
            }
            assertEquals(
                "a save the room does not hold is said once",
                1,
                said.sentences().count { it == Say.unlistedSaved("extra.txt") },
            )
            assertEquals(Notifier.Level.WARNING, said.all.first { it.sentence == Say.unlistedSaved("extra.txt") }.level)
        } finally {
            Notifier.progressListeners.remove(recordProgress)
        }
    }

    fun testAnUnsavedCopyWhosePathLeftTheListingStaysOnDisk() {
        val listing = AtomicReference(listOf("a.txt", "b.txt"))
        val host = hostEngine("Grace", listing, mapOf("a.txt" to "alpha\n", "b.txt" to "beta\n"))
        val service = SelvageService.get()
        service.join(project, host.invite!!)
        val session = service.current ?: throw AssertionError("no session; said: ${said.sentences()}")
        val mirror = session.mirror!!
        eventually("the mirror holds a.txt") { Files.isRegularFile(mirror.root.resolve("a.txt")) }
        val editor = session.openRoomPath("a.txt")!!
        eventually("the room's text fills a.txt") { editor.document.text == "alpha\n" }
        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "mine ") }
        eventually("the keystroke reaches the host") { host.text("a.txt") == "mine alpha\n" }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(editor.document))

        listing.set(listOf("b.txt"))
        host.listingChanged()
        eventually("the kept copy is said") { said.sentences().contains(Say.leftListingKept("a.txt")) }
        listing.set(listOf("b.txt", "c.txt"))
        host.listingChanged()
        eventually("the next listing is in the mirror") { Files.isRegularFile(mirror.root.resolve("c.txt")) }
        assertTrue("the kept copy is still on disk", Files.isRegularFile(mirror.root.resolve("a.txt")))
        val file = FileDocumentManager.getInstance().getFile(editor.document)!!
        assertTrue("and still open", FileEditorManager.getInstance(project).isFileOpen(file))
        assertEquals("mine alpha\n", editor.document.text)
        assertEquals("no longer shared", "mine alpha\n", host.text("a.txt"))
        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "more ") }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("a kept copy's keystroke is not published", "mine alpha\n", session.engine.text("a.txt"))
    }

    fun testThePeopleListsFollowTheRoomWhileTheyAreOpen() {
        val root = Files.createDirectories(Path.of(project.basePath!!))
        Files.writeString(root.resolve("README.md"), "hello\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
        val service = SelvageService.get()
        service.host(project)
        val session = service.current ?: throw AssertionError("no session; said: ${said.sentences()}")
        val bob = SelvageSession.join(session.invite()!!, options("Bob")).also { engines.add(it) }
        val carol = SelvageSession.join(session.invite()!!, options("Carol")).also { engines.add(it) }
        eventually("both guests are in the list") { session.participants().size == 2 }

        prompts.holdNext = 1
        service.goToParticipant(project)
        val goTo = prompts.held.single()
        assertEquals(SelvageService.PICK_A_PARTICIPANT, goTo.placeholder)
        assertEquals(2, goTo.rows.size)
        prompts.holdNext = 1
        service.peers(project)
        val everyone = prompts.held.last()
        assertEquals(People.EVERYONE_LABEL, everyone.placeholder)
        assertEquals(3, everyone.rows.size)

        carol.leave()
        eventually("a person who leaves is dropped from both open lists") {
            goTo.rows.size == 1 && everyone.rows.size == 2
        }
        assertFalse(everyone.rows.toString(), everyone.rows.any { it.startsWith("Carol") })

        prompts.holdNext = 1
        everyone.pick(everyone.rows.indexOfFirst { it.startsWith("Bob") })
        val menu = prompts.held.last()
        assertEquals("Bob", menu.title)
        assertEquals("not in a file yet", menu.placeholder)
        bob.rename("Robert").get(5, TimeUnit.SECONDS)
        eventually("the person's menu follows the rename") { menu.title == "Robert" }

        bob.leave()
        eventually("the lists close when nobody is left to pick") { !menu.open && !goTo.open }
        assertTrue("no list is left listening", session.pickers.isEmpty())
        prompts.confirms.add(true)
        service.leave(project)
    }

    /**
     * A transport that holds every binary frame the server sends this window while [hold] is set, and
     * delivers them in order when [release] is called. The room's text arrives as one of those frames,
     * so a window dialling through this has not got the text while it still holds the path.
     */
    private class HeldFrames(
        private val inner: Transport = JdkTransport(),
    ) : Transport {
        private val lock = Any()
        private var holding = false
        private val held = ArrayList<ByteArray>()
        private var listener: SocketListener? = null

        fun hold() = synchronized(lock) { holding = true }

        fun release() {
            val pending: List<ByteArray>
            synchronized(lock) {
                holding = false
                pending = ArrayList(held)
                held.clear()
            }
            val to = listener ?: return
            pending.forEach(to::onBinary)
        }

        override fun open(
            url: String,
            timeout: Duration,
            listener: SocketListener,
        ): CompletableFuture<WireSocket> {
            this.listener = listener
            val relay =
                object : SocketListener {
                    override fun onText(text: String) = listener.onText(text)

                    override fun onBinary(bytes: ByteArray) {
                        synchronized(lock) {
                            if (holding) {
                                held.add(bytes)
                                return
                            }
                        }
                        listener.onBinary(bytes)
                    }

                    override fun onClose(
                        code: Int,
                        reason: String,
                    ) = listener.onClose(code, reason)
                }
            return inner.open(url, timeout, relay)
        }
    }

    /** A transport whose sockets a test can cut, as a dropped connection: the engine hears a 1006 and nothing after. */
    private class Severable(
        private val inner: Transport = JdkTransport(),
    ) : Transport {
        private class Open(
            val socket: WireSocket,
            val listener: SocketListener,
            val cut: java.util.concurrent.atomic.AtomicBoolean,
        )

        private val open = CopyOnWriteArrayList<Open>()

        override fun open(
            url: String,
            timeout: Duration,
            listener: SocketListener,
        ): CompletableFuture<WireSocket> {
            val cut =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val relay =
                object : SocketListener {
                    override fun onText(text: String) {
                        if (!cut.get()) listener.onText(text)
                    }

                    override fun onBinary(bytes: ByteArray) {
                        if (!cut.get()) listener.onBinary(bytes)
                    }

                    override fun onClose(
                        code: Int,
                        reason: String,
                    ) {
                        if (cut.compareAndSet(false, true)) listener.onClose(code, reason)
                    }
                }
            return inner.open(url, timeout, relay).thenApply { socket ->
                socket.also { open.add(Open(it, listener, cut)) }
            }
        }

        fun sever(): Int {
            var count = 0
            for (each in open) {
                if (each.cut.compareAndSet(false, true)) {
                    count += 1
                    each.listener.onClose(1006, "cut by the test")
                    each.socket.close()
                }
            }
            open.clear()
            return count
        }
    }

    fun testAGuestWhoseSocketDropsReconnectsAndKeepsEditing() {
        val host = hostEngine("Grace", AtomicReference(listOf("README.md")), mapOf("README.md" to "hello\n"))
        val severable = Severable()
        val service = SelvageService.get()
        service.sessionOptions = { options(it, severable) }
        service.join(project, host.invite!!)
        val session = service.current ?: throw AssertionError("no session; said: ${said.sentences()}")
        eventually("the host is named") { session.statusText() == Words.guestIdentity("Grace") }
        eventually("the room's listing is in") { session.listed() == listOf("README.md") }
        prompts.choices.add(0)
        service.openDocument(project)
        eventually("the room's text fills the mirror file") {
            FileEditorManager
                .getInstance(project)
                .selectedTextEditor
                ?.document
                ?.text == "hello\n"
        }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        val rows = CopyOnWriteArrayList<Pair<String, String>>()
        session.changed.add { rows.add(session.statusText() to session.statusTooltip()) }

        assertEquals("one socket was cut", 1, severable.sever())
        eventually("the row says it is reconnecting") { rows.any { it.first == Say.RECONNECTING } }
        assertEquals(RoomSession.RECONNECTING_TOOLTIP, rows.first { it.first == Say.RECONNECTING }.second)
        eventually("and then names the host again", timeoutMs = 20_000) {
            session.statusText() == Words.guestIdentity("Grace") && !session.isReconnecting()
        }
        assertSame("the session survived the drop", session, service.current)

        host.insert("README.md", 0, "> ")
        eventually("the host's edit reaches the re-seated guest") { editor.document.text == "> hello\n" }
        WriteCommandAction.runWriteCommandAction(
            project,
        ) { editor.document.insertString(editor.document.textLength, "bye\n") }
        eventually("the guest's keystroke reaches the host") { host.text("README.md") == "> hello\nbye\n" }
        assertFalse(
            said.sentences().toString(),
            said.sentences().any { it.startsWith("Selvage: the connection ended") },
        )
    }
}
