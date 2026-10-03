package dev.dontblameme.selvage.intellij

import java.io.File
import java.util.concurrent.TimeUnit

/** The checkouts and tools a test reads or spawns, named by property or environment, else the sibling. */
object Siblings {
    val workspace: File =
        File(System.getProperty("selvage.workspaceRoot") ?: System.getProperty("user.dir")).absoluteFile

    fun vscodeClient(): File {
        System.getProperty("selvage.vscodeClient")?.let { return File(it) }
        val found =
            generateSequence(workspace) { it.parentFile }
                .map { File(it, "vscode_client") }
                .firstOrNull { File(it, "src/bridge/words.ts").isFile }
        return found
            ?: throw AssertionError(
                "no vscode_client checkout beside $workspace: the pins compare against its sources, so set " +
                    "SELVAGE_VSCODE_CLIENT to one",
            )
    }

    fun node(): String = System.getProperty("selvage.node") ?: "node"

    /** Runs [command] with [input] on stdin, bounded, and returns stdout; fails with stderr otherwise. */
    fun run(
        command: List<String>,
        input: String,
        timeoutSeconds: Long = 60,
    ): String {
        val process =
            ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
        val out = StringBuilder()
        val err = StringBuilder()
        val readers =
            listOf(
                Thread { out.append(process.inputStream.bufferedReader().readText()) },
                Thread { err.append(process.errorStream.bufferedReader().readText()) },
            )
        readers.forEach {
            it.isDaemon = true
            it.start()
        }
        process.outputStream.bufferedWriter().use { it.write(input) }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("$command did not finish within $timeoutSeconds s; stderr: $err")
        }
        readers.forEach { it.join(5_000) }
        if (process.exitValue() != 0) throw AssertionError("$command exited ${process.exitValue()}: $err")
        return out.toString()
    }

    /** `JSON.stringify` for the values the pins exchange: strings, integers, booleans, lists and maps. */
    fun json(value: Any?): String =
        when (value) {
            null -> {
                "null"
            }

            is String -> {
                quote(value)
            }

            is Boolean, is Int, is Long -> {
                value.toString()
            }

            is Double -> {
                if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            }

            is List<*> -> {
                value.joinToString(",", "[", "]") { json(it) }
            }

            is Map<*, *> -> {
                value.entries.joinToString(
                    ",",
                    "{",
                    "}",
                ) { "${quote(it.key.toString())}:${json(it.value)}" }
            }

            else -> {
                throw IllegalArgumentException("no JSON form for $value")
            }
        }

    private fun quote(text: String): String =
        buildString {
            append('"')
            var index = 0
            while (index < text.length) {
                val c = text[index]
                when {
                    c == '"' -> {
                        append("\\\"")
                    }

                    c == '\\' -> {
                        append("\\\\")
                    }

                    c == '\b' -> {
                        append("\\b")
                    }

                    c == '\u000c' -> {
                        append("\\f")
                    }

                    c == '\n' -> {
                        append("\\n")
                    }

                    c == '\r' -> {
                        append("\\r")
                    }

                    c == '\t' -> {
                        append("\\t")
                    }

                    c < ' ' -> {
                        append("\\u%04x".format(c.code))
                    }

                    c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                        append(c).append(text[index + 1])
                        index += 1
                    }

                    c.isSurrogate() -> {
                        append("\\u%04x".format(c.code))
                    }

                    else -> {
                        append(c)
                    }
                }
                index += 1
            }
            append('"')
        }
}
