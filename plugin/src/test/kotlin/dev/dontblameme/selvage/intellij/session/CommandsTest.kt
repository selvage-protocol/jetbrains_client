package dev.dontblameme.selvage.intellij.session

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dontblameme.selvage.intellij.bridge.Say
import dev.dontblameme.selvage.intellij.settings.SelvageSettings
import dev.dontblameme.selvage.intellij.ui.Notifier
import dev.dontblameme.selvage.intellij.ui.Prompts
import dev.dontblameme.selvage.sealed.KeyCodec

/**
 * The twelve commands: registered under the canonical titles, grouped under Tools → Selvage (which
 * the status bar's menu opens), enabled in every state as VS Code's palette has them, and each saying
 * its shared sentence where there is no session to act on.
 */
class CommandsTest : BasePlatformTestCase() {
    private val ids =
        listOf(
            "Selvage.Host" to Say.HOST,
            "Selvage.Join" to Say.JOIN,
            "Selvage.CopyInvite" to Say.COPY_INVITE,
            "Selvage.OpenDocument" to Say.OPEN_DOCUMENT,
            "Selvage.Fetch" to Say.FETCH,
            "Selvage.Leave" to Say.LEAVE,
            "Selvage.DisplayName" to Say.DISPLAY_NAME,
            "Selvage.ChangeServer" to Say.CHANGE_SERVER,
            "Selvage.Peers" to Say.PEERS,
            "Selvage.GoToParticipant" to Say.GO_TO,
            "Selvage.FollowParticipant" to Say.FOLLOW,
            "Selvage.StopFollowing" to Say.STOP_FOLLOWING,
        )

    private lateinit var prompts: ScriptedPrompts

    private var tolerated: com.intellij.openapi.application.AccessToken? = null

    override fun setUp() {
        tolerated =
            dev.dontblameme.selvage.intellij.TestIde
                .tolerateProductExtensions()
        super.setUp()
        prompts = ScriptedPrompts()
        Prompts.current = prompts
        SelvageSettings.get().loadState(SelvageSettings.Options())
    }

    override fun tearDown() {
        try {
            Prompts.current = Prompts.Ide
        } finally {
            try {
                super.tearDown()
            } finally {
                tolerated?.close()
            }
        }
    }

    private fun event(id: String): AnActionEvent {
        val action = ActionManager.getInstance().getAction(id)
        return TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
    }

    fun testTheTwelveAreInTheSelvageGroupUnderTools() {
        val manager = ActionManager.getInstance()
        val group = manager.getAction("Selvage.Group") as DefaultActionGroup
        assertEquals("Selvage", group.templatePresentation.text)
        assertEquals(ids.map { it.first }, group.childActionsOrStubs.map { manager.getId(it) })
        for ((id, title) in ids) assertEquals(id, title, manager.getAction(id).templatePresentation.text)
        val tools = manager.getAction("ToolsMenu") as DefaultActionGroup
        assertTrue("Tools → Selvage", tools.childActionsOrStubs.any { manager.getId(it) == "Selvage.Group" })
    }

    fun testEveryCommandIsEnabledWithoutASession() {
        for ((id, _) in ids) {
            val event = event(id)
            ActionManager.getInstance().getAction(id).update(event)
            assertTrue("$id is enabled", event.presentation.isEnabledAndVisible)
        }
    }

    fun testWithoutASessionEachCommandSaysWhy() {
        Said().use { said ->
            val expected =
                listOf(
                    "Selvage.CopyInvite" to Say.noInvite(),
                    "Selvage.OpenDocument" to Say.joinFirst(),
                    "Selvage.Fetch" to Say.joinFirst(),
                    "Selvage.Leave" to Say.notInSession(),
                    "Selvage.Peers" to Say.joinFirst(),
                    "Selvage.GoToParticipant" to Say.joinFirst(),
                    "Selvage.FollowParticipant" to Say.joinFirst(),
                    "Selvage.StopFollowing" to Say.joinFirst(),
                    "Selvage.DisplayName" to Say.noDisplayName(),
                    "Selvage.ChangeServer" to Say.noServerRemembered(),
                )
            for ((id, sentence) in expected) {
                ActionManager.getInstance().getAction(id).actionPerformed(event(id))
                assertEquals(id, sentence, said.last().sentence)
            }
            assertEquals(Notifier.Level.WARNING, said.all.first().level)
            assertEquals(listOf(Say.CHANGE_THE_NAME), said.all.first { it.sentence == Say.noDisplayName() }.buttons)
            assertEquals(listOf(Say.CHANGE_SERVER), said.last().buttons)
        }
    }

    fun testAJoinWithABadLinkIsRefusedBeforeAnythingElse() {
        Said().use { said ->
            prompts.inputs.add("https://example.com/not-an-invite")
            ActionManager.getInstance().getAction("Selvage.Join").actionPerformed(event("Selvage.Join"))
            assertEquals(
                Say.wrap(dev.dontblameme.selvage.intellij.bridge.Invites.INVITE_LINK_HINT),
                said.last().sentence,
            )
            // The link's fragment is read by the engine, so the sentence for a `k` that is not a
            // key is its own, rather than a second reader's guess about the `h` that is not there
            // either.
            prompts.inputs.add("https://example.com/?room=r&token=t#k=abc")
            ActionManager.getInstance().getAction("Selvage.Join").actionPerformed(event("Selvage.Join"))
            assertEquals(
                "Selvage: `k` is not a 32-byte key in the fragment's encoding",
                said.last().sentence,
            )
        }
    }

    /**
     * §5.1: a page link that repeats a join key is refused locally, by the box, and by name — no
     * name question, no socket, and no choice between the two values the rewrite was handed.
     */
    fun testAJoinWithARepeatedJoinKeyIsRefusedByTheBox() {
        Said().use { said ->
            val k = KeyCodec.encode(ByteArray(32) { it.toByte() })
            val h = KeyCodec.encode(ByteArray(32) { (255 - it).toByte() })
            prompts.inputs.add("https://example.com/?room=r-1&room=r-2&token=t#k=$k&h=$h")
            ActionManager.getInstance().getAction("Selvage.Join").actionPerformed(event("Selvage.Join"))
            assertEquals(Say.wrap("the invite names `room` twice"), said.last().sentence)
            assertEquals(listOf("input: ${Say.JOIN_TITLE}"), prompts.asked)
        }
    }

    fun testTheServerIsRememberedAndAConfiguredOneIsFixed() {
        Said().use { said ->
            SelvageService.get().changeServer(project, "selvage.example")
            assertEquals(Say.willHostOn("wss://selvage.example"), said.last().sentence)
            assertEquals("wss://selvage.example", SelvageSettings.get().state.lastServer)
            SelvageService.get().changeServer(project)
            assertEquals(Say.nextHostUses("wss://selvage.example"), said.last().sentence)
            SelvageSettings.get().update { serverUrl = "ws://127.0.0.1:8080/session" }
            SelvageService.get().changeServer(project)
            assertEquals(Say.settingFixesServer("ws://127.0.0.1:8080"), said.last().sentence)
        }
    }

    fun testANameOverTheBoundIsRefused() {
        Said().use { said ->
            prompts.inputs.add("x".repeat(33))
            SelvageService.get().displayName(project, rename = true)
            assertEquals(
                "Selvage: this name is 33 UTF-16 code units and the limit is 32; a name is refused rather than shortened.",
                said.last().sentence,
            )
            prompts.inputs.add("Ada")
            SelvageService.get().displayName(project, rename = true)
            assertEquals(Say.displayNameSet("Ada"), said.last().sentence)
            assertEquals("Ada", SelvageSettings.get().displayName)
        }
    }

    @Suppress("unused")
    private val place = ActionPlaces.UNKNOWN
}
