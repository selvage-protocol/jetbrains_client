package dev.dontblameme.selvage.intellij.session

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.dontblameme.selvage.engine.HostContent
import dev.dontblameme.selvage.engine.SelvageSession
import dev.dontblameme.selvage.engine.SessionOptions
import dev.dontblameme.selvage.intellij.TestIde
import dev.dontblameme.selvage.intellij.bridge.Say
import dev.dontblameme.selvage.intellij.bridge.Words
import dev.dontblameme.selvage.intellij.settings.SelvageSettings
import dev.dontblameme.selvage.intellij.ui.Prompts
import dev.dontblameme.selvage.peer.Keepalive
import dev.dontblameme.selvage.peer.Selection
import dev.dontblameme.selvage.sealed.Role
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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

    private fun options(name: String) =
        SessionOptions(
            name,
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
        service.sessionOptions = ::options
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
}
