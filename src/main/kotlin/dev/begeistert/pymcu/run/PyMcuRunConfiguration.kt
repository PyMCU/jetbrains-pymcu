package dev.begeistert.pymcu.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.FormBuilder
import dev.begeistert.pymcu.cli.PyMcuCli
import dev.begeistert.pymcu.console.PyMcuConsoleFilter
import org.jdom.Element
import java.nio.charset.StandardCharsets
import javax.swing.JComponent
import javax.swing.JPanel

/** The `pymcu` sub-commands worth a run configuration. */
val PYMCU_COMMANDS: List<String> = listOf("build", "flash", "clean", "sync", "stubs")

/**
 * Runs one `pymcu` sub-command in the project directory.
 *
 * The process is wired to a console carrying [PyMcuConsoleFilter], so compiler
 * diagnostics become clickable links, and killable so a hung flash can be
 * stopped from the toolbar.
 */
class PyMcuRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<RunnerSettings>(project, factory, name) {

    var command: String = "build"

    /** Extra CLI arguments, as typed by the user (shell-style tokenisation). */
    var arguments: String = ""

    /** `--verbose`: the driver's global diagnostic flag. */
    var verbose: Boolean = false

    /** `build --explain`: report the setup the compiler injected implicitly. */
    var explain: Boolean = false

    /** `build --debug`: emit debug symbols and the line map for the emulator. */
    var debug: Boolean = false

    // ── persistence ──────────────────────────────────────────────────────────

    override fun readExternal(element: Element) {
        super.readExternal(element)
        command = element.getAttributeValue("command") ?: "build"
        arguments = element.getAttributeValue("arguments") ?: ""
        verbose = element.getAttributeValue("verbose")?.toBoolean() ?: false
        explain = element.getAttributeValue("explain")?.toBoolean() ?: false
        debug = element.getAttributeValue("debug")?.toBoolean() ?: false
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("command", command)
        element.setAttribute("arguments", arguments)
        element.setAttribute("verbose", verbose.toString())
        element.setAttribute("explain", explain.toString())
        element.setAttribute("debug", debug.toString())
    }

    override fun getConfigurationEditor(): SettingsEditor<PyMcuRunConfiguration> = Editor()

    // ── execution ────────────────────────────────────────────────────────────

    /** The full argument vector, options folded in. */
    fun buildArguments(): List<String> = buildList {
        if (verbose) add("--verbose")
        add(command)
        if (command == "build") {
            if (explain) add("--explain")
            if (debug) add("--debug")
        }
        addAll(ParametersListUtil.parse(arguments))
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): CommandLineState {
        val basePath = project.basePath
            ?: throw ExecutionException("Cannot determine the project base directory.")
        val executable = PyMcuCli.executable(project)
        val args = buildArguments()

        return object : CommandLineState(environment) {
            init {
                consoleBuilder = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(project)
                    .apply { addFilter(PyMcuConsoleFilter(project)) }
            }

            override fun startProcess(): ProcessHandler {
                val commandLine = GeneralCommandLine(listOf(executable) + args)
                    .withWorkDirectory(basePath)
                    .withCharset(StandardCharsets.UTF_8)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                    .withRedirectErrorStream(true)

                val handler = KillableColoredProcessHandler(commandLine)
                // Prints the "Process finished with exit code N" footer, which is
                // how the user tells a clean build from a failed one at a glance.
                ProcessTerminatedListener.attach(handler, project)
                return handler
            }
        }
    }

    // ── editor ───────────────────────────────────────────────────────────────

    private inner class Editor : SettingsEditor<PyMcuRunConfiguration>() {

        private val commandCombo = ComboBox(PYMCU_COMMANDS.toTypedArray())
        private val argumentsField = JBTextField()
        private val verboseCheck = JBCheckBox("Verbose output (--verbose)")
        private val explainCheck = JBCheckBox("Explain implicit setup (--explain)")
        private val debugCheck = JBCheckBox("Debug symbols and line map (--debug)")

        init {
            commandCombo.addActionListener { updateBuildOptionState() }
        }

        private fun updateBuildOptionState() {
            val isBuild = commandCombo.selectedItem == "build"
            explainCheck.isEnabled = isBuild
            debugCheck.isEnabled = isBuild
        }

        override fun resetEditorFrom(config: PyMcuRunConfiguration) {
            commandCombo.selectedItem = config.command
            argumentsField.text = config.arguments
            verboseCheck.isSelected = config.verbose
            explainCheck.isSelected = config.explain
            debugCheck.isSelected = config.debug
            updateBuildOptionState()
        }

        override fun applyEditorTo(config: PyMcuRunConfiguration) {
            config.command = commandCombo.selectedItem as? String ?: "build"
            config.arguments = argumentsField.text.trim()
            config.verbose = verboseCheck.isSelected
            config.explain = explainCheck.isSelected
            config.debug = debugCheck.isSelected
        }

        override fun createEditor(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Command:"), commandCombo, 1, false)
            .addLabeledComponent(JBLabel("Additional arguments:"), argumentsField, 1, false)
            .addComponent(verboseCheck)
            .addComponent(explainCheck)
            .addComponent(debugCheck)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }
}
