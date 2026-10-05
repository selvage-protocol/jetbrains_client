package dev.dontblameme.selvage.intellij.bridge

import dev.dontblameme.selvage.intellij.Siblings
import dev.dontblameme.selvage.sealed.Role
import junit.framework.TestCase
import java.io.File

/**
 * The ported bridge against the TypeScript it was ported from: every call below is evaluated by the
 * VS Code client's own `words.ts`, `seats.ts`, `names.ts`, `initials.ts` and `grant.ts` through Node,
 * and by the Kotlin port, and the two outputs must be equal. An export the TypeScript gains that this
 * test does not name fails it too, so drift on either side fails the build.
 */
class BridgeParityTest : TestCase() {
    private class Call(
        val module: String,
        val name: String,
        val args: List<Any?>?,
        val kotlin: () -> Any?,
    )

    private val calls = ArrayList<Call>()

    private fun constant(
        module: String,
        name: String,
        kotlin: () -> Any?,
    ) {
        calls.add(Call(module, name, null, kotlin))
    }

    private fun call(
        module: String,
        name: String,
        vararg args: Any?,
        kotlin: () -> Any?,
    ) {
        calls.add(Call(module, name, args.toList(), kotlin))
    }

    private val names =
        listOf("Ada", "", "  ", " Grace ", "😀 Emoji", "\u00a0", "Ünïcödé", "a\tb", "x".repeat(40))

    private fun wordsCases() {
        constant("words", "SHARED_SESSION_IDENTITY") { Words.SHARED_SESSION_IDENTITY }
        constant("words", "COPY_INVITE_LABEL") { Words.COPY_INVITE_LABEL }
        constant("words", "COPIED_LABEL") { Words.COPIED_LABEL }
        constant("words", "COPIED_STAND_MS") { Words.COPIED_STAND_MS }
        constant("words", "RECONNECTING_NOTE") { Words.RECONNECTING_NOTE }
        constant("words", "LEAVE_HOST_LABEL") { Words.LEAVE_HOST_LABEL }
        constant("words", "LEAVE_ASKING_LABEL") { Words.LEAVE_ASKING_LABEL }
        constant("words", "LEAVE_CANCEL_LABEL") { Words.LEAVE_CANCEL_LABEL }
        constant("words", "HOST_LEAVE_CONSEQUENCE") { Words.HOST_LEAVE_CONSEQUENCE }
        constant("words", "HOST_LEAVE_QUESTION") { Words.HOST_LEAVE_QUESTION }
        constant("words", "DOWNLOAD_COST_MANY_SENTENCE") { Words.DOWNLOAD_COST_MANY_SENTENCE }
        constant("words", "SESSION_ENDED_MESSAGE") { Words.SESSION_ENDED_MESSAGE }
        for (name in names) {
            call("words", "hostingIdentity", name) { Words.hostingIdentity(name) }
            call("words", "guestIdentity", name) { Words.guestIdentity(name) }
            call("words", "hostLeftSentence", name) { Words.hostLeftSentence(name) }
            call("words", "hostBackSentence", name) { Words.hostBackSentence(name) }
            call("words", "goToNotInFile", name) { Words.goToNotInFile(name) }
            call("words", "goToCursorNotFound", name) { Words.goToCursorNotFound(name) }
            call("words", "downloadCostSentence", name) { Words.downloadCostSentence(name) }
            call("words", "followEndedByTyping", name) { Words.followEndedByTyping(name) }
            call("words", "followEndedByMoving", name) { Words.followEndedByMoving(name) }
            call("words", "followEndedByLeaving", name) { Words.followEndedByLeaving(name) }
            call("words", "followEndedByFileGone", name) { Words.followEndedByFileGone(name) }
        }
        val windows =
            listOf(
                -5L,
                0L,
                1L,
                999L,
                1_000L,
                1_001L,
                1_999L,
                29_999L,
                30_000L,
                59_999L,
                60_000L,
                61_000L,
                119_999L,
                3_599_999L,
                3_600_000L,
                7_200_001L,
            )
        for (ms in windows) {
            call("words", "graceWording", ms) { Words.graceWording(ms.toDouble()) }
            call("words", "hostAwaySentence", "Ada", ms) { Words.hostAwaySentence("Ada", ms.toDouble()) }
            call("words", "hostAwaySentence", "", ms) { Words.hostAwaySentence("", ms.toDouble()) }
            for (left in listOf(-1L, 0L, 1L, 500L, 1_000L, 1_001L, 18_200L, 29_999L, 59_000L, 125_000L)) {
                call(
                    "words",
                    "disconnectingReading",
                    ms,
                    left,
                ) { Words.disconnectingReading(ms.toDouble(), left.toDouble()) }
            }
        }
        val reasons =
            listOf(
                "the room closed",
                " the room closed ",
                "the host has been away past its window",
                "the room has sealed as many frames as its key allows; start a new room",
                "no state arrived within the no-state window",
                "",
                "   ",
                "the room is gone",
            )
        for (reason in reasons) call("words", "roomGoneSentence", reason) { Words.roomGoneSentence(reason) }
    }

    private fun seatsCases() {
        constant("seats", "SEAT_PALETTE") { Seats.SEAT_PALETTE }
        constant("seats", "SEAT_LIMIT") { Seats.SEAT_LIMIT }
        val rooms =
            listOf(
                emptyList(),
                listOf("me" to Role.GUEST, "h" to Role.HOST, "b" to Role.GUEST),
                listOf("h" to Role.HOST),
                listOf("a" to Role.VIEWER, "b" to Role.GUEST),
                (1..16).map { "p$it" to if (it == 9) Role.HOST else Role.GUEST },
                listOf("h1" to Role.HOST, "h2" to Role.HOST, "g" to Role.GUEST),
            )
        for (room in rooms) {
            val seats = room.map { mapOf("peerId" to it.first, "role" to it.second.wire) }
            call("seats", "seatColours", seats) { Seats.seatColours(room.map { Seats.Seat(it.first, it.second) }) }
        }
    }

    private fun namesCases() {
        val rooms =
            listOf(
                listOf("Ada" to "peer-1111", "Bob" to "peer-2222"),
                listOf("Ada" to "peer-1111", "Ada" to "peer-2222"),
                listOf("Ada" to "xx-abcd1111", "Ada" to "yy-abcd1111", "Ada" to "zzzz"),
                listOf("Ada" to "ab", "Ada" to "cab"),
                listOf("" to "p-1", "" to "p-2"),
            )
        for (room in rooms) {
            val all = room.map { mapOf("displayName" to it.first, "peerId" to it.second) }
            val kotlinAll = room.map { Names.NamedPeer(it.first, it.second) }
            for ((index, peer) in all.withIndex()) {
                call("names", "rosterLabel", peer, all) { Names.rosterLabel(kotlinAll[index], kotlinAll) }
            }
        }
    }

    private fun initialsCases() {
        constant("initials", "ANONYMOUS_INITIALS") { Initials.ANONYMOUS_INITIALS }
        constant("initials", "INITIALS_LIMIT") { Initials.INITIALS_LIMIT }
        for (label in listOf("Ada", "", "A", "😀bc", "a😀", "😀😁😂", "Ünïcödé", " x", "e\u0301z")) {
            call("initials", "initials", label) { Initials.initials(label) }
        }
    }

    private fun grantCases() {
        constant("grant", "MAX_GRANT_PATHS") { Grant.MAX_GRANT_PATHS }
        constant("grant", "MAX_GRANT_LISTING_BYTES") { Grant.MAX_GRANT_LISTING_BYTES }
        constant("grant", "MAX_GRANT_NODES") { Grant.MAX_GRANT_NODES }
        constant("grant", "MAX_GRANT_PATH_BYTES") { Grant.MAX_GRANT_PATH_BYTES }
        constant("grant", "MAX_GRANT_FILE_BYTES") { Grant.MAX_GRANT_FILE_BYTES }
        constant("grant", "GRANT_EXCLUDED_DIRS") { Grant.GRANT_EXCLUDED_DIRS }
        constant("grant", "GRANT_BINARY_SUFFIXES") { Grant.GRANT_BINARY_SUFFIXES }
        val paths =
            listOf(
                "README.md",
                "src/main.rs",
                "",
                " ",
                "/etc/passwd",
                "../up",
                "a/../b",
                "a/./b",
                "a//b",
                "a\\b",
                ".git/config",
                ".GIT/config",
                "node_modules/x/index.js",
                "Node_Modules/x.js",
                "build/out.txt",
                "Build/out.txt",
                ".env",
                ".env.local",
                ".env.example",
                ".env.production",
                ".env.dev.local",
                ".ENV",
                "keys/id_rsa",
                "id_ed25519.pub",
                "server.pem",
                "a.PEM",
                "x.key",
                "creds/.npmrc",
                ".ssh/config",
                "sub/.aws/credentials",
                "terraform.tfstate.backup",
                "img/photo.JPG",
                ".zip",
                "LICENSE.zip",
                "doc.pdf",
                "bidi\u202eexe.txt",
                "line\u2028break",
                "tab\tname",
                "ok/übung.txt",
                "x".repeat(5000),
                ".idea/workspace.xml",
                "deep/a/b/c/d.txt",
                "vendor/x.go",
                "pkg/vendor.go",
            )
        for (path in paths) {
            for ((platform, fold) in listOf("linux" to false, "darwin" to true)) {
                call("grant", "isGrantedPath", path, platform) { Grant.isGrantedPath(path, fold) }
            }
            call("grant", "isBinaryNamedPath", path) { Grant.isBinaryNamedPath(path) }
        }
        for (size in listOf(
            0,
            10,
            Grant.MAX_GRANT_FILE_BYTES / 3,
            Grant.MAX_GRANT_FILE_BYTES / 2,
            Grant.MAX_GRANT_FILE_BYTES + 1,
        )) {
            for (unit in listOf("a", "é", "😀")) {
                val text = unit.repeat(size / unit.length)
                call("grant", "overFileBound", text) { Grant.overFileBound(text) }
            }
        }
        val ignores =
            listOf(
                listOf("" to "*.log\n!keep.log\n/build/\ndocs/**/draft*\n"),
                listOf("" to "secret/\n", "secret" to "!x.txt\n"),
                listOf("" to "\ufeff# comment\n\\#hash\n\\!bang\nfoo\\ \n[a-c].txt\n[!x]y.md\n[[:upper:]]*.TXT\n"),
                listOf("" to "a/**\n**/b\nc/**/d\n***\n"),
                listOf("src" to "*.gen.kt\n", "src/sub" to "!*.gen.kt\n"),
                listOf("" to "trailing   \nwin\r\n"),
            )
        val ignored =
            listOf(
                "app.log",
                "keep.log",
                "x/keep.log",
                "build",
                "build/x.txt",
                "sub/build/x",
                "docs/a/b/draft1.md",
                "docs/draft.md",
                "secret/x.txt",
                "secret",
                "#hash",
                "!bang",
                "foo ",
                "b.txt",
                "d.txt",
                "zy.md",
                "xy.md",
                "B.TXT",
                "b.TXT",
                "a/x",
                "a",
                "q/b",
                "b",
                "c/d",
                "c/x/y/d",
                "src/x.gen.kt",
                "src/sub/y.gen.kt",
                "trailing",
                "win",
            )
        for (sources in ignores) {
            val js = sources.map { mapOf("dir" to it.first, "text" to it.second) }
            val kt = sources.map { Grant.IgnoreSource(it.first, it.second) }
            for (path in ignored) {
                for (directory in listOf(false, true)) {
                    for ((platform, fold) in listOf("linux" to false, "win32" to true)) {
                        call(
                            "grant",
                            "isIgnoredPath",
                            js,
                            path,
                            directory,
                            platform,
                        ) { Grant.isIgnoredPath(kt, path, directory, fold) }
                    }
                }
            }
        }
        val listing = listOf("b", "B", "a/z", "a", "é", "z", "~", "a b")
        call("grant", "sortGrant", listing) { Grant.sortGrant(listing) }
        call("grant", "grantUnion", listing, listOf("x.png", "a", "doc.md")) {
            Grant.grantUnion(listing, listOf("x.png", "a", "doc.md"))
        }
    }

    /** Exports this port leaves out on purpose, each with its reason. */
    private val notPorted =
        mapOf(
            "grant.hostPlatform" to "reads Node's process; the port reads the JVM's os.name (Grant.hostFoldsCase)",
            "grant.IgnoreSource" to "a type, not a value",
        )

    fun testThePortSaysWhatTheTypeScriptSays() {
        wordsCases()
        seatsCases()
        namesCases()
        initialsCases()
        grantCases()
        val root = Siblings.vscodeClient()
        val driver = File(Siblings.workspace, "plugin/src/test/node/bridge-driver.mjs")
        assertTrue("the driver is at $driver", driver.isFile)
        val input =
            buildString {
                append("exports\n")
                for (call in calls) {
                    append(call.module).append('\t').append(call.name)
                    if (call.args != null) append('\t').append(Siblings.json(call.args))
                    append('\n')
                }
            }
        val lines =
            Siblings
                .run(
                    listOf(Siblings.node(), driver.path, root.path),
                    input,
                ).lines()
                .filter { it.isNotEmpty() }
        assertEquals("one answer per call, and the exports", calls.size + 1, lines.size)
        val exported =
            Regex("\"(\\w+)\":\\[([^]]*)]").findAll(lines.first()).associate { match ->
                match.groupValues[1] to
                    Regex("\"(\\w+)\"").findAll(match.groupValues[2]).map { it.groupValues[1] }.toSet()
            }
        val covered = calls.map { "${it.module}.${it.name}" }.toSet() + notPorted.keys
        val uncovered = exported.flatMap { (module, names) -> names.map { "$module.$it" } }.filter { it !in covered }
        val unported =
            uncovered.filter {
                it.startsWith("words.") || it.startsWith("seats.") || it.startsWith("names.") ||
                    it.startsWith("initials.")
            }
        assertEquals("every export of words, seats, names and initials is pinned", emptyList<String>(), unported)
        val mismatches = ArrayList<String>()
        for ((index, call) in calls.withIndex()) {
            val expected = lines[index + 1]
            val actual = Siblings.json(call.kotlin())
            if (expected !=
                actual
            ) {
                mismatches.add(
                    "${call.module}.${call.name}(${call.args?.let(Siblings::json) ?: ""}): ts $expected, kt $actual",
                )
            }
        }
        assertTrue("${calls.size} calls compared", calls.size > 1000)
        assertEquals(
            "the port differs from the TypeScript:\n" + mismatches.take(30).joinToString("\n"),
            0,
            mismatches.size,
        )
    }

    /** The lists `grant.ts` keeps unexported, read out of its source. */
    fun testTheUnexportedGrantListsAreTheTypeScriptsOwn() {
        val source = File(Siblings.vscodeClient(), "src/bridge/grant.ts").readText()

        fun list(name: String): List<String> {
            val body =
                Regex("const $name[^=]*= \\[(.*?)];", RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.get(1)
                    ?: throw AssertionError("grant.ts no longer declares $name")
            return Regex("'([^']*)'").findAll(body).map { it.groupValues[1] }.toList()
        }
        assertEquals(list("GRANT_EXCLUDED_FILES"), Grant.GRANT_EXCLUDED_FILES)
        assertEquals(list("GRANT_SECRET_DIRS"), Grant.GRANT_SECRET_DIRS)
        assertEquals(list("GRANT_SECRET_KEY_PREFIXES"), Grant.GRANT_SECRET_KEY_PREFIXES)
        assertEquals(list("GRANT_SECRET_KEY_SUFFIXES"), Grant.GRANT_SECRET_KEY_SUFFIXES)
        assertTrue(Grant.GRANT_SECRET_KEY_SUFFIXES.isNotEmpty())
    }

    fun testAGuestBeforeTheHostIsNamedIsInASharedSession() {
        assertEquals(Words.SHARED_SESSION_IDENTITY, Words.guestIdentity(null))
    }
}
