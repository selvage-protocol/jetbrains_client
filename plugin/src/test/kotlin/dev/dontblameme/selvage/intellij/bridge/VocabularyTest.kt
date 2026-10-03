package dev.dontblameme.selvage.intellij.bridge

import com.google.gson.JsonParser
import dev.dontblameme.selvage.intellij.Siblings
import junit.framework.TestCase
import java.io.File
import java.lang.reflect.Modifier

/**
 * The shared vocabulary, pinned as the other desktop clients pin it (`vscode_client/test/vocabulary.test.ts`,
 * `nvim_client/test/lua/vocabulary.lua`): the twelve titles are §5's, and every sentence this client
 * can show is, holes and all, one the VS Code client's own pin lists, except the few this editor
 * says in its own words, which are pinned here by name. The VS Code list is read from its source, so
 * a sentence that moves there moves here or fails.
 */
class VocabularyTest : TestCase() {
    private val titles =
        mapOf(
            "selvage.host" to "Host a session",
            "selvage.join" to "Join a session from an invite link",
            "selvage.copyInvite" to "Copy the invite link",
            "selvage.openDocument" to "Open a document from the room",
            "selvage.fetch" to "Download a file from the room",
            "selvage.leave" to "Leave the session",
            "selvage.displayName" to "Set the name other participants see",
            "selvage.changeServer" to "Change the server",
            "selvage.peers" to "List the room's participants",
            "selvage.goToParticipant" to "Go to a participant",
            "selvage.followParticipant" to "Follow a participant",
            "selvage.stopFollowing" to "Stop following",
        )

    /** Sentences this client words itself, each with the reason the VS Code wording does not fit. */
    private val ownSentences =
        mapOf(
            "Selvage: the room's project settings (\${}) are not put in this window, " +
                "because the IDE would apply them rather than just show them." to
                "VS Code's names VS Code and its `.vscode` settings; " +
                "a project opened here applies `.idea` and module files",
            "Reconnecting…" to "the status bar text without VS Code's codicon",
            "Connecting…" to "the status bar text without VS Code's codicon",
            "\${} · Disconnecting in \${}" to "the status bar text without VS Code's codicon",
            "Following \${}" to "the status bar text without VS Code's codicon",
            "At most 32 characters; some emoji and accented characters count as more than one." to
                "the name box's prompt, `displayNameInput` in display-name.ts",
            "At most 32 characters; some emoji and accented characters count as more than one. " +
                "The name others see is \"\${}\"." to
                "the name box's prompt with the name in force, `displayNameInput` in display-name.ts",
            "could not share \${}: \${}; nothing was shared for it" to
                "the bridge's own refusal (`bridge.ts`), wrapped as a session error",
            "will not share \${} with the room: \${}; nothing was shared for it" to
                "the bridge's own refusal (`bridge.ts`)",
            "the host no longer shares \${}; it may have been deleted after the listing was published" to
                "`leftListingNotice` in extension.ts",
        )

    private val vscodeTest: String by lazy { File(Siblings.vscodeClient(), "test/vocabulary.test.ts").readText() }

    /** The string literals of the array `name` in the VS Code pin, with `\${…}` collapsed to `\${}`. */
    private fun vscodeList(name: String): List<String> {
        val body =
            Regex("const $name = \\[(.*?)\\n];", RegexOption.DOT_MATCHES_ALL).find(vscodeTest)?.groupValues?.get(1)
                ?: throw AssertionError("vscode_client/test/vocabulary.test.ts no longer declares $name")
        val literals = ArrayList<String>()
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//")) continue
            val match = Regex("^(['\"`])(.*)\\1,?$").find(trimmed) ?: continue
            val (quote, inner) = match.destructured
            val text = if (quote == "\"") inner.replace("\\\"", "\"") else inner
            val unwrapped = Regex("^(['`])(.*)\\1$").find(text)?.groupValues?.get(2) ?: text
            literals.add(unwrapped.replace("\\'", "'").replace("\\`", "`"))
        }
        return literals
    }

    /** Every sentence [Say] can produce, its String parameters filled with `\${}`. */
    private fun ourSentences(): List<String> {
        val found = ArrayList<String>()
        for (method in Say::class.java.declaredMethods) {
            if (!Modifier.isPublic(method.modifiers) || method.returnType != String::class.java) continue
            if (method.parameterTypes.any { it != String::class.java }) continue
            if (method.name in setOf("wrap", "getHOST_LEAVE_ASKING")) continue
            val sentence = method.invoke(Say, *Array(method.parameterCount) { "\${}" }) as String
            found.add(sentence)
        }
        found.add(Say.namePrompt(null))
        found.add(Say.RECONNECTING)
        found.add(Say.CONNECTING)
        return found
    }

    fun testTheTwelveTitlesAreTheCanonicalOnes() {
        val ours =
            listOf(
                Say.HOST,
                Say.JOIN,
                Say.COPY_INVITE,
                Say.OPEN_DOCUMENT,
                Say.FETCH,
                Say.LEAVE,
                Say.DISPLAY_NAME,
                Say.CHANGE_SERVER,
                Say.PEERS,
                Say.GO_TO,
                Say.FOLLOW,
                Say.STOP_FOLLOWING,
            )
        assertEquals(titles.values.toList(), ours)
        val pinned =
            Regex("'(selvage\\.\\w+)': \"?'?([^\"\\n]*?)\"?'?,\\n").findAll(vscodeTest).associate {
                it.groupValues[1] to it.groupValues[2]
            }
        assertEquals("the VS Code pin's TITLES", titles, pinned.filterKeys { it in titles })
        val manifest = JsonParser.parseString(File(Siblings.vscodeClient(), "package.json").readText()).asJsonObject
        val commands =
            manifest.getAsJsonObject("contributes").getAsJsonArray("commands").associate {
                it.asJsonObject.get("command").asString to it.asJsonObject.get("title").asString
            }
        assertEquals("the VS Code manifest's titles", titles, commands)
    }

    fun testEverySentenceIsOneTheVsCodeClientSays() {
        val theirs = (vscodeList("SENTENCES") + vscodeList("PLAIN_SENTENCES")).toSet()
        assertTrue("the VS Code pin was read (${theirs.size} sentences)", theirs.size > 80)
        val ours = ourSentences()
        assertTrue("Say was read (${ours.size} sentences)", ours.size > 70)
        val unknown = ours.filter { it !in theirs && it !in ownSentences.keys }
        assertEquals("sentences neither the VS Code pin nor this client's own list holds", emptyList<String>(), unknown)
        val stale = ownSentences.keys.filter { it !in ours }
        assertEquals("own sentences this client no longer says", emptyList<String>(), stale)
    }

    fun testTheWordsFromTheBridgeAreTheSharedOnes() {
        assertEquals(
            "Leaving ends the room for everyone and stops the invite link.",
            Say.HOST_LEAVE_ASKING,
        )
        assertEquals("a name is needed.", Invites.displayNameRefusal("  "))
        assertEquals(
            "this name is 33 UTF-16 code units and the limit is 32; a name is refused rather than shortened.",
            Invites.displayNameRefusal("x".repeat(33)),
        )
        val refusals =
            Regex(
                "const REFUSALS = \\[(.*?)];",
                RegexOption.DOT_MATCHES_ALL,
            ).find(vscodeTest)!!.groupValues[1]
        assertTrue(refusals.contains("'a name is needed.'"))
        assertTrue(
            refusals.contains(
                "'this name is 33 UTF-16 code units and the limit is 32; a name is refused rather than shortened.'",
            ),
        )
    }

    /** §5's rows, as sentences: the ones a person meets most, pinned literally so a change here is deliberate. */
    fun testSectionFiveRows() {
        assertEquals(
            "Selvage: the room is open. Send this link to your friend — it is on the clipboard.",
            Say.roomOpen(),
        )
        assertEquals("Selvage: joined the room. No one has a file open yet.", Say.joinedEmpty())
        assertEquals(
            "Selvage: joined the room, opening a.md. 2 more files are open.",
            Say.joinedOpeningMore("a.md", Say.moreFiles(2)),
        )
        assertEquals(
            "Selvage: joined the room, opening a.md. 1 more file is open.",
            Say.joinedOpeningMore("a.md", Say.moreFiles(1)),
        )
        assertEquals("Selvage: there is no invite link; host or join a room first.", Say.noInvite())
        assertEquals("Selvage: join a session first.", Say.joinFirst())
        assertEquals("Selvage: not in a session.", Say.notInSession())
        assertEquals("Selvage: left the session.", Say.leftSession())
        assertEquals("Selvage: not following anyone.", Say.notFollowing())
        assertEquals("Selvage: no other participants yet.", Say.noOtherParticipants())
        assertEquals("Selvage: you are the host — the files you open are the ones your guests see.", Say.youAreHost())
        assertEquals(
            "Selvage: your files are already on your disk, so there is nothing to fetch while you host.",
            Say.fetchWhileHosting(),
        )
        assertEquals(
            "The host ended the session. Your copy is kept at /m.",
            Say.copyKept(Words.roomGoneSentence("the room closed"), "/m"),
        )
        assertEquals(
            "Selvage: could not host on wss://x. No server answered — ${Invites.HOST_CHECK}",
            Say.couldNotHost("wss://x", Invites.connectRefusal(RuntimeException("refused"), Invites.HOST_CHECK)),
        )
    }
}
