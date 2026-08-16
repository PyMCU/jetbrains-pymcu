package dev.begeistert.pymcu.lint

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import dev.begeistert.pymcu.notifications.PyMcuNotifications

/** Fired when a whole-project lint run finishes. */
fun interface PyMcuLintResultsListener {
    fun lintFinished(report: LintReport?)

    companion object {
        val TOPIC: Topic<PyMcuLintResultsListener> =
            Topic.create("PyMCU lint results", PyMcuLintResultsListener::class.java)
    }
}

/** Holds the last whole-project lint report so the tool window can render it. */
@Service(Service.Level.PROJECT)
class PyMcuLintResults {
    @Volatile
    var report: LintReport? = null
        internal set

    companion object {
        fun getInstance(project: Project): PyMcuLintResults =
            project.getService(PyMcuLintResults::class.java)
    }
}

/** Runs the porting assistant over the whole project and shows the findings. */
object PyMcuLintRunner {

    fun lintProject(project: Project) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Running the PyMCU porting assistant", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val report = PyMcuLint.lintSources(project)
                    if (project.isDisposed) return

                    PyMcuLintResults.getInstance(project).report = report
                    project.messageBus.syncPublisher(PyMcuLintResultsListener.TOPIC).lintFinished(report)

                    if (report == null) {
                        PyMcuNotifications.warn(
                            project, "PyMCU porting assistant",
                            "Could not run `pymcu lint`. Check the executable in Settings | Tools | PyMCU."
                        )
                        return
                    }
                    showToolWindow(project)
                    notifySummary(project, report)
                }
            }
        )
    }

    private fun showToolWindow(project: Project) {
        ToolWindowManager.getInstance(project).invokeLater {
            ToolWindowManager.getInstance(project).getToolWindow("PyMCU")?.show(null)
        }
    }

    private fun notifySummary(project: Project, report: LintReport) {
        val findings = report.allFindings
        val errors = findings.count { it.severity == "error" }
        val warnings = findings.count { it.severity == "warn" }
        val flavor = report.flavor?.let { " (flavor: $it)" } ?: ""

        if (findings.isEmpty()) {
            PyMcuNotifications.info(
                project, "PyMCU porting assistant",
                "No findings$flavor — this should port cleanly."
            )
        } else {
            PyMcuNotifications.info(
                project, "PyMCU porting assistant",
                "${findings.size} finding(s)$flavor: $errors error(s), $warnings warning(s)."
            )
        }
    }
}
