package dev.dontblameme.selvage.intellij.e2e

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.diagnostic.MessagePool
import com.intellij.ide.ActivityTracker
import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.StatusBarEx
import com.intellij.psi.PsiManager
import com.intellij.util.ui.UIUtil
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.intellij.settings.SelvageConfigurable
import dev.dontblameme.selvage.intellij.ui.ParticipantsPanel
import java.awt.Dialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.MenuElement
import javax.swing.MenuSelectionManager

/**
 * What the screenshot script (`scripts/screenshots/capture.py`) asks of the IDE beyond the plugin's
 * commands: a window filling its display, no balloons left over, a tool window or a menu open, the
 * settings page, and whether anything is still indexing or covering the frame.
 */
object Stage {
    val ops = setOf("frame", "quiet", "reveal", "toolWindow", "menu", "settingsPage")

    fun handle(
        op: String,
        command: JsonValue.Obj,
        project: Project,
    ): Map<String, JsonValue> =
        when (op) {
            "frame" -> frame(project)
            "quiet" -> quiet(project)
            "reveal" -> reveal(command, project)
            "toolWindow" -> toolWindow(command, project)
            "menu" -> menu(command, project)
            else -> settingsPage(project)
        }

    /** The project's frame fills the screen, as a maximised window does. */
    private fun frame(project: Project): Map<String, JsonValue> {
        val frame = WindowManager.getInstance().getFrame(project) ?: throw IllegalStateException("no frame")
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        if (frame.bounds != screen) {
            frame.extendedState = Frame.MAXIMIZED_BOTH
            frame.bounds = screen
        }
        frame.toFront()
        return mapOf("bounds" to str("${frame.x},${frame.y},${frame.width}x${frame.height}"))
    }

    /**
     * Balloons and notifications gone, the status bar's error mark cleared (the errors it held are
     * answered, for the script to report), a caret that does not blink between two captures, and no
     * trial button: the sandbox's IDE has no licence, and a licensed one does not show it.
     */
    private fun quiet(project: Project): Map<String, JsonValue> {
        val errors = errors()
        hideTrialButton()
        MessagePool.getInstance().clearErrors()
        NotificationsManager
            .getNotificationsManager()
            .getNotificationsOfType(Notification::class.java, project)
            .forEach { it.expire() }
        EditorSettingsExternalizable.getInstance().isBlinkCaret = false
        return mapOf("cleared" to errors)
    }

    private fun hideTrialButton() {
        val actions = ActionManager.getInstance()
        val toolbar = actions.getAction("MainToolbarRight") as? DefaultActionGroup ?: return
        val trial = actions.getAction("TrialStateWidget") ?: return
        if (trial in toolbar.childActionsOrStubs) {
            toolbar.remove(trial, actions)
            ActivityTracker.getInstance().inc()
        }
    }

    private fun errors(): JsonValue =
        JsonValue.Arr(
            MessagePool.getInstance().getFatalErrors(true, true).map {
                str(it.throwableText.lineSequence().firstOrNull() ?: it.message ?: "")
            },
        )

    /** The project view opened down to `path`, as Select Opened File does. */
    private fun reveal(
        command: JsonValue.Obj,
        project: Project,
    ): Map<String, JsonValue> {
        val file =
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(
                Path.of(project.basePath!!).resolve(command.string("path")!!),
            ) ?: throw IllegalArgumentException("no such file")
        ProjectView.getInstance(project).select(null, file, false)
        val tree = ProjectView.getInstance(project).currentProjectViewPane?.tree
        val selected = tree?.selectionPath?.lastPathComponent?.toString()
        return mapOf("selected" to (selected?.let(::str) ?: JsonValue.Null))
    }

    private fun toolWindow(
        command: JsonValue.Obj,
        project: Project,
    ): Map<String, JsonValue> {
        val id = command.string("id") ?: throw IllegalArgumentException("`toolWindow` needs `id`")
        val window =
            ToolWindowManager.getInstance(project).getToolWindow(id) ?: throw IllegalArgumentException("no $id")
        if (command["show"] == JsonValue.Bool(false)) {
            window.hide()
            return emptyMap()
        }
        window.show()
        val panel = window.contentManager.contents.firstNotNullOfOrNull { it.component as? ParticipantsPanel }
        val rows = panel?.rows().orEmpty()
        command.string("select")?.let { wanted ->
            val index = rows.indexOfFirst { it.label.startsWith(wanted) }
            if (index >= 0 && panel != null && panel.list.selectedIndex != index) {
                panel.list.selectedIndex = index
                ActivityTracker.getInstance().inc()
            }
        }
        val list = panel?.list
        val row = list?.selectedIndex ?: -1
        if (command["menu"] == JsonValue.Bool(true) && list != null && row >= 0 && popupItems().isEmpty()) {
            // The row's context menu, opened by the right click a person would make on it.
            val cell = list.getCellBounds(row, row)
            val x = cell.x + cell.width / 2
            val y = cell.y + cell.height / 2
            for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
                list.dispatchEvent(
                    MouseEvent(
                        list,
                        id,
                        System.currentTimeMillis(),
                        InputEvent.BUTTON3_DOWN_MASK,
                        x,
                        y,
                        1,
                        true,
                        MouseEvent.BUTTON3,
                    ),
                )
            }
        }
        return mapOf(
            "menu" to JsonValue.Arr(popupItems().map(::str)),
            "visible" to JsonValue.Bool(window.isVisible),
            "rows" to
                JsonValue.Arr(
                    rows.map { str(if (it.description.isEmpty()) it.label else "${it.label}  ${it.description}") },
                ),
            "selected" to (
                panel
                    ?.list
                    ?.selectedValue
                    ?.label
                    ?.let(::str) ?: JsonValue.Null
            ),
        )
    }

    /**
     * Opens the menu bar's menus along `path`, one level per call where a level fills late, and
     * answers with the items of the deepest one open.
     */
    private fun menu(
        command: JsonValue.Obj,
        project: Project,
    ): Map<String, JsonValue> {
        val path = (command["path"] as? JsonValue.Arr)?.items?.map { (it as JsonValue.Str).value }.orEmpty()
        if (path.isEmpty()) {
            MenuSelectionManager.defaultManager().clearSelectedPath()
            return emptyMap()
        }
        val frame = WindowManager.getInstance().getFrame(project) ?: throw IllegalStateException("no frame")
        val menus = { component: java.awt.Component ->
            UIUtil
                .findComponentsOfType(component as javax.swing.JComponent, JMenuBar::class.java)
                .filter { it.isShowing }
                .flatMap { bar -> (0 until bar.menuCount).mapNotNull { bar.getMenu(it) } }
                .filter { it.isShowing }
        }
        var items: List<JMenuItem> = menus(frame.rootPane)
        if (items.none { it.text == path.first() }) {
            // A narrow window keeps the rest of the menu bar behind the main menu button, as a
            // person would find it there.
            if (MenuSelectionManager.defaultManager().selectedPath.isEmpty()) {
                UIUtil
                    .findComponentsOfType(frame.rootPane, ActionButton::class.java)
                    .firstOrNull { it.isShowing && it.action.javaClass.simpleName == "ShowMenuAction" }
                    ?.click()
            }
            return mapOf("missing" to str(path.first()), "items" to JsonValue.Arr(items.map { str(it.text ?: "") }))
        }
        val open = MenuSelectionManager.defaultManager().selectedPath.toSet<MenuElement>()
        for (name in path) {
            val menu =
                items.firstOrNull { it.text == name } as? JMenu
                    ?: return mapOf("missing" to str(name), "items" to JsonValue.Arr(items.map { str(it.text ?: "") }))
            if (menu !in open) menu.doClick(0)
            items = menu.popupMenu.components.filterIsInstance<JMenuItem>()
        }
        return mapOf("items" to JsonValue.Arr(items.map { str(it.text ?: "") }))
    }

    private fun popupItems(): List<String> =
        (MenuSelectionManager.defaultManager().selectedPath.firstOrNull() as? JPopupMenu)
            ?.components
            ?.filterIsInstance<JMenuItem>()
            ?.filter { it.isVisible }
            ?.map { it.text ?: "" }
            .orEmpty()

    /** Settings → Tools → Selvage, opened after this answer, since the dialog is modal. */
    private fun settingsPage(project: Project): Map<String, JsonValue> {
        ApplicationManager.getApplication().invokeLater({
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SelvageConfigurable::class.java)
        }, ModalityState.nonModal())
        return emptyMap()
    }

    /** Whether the file in front of each project has been through the IDE's analysis, so its marks are drawn. */
    private fun analyzed(project: Project): Boolean =
        ReadAction.compute<Boolean, RuntimeException> {
            val file = FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile
            val psi = file?.let { PsiManager.getInstance(project).findFile(it) }
            psi == null || DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(psi)
        }

    /**
     * What still moves or covers a frame: indexing, background tasks, analysis still running, a modal
     * dialog, other windows.
     */
    fun report(): Map<String, JsonValue> {
        val projects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        val busy =
            projects.sumOf { project ->
                (WindowManager.getInstance().getStatusBar(project) as? StatusBarEx)?.backgroundProcesses?.size ?: 0
            }
        return mapOf(
            "smart" to JsonValue.Bool(projects.none { DumbService.isDumb(it) }),
            "analyzed" to JsonValue.Bool(projects.all { analyzed(it) }),
            "busy" to JsonValue.Number.of(busy.toLong()),
            "errors" to errors(),
            "modal" to JsonValue.Bool(Window.getWindows().any { it.isShowing && it is Dialog && it.isModal }),
            "windows" to
                JsonValue.Arr(
                    Window.getWindows().filter { it.isShowing }.map {
                        str("${it.javaClass.simpleName}:${(it as? Dialog)?.title ?: (it as? Frame)?.title ?: ""}")
                    },
                ),
        )
    }

    private fun str(value: String) = JsonValue.Str(value)
}
