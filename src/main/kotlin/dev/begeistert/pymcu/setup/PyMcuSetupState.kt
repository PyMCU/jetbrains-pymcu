package dev.begeistert.pymcu.setup

import com.intellij.openapi.project.Project
import dev.begeistert.pymcu.actions.PyMcuSyncTask
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
        val depsReady = sitePackages != null && compatInstalled
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
                !compatInstalled -> "`pymcu-$flavor` is not installed in the project virtualenv."
                else -> "Virtualenv ready at ${sitePackages.parent.parent.parent.fileName}/."
            },
            actionLabel = "Sync Project",
        )

        // ── 4. generated IDE support ─────────────────────────────────────────
        val generated = basePath?.let { File(it, "dist/_generated") }?.isDirectory == true
        val stubs = basePath?.let { File(it, PyMcuSyncTask.STUBS_DIR) }?.isDirectory == true
        steps += SetupStep(
            id = "generated",
            title = "Generate IDE support files",
            status = when {
                blocked -> StepStatus.BLOCKED
                generated && stubs -> StepStatus.DONE
                else -> StepStatus.PENDING
            },
            detail = when {
                generated && stubs -> "Board module and typed stubs are in dist/_generated."
                generated -> "Board module present; typed stubs missing, so completions are untyped."
                else -> "Missing — `import board` and the compat imports will not resolve."
            },
            actionLabel = "Sync Project",
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
