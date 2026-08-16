package dev.begeistert.pymcu.notifications

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Balloon notifications from the "PyMCU" group registered in plugin.xml. */
object PyMcuNotifications {

    private const val GROUP_ID = "PyMCU"

    fun info(project: Project?, title: String, content: String, vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.INFORMATION, *actions)

    fun warn(project: Project?, title: String, content: String, vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.WARNING, *actions)

    fun error(project: Project?, title: String, content: String, vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.ERROR, *actions)

    private fun notify(
        project: Project?,
        title: String,
        content: String,
        type: NotificationType,
        vararg actions: NotificationAction
    ) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID) ?: return
        val notification = group.createNotification(title, content, type)
        actions.forEach(notification::addAction)
        notification.notify(project)
    }

    /** Convenience for the frequent "do X" follow-up button. */
    fun action(text: String, run: () -> Unit): NotificationAction =
        NotificationAction.createSimpleExpiring(text, run)
}
