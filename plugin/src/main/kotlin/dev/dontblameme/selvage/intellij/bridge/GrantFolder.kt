package dev.dontblameme.selvage.intellij.bridge

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * The folders a host shares, read on a peer's behalf (`DESIGN.md` §4.2). A path a peer names is
 * untrusted: it must name one of the roots — the path's own first segment when there is more than
 * one — and [Grant.isGrantedPath] then holds for the path inside it, which is resolved one segment
 * at a time, never following a link. A link, a directory where a file was expected, or a name that
 * is only a case-folded match is refused rather than read.
 *
 * One root is the folder's own: its paths carry no prefix and the listing and the reads are what a
 * single-folder session has always served. Two or more put each root's name in front of its paths,
 * so two roots holding the same relative path are two room paths (`listing-walk.ts`).
 */
class GrantFolder(
    val roots: List<Root>,
    private val fold: Boolean = Grant.hostFoldsCase(),
) {
    /**
     * One folder a host shares: the directory, and the name its paths carry when the session
     * shares more than one. The name is the folder's own, as `WorkspaceFolder.name` is.
     */
    data class Root(
        val path: Path,
        val name: String,
    ) {
        constructor(path: Path) : this(path, path.fileName?.toString() ?: "")
    }

    /** The one folder a single-root session shares. */
    constructor(root: Path, fold: Boolean = Grant.hostFoldsCase()) : this(listOf(Root(root)), fold)

    enum class Refusal { NOT_GRANTED, MISSING, NOT_A_FILE, TOO_LARGE, BINARY }

    sealed interface Read {
        data class Text(
            val text: String,
        ) : Read

        data class Refused(
            val cause: Refusal,
        ) : Read
    }

    /**
     * The text a peer may be given for [path], LF-only, or why there is none.
     *
     * The path is first resolved to the root it names and its path inside it: a path no root holds,
     * or one whose first segment names no root when the session shares several, is not granted at
     * all. Each segment inside the root is then checked with links not followed (`lstat`), the leaf
     * is opened with `O_NOFOLLOW`, and the file read is then required to lie under the root's real
     * path. The IDE installs its own default filesystem provider, which offers no
     * `SecureDirectoryStream`, so the steps cannot be resolved inside the descriptor the step before
     * opened. A residual: a directory on the path swapped for a link between its check and the open
     * can be followed for that one read, and the containment check after the read narrows that to a
     * swap undone within the read. A second: a file swapped for a named pipe between its check and
     * the open blocks the open until a writer comes, and the calling thread with it, since Java
     * opens with no `O_NONBLOCK`. The threat in both is a process on the host racing the host's own
     * folder.
     */
    fun read(path: String): Read {
        val located = locate(path) ?: return Read.Refused(Refusal.NOT_GRANTED)
        val relative = located.relative
        if (!Grant.isGrantedPath(relative, fold)) return Read.Refused(Refusal.NOT_GRANTED)
        val segments = relative.split('/')
        val root = located.root.path
        val realRoot =
            try {
                root.toRealPath()
            } catch (e: IOException) {
                return Read.Refused(Refusal.MISSING)
            }
        var dir = root
        val ignores = ArrayList<Grant.IgnoreSource>()
        rootExclude(root)?.let { ignores.add(Grant.IgnoreSource("", it)) }
        for ((index, segment) in segments.withIndex()) {
            val exact =
                try {
                    Files.list(dir).use { list -> list.anyMatch { it.fileName.toString() == segment } }
                } catch (e: IOException) {
                    false
                }
            if (!exact) return Read.Refused(Refusal.MISSING)
            readIgnore(dir.resolve(".gitignore"))?.let {
                ignores.add(Grant.IgnoreSource(segments.subList(0, index).joinToString("/"), it))
            }
            val next = RoomPaths.child(dir, segment) ?: return Read.Refused(Refusal.NOT_GRANTED)
            val attributes =
                try {
                    Files.readAttributes(next, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (e: IOException) {
                    return Read.Refused(Refusal.MISSING)
                }
            if (index < segments.size - 1) {
                if (!attributes.isDirectory || attributes.isSymbolicLink) return Read.Refused(Refusal.NOT_A_FILE)
                dir = next
                continue
            }
            if (Grant.isIgnoredPath(ignores, relative, false, fold)) return Read.Refused(Refusal.NOT_GRANTED)
            if (!attributes.isRegularFile || attributes.isSymbolicLink) return Read.Refused(Refusal.NOT_A_FILE)
            if (attributes.size() > Grant.MAX_GRANT_FILE_BYTES) return Read.Refused(Refusal.TOO_LARGE)
            val bytes =
                try {
                    Files
                        .newByteChannel(next, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                        .use { readBounded(it, Grant.MAX_GRANT_FILE_BYTES) }
                } catch (e: IOException) {
                    return Read.Refused(Refusal.MISSING)
                } ?: return Read.Refused(Refusal.TOO_LARGE)
            val contained =
                try {
                    next.toRealPath().startsWith(realRoot)
                } catch (e: IOException) {
                    false
                }
            if (!contained) return Read.Refused(Refusal.NOT_A_FILE)
            return decodableText(bytes)?.let { Read.Text(Editing.toCrdt(it)) } ?: Read.Refused(Refusal.BINARY)
        }
        return Read.Refused(Refusal.MISSING)
    }

    /**
     * The file [path] names in its root, when each step to it is a plain directory and it is a plain
     * file, judged from the steps' attributes alone and reading nothing. For opening a listed path in
     * the host's own editor; the editor opens it by path afterwards, so a swap in between is the same
     * residual [read] states.
     */
    fun plainFile(path: String): Path? {
        val located = locate(path) ?: return null
        if (!Grant.isGrantedPath(located.relative, fold)) return null
        val segments = located.relative.split('/')
        var at = located.root.path
        for ((index, segment) in segments.withIndex()) {
            at = RoomPaths.child(at, segment) ?: return null
            val attributes =
                try {
                    Files.readAttributes(at, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (e: IOException) {
                    return null
                }
            if (if (index < segments.size - 1) !attributes.isDirectory else !attributes.isRegularFile) return null
        }
        return at
    }

    /**
     * `.git/info/exclude`, read only through a plain `.git` and a plain `info`, as the walk reads it:
     * a linked `.git` brings no rules from outside the folder.
     */
    private fun rootExclude(root: Path): String? {
        var dir = root
        for (segment in listOf(".git", "info")) {
            dir = dir.resolve(segment)
            val attributes =
                try {
                    Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (e: IOException) {
                    return null
                }
            if (!attributes.isDirectory || attributes.isSymbolicLink) return null
        }
        return readIgnore(dir.resolve("exclude"))
    }

    private fun readIgnore(file: Path): String? =
        try {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) {
                null
            } else {
                Files
                    .newByteChannel(file, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                    .use { readBounded(it, Grant.MAX_GRANT_FILE_BYTES) }
                    ?.let(::decodableText)
            }
        } catch (e: IOException) {
            null
        }

    // --- the root a path names ---

    /**
     * The root [path] belongs to and the path inside it, or null when no root holds it. A single
     * root takes the path as its own; several put the root's name in front of every path, so the
     * first segment names the root and nothing else does (`listing-walk.ts`).
     */
    private class Located(
        val root: Root,
        val relative: String,
    )

    private fun locate(path: String): Located? {
        roots.singleOrNull()?.let { return if (path.isEmpty()) null else Located(it, path) }
        val slash = path.indexOf('/')
        if (slash <= 0) return null
        val root = roots.firstOrNull { it.name == path.substring(0, slash) } ?: return null
        return Located(root, path.substring(slash + 1))
    }

    /**
     * The room path a file under one of the roots is shared as, or null when it is under none. The
     * roots are the ones the session captured, so a folder the window is opened on afterwards is not
     * quietly added to the grant.
     */
    fun roomPathOf(file: Path): String? {
        for (root in roots) {
            val relative = under(root.path, file) ?: continue
            return if (roots.size > 1) "${root.name}/$relative" else relative
        }
        return null
    }

    /**
     * The file [path] names under its root, when the grant shares that name and [RoomPaths] allows it.
     * Reading nothing: for a path already in the room, to hand the host its own file.
     */
    fun local(path: String): Path? {
        val located = locate(path) ?: return null
        if (!Grant.isGrantedPath(located.relative, fold)) return null
        return RoomPaths.under(located.root.path, located.relative)
    }

    /** [file] as it reads inside [base], or null when it is not inside it or is [base] itself. */
    private fun under(
        base: Path,
        file: Path,
    ): String? {
        if (!file.startsWith(base)) return null
        val relative = base.relativize(file).joinToString("/")
        return relative.ifEmpty { null }
    }

    // --- the listing ---------------------------------------------------------------------------

    /** The listing every root grants: the walk of `listing-walk.ts`, links never entered. */
    fun walk(): Grant.WalkResult =
        Grant.walkListing(PathSource(fold), roots.map { Grant.ListingRoot(it.path, it.name) })

    private class PathSource(
        override val fold: Boolean,
    ) : Grant.WalkSource<Path> {
        private fun attributes(path: Path): BasicFileAttributes? =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: IOException) {
                null
            }

        override fun entries(dir: Path): List<Grant.WalkEntry>? =
            try {
                Files.newDirectoryStream(dir).use { stream: DirectoryStream<Path> ->
                    stream.map { child ->
                        val attributes = attributes(child)
                        val kind =
                            when {
                                attributes == null || attributes.isSymbolicLink -> Grant.WalkEntry.Kind.OTHER
                                attributes.isRegularFile -> Grant.WalkEntry.Kind.FILE
                                attributes.isDirectory -> Grant.WalkEntry.Kind.DIRECTORY
                                else -> Grant.WalkEntry.Kind.OTHER
                            }
                        Grant.WalkEntry(child.fileName.toString(), kind)
                    }
                }
            } catch (e: IOException) {
                null
            }

        override fun ignoreText(
            dir: Path,
            entries: List<Grant.WalkEntry>,
        ): String? =
            if (entries.any { it.name == ".gitignore" && it.kind == Grant.WalkEntry.Kind.FILE }) {
                readText(dir.resolve(".gitignore"))
            } else {
                null
            }

        override fun shareable(
            dir: Path,
            name: String,
        ): Boolean {
            val attributes = attributes(dir.resolve(name)) ?: return false
            return attributes.isRegularFile && attributes.size() <= Grant.MAX_GRANT_FILE_BYTES
        }

        override fun child(
            dir: Path,
            name: String,
        ): Path? {
            val child = dir.resolve(name)
            val attributes = attributes(child) ?: return null
            return if (attributes.isDirectory && !attributes.isSymbolicLink) child else null
        }

        override fun rootIgnores(
            dir: Path,
            entries: List<Grant.WalkEntry>,
        ): List<Grant.IgnoreSource> {
            if (entries.none { it.name == ".git" && it.kind == Grant.WalkEntry.Kind.DIRECTORY }) return emptyList()
            val info = dir.resolve(".git").resolve("info")
            val infoAttributes = attributes(info) ?: return emptyList()
            if (!infoAttributes.isDirectory || infoAttributes.isSymbolicLink) return emptyList()
            return readText(info.resolve("exclude"))?.let { listOf(Grant.IgnoreSource("", it)) } ?: emptyList()
        }

        private fun readText(file: Path): String? {
            val attributes = attributes(file) ?: return null
            if (!attributes.isRegularFile) return null
            return try {
                Files
                    .newByteChannel(file, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                    .use { readBounded(it, Grant.MAX_GRANT_FILE_BYTES) }
                    ?.let(::decodableText)
            } catch (e: IOException) {
                null
            }
        }
    }

    companion object {
        /** At most [bound] bytes from [channel], or null when it holds more. */
        fun readBounded(
            channel: SeekableByteChannel,
            bound: Int,
        ): ByteArray? {
            val buffer = ByteBuffer.allocate(bound + 1)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            if (buffer.position() > bound) return null
            return buffer.array().copyOf(buffer.position())
        }

        /** Strict UTF-8 with no NUL byte, a leading byte-order mark dropped as `TextDecoder` drops it. */
        fun decodableText(bytes: ByteArray): String? {
            if (bytes.any { it.toInt() == 0 }) return null
            val text =
                try {
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (e: CharacterCodingException) {
                    return null
                }
            return text.removePrefix(0xfeff.toChar().toString())
        }
    }
}
