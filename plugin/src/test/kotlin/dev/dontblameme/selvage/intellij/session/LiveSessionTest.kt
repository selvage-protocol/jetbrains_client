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
