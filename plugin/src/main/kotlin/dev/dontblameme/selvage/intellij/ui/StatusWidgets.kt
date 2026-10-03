package dev.dontblameme.selvage.intellij.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import dev.dontblameme.selvage.intellij.bridge.Say
import dev.dontblameme.selvage.intellij.session.SelvageService
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * The status bar's three controls, as VS Code's: the session row (its identity, `Reconnecting…` or
 * the host-away countdown; a click opens the Selvage menu), the invite control (`Copy invite link`,
 * `Copied` for a moment after a copy) and, while a follow lasts, the follow control that stops it.
 */
object StatusWidgets {
    const val SESSION_ID = "Selvage.Session"
    const val INVITE_ID = "Selvage.Invite"
    const val FOLLOW_ID = "Selvage.Follow"
    const val GROUP_ID = "Selvage.Group"

    fun sessionText(project: Project): String? {
        val service = SelvageService.get()
        val session = service.sessionFor(project)
        if (session == null) return if (service.connecting) Say.CONNECTING else null
        return session.statusText()
    }

    fun inviteText(project: Project): String? {
        val session = SelvageService.get().sessionFor(project) ?: return null
        if (session.invite() == null) return null
        return session.inviteLabel()
    }

    fun followText(project: Project): String? = SelvageService.get().sessionFor(project)?.followLabel()

    /** Redraws the three controls in every open project, adding or removing them as the session asks. */
    fun refresh(project: Project) {
        if (project.isDisposed) return
        val manager = project.getService(StatusBarWidgetsManager::class.java)
        manager.updateWidget(SessionWidgetFactory::class.java)
        manager.updateWidget(InviteWidgetFactory::class.java)
        manager.updateWidget(FollowWidgetFactory::class.java)
        val bar = WindowManager.getInstance().getStatusBar(project) ?: return
        bar.updateWidget(SESSION_ID)
        bar.updateWidget(INVITE_ID)
        bar.updateWidget(FOLLOW_ID)
    }

    fun showMenu(
        project: Project,
        component: Component,
    ) {
        val group = ActionManager.getInstance().getAction(GROUP_ID) as? ActionGroup ?: return
        JBPopupFactory
            .getInstance()
            .createActionGroupPopup(
                "Selvage",
                group,
                SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
            ).show(RelativePoint.getNorthWestOf(component as javax.swing.JComponent))
    }
}

private abstract class TextWidget(
    protected val project: Project,
    private val id: String,
) : StatusBarWidget,
    StatusBarWidget.TextPresentation {
    override fun ID(): String = id

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {}

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

    override fun dispose() {}
}

private class SessionWidget(
    project: Project,
) : TextWidget(project, StatusWidgets.SESSION_ID) {
    override fun getText(): String = StatusWidgets.sessionText(project) ?: ""

    override fun getTooltipText(): String = "Selvage: ${getText()}"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { StatusWidgets.showMenu(project, it.component) }
}

private class InviteWidget(
    project: Project,
) : TextWidget(project, StatusWidgets.INVITE_ID) {
    override fun getText(): String = StatusWidgets.inviteText(project) ?: ""

    override fun getTooltipText(): String = Say.COPY_INVITE

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { SelvageService.get().copyInvite(project) }
}

private class FollowWidget(
    project: Project,
) : TextWidget(project, StatusWidgets.FOLLOW_ID) {
    override fun getText(): String = StatusWidgets.followText(project) ?: ""

    override fun getTooltipText(): String = "${getText()}; select to stop following"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { SelvageService.get().stopFollowing(project) }
}

abstract class SelvageWidgetFactory(
    private val id: String,
    private val name: String,
) : StatusBarWidgetFactory {
    override fun getId(): String = id

    override fun getDisplayName(): String = name

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class SessionWidgetFactory : SelvageWidgetFactory(StatusWidgets.SESSION_ID, "Selvage session") {
    override fun isAvailable(project: Project): Boolean = StatusWidgets.sessionText(project) != null

    override fun createWidget(project: Project): StatusBarWidget = SessionWidget(project)
}

class InviteWidgetFactory : SelvageWidgetFactory(StatusWidgets.INVITE_ID, "Selvage invite") {
    override fun isAvailable(project: Project): Boolean = StatusWidgets.inviteText(project) != null

    override fun createWidget(project: Project): StatusBarWidget = InviteWidget(project)
}

class FollowWidgetFactory : SelvageWidgetFactory(StatusWidgets.FOLLOW_ID, "Selvage follow") {
    override fun isAvailable(project: Project): Boolean = StatusWidgets.followText(project) != null

    override fun createWidget(project: Project): StatusBarWidget = FollowWidget(project)
}
