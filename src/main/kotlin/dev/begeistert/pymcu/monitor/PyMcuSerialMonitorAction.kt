package dev.begeistert.pymcu.monitor

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.begeistert.pymcu.actions.PyMcuAction
import dev.begeistert.pymcu.cli.SerialPorts
import dev.begeistert.pymcu.notifications.PyMcuNotifications
import dev.begeistert.pymcu.project.PyMcuProjectService
import java.nio.charset.StandardCharsets

/**
 * Opens a console streaming the board's UART output.
 *
 * The port comes from `[tool.pymcu.flash] port` when it is pinned, and from a
 * picker otherwise — the same order `pymcu flash` uses, so the monitor listens
 * to the board the flash just wrote to.
 */
class PyMcuSerialMonitorAction : PyMcuAction(
    "Serial Monitor",
    "Read what the firmware prints over UART",
    AllIcons.Actions.Show
) {

    override fun perform(project: Project) {
        val config = PyMcuProjectService.config(project)
        val baud = config?.stdoutBaud ?: PyMcuSerialMonitor.DEFAULT_BAUD

        val pinned = config?.flash?.port
        if (pinned != null) {
            open(project, pinned, baud)
            return
        }

        val ports = SerialPorts.list()
        when {
            ports.isEmpty() -> PyMcuNotifications.warn(
                project, "No serial port found",
                "Connect the board, or pin one with <code>[tool.pymcu.flash] port</code>.",
            )
            ports.size == 1 -> open(project, ports.single(), baud)
            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(ports)
                .setTitle("Serial Port to Monitor")
                .setItemChosenCallback { open(project, it, baud) }
                .createPopup()
                .showCenteredInCurrentWindow(project)
        }
    }

    private fun open(project: Project, port: String, baud: Int) {
        when (val plan = PyMcuSerialMonitor.plan(port, baud)) {
            is PyMcuSerialMonitor.Plan.Unsupported ->
                PyMcuNotifications.warn(project, "Serial monitor", plan.reason)

            is PyMcuSerialMonitor.Plan.Command -> ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                start(project, plan.argv, port, baud)
            }, project.disposed)
        }
    }

    private fun start(project: Project, argv: List<String>, port: String, baud: Int) {
        try {
            val commandLine = GeneralCommandLine(argv)
                .withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            val handler = KillableColoredProcessHandler(commandLine)

            RunContentExecutor(project, handler)
                .withTitle(PyMcuSerialMonitor.title(port, baud))
                // Reconnecting after unplugging the board is the common case, and
                // it is the same command every time.
                .withRerun { PyMcuSerialMonitorAction().open(project, port, baud) }
                .withActivateToolWindow(true)
                .run()
        } catch (e: Exception) {
            PyMcuNotifications.error(
                project, "Serial monitor",
                "Could not open $port: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }
}
