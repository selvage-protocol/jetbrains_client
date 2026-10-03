package dev.dontblameme.selvage.intellij.bridge

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/** A guest's mirror takes the room's names, never a path out of itself, and never project settings. */
class MirrorTest : TestCase() {
    private lateinit var scratch: Path

    override fun setUp() {
        super.setUp()
        scratch = Files.createTempDirectory("mirror")
    }

    override fun tearDown() {
        scratch.toFile().deleteRecursively()
        super.tearDown()
    }

    fun testTheListingBecomesEmptyFilesAndBadNamesAreRefused() {
        val mirror = Mirror.mint(scratch.resolve("storage"), "room/1", "w")
        assertEquals(scratch.resolve("storage/rooms/room-1/w"), mirror.root)
        assertTrue(Files.isRegularFile(mirror.root.resolve(Mirror.MARKER)))
        val report =
            mirror.materialise(
                listOf(
                    "a.txt",
                    "src/b.kt",
                    "../escape.txt",
                    "/abs.txt",
                    ".idea/workspace.xml",
                    "app.iml",
                    Mirror.MARKER,
                    ".env",
                ),
            )
        assertEquals(listOf("a.txt", "src/b.kt"), report.mirrored)
        assertEquals(listOf("../escape.txt", "/abs.txt", ".idea/workspace.xml", Mirror.MARKER, ".env"), report.refused)
        assertEquals(listOf("app.iml"), report.withheld)
        assertEquals(0L, Files.size(mirror.root.resolve("src/b.kt")))
        assertFalse(Files.exists(scratch.resolve("storage/rooms/room-1/escape.txt")))
    }

    fun testALinkInsideTheMirrorIsNotWrittenThrough() {
        val mirror = Mirror.mint(scratch.resolve("storage"), "r", "w")
        val outside = Files.createDirectories(scratch.resolve("outside"))
        Files.createSymbolicLink(mirror.root.resolve("linked"), outside)
        val report = mirror.materialise(listOf("linked/x.txt"))
        assertEquals(listOf("linked/x.txt"), report.refused)
        assertFalse(Files.exists(outside.resolve("x.txt")))
        assertFalse(mirror.write("linked/x.txt", "text"))
    }

    fun testAMintThroughALinkIsRefused() {
        val outside = Files.createDirectories(scratch.resolve("elsewhere"))
        Files.createDirectories(scratch.resolve("storage"))
        Files.createSymbolicLink(scratch.resolve("storage/rooms"), outside)
        try {
            Mirror.mint(scratch.resolve("storage"), "r", "w")
            fail("minted through a link")
        } catch (expected: java.io.IOException) {
            assertFalse(Files.exists(outside.resolve("r")))
        }
    }

    fun testARepublishRemovesWhatTheRoomNoLongerListsUnlessItIsHeld() {
        val mirror = Mirror.mint(scratch.resolve("storage"), "r", "w")
        mirror.materialise(listOf("a.txt", "b.txt", "c.txt"))
        Files.createDirectories(mirror.root.resolve(".idea"))
        Files.writeString(mirror.root.resolve(".idea/misc.xml"), "<project/>")
        val report = mirror.republish(listOf("a.txt")) { it == "b.txt" }
        assertEquals(listOf("c.txt"), report.removed)
        assertTrue(Files.exists(mirror.root.resolve("b.txt")))
        assertTrue("the IDE's own project files stay", Files.exists(mirror.root.resolve(".idea/misc.xml")))
        mirror.remove()
        assertFalse(Files.exists(mirror.root))
    }
}
