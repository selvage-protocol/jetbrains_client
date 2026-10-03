package dev.dontblameme.selvage.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory

/**
 * The questions a command asks: VS Code's input boxes, quick picks and modal warnings in IntelliJ's
 * idiom (an input dialog, a popup chooser, a message dialog). A test swaps [current] for scripted answers.
 */
interface Prompts {
    fun input(
        project: Project?,
        title: String,
        prompt: String,
        initial: String,
        validate: (String) -> String?,
    ): String?

    /** A list to pick one row of; [chosen] is called with its index, or not at all when dismissed. */
    fun choose(
        project: Project?,
        title: String,
        rows: List<String>,
        chosen: (Int) -> Unit,
    )

    /** A modal warning with one affirming answer and Cancel; true when [yes] was chosen. */
    fun confirm(
        project: Project?,
        message: String,
        yes: String,
    ): Boolean

    companion object {
        @Volatile
        var current: Prompts = Ide
    }

    object Ide : Prompts {
        override fun input(
            project: Project?,
            title: String,
            prompt: String,
            initial: String,
            validate: (String) -> String?,
        ): String? =
            Messages.showInputDialog(
                project,
                prompt,
                title,
                null,
                initial,
                object : InputValidatorEx {
                    override fun getErrorText(inputString: String): String? = validate(inputString)
                },
            )

        override fun choose(
            project: Project?,
            title: String,
            rows: List<String>,
            chosen: (Int) -> Unit,
        ) {
            val popup =
                JBPopupFactory
                    .getInstance()
                    .createPopupChooserBuilder(rows.indices.toList())
                    .setTitle(title)
                    .setRenderer(
                        com.intellij.ui.SimpleListCellRenderer
                            .create("") { rows[it] },
                    ).setItemChosenCallback { chosen(it) }
                    .setNamerForFiltering { rows[it] }
                    .createPopup()
            if (project != null) popup.showCenteredInCurrentWindow(project) else popup.showInFocusCenter()
        }

        override fun confirm(
            project: Project?,
            message: String,
            yes: String,
        ): Boolean =
            Messages.showDialog(
                project,
                message,
                "Selvage",
                arrayOf(yes, "Cancel"),
                0,
                Messages.getWarningIcon(),
            ) == 0
    }
}
