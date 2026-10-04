package dev.dontblameme.selvage.intellij.bridge

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The host-side read a peer's request drives, and the walk that lists the folder, against paths that
 * try to leave it (`AGENTS.md` §3): `..`, an absolute path, a link to a directory outside, a link to
 * a file outside, a path through a link, a case-folded excluded name, and an ignored file.
 */
class GrantFolderTest : TestCase() {
    private lateinit var scratch: Path
    private lateinit var root: Path
    private lateinit var outside: Path

    override fun setUp() {
        super.setUp()
        scratch = Files.createTempDirectory("grant-folder")
        root = Files.createDirectories(scratch.resolve("shared"))
        outside = Files.createDirectories(scratch.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "outside the grant\n")
        Files.writeString(root.resolve("README.md"), "hello\r\nworld\r\n")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/main.kt"), "fun main() {}\n")
        Files.createSymbolicLink(root.resolve("linked-dir"), outside)
        Files.createSymbolicLink(root.resolve("linked-file.txt"), outside.resolve("secret.txt"))
        Files.createSymbolicLink(root.resolve("src/up"), scratch)
        Files.writeString(root.resolve(".gitignore"), "*.log\n")
        Files.writeString(root.resolve("debug.log"), "ignored\n")
        Files.write(root.resolve("data.bin"), byteArrayOf(1, 0, 2))
        Files.write(
            root.resolve("bom.txt"),
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 'a'.code.toByte()),
        )
        Files.write(root.resolve("big.txt"), ByteArray(Grant.MAX_GRANT_FILE_BYTES + 1) { 'a'.code.toByte() })
        Files.createDirectories(root.resolve(".git"))
        Files.writeString(root.resolve(".git/config"), "[core]\n")
        Files.writeString(root.resolve(".env"), "TOKEN=1\n")
    }

    override fun tearDown() {
        scratch.toFile().deleteRecursively()
        super.tearDown()
    }

    private fun folder(fold: Boolean = false) = GrantFolder(root, fold)

    private fun refused(
        path: String,
        cause: GrantFolder.Refusal,
        fold: Boolean = false,
    ) = assertEquals(path, GrantFolder.Read.Refused(cause), folder(fold).read(path))

    fun testALinkedGitBringsNoIgnoreRulesFromOutsideTheFolder() {
        val linkedRoot = Files.createDirectories(scratch.resolve("linked-git"))
        Files.writeString(linkedRoot.resolve("README.md"), "hello\n")
        val elsewhere = Files.createDirectories(scratch.resolve("elsewhere-git/info"))
        Files.writeString(elsewhere.resolve("exclude"), "README.md\n")
        Files.createSymbolicLink(linkedRoot.resolve(".git"), elsewhere.parent)
        val folder = GrantFolder(linkedRoot, false)
        assertEquals(listOf("README.md"), folder.walk().paths)
        val read = folder.read("README.md")
        assertEquals("the read applies the rules the walk applied", GrantFolder.Read.Text("hello\n"), read)
        val linkedInfo = Files.createDirectories(scratch.resolve("linked-info"))
        Files.writeString(linkedInfo.resolve("README.md"), "hello\n")
        Files.createDirectories(linkedInfo.resolve(".git"))
        Files.createSymbolicLink(linkedInfo.resolve(".git/info"), elsewhere)
        assertEquals(GrantFolder.Read.Text("hello\n"), GrantFolder(linkedInfo, false).read("README.md"))
    }

    fun testAFileOpenedForTheHostIsAPlainFileReachedThroughPlainDirectories() {
        assertEquals(root.resolve("src").resolve("main.kt"), folder().plainFile("src/main.kt"))
        for (path in listOf(
            "linked-file.txt",
            "linked-dir/secret.txt",
            "src/up/outside/secret.txt",
            "../outside/secret.txt",
            "/etc/passwd",
            "src",
            "missing.txt",
            ".env",
        )) {
            assertNull(path, folder().plainFile(path))
        }
    }

    fun testAGrantedFileIsServedWithLfLineEndings() {
        assertEquals(GrantFolder.Read.Text("hello\nworld\n"), folder().read("README.md"))
        assertEquals(GrantFolder.Read.Text("fun main() {}\n"), folder().read("src/main.kt"))
        assertEquals(GrantFolder.Read.Text("a"), folder().read("bom.txt"))
    }

    fun testDotDotDoesNotLeaveTheFolder() {
        refused("../outside/secret.txt", GrantFolder.Refusal.NOT_GRANTED)
        refused("src/../../outside/secret.txt", GrantFolder.Refusal.NOT_GRANTED)
    }

    fun testAnAbsolutePathIsNotAPathInTheFolder() {
        refused(outside.resolve("secret.txt").toString(), GrantFolder.Refusal.NOT_GRANTED)
        refused("/etc/passwd", GrantFolder.Refusal.NOT_GRANTED)
    }

    fun testALinkedDirectoryIsNotEntered() {
        refused("linked-dir/secret.txt", GrantFolder.Refusal.NOT_A_FILE)
    }

    fun testALinkedFileIsNotRead() {
        refused("linked-file.txt", GrantFolder.Refusal.NOT_A_FILE)
    }

    fun testAPathThroughALinkIsNotFollowed() {
        refused("src/up/outside/secret.txt", GrantFolder.Refusal.NOT_A_FILE)
    }

    fun testExcludedAndIgnoredNamesAreNotServed() {
        refused(".git/config", GrantFolder.Refusal.NOT_GRANTED)
        refused(".GIT/config", GrantFolder.Refusal.NOT_GRANTED, fold = true)
        refused(".env", GrantFolder.Refusal.NOT_GRANTED)
        refused("debug.log", GrantFolder.Refusal.NOT_GRANTED)
    }

    fun testANameThatOnlyFoldsToAFileIsMissing() {
        refused("readme.md", GrantFolder.Refusal.MISSING)
    }

    fun testWhatARoomCannotCarryIsRefusedForWhatItIs() {
        refused("data.bin", GrantFolder.Refusal.BINARY)
        refused("big.txt", GrantFolder.Refusal.TOO_LARGE)
        refused("src", GrantFolder.Refusal.NOT_A_FILE)
        refused("nothing.txt", GrantFolder.Refusal.MISSING)
    }

    fun testTheListingHoldsNoLinkNoExcludedNameAndNoIgnoredFile() {
        val walk = folder().walk()
        assertEquals(listOf(".gitignore", "README.md", "bom.txt", "src/main.kt"), walk.paths)
        assertNull(walk.cut)
        assertFalse(
            walk.paths.any {
                it.startsWith("linked") || it.startsWith(".git/") || it == ".env" ||
                    it == "debug.log"
            },
        )
    }
}
