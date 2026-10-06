package dev.dontblameme.selvage.intellij.bridge

import java.util.Locale

/**
 * The grant's shape, ported from `vscode_client/src/bridge/grant.ts` and `listing-walk.ts`: which
 * paths a host may publish or serve, the folder's ignore files, and the walk that builds a listing.
 * The lists are the VS Code client's, so a session shares the same names from either editor;
 * `BridgeParityTest` reads them out of the TypeScript source and fails on a difference.
 */
object Grant {
    const val MAX_GRANT_PATHS = 100_000
    const val MAX_GRANT_LISTING_BYTES = 4 * 1024 * 1024
    const val MAX_GRANT_NODES = 2 * MAX_GRANT_PATHS
    const val MAX_GRANT_PATH_BYTES = 4096
    const val MAX_GRANT_FILE_BYTES = 1024 * 1024

    val GRANT_EXCLUDED_DIRS: List<String> =
        listOf(
            ".git",
            ".hg",
            ".svn",
            ".gradle",
            ".idea",
            ".vscode-test",
            "node_modules",
            "vendor",
            "target",
            "dist",
            "build",
            "out",
            "coverage",
            ".next",
            ".cache",
            "__pycache__",
            ".venv",
            "venv",
            ".tox",
            ".terraform",
            "Pods",
            "_build",
            "deps",
            ".dart_tool",
        )

    val GRANT_EXCLUDED_FILES: List<String> =
        listOf(
            ".envrc",
            ".npmrc",
            ".pypirc",
            ".netrc",
            "_netrc",
            ".git-credentials",
            ".pgpass",
            ".htpasswd",
        )

    val GRANT_SECRET_DIRS: List<String> = listOf(".aws", ".ssh", ".gnupg")

    val GRANT_SECRET_KEY_PREFIXES: List<String> = listOf("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519")

    val GRANT_SECRET_KEY_SUFFIXES: List<String> =
        listOf(
            ".pem",
            ".key",
            ".p12",
            ".pfx",
            ".jks",
            ".keystore",
            ".ppk",
            ".tfstate",
            ".tfstate.backup",
        )

    val GRANT_BINARY_SUFFIXES: List<String> =
        listOf(
            ".3gp",
            ".7z",
            ".a",
            ".aac",
            ".aif",
            ".aiff",
            ".apk",
            ".asar",
            ".avif",
            ".avi",
            ".avro",
            ".bin",
            ".bmp",
            ".bz2",
            ".cab",
            ".class",
            ".dll",
            ".dmg",
            ".doc",
            ".docx",
            ".dylib",
            ".ear",
            ".elf",
            ".eot",
            ".epub",
            ".exe",
            ".flac",
            ".flv",
            ".gif",
            ".gguf",
            ".gz",
            ".h5",
            ".hdf5",
            ".heic",
            ".heif",
            ".ico",
            ".iso",
            ".jar",
            ".jpeg",
            ".jp2",
            ".jpg",
            ".jxl",
            ".ko",
            ".lib",
            ".lz4",
            ".m4a",
            ".m4v",
            ".mdb",
            ".mkv",
            ".mov",
            ".mp3",
            ".mp4",
            ".mpeg",
            ".mpg",
            ".node",
            ".npy",
            ".npz",
            ".nupkg",
            ".o",
            ".obj",
            ".odp",
            ".ods",
            ".odt",
            ".oga",
            ".ogg",
            ".onnx",
            ".opus",
            ".otf",
            ".parquet",
            ".pb",
            ".pickle",
            ".pkl",
            ".png",
            ".ppt",
            ".pptx",
            ".psd",
            ".pyd",
            ".pyc",
            ".pyo",
            ".rar",
            ".rlib",
            ".rmeta",
            ".rpm",
            ".safetensors",
            ".so",
            ".sqlite",
            ".sqlite3",
            ".svgz",
            ".tar",
            ".tflite",
            ".tgz",
            ".tif",
            ".tiff",
            ".ttc",
            ".ttf",
            ".vsix",
            ".war",
            ".wasm",
            ".wav",
            ".webm",
            ".webp",
            ".whl",
            ".wmv",
            ".woff",
            ".woff2",
            ".xls",
            ".xlsx",
            ".xz",
            ".zip",
            ".zst",
        )

    /** Whether this host's filesystem folds case: macOS and Windows do, Linux does not; unknown folds. */
    fun hostFoldsCase(osName: String = System.getProperty("os.name") ?: ""): Boolean {
        val os = osName.lowercase(Locale.ROOT)
        return os == "" || os.startsWith("mac") || os.startsWith("windows")
    }

    fun overFileBound(text: String): Boolean {
        if (text.length > MAX_GRANT_FILE_BYTES) return true
        if (text.length * 3 <= MAX_GRANT_FILE_BYTES) return false
        return text.toByteArray(Charsets.UTF_8).size > MAX_GRANT_FILE_BYTES
    }

    private fun hasUnsafeChar(segment: String): Boolean {
        var index = 0
        while (index < segment.length) {
            val code = segment.codePointAt(index)
            index += Character.charCount(code)
            if (code < 0x20 || code in 0x7f..0x9f) return true
            if (code == 0x61c || code == 0x200e || code == 0x200f || code == 0x2028 || code == 0x2029 ||
                code in 0x202a..0x202e || code in 0x2066..0x2069 || code == 0xfeff
            ) {
                return true
            }
        }
        return false
    }

    private fun isEnvSecret(leaf: String): Boolean {
        if (leaf == ".env" || leaf == ".env.local") return true
        return leaf.startsWith(".env.") && leaf.endsWith(".local") && leaf.length > ".env.local".length
    }

    private fun isSecretKeyName(folded: String): Boolean =
        GRANT_SECRET_KEY_PREFIXES.any { folded.startsWith(it) } ||
            GRANT_SECRET_KEY_SUFFIXES.any { folded.length > it.length && folded.endsWith(it) }

    fun isBinaryNamedPath(path: String): Boolean {
        val leaf = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return GRANT_BINARY_SUFFIXES.any { leaf.length > it.length && leaf.endsWith(it) }
    }

    /**
     * Whether a folder-relative, `/`-separated path is one a host may publish or serve: no empty,
     * `.` or `..` segment, no backslash, no excluded directory or secret name. An accident guard
     * against sharing the wrong file, not a boundary against a host sharing its own disk.
     */
    fun isGrantedPath(
        path: String,
        fold: Boolean = hostFoldsCase(),
    ): Boolean {
        if (path.jsTrim() == "" || path.contains('\\')) return false
        if (path.toByteArray(Charsets.UTF_8).size > MAX_GRANT_PATH_BYTES) return false
        val segments = path.split('/')
        val leaf = segments.size - 1
        for ((index, segment) in segments.withIndex()) {
            if (segment == "" || segment == "." || segment == "..") return false
            if (hasUnsafeChar(segment)) return false
            val name = if (fold) segment.lowercase(Locale.ROOT) else segment
            if (name in GRANT_EXCLUDED_DIRS || name in GRANT_SECRET_DIRS) return false
            if (index == leaf &&
                (name in GRANT_EXCLUDED_FILES || isEnvSecret(name) || isSecretKeyName(name))
            ) {
                return false
            }
        }
        return true
    }

    fun sortGrant(paths: Iterable<String>): List<String> = paths.sorted()

    /** What a window offers: the grant and the room's open set, less names that declare a binary format. */
    fun grantUnion(
        grant: Iterable<String>,
        documents: Iterable<String>,
    ): List<String> = sortGrant((grant + documents).filter { !isBinaryNamedPath(it) }.toSet())

    // --- the folder's ignore files ----------------------------------------------------------

    /** One ignore file a host read: the directory it governs (`""` is the root) and its text. */
    data class IgnoreSource(
        val dir: String,
        val text: String,
    )

    fun isIgnoredPath(
        sources: List<IgnoreSource>,
        path: String,
        isDirectory: Boolean,
        fold: Boolean = hostFoldsCase(),
    ): Boolean {
        val compiled = sources.map { Ignore.compiled(it, fold) }
        val segments = (if (fold) path.lowercase(Locale.ROOT) else path).split('/')
        for (depth in 1 until segments.size) {
            if (Ignore.decides(compiled, segments, depth, true)) return true
        }
        return Ignore.decides(compiled, segments, segments.size, isDirectory)
    }

    // --- the walk ------------------------------------------------------------------------------

    enum class Cut { PATHS, BYTES, BUDGET }

    data class WalkEntry(
        val name: String,
        val kind: Kind,
    ) {
        enum class Kind { FILE, DIRECTORY, OTHER }
    }

    /** What a walk reads: one host's directories, through whatever handle it opens them by. */
    interface WalkSource<D> {
        val fold: Boolean

        fun entries(dir: D): List<WalkEntry>?

        fun ignoreText(
            dir: D,
            entries: List<WalkEntry>,
        ): String?

        fun shareable(
            dir: D,
            name: String,
        ): Boolean

        fun child(
            dir: D,
            name: String,
        ): D?

        fun rootIgnores(
            dir: D,
            entries: List<WalkEntry>,
        ): List<IgnoreSource>
    }

    data class WalkResult(
        val paths: List<String>,
        val entered: List<String>,
        val cut: Cut?,
    )

    private class WalkState {
        val paths = ArrayList<String>()
        val entered = ArrayList<String>()
        var bytes = 0L
        var nodes = MAX_GRANT_NODES
        var cut: Cut? = null
    }

    fun <D> walkListing(
        source: WalkSource<D>,
        root: D,
    ): WalkResult {
        val state = WalkState()
        walk(source, root, "", emptyList(), state)
        return WalkResult(sortGrant(state.paths), state.entered, state.cut)
    }

    private fun <D> walk(
        source: WalkSource<D>,
        dir: D,
        relative: String,
        inherited: List<IgnoreSource>,
        state: WalkState,
    ) {
        if (state.cut != null) return
        if (state.nodes <= 0) {
            state.cut = Cut.BUDGET
            return
        }
        state.nodes -= 1
        val listed = source.entries(dir) ?: return
        val beneath = if (relative == "") source.rootIgnores(dir, listed) else inherited
        val own = source.ignoreText(dir, listed)
        val ignores = if (own == null) beneath else beneath + IgnoreSource(relative, own)
        for (entry in listed.sortedBy { it.name }) {
            if (state.cut != null) return
            val child = if (relative == "") entry.name else "$relative/${entry.name}"
            val directory = entry.kind == WalkEntry.Kind.DIRECTORY
            if (!isGrantedPath(child, source.fold) || isIgnoredPath(ignores, child, directory, source.fold)) continue
            if (entry.kind == WalkEntry.Kind.OTHER) continue
            if (directory) {
                state.entered.add(child)
                source.child(dir, entry.name)?.let { walk(source, it, child, ignores, state) }
                continue
            }
            if (isBinaryNamedPath(child)) continue
            if (state.nodes <= 0) {
                state.cut = Cut.BUDGET
                return
            }
            state.nodes -= 1
            if (!source.shareable(dir, entry.name)) continue
            val size = child.toByteArray(Charsets.UTF_8).size
            val bound =
                when {
                    state.paths.size >= MAX_GRANT_PATHS -> Cut.PATHS
                    state.bytes + size > MAX_GRANT_LISTING_BYTES -> Cut.BYTES
                    else -> null
                }
            if (bound != null) {
                state.cut = bound
                return
            }
            state.paths.add(child)
            state.bytes += size
        }
    }
}

/** Git's ignore-file patterns, as `vscode_client/src/bridge/grant.ts` compiles and matches them. */
internal object Ignore {
    private val BOM = 0xfeff.toChar().toString()

    private val POSIX_CLASSES: Map<String, (Int) -> Boolean> =
        mapOf(
            "alnum" to { c -> c in 0x30..0x39 || c in 0x61..0x7a || c in 0x41..0x5a },
            "alpha" to { c -> c in 0x61..0x7a || c in 0x41..0x5a },
            "blank" to { c -> c == 0x20 || c == 0x09 },
            "cntrl" to { c -> c < 0x20 || c == 0x7f },
            "digit" to { c -> c in 0x30..0x39 },
            "graph" to { c -> c > 0x20 && c < 0x7f },
            "lower" to { c -> c in 0x61..0x7a },
            "print" to { c -> c >= 0x20 && c < 0x7f },
            "punct" to { c -> c > 0x20 && c < 0x7f && !(c in 0x30..0x39 || c in 0x61..0x7a || c in 0x41..0x5a) },
            "space" to { c -> c == 0x20 || c in 0x09..0x0d },
            "upper" to { c -> c in 0x41..0x5a },
            "xdigit" to { c -> c in 0x30..0x39 || c in 0x61..0x66 || c in 0x41..0x46 },
        )

    sealed interface Element {
        data class Literal(
            val value: Char,
        ) : Element

        data object Any : Element

        data object Star : Element

        data class Class(
            val negated: Boolean,
            val members: List<Member>,
        ) : Element
    }

    sealed interface Member {
        data class Range(
            val from: Char,
            val to: Char,
        ) : Member

        data class Named(
            val name: String,
        ) : Member
    }

    class Segment(
        val deep: Boolean,
        val elements: List<Element>,
    )

    class Pattern(
        val negated: Boolean,
        val directoryOnly: Boolean,
        val anchored: Boolean,
        val segments: List<Segment>,
    )

    class Compiled(
        val base: List<String>,
        val patterns: List<Pattern>,
    )

    fun compiled(
        source: Grant.IgnoreSource,
        fold: Boolean,
    ): Compiled =
        Compiled(
            if (source.dir ==
                ""
            ) {
                emptyList()
            } else {
                (if (fold) source.dir.lowercase(Locale.ROOT) else source.dir).split('/')
            },
            compileText(if (fold) source.text.lowercase(Locale.ROOT) else source.text, fold),
        )

    fun decides(
        sources: List<Compiled>,
        segments: List<String>,
        depth: Int,
        isDirectory: Boolean,
    ): Boolean {
        var ignored = false
        for (source in sources) {
            if (source.base.size >= depth || !source.base.indices.all { segments[it] == source.base[it] }) continue
            val relative = segments.subList(source.base.size, depth)
            for (pattern in source.patterns) {
                if (matches(pattern, relative, isDirectory)) ignored = !pattern.negated
            }
        }
        return ignored
    }

    private fun matches(
        pattern: Pattern,
        relative: List<String>,
        isDirectory: Boolean,
    ): Boolean {
        if (pattern.directoryOnly && !isDirectory) return false
        if (!pattern.anchored) {
            val leaf = pattern.segments.firstOrNull() ?: return false
            return segmentMatches(leaf.elements, relative.lastOrNull() ?: "")
        }
        return segmentsMatch(pattern.segments, relative)
    }

    private fun segmentsMatch(
        pattern: List<Segment>,
        path: List<String>,
    ): Boolean {
        val width = path.size + 1
        val failed = BooleanArray((pattern.size + 1) * width)

        fun at(
            element: Int,
            index: Int,
        ): Boolean {
            if (failed[element * width + index]) return false
            val segment = pattern.getOrNull(element)
            val matched =
                when {
                    segment == null -> {
                        index == path.size
                    }

                    segment.deep && element + 1 == pattern.size -> {
                        index < path.size
                    }

                    segment.deep -> {
                        (index..path.size).any { at(element + 1, it) }
                    }

                    else -> {
                        index < path.size && segmentMatches(segment.elements, path[index]) &&
                            at(element + 1, index + 1)
                    }
                }
            if (!matched) failed[element * width + index] = true
            return matched
        }
        return at(0, 0)
    }

    private fun segmentMatches(
        elements: List<Element>,
        name: String,
    ): Boolean {
        var element = 0
        var index = 0
        var star = -1
        var starIndex = 0
        while (index < name.length) {
            if (elements.getOrNull(element) == Element.Star) {
                star = element
                starIndex = index
                element += 1
                continue
            }
            val current = elements.getOrNull(element)
            if (current != null && elementMatches(current, name[index])) {
                element += 1
                index += 1
                continue
            }
            if (star == -1) return false
            starIndex += 1
            index = starIndex
            element = star + 1
        }
        while (elements.getOrNull(element) == Element.Star) element += 1
        return element == elements.size
    }

    private fun elementMatches(
        element: Element,
        char: Char,
    ): Boolean =
        when (element) {
            is Element.Literal -> element.value == char
            Element.Any, Element.Star -> true
            is Element.Class -> element.members.any { memberMatches(it, char) } != element.negated
        }

    private fun memberMatches(
        member: Member,
        char: Char,
    ): Boolean =
        when (member) {
            is Member.Range -> char >= member.from && char <= member.to
            is Member.Named -> POSIX_CLASSES[member.name]?.invoke(char.code) ?: false
        }

    fun compileText(
        text: String,
        fold: Boolean,
    ): List<Pattern> {
        val patterns = ArrayList<Pattern>()
        val body = if (text.startsWith(BOM)) text.substring(BOM.length) else text
        for (raw in body.split('\n')) {
            val line = trimTrailingSpaces(if (raw.endsWith('\r')) raw.dropLast(1) else raw)
            if (line == "" || line.startsWith('#')) continue
            var pattern = line
            var negated = false
            if (pattern.startsWith("\\#") || pattern.startsWith("\\!")) {
                pattern = pattern.substring(1)
            } else if (pattern.startsWith('!')) {
                negated = true
                pattern = pattern.substring(1)
            }
            var directoryOnly = false
            if (pattern.endsWith('/')) {
                directoryOnly = true
                pattern = pattern.dropLast(1)
            }
            var anchored = false
            if (pattern.startsWith('/')) {
                anchored = true
                pattern = pattern.substring(1)
            } else if (pattern.contains('/')) {
                anchored = true
            }
            if (pattern == "") continue
            val segments = ArrayList<Segment>()
            var valid = true
            for (piece in pattern.split('/')) {
                val elements = compileSegment(piece, fold)
                if (elements == null) {
                    valid = false
                    break
                }
                val deep = piece == "**"
                if (deep && segments.lastOrNull()?.deep == true) segments.removeAt(segments.size - 1)
                segments.add(Segment(deep, elements))
            }
            if (valid) patterns.add(Pattern(negated, directoryOnly, anchored, segments))
        }
        return patterns
    }

    private fun trimTrailingSpaces(line: String): String {
        var last = -1
        var index = 0
        while (index < line.length) {
            val char = line[index]
            if (char == '\\') {
                index += 2
                last = -1
                continue
            }
            if (char == ' ') {
                if (last == -1) last = index
            } else {
                last = -1
            }
            index += 1
        }
        return if (last == -1) line else line.substring(0, last)
    }

    private fun compileSegment(
        piece: String,
        fold: Boolean,
    ): List<Element>? {
        val elements = ArrayList<Element>()
        var index = 0
        while (index < piece.length) {
            when (val char = piece[index]) {
                '\\' -> {
                    val literal = piece.getOrNull(index + 1) ?: return null
                    elements.add(Element.Literal(literal))
                    index += 2
                }

                '*' -> {
                    if (elements.lastOrNull() != Element.Star) elements.add(Element.Star)
                    index += 1
                }

                '?' -> {
                    elements.add(Element.Any)
                    index += 1
                }

                '[' -> {
                    val (element, next) = compileClass(piece, index, fold) ?: return null
                    elements.add(element)
                    index = next
                }

                else -> {
                    elements.add(Element.Literal(char))
                    index += 1
                }
            }
        }
        return elements
    }

    private fun compileClass(
        piece: String,
        start: Int,
        fold: Boolean,
    ): Pair<Element, Int>? {
        var index = start + 1
        var negated = false
        if (piece.getOrNull(index) == '!' || piece.getOrNull(index) == '^') {
            negated = true
            index += 1
        }
        val members = ArrayList<Member>()
        var first = true
        while (index < piece.length) {
            var char = piece[index]
            if (char == ']' && !first) return Element.Class(negated, members) to index + 1
            first = false
            if (char == '\\') {
                char = piece.getOrNull(index + 1) ?: return null
                index += 2
            } else if (char == '[' && piece.getOrNull(index + 1) == ':') {
                val close = piece.indexOf(":]", index + 2)
                val name = if (close == -1) "" else piece.substring(index + 2, close)
                if (name !in POSIX_CLASSES) return null
                val effective = if (fold && (name == "upper" || name == "lower")) "alpha" else name
                members.add(Member.Named(effective))
                index = close + 2
                continue
            } else {
                index += 1
            }
            val after = piece.getOrNull(index + 1)
            if (piece.getOrNull(index) == '-' && after != null && after != ']') {
                var to: Char = after
                var next = index + 2
                if (to == '\\') {
                    to = piece.getOrNull(index + 2) ?: return null
                    next = index + 3
                }
                members.add(Member.Range(char, to))
                index = next
                continue
            }
            members.add(Member.Range(char, char))
        }
        return null
    }
}
