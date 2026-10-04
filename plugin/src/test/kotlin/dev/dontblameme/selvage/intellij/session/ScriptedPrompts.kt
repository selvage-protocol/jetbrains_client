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

    /** A list the script holds open, so a test can watch it follow the room and pick from it later. */
    inner class Held(
        var title: String,
        var placeholder: String?,
        var rows: List<String>,
        private val closed: () -> Unit,
        private val chosen: (Int) -> Unit,
    ) : Prompts.Chooser {
        var open = true
            private set

        override fun refill(
            title: String,
            placeholder: String?,
            rows: List<String>,
        ) {
            if (!open) throw AssertionError("a closed list was drawn again")
            this.title = title
            this.placeholder = placeholder
            this.rows = rows
        }

        override fun close() {
            if (!open) return
            open = false
            closed()
        }

        fun pick(index: Int) {
            if (!open) throw AssertionError("a pick from a closed list: $title $rows")
            close()
            chosen(index)
        }
    }

    /** How many of the next lists to hold open rather than answer from [choices]. */
    var holdNext = 0
    val held = ArrayList<Held>()

    override fun choose(
        project: Project?,
        title: String,
        rows: List<String>,
        placeholder: String?,
        closed: () -> Unit,
        chosen: (Int) -> Unit,
    ): Prompts.Chooser {
        asked.add("choose: $title: $rows")
        val list = Held(title, placeholder, rows, closed, chosen)
        if (holdNext > 0) {
            holdNext -= 1
            held.add(list)
            return list
        }
        if (choices.isEmpty()) throw AssertionError("an unexpected list: $title $rows")
        val choice = choices.removeFirst()
        if (choice == null) list.close() else list.pick(choice)
        return list
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
