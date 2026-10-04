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

    fun testRemovingTheMirrorRemovesALinkInItAndNotWhatItPointsAt() {
        val mirror = Mirror.mint(scratch.resolve("storage"), "r", "w")
        mirror.materialise(listOf("src/a.txt"))
        val outside = Files.createDirectories(scratch.resolve("dev/foo"))
        Files.writeString(outside.resolve("index.js"), "kept\n")
        val outsideFile = Files.writeString(scratch.resolve("notes.txt"), "kept\n")
        Files.createDirectories(mirror.root.resolve("node_modules"))
        Files.createSymbolicLink(mirror.root.resolve("node_modules/foo"), outside)
        Files.createSymbolicLink(mirror.root.resolve("src/notes.txt"), outsideFile)
        mirror.remove()
        assertFalse("the mirror is gone", Files.exists(mirror.root, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("kept\n", Files.readString(outside.resolve("index.js")))
        assertEquals("kept\n", Files.readString(outsideFile))
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

    /** The shapes Windows reads as somewhere else: a drive, a share, a root, a device, a stream, a stripped name. */
    private val windowsShapes =
        listOf(
            "C:/Users/me/.gitconfig",
            "C:",
            "D:/x.txt",
            "c:x.txt",
            "src/C:/x.txt",
            "src/C:",
            "\\\\server\\share\\x.txt",
            "//server/share/x.txt",
            "/abs.txt",
            "\\abs.txt",
            "a\\b.txt",
            "CON",
            "con.txt",
            "src/NUL",
            "src/aux.c",
            "LPT1.log",
            "COM9",
            "COM\u00b9.txt",
            "CONIN$",
            "conout\$.txt",
            "trailing.",
            "src/trailing ",
            "a<b.txt",
            "a>b.txt",
            "a\"b.txt",
            "a|b.txt",
            "a?b.txt",
            "a*b.txt",
            "file.txt::\$DATA",
            "..",
            "a/../b.txt",
            "./a.txt",
        )

    fun testEveryWindowsShapeIsRefusedSegmentBySegment() {
        for (shape in windowsShapes) {
            assertFalse("a Windows guest takes $shape as a local path", RoomPaths.isLocalPath(shape, windows = true))
        }
        for (fine in listOf("src/main.kt", "console.txt", "COM10.txt", "a.b.c", "LPT.txt", "con-fig/x.txt")) {
            assertTrue("a Windows guest refuses $fine", RoomPaths.isLocalPath(fine, windows = true))
        }
    }

    fun testAWindowsMirrorNeitherResolvesNorWritesThoseShapes() {
        val mirror = Mirror.mint(scratch.resolve("storage"), "r", "w", windows = true)
        for (shape in windowsShapes) {
            assertFalse("the mirror accepts $shape", mirror.accepts(shape))
            assertNull("the mirror resolves $shape", mirror.fileOf(shape))
            assertFalse("the mirror calls $shape plain", mirror.plainPath(shape))
        }
        val report = mirror.materialise(windowsShapes + "src/main.kt")
        assertEquals(listOf("src/main.kt"), report.mirrored)
        assertEquals(windowsShapes, report.refused)
        val written = Files.walk(scratch).use { walk -> walk.filter { Files.isRegularFile(it) }.toList() }
        assertEquals(
            setOf(mirror.root.resolve(Mirror.MARKER), mirror.root.resolve("src/main.kt")),
            written.toSet(),
        )
    }

    fun testOnThisHostAPathIsOnlyEverAppendedToTheRoot() {
        val root = Files.createDirectories(scratch.resolve("root"))
        assertEquals(root.resolve("a").resolve("b.txt"), RoomPaths.under(root, "a/b.txt", windows = false))
        for (shape in listOf("/etc/passwd", "../x", "a/../../x", "a/./b", ".", "a//b", "", "a/")) {
            assertNull("$shape resolved", RoomPaths.under(root, shape, windows = false))
        }
        assertNull(RoomPaths.child(root, "..", windows = false))
        assertNull(RoomPaths.child(root, "/etc", windows = false))
        val mirror = Mirror.mint(scratch.resolve("storage"), "r", "w", windows = false)
        assertNull(mirror.fileOf("/etc/passwd"))
        assertNull(mirror.fileOf("../w2/x.txt"))
        assertNull("the marker in another case is still the marker", mirror.fileOf(".SELVAGE-MIRROR.JSON"))
        assertEquals(mirror.root.resolve("C:").resolve("x.txt"), mirror.fileOf("C:/x.txt"))
    }
}
