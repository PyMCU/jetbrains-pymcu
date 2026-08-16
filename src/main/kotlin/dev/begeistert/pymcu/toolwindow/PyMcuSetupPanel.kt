package dev.begeistert.pymcu.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.begeistert.pymcu.actions.PyMcuSyncTask
import dev.begeistert.pymcu.configure.PyMcuConfigureDialog
import dev.begeistert.pymcu.project.PyMcuConfigListener
import dev.begeistert.pymcu.run.PyMcuTaskRunner
import dev.begeistert.pymcu.setup.PyMcuSetupState
import dev.begeistert.pymcu.setup.SetupStep
import dev.begeistert.pymcu.setup.StepStatus
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * "Get started": the path from an empty project to a flashed board, with each
 * step showing what is actually true right now.
 *
 * This is the plugin's answer to the VS Code walkthrough, and it is deliberately
 * not a tour — the state is recomputed from disk, so it doubles as the place to
 * look when something does not resolve.
 */
class PyMcuSetupPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val stepsHost = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8, 12, 12, 12)
    }

    private val statusLabel = JBLabel("Checking the project…").apply {
        border = JBUI.Borders.empty(10, 12, 0, 12)
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        add(JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(JBScrollPane(stepsHost).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }, BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(PyMcuConfigListener.TOPIC, PyMcuConfigListener {
            refresh()
        })
        refresh()
    }

    /** Recomputes off the EDT — step 1 probes the CLI. */
    fun refresh() {
        statusLabel.text = "Checking the project…"
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val steps = PyMcuSetupState.compute(project)
            SwingUtilities.invokeLater { if (!project.isDisposed) render(steps) }
        }
    }

    private fun render(steps: List<SetupStep>) {
        stepsHost.removeAll()

        val remaining = steps.count { it.status != StepStatus.DONE }
        statusLabel.text = if (remaining == 0)
            "Everything is set up. Edit your sources and build."
        else
            "$remaining step(s) left before you can flash."

        for (step in steps) {
            stepsHost.add(row(step))
            stepsHost.add(Box.createVerticalStrut(JBUI.scale(4)))
        }
        stepsHost.revalidate()
        stepsHost.repaint()
    }

    private fun row(step: SetupStep): JPanel = JPanel(GridBagLayout()).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(6, 0)

        val icon = JBLabel(
            when (step.status) {
                StepStatus.DONE -> AllIcons.General.InspectionsOK
                StepStatus.PENDING -> AllIcons.General.Information
                StepStatus.BLOCKED -> AllIcons.General.Warning
            }
        )

        val title = JBLabel(step.title).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            if (step.status == StepStatus.DONE) foreground = UIUtil.getContextHelpForeground()
        }

        val detail = JBLabel("<html><body style='width:420px'>${escape(step.detail)}</body></html>")
            .apply { foreground = UIUtil.getContextHelpForeground() }

        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(0, 0, 0, 8)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridheight = 2
        add(icon, gbc)

        gbc.gridheight = 1
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        add(title, gbc)

        gbc.gridy = 1
        add(detail, gbc)

        // A finished step keeps its action — re-syncing or rebuilding is normal.
        val label = step.actionLabel
        if (label != null) {
            gbc.gridx = 2; gbc.gridy = 0; gbc.gridheight = 2
            gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            gbc.insets = Insets(0, JBUI.scale(12), 0, 0)
            add(ActionLink(label) { runStep(step) }.apply {
                isEnabled = step.status != StepStatus.BLOCKED
            }, gbc)
        }
    }

    private fun runStep(step: SetupStep) {
        when (step.id) {
            "cli" -> BrowserUtil.browse("https://github.com/PyMCU/PyMCU#readme")
            "target" -> PyMcuConfigureDialog.show(project)
            "deps", "generated" -> PyMcuSyncTask.launch(project)
            "build" -> PyMcuTaskRunner.run(project, "build")
            "flash" -> PyMcuTaskRunner.run(project, "flash")
        }
        // The action runs in the background; re-check once it has had a moment.
        refresh()
    }

    private fun escape(text: String): String =
        com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text)

    override fun dispose() = Unit
}
