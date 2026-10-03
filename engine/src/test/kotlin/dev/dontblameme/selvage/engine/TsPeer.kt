package dev.dontblameme.selvage.engine

import dev.dontblameme.selvage.crdt.Json
import dev.dontblameme.selvage.crdt.TestPaths
import dev.dontblameme.selvage.crdt.YAny
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * The TypeScript engine `vscode_client`, `nvim_client` and `web_client` share, behind
 * `engine/src/test/node/ts-peer.mjs`: one JSON line per request and reply. Every reply is awaited
 * for at most [REPLY_TIMEOUT_S] seconds, and [close] ends the process within [EXIT_TIMEOUT_S]
 * seconds, forcibly if it has to.
 */
class TsPeer : AutoCloseable {
    private val process: Process
    private val stdin: OutputStreamWriter
    private val replies = LinkedBlockingQueue<String>()
    private val stderr = StringBuffer()

    init {
        val driver = File(TestPaths.workspaceRoot, "engine/src/test/node/ts-peer.mjs")
        check(driver.isFile) { "the TypeScript driver is missing: $driver" }
        process =
            ProcessBuilder(TestPaths.node, driver.absolutePath, vscodeClient.absolutePath)
                .directory(TestPaths.workspaceRoot)
                .start()
        stdin = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
        pump("ts-peer-stdout", process.inputStream) { replies.put(it) }
        pump("ts-peer-stderr", process.errorStream) { stderr.append(it).append('\n') }
    }

    private fun pump(
        name: String,
        stream: InputStream,
        sink: (String) -> Unit,
    ) {
        Thread({
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines -> lines.forEach(sink) }
        }, name).apply {
            isDaemon = true
            start()
        }
    }

    /** Sends one request and returns the reply; fails the test on a refusal or a timeout. */
    fun call(
        op: String,
        vararg args: Pair<String, YAny>,
    ): YAny.Obj {
        val request = YAny.Obj.of(listOf("op" to YAny.Str(op)) + args)
        val line = Json.stringify(request) ?: fail("cannot write $request")
        try {
            stdin.write(line)
            stdin.write("\n")
            stdin.flush()
        } catch (e: IOException) {
            fail("the TypeScript peer took no $op (alive: ${process.isAlive}): $e; stderr:\n$stderr")
        }
        val reply =
            replies.poll(REPLY_TIMEOUT_S, TimeUnit.SECONDS)
                ?: fail(
                    "the TypeScript peer gave no reply to $op in ${REPLY_TIMEOUT_S}s " +
                        "(alive: ${process.isAlive}); stderr:\n$stderr",
                )
        val parsed = Json.parse(reply) as? YAny.Obj ?: fail("the TypeScript peer answered $op with $reply")
        if (parsed["ok"] != YAny.Bool(true)) {
            fail("the TypeScript peer refused $op: ${parsed.str("error")}\nrequest: $line\nstderr:\n$stderr")
        }
        return parsed
    }

    override fun close() {
        if (process.isAlive) {
            runCatching {
                stdin.write("{\"op\":\"quit\"}\n")
                stdin.flush()
            }
        }
        runCatching { stdin.close() }
        if (!process.waitFor(EXIT_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(EXIT_TIMEOUT_S, TimeUnit.SECONDS)
        }
    }

    companion object {
        const val REPLY_TIMEOUT_S = 30L
        const val EXIT_TIMEOUT_S = 10L

        /** What the driver imports from the checkout's `node_modules`. */
        private val PACKAGES = listOf("ws", "yjs", "y-protocols", "lib0")

        /**
         * The `vscode_client` checkout whose engine the driver loads: SELVAGE_VSCODE_CLIENT (or
         * -Pselvage.vscodeClient), else the sibling checkout found by walking up. Its packages must
         * be installed; this says how when they are not.
         */
        val vscodeClient: File by lazy {
            val named = System.getProperty("selvage.vscodeClient")
            val candidates =
                if (named != null) {
                    listOf(File(named))
                } else {
                    generateSequence(TestPaths.workspaceRoot.absoluteFile) { it.parentFile }
                        .map { File(it, "vscode_client") }
                        .toList()
                }
            val dir =
                candidates.firstOrNull { File(it, "src/engine/relay.ts").isFile }
                    ?: fail(
                        "no vscode_client checkout for the cross-implementation test: set SELVAGE_VSCODE_CLIENT " +
                            "(or -Pselvage.vscodeClient) to one; looked in ${candidates.joinToString()}",
                    )
            val missing = PACKAGES.filterNot { File(dir, "node_modules/$it/package.json").isFile }
            if (missing.isNotEmpty()) {
                fail("$dir has no node_modules for ${missing.joinToString()}: run `npm ci` there")
            }
            dir
        }
    }
}

fun YAny.Obj.str(key: String): String? = (this[key] as? YAny.Str)?.value

fun YAny.Obj.num(key: String): Long? = (this[key] as? YAny.Num)?.value?.toLong()

fun YAny.Obj.obj(key: String): YAny.Obj? = this[key] as? YAny.Obj

fun YAny.Obj.arr(key: String): List<YAny> = (this[key] as? YAny.Arr)?.items ?: emptyList()

fun YAny.Obj.strings(key: String): List<String> = arr(key).mapNotNull { (it as? YAny.Str)?.value }
