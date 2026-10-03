package dev.dontblameme.selvage.engine

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.fail

/** A real `selvaged` (SELVAGE_SELVAGED) on an ephemeral loopback port, for the live tests. */
class LiveServer private constructor(
    private val process: Process,
    private val errors: File,
    /** The `ws://host:port` a session URL is built on. */
    val base: String,
) : AutoCloseable {
    fun stderr(): String = if (errors.isFile) errors.readText().takeLast(4000) else ""

    override fun close() {
        process.destroy()
        if (!process.waitFor(EXIT_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(EXIT_TIMEOUT_S, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val START_TIMEOUT_S = 10L
        private const val EXIT_TIMEOUT_S = 5L

        fun binary(): File {
            val path =
                System.getProperty("selvage.selvaged")
                    ?: fail(
                        "SELVAGE_SELVAGED is not set: the live test spawns a real selvaged, so point it at one " +
                            "(cargo build --release -p selvaged in reference_server)",
                    )
            val file = File(path)
            if (!file.canExecute()) fail("SELVAGE_SELVAGED is $path, which is not an executable file")
            return file
        }

        /** Starts one, its stderr kept in [dir], and waits at most [START_TIMEOUT_S] for its address. */
        fun start(
            dir: File,
            roomGraceMs: Long = 5_000,
        ): LiveServer {
            val errors = File(dir, "selvaged.err")
            val process =
                ProcessBuilder(binary().path, "--listen", "127.0.0.1:0", "--room-grace-ms", roomGraceMs.toString())
                    .redirectError(errors)
                    .start()
            val lines = LinkedBlockingQueue<String>()
            Thread({ process.inputStream.bufferedReader().forEachLine { lines.add(it) } }, "selvaged-out")
                .apply { isDaemon = true }
                .start()
            val first = lines.poll(START_TIMEOUT_S, TimeUnit.SECONDS)
            val url = first?.let { Regex("ws://\\S+/session").find(it)?.value }
            if (url == null) {
                process.destroyForcibly().waitFor(EXIT_TIMEOUT_S, TimeUnit.SECONDS)
                fail(
                    "selvaged named no URL within $START_TIMEOUT_S s (first line: $first); " +
                        "stderr: ${errors.readText().take(2000)}",
                )
            }
            return LiveServer(process, errors, url.removeSuffix("/session"))
        }
    }
}

/**
 * Polls [condition] every 10 ms until it holds, failing after [timeoutMs] with what [observed]
 * reports at that moment.
 */
fun eventually(
    what: String,
    timeoutMs: Long = 10_000,
    observed: () -> String = { "" },
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (!condition()) {
        if (System.nanoTime() > deadline) {
            val state = observed()
            fail("not within $timeoutMs ms: $what" + if (state.isEmpty()) "" else "; observed $state")
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
    }
}
