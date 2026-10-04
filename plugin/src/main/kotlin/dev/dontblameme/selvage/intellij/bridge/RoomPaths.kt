package dev.dontblameme.selvage.intellij.bridge

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * A room path as a file under a local root. The path comes from a peer, so it is never handed to
 * `Path.resolve` whole: each segment is checked on its own and appended to the directory before it,
 * and the result must be that directory's direct child. On Windows, `resolve` returns its argument
 * when that argument is absolute or names a drive (`C:`, `C:/x`, `\\server\share`), so a segment the
 * local filesystem would read as a root, a drive, a device, a stream or a parent is refused, and so
 * is any result whose parent is not the directory it was appended to.
 */
object RoomPaths {
    /** Device names Windows reserves in every directory, with or without an extension. */
    private val WINDOWS_RESERVED: Set<String> =
        buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"))
            for (digit in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "\u00b9", "\u00b2", "\u00b3")) {
                add("COM$digit")
                add("LPT$digit")
            }
        }

    private const val WINDOWS_FORBIDDEN = "<>:\"|?*"

    fun isWindows(osName: String = System.getProperty("os.name") ?: ""): Boolean =
        osName.lowercase(Locale.ROOT).startsWith("windows")

    /**
     * Whether [segment] names one entry of one directory on this OS and nothing else: not empty,
     * `.` or `..`, no separator of either kind and no NUL. On Windows also no control character, none
     * of `<>:"|?*` (a `:` is a drive or an alternate data stream), no trailing dot or space (which
     * Windows strips, so the name would be another file's) and no reserved device name.
     */
    fun isLocalSegment(
        segment: String,
        windows: Boolean = isWindows(),
    ): Boolean {
        if (segment.isEmpty() || segment == "." || segment == "..") return false
        if (segment.any { it == '/' || it == '\\' || it == '\u0000' }) return false
        if (!windows) return true
        if (segment.any { it < ' ' || it in WINDOWS_FORBIDDEN }) return false
        if (segment.endsWith('.') || segment.endsWith(' ')) return false
        val stem = segment.substringBefore('.').trimEnd(' ').uppercase(Locale.ROOT)
        return stem !in WINDOWS_RESERVED
    }

    /** Whether every `/`-separated segment of [path] is a [isLocalSegment]. */
    fun isLocalPath(
        path: String,
        windows: Boolean = isWindows(),
    ): Boolean = path.split('/').all { isLocalSegment(it, windows) }

    /** [dir]'s direct child named [segment], or null when the name would land anywhere else. */
    fun child(
        dir: Path,
        segment: String,
        windows: Boolean = isWindows(),
    ): Path? {
        if (!isLocalSegment(segment, windows)) return null
        val child =
            try {
                dir.resolve(segment)
            } catch (e: InvalidPathException) {
                return null
            }
        return if (child.parent == dir && child.fileName?.toString() == segment) child else null
    }

    /** The file [path] names under [root], built by appending one checked segment at a time, or null. */
    fun under(
        root: Path,
        path: String,
        windows: Boolean = isWindows(),
    ): Path? {
        var dir = root
        for (segment in path.split('/')) dir = child(dir, segment, windows) ?: return null
        return dir
    }
}
