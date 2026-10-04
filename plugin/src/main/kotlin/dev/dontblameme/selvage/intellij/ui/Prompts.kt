package dev.dontblameme.selvage.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBList
import javax.swing.JList

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

    /** An open list, which its caller may redraw while the person reads it, or close. */
    interface Chooser {
        fun refill(
            title: String,
            placeholder: String?,
            rows: List<String>,
        )

        fun close()
    }

    /**
     * A list to pick one row of; [chosen] is called with its index, or not at all when dismissed.
     * [placeholder] is VS Code's quick-pick placeholder, the line under the rows here; [closed] is
     * called once when the list goes, whichever way.
     */
    fun choose(
        project: Project?,
        title: String,
        rows: List<String>,
        placeholder: String? = null,
        closed: () -> Unit = {},
        chosen: (Int) -> Unit,
    ): Chooser

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
            placeholder: String?,
            closed: () -> Unit,
            chosen: (Int) -> Unit,
        ): Chooser {
            var current = rows
            val model = CollectionListModel(rows.indices.toList())
            val list = JBList(model)
            list.cellRenderer =
                object : ColoredListCellRenderer<Int>() {
                    override fun customizeCellRenderer(
                        list: JList<out Int>,
                        value: Int,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean,
                    ) {
                        current.getOrNull(value)?.let { append(it) }
                    }
                }
            val builder = PopupChooserBuilder<Int>(list)
            builder.setTitle(title)
            builder.setItemChosenCallback(com.intellij.util.Consumer<Int> { chosen(it) })
            builder.setNamerForFiltering(com.intellij.util.Function<Int, String> { current.getOrNull(it) ?: "" })
            builder.addListener(
                object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) = closed()
                },
            )
            placeholder?.let { builder.setAdText(it) }
            val popup = builder.createPopup()
            if (project != null) popup.showCenteredInCurrentWindow(project) else popup.showInFocusCenter()
            return object : Chooser {
                override fun refill(
                    title: String,
                    placeholder: String?,
                    rows: List<String>,
                ) {
                    if (popup.isDisposed) return
                    current = rows
                    model.replaceAll(rows.indices.toList())
                    popup.setCaption(title)
                    placeholder?.let { popup.setAdText(it, javax.swing.SwingConstants.LEFT) }
                }

                override fun close() {
                    if (!popup.isDisposed) popup.cancel()
                }
            }
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
