package dev.dontblameme.selvage.intellij.session

import com.intellij.openapi.project.Project
import dev.dontblameme.selvage.intellij.ui.Notifier
import dev.dontblameme.selvage.intellij.ui.Prompts
import java.util.ArrayDeque

/** Answers a command's questions from a script, and fails on a question the script did not expect. */
class ScriptedPrompts : Prompts {
    val inputs = ArrayDeque<String?>()
    val choices = ArrayDeque<Int?>()
    val confirms = ArrayDeque<Boolean>()
    val asked = ArrayList<String>()

    override fun input(
        project: Project?,
        title: String,
        prompt: String,
        initial: String,
        validate: (String) -> String?,
    ): String? {
        asked.add("input: $title")
        if (inputs.isEmpty()) throw AssertionError("an unexpected question: $title")
        return inputs.removeFirst()
    }

    override fun choose(
        project: Project?,
        title: String,
        rows: List<String>,
        chosen: (Int) -> Unit,
    ) {
        asked.add("choose: $title: $rows")
        if (choices.isEmpty()) throw AssertionError("an unexpected list: $title $rows")
        choices.removeFirst()?.let(chosen)
    }

    override fun confirm(
        project: Project?,
        message: String,
        yes: String,
    ): Boolean {
        asked.add("confirm: $message")
        if (confirms.isEmpty()) throw AssertionError("an unexpected confirmation: $message")
        return confirms.removeFirst()
    }
}

/** Records what the Selvage notification group said. */
class Said : AutoCloseable {
    val all = java.util.concurrent.CopyOnWriteArrayList<Notifier.Said>()
    private val listener: (Notifier.Said) -> Unit = { all.add(it) }

    init {
        Notifier.listeners.add(listener)
    }

    fun sentences(): List<String> = all.map { it.sentence }

    fun last(): Notifier.Said = all.lastOrNull() ?: throw AssertionError("nothing was said")

    override fun close() {
        Notifier.listeners.remove(listener)
    }
}
