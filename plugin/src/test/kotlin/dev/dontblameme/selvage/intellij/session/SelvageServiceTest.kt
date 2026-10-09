package dev.dontblameme.selvage.intellij.session

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dontblameme.selvage.engine.ErrorSink
import dev.dontblameme.selvage.intellij.TestIde
import dev.dontblameme.selvage.intellij.bridge.GrantFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one failure the join flow swallows: the IDE refusing the guest's mirror. The sentence the
 * person is shown says only that it did not open, so the cause has to be recorded on the error
 * sink, which the plugin backs with the platform's logger.
 */
class SelvageServiceTest : BasePlatformTestCase() {
    private var tolerated: com.intellij.openapi.application.AccessToken? = null

    override fun setUp() {
        tolerated = TestIde.tolerateProductExtensions()
        super.setUp()
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            tolerated?.close()
        }
    }

    fun testAGuestMirrorTheIdeRefusesIsRecordedWithItsCause() {
        val service = SelvageService.get()
        val thrown = IllegalStateException("the IDE refused the folder")
        var reported: Pair<String, Throwable>? = null
        val sink = service.errorSink
        val open = service.openMirror
        service.errorSink = ErrorSink { what, error -> reported = what to error }
        service.openMirror = { throw thrown }
        try {
            assertNull(service.openGuestRoom(Path.of("/no/such/room")))
            assertEquals("the IDE did not open the guest's room folder", reported?.first)
            assertSame(thrown, reported?.second)
        } finally {
            service.errorSink = sink
            service.openMirror = open
        }
    }

    /**
     * A project's folders are its content roots: a project with one shares that folder, and one with
     * several shares every one of them, each under its own name. The names are what a session with
     * more than one root qualifies its paths with.
     */
    fun testHostingSharesEveryContentRootOfTheProject() {
        val base = Files.createDirectories(Path.of(project.basePath!!))
        val second = Files.createDirectories(Files.createTempDirectory("selvage-second-root"))
        val baseFile =
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)
                ?: throw AssertionError("the base folder $base did not appear in the VFS")
        try {
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.contentEntries.toList().forEach { model.removeContentEntry(it) }
                model.addContentEntry(baseFile)
            }
            val alone = SelvageService.get().hostRoots(project)
            assertEquals(listOf(base), alone.map { it.path })
            assertEquals(listOf(base.fileName.toString()), alone.map { it.name })

            ModuleRootModificationUtil.addContentRoot(module, second.toString())
            val both = SelvageService.get().hostRoots(project)
            assertEquals(setOf(base, second), both.map { it.path }.toSet())
            assertEquals(both.map { it.path.fileName.toString() }, both.map { it.name })
        } finally {
            second.toFile().deleteRecursively()
        }
    }

    /**
     * Module roots nest: a root module's folder holds its submodules' folders. Such a project shares
     * the outermost folder alone — one root, its paths unprefixed and listed once — instead of the
     * same file twice under two roots that can carry the same name.
     */
    fun testHostingSharesTheOutermostOfNestedContentRoots() {
        val parent = Files.createDirectories(Files.createTempDirectory("selvage-nested-parent"))
        val child = Files.createDirectories(parent.resolve("child"))
        Files.writeString(parent.resolve("README.md"), "hello\n")
        Files.writeString(child.resolve("deep.txt"), "deep\n")
        val parentFile =
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parent)
                ?: throw AssertionError("the folder $parent did not appear in the VFS")
        try {
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.contentEntries.toList().forEach { model.removeContentEntry(it) }
                model.addContentEntry(parentFile)
            }
            ModuleRootModificationUtil.addContentRoot(module, child.toString())
            val roots = SelvageService.get().hostRoots(project)
            assertEquals(listOf(parent), roots.map { it.path })
            val listing = GrantFolder(roots).walk().paths
            assertEquals(listOf("README.md", "child/deep.txt"), listing)
            assertEquals(listing.distinct().size, listing.size)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
