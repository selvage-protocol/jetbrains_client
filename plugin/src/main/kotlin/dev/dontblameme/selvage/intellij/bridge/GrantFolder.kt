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
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/**
 * The folder a host shares, read on a peer's behalf (`DESIGN.md` §4.2). A path a peer names is
 * untrusted: it must pass [Grant.isGrantedPath], and it is then resolved one segment at a time,
 * each inside the directory the step before it opened, never following a link. A link, a directory
 * where a file was expected, or a name that is only a case-folded match is refused rather than read.
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

    /** The text a peer may be given for [path], LF-only, or why there is none. */
    fun read(path: String): Read {
        if (!Grant.isGrantedPath(path, fold)) return Read.Refused(Refusal.NOT_GRANTED)
        val segments = path.split('/')
        val stream =
            try {
                Files.newDirectoryStream(root)
            } catch (e: IOException) {
                return Read.Refused(Refusal.MISSING)
            }
        if (stream !is SecureDirectoryStream<Path>) {
            stream.close()
            return readWithoutDescriptors(segments)
        }
        return descend(stream, segments)
    }

    private fun descend(
        rootStream: SecureDirectoryStream<Path>,
        segments: List<String>,
    ): Read {
        val opened = ArrayList<SecureDirectoryStream<Path>>()
        opened.add(rootStream)
        try {
            val ignores = ArrayList<Grant.IgnoreSource>()
            rootIgnores(rootStream)?.let { ignores.add(Grant.IgnoreSource("", it)) }
            var dir = rootStream
            for ((index, segment) in segments.withIndex()) {
                val name = Path.of(segment)
                val leaf = index == segments.size - 1
                if (!hasExactChild(dir, segment)) return Read.Refused(Refusal.MISSING)
                textOf(dir, Path.of(".gitignore"), Grant.MAX_GRANT_FILE_BYTES)?.let {
                    ignores.add(Grant.IgnoreSource(segments.subList(0, index).joinToString("/"), it))
                }
                val attributes =
                    attributes(dir, name) ?: return Read.Refused(Refusal.MISSING)
                if (!leaf) {
                    if (!attributes.isDirectory || attributes.isSymbolicLink) return Read.Refused(Refusal.NOT_A_FILE)
                    val next =
                        try {
                            dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
                        } catch (e: IOException) {
                            return Read.Refused(Refusal.NOT_A_FILE)
                        }
                    opened.add(next)
                    dir = next
                    continue
                }
                if (Grant.isIgnoredPath(ignores, segments.joinToString("/"), false, fold)) {
                    return Read.Refused(Refusal.NOT_GRANTED)
                }
                if (!attributes.isRegularFile || attributes.isSymbolicLink) return Read.Refused(Refusal.NOT_A_FILE)
                if (attributes.size() > Grant.MAX_GRANT_FILE_BYTES) return Read.Refused(Refusal.TOO_LARGE)
                val bytes =
                    try {
                        dir
                            .newByteChannel(name, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                            .use { readBounded(it, Grant.MAX_GRANT_FILE_BYTES) }
                    } catch (e: IOException) {
                        return Read.Refused(Refusal.MISSING)
                    } ?: return Read.Refused(Refusal.TOO_LARGE)
                val text = decodableText(bytes) ?: return Read.Refused(Refusal.BINARY)
                return Read.Text(Editing.toCrdt(text))
            }
            return Read.Refused(Refusal.MISSING)
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun attributes(
        dir: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes? =
        try {
            dir
                .getFileAttributeView(
                    name,
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
        } catch (e: IOException) {
            null
        }

    /**
     * Whether [dir] holds an entry spelled exactly [name]: on a filesystem that folds case, opening
     * `.GIT` reaches `.git`, and the rule that refuses one must not be passed by the other.
     */
    private fun hasExactChild(
        dir: SecureDirectoryStream<Path>,
        name: String,
    ): Boolean {
        val listing =
            try {
                dir.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS)
            } catch (e: IOException) {
                return false
            }
        return listing.use { entries -> entries.any { it.fileName?.toString() == name } }
    }

    private fun rootIgnores(rootStream: SecureDirectoryStream<Path>): String? {
        val git = attributes(rootStream, Path.of(".git")) ?: return null
        if (!git.isDirectory || git.isSymbolicLink) return null
        return try {
            rootStream.newDirectoryStream(Path.of(".git"), LinkOption.NOFOLLOW_LINKS).use { gitDir ->
                val gitStream = gitDir as? SecureDirectoryStream<Path> ?: return null
                val info = attributes(gitStream, Path.of("info")) ?: return null
                if (!info.isDirectory || info.isSymbolicLink) return null
                gitStream.newDirectoryStream(Path.of("info"), LinkOption.NOFOLLOW_LINKS).use { infoDir ->
                    textOf(infoDir as SecureDirectoryStream<Path>, Path.of("exclude"), Grant.MAX_GRANT_FILE_BYTES)
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun textOf(
        dir: SecureDirectoryStream<Path>,
        name: Path,
        bound: Int,
    ): String? {
        val attributes = attributes(dir, name) ?: return null
        if (!attributes.isRegularFile || attributes.isSymbolicLink) return null
        return try {
            dir
                .newByteChannel(name, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                .use { readBounded(it, bound) }
                ?.let(::decodableText)
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Where the platform offers no descriptor-relative reads (`SecureDirectoryStream` is absent on
     * Windows), each step is checked by path with links not followed. A residual: a directory swapped
     * for a link between a step's check and the next step's open is followed there; the threat is a
     * local process racing the host's own folder.
     */
    private fun readWithoutDescriptors(segments: List<String>): Read {
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
            val next = dir.resolve(segment)
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
            if (Grant.isIgnoredPath(
                    ignores,
                    segments.joinToString("/"),
                    false,
                    fold,
                )
            ) {
                return Read.Refused(Refusal.NOT_GRANTED)
            }
            if (!attributes.isRegularFile) return Read.Refused(Refusal.NOT_A_FILE)
            if (attributes.size() > Grant.MAX_GRANT_FILE_BYTES) return Read.Refused(Refusal.TOO_LARGE)
            val bytes =
                try {
                    Files
                        .newByteChannel(next, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                        .use { readBounded(it, Grant.MAX_GRANT_FILE_BYTES) }
                } catch (e: IOException) {
                    return Read.Refused(Refusal.MISSING)
                } ?: return Read.Refused(Refusal.TOO_LARGE)
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
