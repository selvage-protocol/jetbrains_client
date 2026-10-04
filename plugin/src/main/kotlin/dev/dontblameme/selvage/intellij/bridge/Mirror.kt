package dev.dontblameme.selvage.intellij.bridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID

/**
 * A guest's mirror (`DESIGN.md` §4.2): the room's listing as a real directory, so code insight works
 * on ordinary paths. A cache of the room, never a source of truth: an empty file per listed path,
 * filled when a document is fetched, gone when the session is left. Ported from
 * `vscode_client/src/adapter/mirror.ts`; the marker carries the same fields (room, window, pid and
 * when it was made), and a room's copies whose process is gone are pruned as VS Code prunes them.
 *
 * Unlike VS Code's, the copy is one folder below the window's directory, named [FOLDER], and the
 * marker sits beside it rather than in it: the IDE names a project after its folder, so the guest's
 * window reads as a session rather than a window id, and the marker stays out of the project view.
 */
class Mirror private constructor(
    val room: String,
    val window: String,
    /** The window's directory: the marker and the copy, and what [remove] deletes. */
    val home: Path,
    private val windows: Boolean,
) {
    /** The copy itself, the folder the guest's project is opened on. */
    val root: Path = home.resolve(FOLDER)

    data class Report(
        val mirrored: List<String>,
        val refused: List<String>,
        val withheld: List<String>,
        val overCapacity: List<String>,
        val removed: List<String> = emptyList(),
    )

    /** Whether [path] is one this mirror would hold a document at. */
    fun accepts(path: String): Boolean = isMirrorable(path) && !isWorkspaceConfigPath(path)

    /**
     * A granted path that names a plain file under the root on this OS, and not the marker's name:
     * VS Code's copy holds its marker at its root, and a guest sees the same files in either client.
     */
    private fun isMirrorable(path: String): Boolean =
        Grant.isGrantedPath(path) && !path.equals(MARKER, ignoreCase = true) && RoomPaths.isLocalPath(path, windows)

    /** The file a room path lives at, appended one checked segment at a time, or null for a path this mirror refuses. */
    fun fileOf(path: String): Path? = if (accepts(path)) RoomPaths.under(root, path, windows) else null

    /** Writes one empty file per listed path, with the directories on the way, never through a link. */
    fun materialise(listing: List<String>): Report {
        val mirrored = ArrayList<String>()
        val refused = ArrayList<String>()
        val withheld = ArrayList<String>()
        val overCapacity = ArrayList<String>()
        listing.forEachIndexed { index, path ->
            when {
                index >= Grant.MAX_GRANT_PATHS -> overCapacity.add(path)
                !isMirrorable(path) -> refused.add(path)
                isWorkspaceConfigPath(path) -> withheld.add(path)
                materialiseOne(path) -> mirrored.add(path)
                else -> refused.add(path)
            }
        }
        return Report(mirrored, refused, withheld, overCapacity)
    }

    /**
     * A republished listing: materialise, then remove what it no longer names unless [held] keeps it.
     * Up to a whole listing of files and a walk of the mirror, so never on the event thread.
     */
    fun republish(
        listing: List<String>,
        held: (String) -> Boolean,
    ): Report {
        if (ApplicationManager.getApplication()?.isDispatchThread == true) {
            Logger.getInstance(Mirror::class.java).error("a listing was written into the mirror on the event thread")
        }
        val applied = materialise(listing)
        val keep = listing.toHashSet()
        val removed = ArrayList<String>()
        for (relative in filesUnder()) {
            if (relative in keep || held(relative) || isWorkspaceConfigPath(relative)) continue
            val file = RoomPaths.under(root, relative, windows) ?: continue
            try {
                Files.deleteIfExists(file)
                removed.add(relative)
            } catch (e: IOException) {
                continue
            }
        }
        return applied.copy(removed = removed)
    }

    /** Whether [path] resolves under the root through plain directories only, to a plain file or nothing. */
    fun plainPath(path: String): Boolean {
        val segments = path.split('/')
        var dir = root
        for (segment in segments.dropLast(1)) {
            dir = RoomPaths.child(dir, segment, windows) ?: return false
            val attributes = attributes(dir) ?: continue
            if (!attributes.isDirectory || attributes.isSymbolicLink) return false
        }
        val leaf = attributes(RoomPaths.child(dir, segments.last(), windows) ?: return false) ?: return true
        return leaf.isRegularFile && !leaf.isSymbolicLink
    }

    /**
     * Deletes the mirror, its marker with it. Links are not followed: a link in the copy (a package
     * linked in by hand) is removed as a link, and what it points at is left alone. What cannot be
     * deleted is left in place and the rest still goes.
     */
    fun remove() = deleteTree(home)

    private fun materialiseOne(path: String): Boolean {
        val segments = path.split('/')
        var dir = root
        for (segment in segments.dropLast(1)) {
            dir = RoomPaths.child(dir, segment, windows) ?: return false
            val attributes = attributes(dir)
            if (attributes == null) {
                try {
                    Files.createDirectory(dir)
                } catch (e: IOException) {
                    if (!isPlainDirectory(dir)) return false
                }
            } else if (!attributes.isDirectory || attributes.isSymbolicLink) {
                return false
            }
        }
        val file = RoomPaths.child(dir, segments.last(), windows) ?: return false
        val existing = attributes(file)
        if (existing != null) return existing.isRegularFile && !existing.isSymbolicLink
        return try {
            Files
                .newByteChannel(
                    file,
                    setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                ).close()
            true
        } catch (e: FileAlreadyExistsException) {
            isPlainFile(file)
        } catch (e: IOException) {
            false
        }
    }

    private fun filesUnder(): List<String> {
        val found = ArrayList<String>()

        fun walk(dir: Path) {
            val children =
                try {
                    Files.newDirectoryStream(dir).use { it.toList() }
                } catch (e: IOException) {
                    return
                }
            for (child in children) {
                val attributes = attributes(child) ?: continue
                if (attributes.isDirectory && !attributes.isSymbolicLink) {
                    walk(child)
                } else if (attributes.isRegularFile) {
                    found.add(root.relativize(child).joinToString("/"))
                }
            }
        }
        walk(root)
        return found
    }

    companion object {
        const val MARKER = ".selvage-mirror.json"

        /** The copy's folder under the window's directory, and so the name of the guest's project. */
        const val FOLDER = "Selvage session"

        /** A room id as one path segment, the way the other clients' mirrors name it. */
        fun sanitiseRoom(room: String): String = room.replace(Regex("[^A-Za-z0-9_-]"), "-")

        /**
         * Whether a room path names what IntelliJ reads as the project's own configuration: anything under
         * `.idea` or `.run`, a module, project or workspace file, or a run configuration stored as a
         * `*.run.xml` file, which the IDE picks up from anywhere in the project. The mirror is opened as a
         * project, and a host's text there would be applied rather than shown, so it is withheld. The
         * comparison folds case and ignores trailing dots and spaces, as the guest's filesystem may.
         */
        fun isWorkspaceConfigPath(path: String): Boolean {
            val segments = path.split('/').map { it.lowercase(Locale.ROOT).replace(Regex("[. ]+$"), "") }
            val leaf = segments.size - 1
            return segments.withIndex().any { (index, segment) ->
                if (index < leaf) {
                    segment == ".idea" || segment == ".run"
                } else {
                    WORKSPACE_CONFIG_SUFFIXES.any { segment.endsWith(it) }
                }
            }
        }

        private val WORKSPACE_CONFIG_SUFFIXES = listOf(".iml", ".ipr", ".iws", ".run.xml")

        /**
         * Mints `<storage>/rooms/<room>/<window>/`, its marker and the copy's folder in it, refusing to
         * mint through a link.
         */
        fun mint(
            storage: Path,
            room: String,
            window: String = UUID.randomUUID().toString(),
            windows: Boolean = RoomPaths.isWindows(),
            pid: Long = ProcessHandle.current().pid(),
        ): Mirror {
            val segment = sanitiseRoom(room)
            require(segment.isNotEmpty()) { "cannot mirror a room with no name in it" }
            val rooms = storage.resolve("rooms")
            val roomDir = rooms.resolve(segment)
            val home = roomDir.resolve(window)
            val root = home.resolve(FOLDER)
            for (dir in listOf(storage, rooms, roomDir, home, root)) {
                val attributes = attributes(dir) ?: continue
                if (!attributes.isDirectory || attributes.isSymbolicLink) {
                    throw IOException("refusing to mirror under $dir: not a plain directory")
                }
            }
            Files.createDirectories(root)
            val created =
                jsonString(
                    java.time.Instant
                        .now()
                        .toString(),
                )
            val marker = "{\"room\":${jsonString(
                room,
            )},\"window\":${jsonString(window)},\"pid\":$pid,\"created\":$created}\n"
            Files.writeString(home.resolve(MARKER), marker)
            return Mirror(room, window, home, windows)
        }

        /**
         * Removes the room's dead copies: each window directory under `<storage>/rooms/<room>/`, other
         * than [currentWindow], whose marker this client wrote for [room] and whose process is gone. A
         * directory with no marker, one it cannot read, or a link is not positively a copy and stays.
         * Returns the windows removed.
         */
        fun pruneRoom(
            storage: Path,
            room: String,
            currentWindow: String,
        ): List<String> {
            val roomDir = storage.resolve("rooms").resolve(sanitiseRoom(room))
            if (!isPlainDirectory(storage.resolve("rooms")) || !isPlainDirectory(roomDir)) return emptyList()
            val entries =
                try {
                    Files.newDirectoryStream(roomDir).use { it.toList() }
                } catch (e: IOException) {
                    return emptyList()
                }
            val removed = ArrayList<String>()
            for (dir in entries) {
                if (!isPlainDirectory(dir)) continue
                val marker = readMarker(dir) ?: continue
                if (marker.room != room || marker.window == currentWindow) continue
                if (ProcessHandle.of(marker.pid).map { it.isAlive }.orElse(false)) continue
                deleteTree(dir)
                removed.add(marker.window)
            }
            return removed
        }

        private class Marker(
            val room: String,
            val window: String,
            val pid: Long,
        )

        /** The marker at [root] when it is one this client (or VS Code) wrote, read without following a link. */
        private fun readMarker(root: Path): Marker? {
            val file = root.resolve(MARKER)
            if (!isPlainFile(file)) return null
            return try {
                val text =
                    Files
                        .newByteChannel(file, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                        .use { GrantFolder.readBounded(it, MAX_MARKER_BYTES) } ?: return null
                val json =
                    dev.dontblameme.selvage.canonical.CanonicalJson
                        .parseObject(String(text, Charsets.UTF_8))
                val pid = (json["pid"] as? dev.dontblameme.selvage.canonical.JsonValue.Number)?.count() ?: return null
                if (json.string("created") == null) return null
                Marker(json.string("room") ?: return null, json.string("window") ?: return null, pid)
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                null
            }
        }

        private const val MAX_MARKER_BYTES = 64 * 1024

        /** Deletes [top] and what is under it, links removed as links and never followed. */
        private fun deleteTree(top: Path) {
            if (!isPlainDirectory(top)) return
            Files.walkFileTree(
                top,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        deleteQuietly(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        deleteQuietly(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        deleteQuietly(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        private fun deleteQuietly(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (e: IOException) {
                return
            }
        }

        private fun jsonString(text: String): String =
            buildString {
                append('"')
                for (c in text) {
                    when {
                        c == '"' -> append("\\\"")
                        c == '\\' -> append("\\\\")
                        c < ' ' -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                }
                append('"')
            }

        private fun attributes(path: Path): BasicFileAttributes? =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: IOException) {
                null
            }

        private fun isPlainDirectory(path: Path): Boolean =
            attributes(path)?.let { it.isDirectory && !it.isSymbolicLink } ?: false

        private fun isPlainFile(path: Path): Boolean =
            attributes(path)?.let { it.isRegularFile && !it.isSymbolicLink } ?: false
    }
}
