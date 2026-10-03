package dev.dontblameme.selvage.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import dev.dontblameme.selvage.intellij.bridge.Initials
import dev.dontblameme.selvage.intellij.bridge.People
import dev.dontblameme.selvage.intellij.bridge.Say
import dev.dontblameme.selvage.intellij.session.PresenceRenderer
import dev.dontblameme.selvage.intellij.session.SelvageService
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList

/**
 * The Participants tool window, VS Code's Participants view: everyone in the room, you first, each in
 * their seat colour with the where-line, and the person's actions (Go to, Follow or Stop following,
 * Rename on your own row) in the toolbar, the context menu and a double click.
 */
class ParticipantsToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = ParticipantsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}

class ParticipantsPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true),
    com.intellij.openapi.Disposable {
    private val model = DefaultListModel<People.PersonRow>()
    val list = JBList(model)
    private val listener: () -> Unit = { refresh() }

    init {
        list.cellRenderer =
            object : ColoredListCellRenderer<People.PersonRow>() {
                override fun customizeCellRenderer(
                    list: JList<out People.PersonRow>,
                    row: People.PersonRow,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    icon = PresenceRenderer.InitialsIcon(row.initials, PresenceRenderer.parse(row.colour))
                    append(row.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (row.description.isNotEmpty()) {
                        append(
                            "  ${row.description}",
                            SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        )
                    }
                    toolTipText = row.tooltip
                }
            }
        list.emptyText.text = "Share a folder with a friend and edit the same files. They join from a browser."
        list.emptyText.appendSecondaryText(Say.HOST, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            SelvageService.get().host(project)
        }
        list.emptyText.appendLine(Say.JOIN, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            SelvageService.get().join(project)
        }
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) selected()?.let { if (!it.self && it.path != null) act(People.Act.GO_TO) }
                }
            },
        )
        val actions =
            DefaultActionGroup(
                RowAction(People.Act.GO_TO, AllIcons.Actions.Forward),
                RowAction(People.Act.FOLLOW, AllIcons.Actions.Show),
                RowAction(People.Act.STOP_FOLLOWING, AllIcons.Actions.Cancel),
                RowAction(People.Act.RENAME, AllIcons.Actions.Edit),
                ActionManager.getInstance().getAction("Selvage.CopyInvite"),
            )
        val toolbar = ActionManager.getInstance().createActionToolbar("SelvageParticipants", actions, true)
        toolbar.targetComponent = list
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(list))
        com.intellij.ui.PopupHandler
            .installPopupMenu(list, actions, "SelvageParticipantsPopup")
        SelvageService.get().sessionChanged.add(listener)
        refresh()
    }

    fun rows(): List<People.PersonRow> = (0 until model.size()).map { model.get(it) }

    fun refresh() {
        val rows = SelvageService.get().sessionFor(project)?.rows() ?: emptyList()
        if (rows == rows()) return
        val chosen = selected()?.peerId
        model.clear()
        rows.forEach(model::addElement)
        rows.indexOfFirst { it.peerId == chosen }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
    }

    private fun selected(): People.PersonRow? = list.selectedValue

    /** Whether [act] applies to the selected row, as VS Code's `view/item/context` menus gate it. */
    fun applies(act: People.Act): Boolean {
        val row = selected() ?: return false
        return act in People.personActs(row)
    }

    fun act(act: People.Act) {
        val row = selected() ?: return
        val session = SelvageService.get().sessionFor(project) ?: return
        SelvageService.get().act(session, row.peerId, act)
    }

    private inner class RowAction(
        private val act: People.Act,
        icon: javax.swing.Icon,
    ) : DumbAwareAction(act.label, null, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = applies(act)
        }

        override fun actionPerformed(e: AnActionEvent) = act(act)
    }

    override fun dispose() {
        SelvageService.get().sessionChanged.remove(listener)
    }
}

/**
 * VS Code's Explorer badges: a room file someone is in wears their initials in their seat colour, or
 * the count when several are, with everyone named in the tooltip.
 */
class PeerBadgeDecorator : ProjectViewNodeDecorator {
    override fun decorate(
        node: ProjectViewNode<*>,
        data: PresentationData,
    ) {
        val project = node.project ?: return
        val file = node.virtualFile ?: return
        if (file.isDirectory) return
        val session = SelvageService.get().sessionFor(project) ?: return
        val people = session.peopleIn(file).distinctBy { it.label }.sortedBy { it.label }
        if (people.isEmpty()) return
        val badge = if (people.size == 1) Initials.initials(people.first().label) else people.size.toString()
        val names = people.map { it.label }
        val colour = if (people.size == 1) PresenceRenderer.parse(people.first().colour) else null
        if (data.coloredText.isEmpty()) {
            data.addText(
                data.presentableText ?: file.name,
                SimpleTextAttributes.REGULAR_ATTRIBUTES,
            )
        }
        data.addText(" $badge", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, colour))
        data.tooltip = if (names.size == 1) "${names.first()} is here" else "${names.joinToString(", ")} are here"
    }
}
