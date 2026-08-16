package dev.begeistert.pymcu.setup

import com.intellij.openapi.project.Project
import dev.begeistert.pymcu.cli.PyMcuCli
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.venv.PyMcuVenv
import java.io.File

enum class StepStatus { DONE, PENDING, BLOCKED }

data class SetupStep(
    val id: String,
    val title: String,
    val status: StepStatus,
    /** What is true right now — the reason the step reads as it does. */
    val detail: String,
    val actionLabel: String?,
)

/**
 * What still stands between this project and a flashed board.
 *
 * WHY a computed checklist rather than a static tour: a walkthrough tells the
 * same story to someone who has already synced and someone who has not
 * installed the CLI. Every step here is derived from what is actually on disk,
 * so the panel is also a diagnostic — "why does `import board` not resolve" is
 * answered by whichever step is not yet green.
 *
 * Blocking; call off the EDT.
 */
object PyMcuSetupState {

    fun compute(project: Project): List<SetupStep> {
        val basePath = project.basePath
        val config = PyMcuProjectService.config(project)
        val steps = mutableListOf<SetupStep>()

        // ── 1. the CLI ───────────────────────────────────────────────────────
        val version = PyMcuCli.run(project, "--version", timeoutMs = 15_000)
        val cliFound = version.started
        steps += SetupStep(
            id = "cli",
            title = "Install the PyMCU CLI",
            status = if (cliFound) StepStatus.DONE else StepStatus.PENDING,
            detail = if (cliFound)
                "Found at ${PyMcuCli.executable(project)}."
            else
                "Not found. Install with `pipx install pymcu-compiler`, or set the path in " +
                    "Settings | Tools | PyMCU.",
            actionLabel = if (cliFound) null else "Installation guide",
        )

        // Everything below shells out to the CLI, so without it they cannot even
        // be attempted — reporting them as "pending" would be misleading.
        val blocked = !cliFound

        // ── 2. the target ────────────────────────────────────────────────────
        val hasTarget = config != null && !config.hasNoTarget && !config.hasConflictingTarget
        steps += SetupStep(
            id = "target",
            title = "Choose a board",
            status = if (hasTarget) StepStatus.DONE else StepStatus.PENDING,
            detail = when {
                config == null -> "No [tool.pymcu] section in pyproject.toml."
                config.hasConflictingTarget -> "`board` and `target` are both set; the build refuses both."
                config.hasNoTarget -> "No board or target set."
                else -> {
                    val flavor = config.flavor?.let { ", $it compat" } ?: ", native HAL"
                    "Targeting ${config.targetLabel}$flavor."
                }
            },
            actionLabel = "Configure Project…",
        )

        // ── 3. dependencies ──────────────────────────────────────────────────
        val sitePackages = basePath?.let { PyMcuVenv.sitePackages(it) }
        val flavor = config?.flavor
        val compatInstalled = flavor == null ||
            sitePackages?.resolve("pymcu_$flavor")?.toFile()?.isDirectory == true
        // `pymcu` itself, not just the compat layer: for a native-HAL project
        // there is no flavor to look for, so checking only that left the step
        // green for an empty virtualenv, and the first build then failed on a
        // missing toolchain with the checklist still saying everything was fine.
        val stdlibInstalled = sitePackages?.resolve("pymcu")?.toFile()?.isDirectory == true
        val depsReady = sitePackages != null && compatInstalled && stdlibInstalled
        steps += SetupStep(
            id = "deps",
            title = "Install dependencies",
            status = when {
                blocked -> StepStatus.BLOCKED
                depsReady -> StepStatus.DONE
                else -> StepStatus.PENDING
            },
            detail = when {
                sitePackages == null -> "No virtualenv found in this project."
                !stdlibInstalled -> "`pymcu-stdlib` is not installed in the project virtualenv."
                !compatInstalled -> "`pymcu-$flavor` is not installed in the project virtualenv."
                else -> "Virtualenv ready at ${sitePackages.parent.parent.parent.fileName}/."
            },
            actionLabel = "Sync Project",
        )

        // ── 4. the generated board module ────────────────────────────────────
        val boardModule = basePath?.let { File(it, "dist/_generated/board.py") }?.isFile == true
        steps += SetupStep(
            id = "generated",
            title = "Generate the board module",
            status = when {
                blocked -> StepStatus.BLOCKED
                boardModule -> StepStatus.DONE
                // The build generates the shim whenever `board` is set, whatever
                // the flavor — `import board` is legal in a MicroPython project
                // too. With no board there is nothing to generate.
                config?.board == null -> StepStatus.DONE
                else -> StepStatus.PENDING
            },
            detail = when {
                boardModule -> "dist/_generated/board.py matches the configured board."
                config?.board == null -> "Not needed: no board is set, so there is none to generate."
                // `pymcu sync` needs a compat flavor and does nothing without one,
                // so for a native-HAL project only a build can produce the shim.
                flavor == null -> "Missing — a build will generate it."
                else -> "Missing — `import board` will not resolve until you sync."
            },
            actionLabel = if (config?.board != null && flavor == null) "Build" else "Sync Project",
        )

        // ── 5. the first build ───────────────────────────────────────────────
        val firmware = basePath
            ?.let { File(it, "dist") }
            ?.listFiles { f -> f.isFile && f.extension in FIRMWARE_EXTENSIONS }
            ?.firstOrNull()
        steps += SetupStep(
            id = "build",
            title = "Build the firmware",
            status = when {
                blocked -> StepStatus.BLOCKED
                firmware != null -> StepStatus.DONE
                else -> StepStatus.PENDING
            },
            detail = firmware?.let { "Last artifact: dist/${it.name}." }
                ?: "Nothing built yet.",
            actionLabel = "Build",
        )

        // ── 6. flashing ──────────────────────────────────────────────────────
        steps += SetupStep(
            id = "flash",
            title = "Flash the board",
            status = when {
                blocked || firmware == null -> StepStatus.BLOCKED
                else -> StepStatus.PENDING
            },
            detail = config?.flash?.port?.let { "Port pinned to $it." }
                ?: "No port pinned; you will be asked which one to use.",
            actionLabel = "Flash",
        )

        return steps
    }

    /** Artifacts `pymcu flash` looks for, across the AVR, PIC and RP targets. */
    private val FIRMWARE_EXTENSIONS = setOf("hex", "uf2", "bin", "elf")
}
