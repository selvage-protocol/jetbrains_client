package dev.dontblameme.selvage.crdt

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * Real yjs behind `engine/src/test/node/yjs-driver.mjs`, one JSON line per request and reply.
 * Every reply is awaited for at most [REPLY_TIMEOUT_S] seconds, and [close] ends the process
 * within [EXIT_TIMEOUT_S] seconds, forcibly if it has to.
 */
class YjsDriver : AutoCloseable {
    private val process: Process
    private val stdin: OutputStreamWriter
    private val replies = LinkedBlockingQueue<String>()
    private val stderr = StringBuffer()

    init {
        val driver = File(TestPaths.workspaceRoot, "engine/src/test/node/yjs-driver.mjs")
        check(driver.isFile) { "the yjs driver is missing: $driver" }
        process =
            ProcessBuilder(TestPaths.node, driver.absolutePath, TestPaths.yjsNodeModules.absolutePath)
                .directory(TestPaths.workspaceRoot)
                .start()
        stdin = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
        pump(
            "yjs-driver-stdout",
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)),
        ) { replies.put(it) }
        pump("yjs-driver-stderr", BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))) {
            stderr.append(it).append('\n')
        }
        val versions = call("versions")
        val yjs = (versions["yjs"] as YAny.Str).value
        check(
            yjs.startsWith("13."),
        ) { "the differential test needs yjs 13.x, found $yjs in ${TestPaths.yjsNodeModules}" }
    }

    private fun pump(
        name: String,
        reader: BufferedReader,
        sink: (String) -> Unit,
    ) {
        Thread({
            reader.useLines { lines -> lines.forEach(sink) }
        }, name).apply {
            isDaemon = true
            start()
        }
    }

    val versions: YAny.Obj by lazy { call("versions") }

    /** Sends one request and returns the reply; fails the test on an error or a timeout. */
    fun call(
        op: String,
        vararg args: Pair<String, Any?>,
    ): YAny.Obj {
        val request = YAny.Obj.of(listOf("op" to YAny.Str(op)) + args.map { (k, v) -> k to toAny(v) })
        stdin.write(Json.stringify(request)!!)
        stdin.write("\n")
        stdin.flush()
        val line =
            replies.poll(REPLY_TIMEOUT_S, TimeUnit.SECONDS)
                ?: fail(
                    "yjs driver gave no reply to $op in ${REPLY_TIMEOUT_S}s (alive: ${process.isAlive}); stderr:\n$stderr",
                )
        val reply = Json.parse(line) as YAny.Obj
        if (reply["ok"] !=
            YAny.Bool(true)
        ) {
            fail(
                "yjs driver failed $op: ${(reply["error"] as? YAny.Str)?.value}\nrequest: ${Json.stringify(
                    request,
                )}",
            )
        }
        return reply
    }

    fun hex(
        op: String,
        key: String,
        vararg args: Pair<String, Any?>,
    ): ByteArray = (call(op, *args)[key] as YAny.Str).value.hexToBytes()

    override fun close() {
        runCatching { stdin.close() }
        if (!process.waitFor(EXIT_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(EXIT_TIMEOUT_S, TimeUnit.SECONDS)
        }
    }

    companion object {
        const val REPLY_TIMEOUT_S = 30L
        const val EXIT_TIMEOUT_S = 10L

        fun toAny(v: Any?): YAny =
            when (v) {
                null -> YAny.Null
                is YAny -> v
                is String -> YAny.Str(v)
                is Boolean -> YAny.Bool(v)
                is Number -> YAny.Num(v.toDouble())
                is ByteArray -> YAny.Str(v.toHex())
                is List<*> -> YAny.Arr(v.map { toAny(it) })
                else -> throw IllegalArgumentException("cannot send $v")
            }
    }
}

/** Where the differential tests find the workspace, Node, yjs and the specification. */
object TestPaths {
    val workspaceRoot: File by lazy {
        File(
            System.getProperty("selvage.workspaceRoot")
                ?: fail("selvage.workspaceRoot is not set: run the tests through Gradle"),
        )
    }

    val node: String by lazy { System.getProperty("selvage.node") ?: "node" }

    /** The parents of the workspace root: the Selvage checkout that holds the sibling repositories. */
    private fun ancestors(): Sequence<File> = generateSequence(workspaceRoot.absoluteFile) { it.parentFile }

    val yjsNodeModules: File by lazy {
        System.getProperty("selvage.yjsNodeModules")?.let { explicit ->
            val dir = File(explicit)
            if (!File(dir, "yjs/package.json").isFile || !File(dir, "y-protocols/package.json").isFile) {
                fail("selvage.yjsNodeModules / SELVAGE_YJS_NODE_MODULES is $dir, which holds no yjs and y-protocols")
            }
            return@lazy dir
        }
        val candidates =
            ancestors()
                .flatMap { sequenceOf(File(it, "vscode_client/node_modules"), File(it, "web_client/node_modules")) }
                .toList()
        candidates.firstOrNull { File(it, "yjs/package.json").isFile && File(it, "y-protocols/package.json").isFile }
            ?: fail(
                "no yjs for the differential test: set SELVAGE_YJS_NODE_MODULES (or -Pselvage.yjsNodeModules) " +
                    "to a node_modules holding yjs 13.x and y-protocols; looked in ${candidates.joinToString()}",
            )
    }

    val specification: File by lazy {
        System.getProperty("selvage.specification")?.let { return@lazy File(it) }
        ancestors().map { File(it, "specification") }.firstOrNull { File(it, "PROTOCOL.md").isFile }
            ?: fail("no specification checkout: set SELVAGE_SPECIFICATION (or -Pselvage.specification)")
    }
}
