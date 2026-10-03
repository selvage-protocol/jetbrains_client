package dev.dontblameme.selvage.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.dontblameme.selvage.intellij.bridge.Say
import dev.dontblameme.selvage.intellij.session.SelvageService

/**
 * The twelve commands, titled as the other clients title them (§5 of the parity study). Each is
 * enabled in every state, as each is in VS Code's palette: a command that cannot act says why in the
 * shared sentence rather than greying out.
 */
abstract class SelvageAction(
    title: String,
    private val needsProject: Boolean,
) : AnAction(title),
    DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = !needsProject || event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        if (needsProject && project == null) return
        run(SelvageService.get(), project)
    }

    abstract fun run(
        service: SelvageService,
        project: Project?,
    )
}

class HostAction : SelvageAction(Say.HOST, needsProject = true) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.host(project!!)
}

class JoinAction : SelvageAction(Say.JOIN, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.join(project)
}

class CopyInviteAction : SelvageAction(Say.COPY_INVITE, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.copyInvite(project)
}

class OpenDocumentAction : SelvageAction(Say.OPEN_DOCUMENT, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.openDocument(project)
}

class FetchAction : SelvageAction(Say.FETCH, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.fetch(project)
}

class LeaveAction : SelvageAction(Say.LEAVE, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.leave(project)
}

class DisplayNameAction : SelvageAction(Say.DISPLAY_NAME, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.displayName(project)
}

class ChangeServerAction : SelvageAction(Say.CHANGE_SERVER, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.changeServer(project)
}

class PeersAction : SelvageAction(Say.PEERS, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.peers(project)
}

class GoToParticipantAction : SelvageAction(Say.GO_TO, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.goToParticipant(project)
}

class FollowParticipantAction : SelvageAction(Say.FOLLOW, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.followParticipant(project)
}

class StopFollowingAction : SelvageAction(Say.STOP_FOLLOWING, needsProject = false) {
    override fun run(
        service: SelvageService,
        project: Project?,
    ) = service.stopFollowing(project)
}
