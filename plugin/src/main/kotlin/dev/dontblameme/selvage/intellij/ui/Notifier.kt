package dev.dontblameme.selvage.intellij.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The "Selvage" notification group: every refusal and notice is one of the shared sentences, shown
 * as VS Code shows it, a button for each follow-up the sentence offers.
 */
object Notifier {
    const val GROUP = "Selvage"

    enum class Level(
        val type: NotificationType,
    ) {
        INFO(NotificationType.INFORMATION),
        WARNING(NotificationType.WARNING),
        ERROR(NotificationType.ERROR),
    }

    data class Said(
        val level: Level,
        val sentence: String,
        val buttons: List<String>,
        val notification: Notification,
    )

    /** What was said, for a reader that has to know (a test, the participants view). */
    val listeners = CopyOnWriteArrayList<(Said) -> Unit>()

    fun info(
        project: Project?,
        sentence: String,
        vararg buttons: Pair<String, () -> Unit>,
    ) = say(project, Level.INFO, sentence, *buttons)

    fun warn(
        project: Project?,
        sentence: String,
        vararg buttons: Pair<String, () -> Unit>,
    ) = say(project, Level.WARNING, sentence, *buttons)

    fun error(
        project: Project?,
        sentence: String,
        vararg buttons: Pair<String, () -> Unit>,
    ) = say(project, Level.ERROR, sentence, *buttons)

    fun say(
        project: Project?,
        level: Level,
        sentence: String,
        vararg buttons: Pair<String, () -> Unit>,
    ): Notification {
        val notification =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(GROUP)
                .createNotification(StringUtil.escapeXmlEntities(sentence), level.type)
        for ((label, act) in buttons) {
            notification.addAction(NotificationAction.createSimpleExpiring(label) { act() })
        }
        notification.notify(project)
        val said = Said(level, sentence, buttons.map { it.first }, notification)
        listeners.forEach { it(said) }
        return said.notification
    }
}
