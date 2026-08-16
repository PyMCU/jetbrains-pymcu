package dev.begeistert.pymcu.newproject

import com.intellij.facet.ui.ValidationResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGenerator
import com.intellij.platform.ProjectGeneratorPeer
import dev.begeistert.pymcu.PyMcuIcons
import dev.begeistert.pymcu.actions.PyMcuSyncTask
import dev.begeistert.pymcu.project.PyMcuProjectService
import javax.swing.Icon

/**
 * Adds "PyMCU" to the New Project wizard.
 *
 * Scaffolds `pyproject.toml` and a blink starter for the chosen board, then
 * runs the same sync path as the Sync action — dependencies, generated board
 * module and IDE stubs — under a visible progress indicator.
 */
class PyMcuProjectGenerator : DirectoryProjectGenerator<PyMcuNewProjectSettings> {

    private val log = Logger.getInstance(PyMcuProjectGenerator::class.java)

    override fun getName(): String = "PyMCU"

    override fun getDescription(): String =
        "Compile Python to bare-metal firmware for AVR, RP2040/RP2350, PIC and RISC-V targets."

    override fun getLogo(): Icon = PyMcuIcons.PyMcu

    override fun validate(baseDirPath: String): ValidationResult = ValidationResult.OK

    override fun createPeer(): ProjectGeneratorPeer<PyMcuNewProjectSettings> = PyMcuProjectGeneratorPeer()

    override fun generateProject(
        project: Project,
        baseDir: VirtualFile,
        settings: PyMcuNewProjectSettings,
        module: Module
    ) {
        val projectName = project.name.ifBlank { baseDir.name }

        ApplicationManager.getApplication().runWriteAction {
            try {
                write(baseDir, "pyproject.toml", buildPyproject(projectName, settings))
                val sources = baseDir.findChild("src") ?: baseDir.createChildDirectory(this, "src")
                write(sources, "main.py", buildMainPy(settings))
                write(baseDir, ".gitignore", GITIGNORE)
            } catch (e: Exception) {
                log.error("Failed to scaffold the PyMCU project", e)
            }
        }

        PyMcuProjectService.getInstance(project).invalidate()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Setting up the PyMCU project", true) {
                override fun run(indicator: ProgressIndicator) = PyMcuSyncTask.execute(project, indicator)
            }
        )
    }

    // ── scaffolding ──────────────────────────────────────────────────────────

    private fun write(dir: VirtualFile, name: String, content: String) {
        dir.findOrCreateChildData(this, name).setBinaryContent(content.toByteArray(Charsets.UTF_8))
    }

    /**
     * Emits the current schema: `board` (or `target` for a bare chip), never the
     * deprecated `chip` key, plus the `[tool.pymcu.flash]` table the driver reads.
     */
    private fun buildPyproject(name: String, settings: PyMcuNewProjectSettings): String {
        val targetLine = settings.board
            ?.let { """board = "$it"""" }
            ?: """target = "${settings.chip ?: "atmega328p"}""""

        val stdlibDependency = settings.stdlib
            .takeIf { it.isNotEmpty() }
            ?.let { "\n    \"pymcu-$it\"," }
            .orEmpty()
        val stdlibLine = settings.stdlib
            .takeIf { it.isNotEmpty() }
            ?.let { "\nstdlib = [\"$it\"]" }
            .orEmpty()

        return """
            [project]
            name = "$name"
            version = "0.1.0"
            requires-python = ">=3.11"
            dependencies = [
                "pymcu-stdlib",
                "pymcu-compiler",$stdlibDependency
            ]

            [tool.pymcu]
            $targetLine
            frequency = ${settings.frequency}
            sources = "src"
            entry = "main.py"$stdlibLine
        """.trimIndent() + "\n"
    }

    private fun buildMainPy(settings: PyMcuNewProjectSettings): String {
        val target = settings.board ?: settings.chip ?: "atmega328p"
        return when (settings.stdlib) {
            "micropython" -> microPythonBlink(target)
            "circuitpython" -> circuitPythonBlink(target)
            else -> nativeBlink(target)
        }
    }

    /**
     * The compat flavors scaffold as top-level scripts, matching what
     * `pymcu new` emits — that is how MicroPython and CircuitPython code is
     * written, and the compiler accepts both shapes.
     */
    private fun microPythonBlink(target: String): String = """
        # PyMCU — target: $target  ·  MicroPython compat
        from machine import Pin
        from time import sleep_ms

        led = Pin(13, Pin.OUT)

        while True:
            led.value(1)
            sleep_ms(500)
            led.value(0)
            sleep_ms(500)
    """.trimIndent() + "\n"

    private fun circuitPythonBlink(target: String): String = """
        # PyMCU — target: $target  ·  CircuitPython compat
        import board
        import digitalio
        import time

        led = digitalio.DigitalInOut(board.LED)
        led.direction = digitalio.Direction.OUTPUT

        while True:
            led.value = True
            time.sleep(0.5)
            led.value = False
            time.sleep(0.5)
    """.trimIndent() + "\n"

    private fun nativeBlink(target: String): String = """
        # PyMCU — target: $target  ·  native HAL
        #
        # The native pymcu.hal API is still moving during the alpha. If you want an
        # API that will not change under you, pick a compat stdlib instead.
        from pymcu.hal.gpio import Pin
        from pymcu.time import delay_ms


        def main():
            led = Pin("PB5", Pin.OUT)

            while True:
                led.high()
                delay_ms(500)
                led.low()
                delay_ms(500)
    """.trimIndent() + "\n"

    private companion object {
        val GITIGNORE = """
            __pycache__/
            *.py[cod]
            .venv/
            venv/
            dist/
            build/
        """.trimIndent() + "\n"
    }
}
