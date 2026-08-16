package dev.begeistert.pymcu.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil

/**
 * Launches a `pymcu` sub-command through the run/debug framework rather than a
 * raw process.
 *
 * WHY: the framework gives the console its stop button, the rerun action, the
 * clickable compiler diagnostics from [dev.begeistert.pymcu.console.PyMcuConsoleFilter]
 * and a visible exit code — all of which the plugin's previous "append the output
 * to a JTextArea" approach lacked.
 */
object PyMcuTaskRunner {

    /**
     * Runs `pymcu <command> [extraArgs]`, reusing an existing configuration for
     * the same command when the user has saved one, and otherwise creating a
     * temporary configuration that does not clutter the run-configuration list.
     */
    fun run(project: Project, command: String, extraArgs: List<String> = emptyList()) {
        val settings = existingConfiguration(project, command, extraArgs)
            ?: temporaryConfiguration(project, command, extraArgs)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    private fun existingConfiguration(
        project: Project,
        command: String,
        extraArgs: List<String>
    ): RunnerAndConfigurationSettings? =
        RunManager.getInstance(project)
            .getConfigurationSettingsList(PyMcuRunConfigurationType.instance())
            .firstOrNull { settings ->
                val config = settings.configuration as? PyMcuRunConfiguration ?: return@firstOrNull false
                config.command == command && ParametersListUtil.parse(config.arguments) == extraArgs
            }

    private fun temporaryConfiguration(
        project: Project,
        command: String,
        extraArgs: List<String>
    ): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val name = if (extraArgs.isEmpty()) "pymcu $command"
                   else "pymcu $command ${extraArgs.joinToString(" ")}"
        val settings = runManager.createConfiguration(name, PyMcuRunConfigurationType.factory())
        (settings.configuration as PyMcuRunConfiguration).apply {
            this.command = command
            this.arguments = ParametersListUtil.join(extraArgs)
        }
        settings.isTemporary = true
        runManager.setTemporaryConfiguration(settings)
        return settings
    }
}
