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
 * The folder a host shares, read on a peer's behalf (`DESIGN.md` §4.2). A path a peer names is
 * untrusted: it must pass [Grant.isGrantedPath], and it is then resolved one segment at a time,
 * never following a link. A link, a directory where a file was expected, or a name that is only a
 * case-folded match is refused rather than read.
 */
class GrantFolder(
    val root: Path,
    private val fold: Boolean = Grant.hostFoldsCase(),
) {
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
     * Each segment is checked with links not followed (`lstat`), the leaf is opened with `O_NOFOLLOW`,
     * and the file read is then required to lie under the folder's real path. The IDE installs its own
     * default filesystem provider, which offers no `SecureDirectoryStream`, so the steps cannot be
     * resolved inside the descriptor the step before opened. A residual: a directory on the path
     * swapped for a link between its check and the open can be followed for that one read, and the
     * containment check after the read narrows that to a swap undone within the read. The threat is a
     * process on the host racing the host's own folder.
     */
    fun read(path: String): Read {
        if (!Grant.isGrantedPath(path, fold)) return Read.Refused(Refusal.NOT_GRANTED)
        val segments = path.split('/')
        val realRoot =
            try {
                root.toRealPath()
            } catch (e: IOException) {
                return Read.Refused(Refusal.MISSING)
            }
        var dir = root
        val ignores = ArrayList<Grant.IgnoreSource>()
        readIgnore(
            root.resolve(".git").resolve("info").resolve("exclude"),
        )?.let { ignores.add(Grant.IgnoreSource("", it)) }
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
            if (Grant.isIgnoredPath(ignores, path, false, fold)) return Read.Refused(Refusal.NOT_GRANTED)
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

    // --- the listing ---------------------------------------------------------------------------

    /** The listing this folder grants: the walk of `listing-walk.ts`, links never entered. */
    fun walk(): Grant.WalkResult = Grant.walkListing(PathSource(fold), root)

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
