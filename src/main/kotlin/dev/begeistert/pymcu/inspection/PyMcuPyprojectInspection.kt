package dev.begeistert.pymcu.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.config.PyMcuConfigReader
import dev.begeistert.pymcu.config.TomlWriter
import dev.begeistert.pymcu.configure.PyMcuConfigureDialog

private const val SECTION = "tool.pymcu"

/**
 * Flags `[tool.pymcu]` configurations the driver will reject or warn about, and
 * offers the edit that fixes each.
 *
 * The conditions are the driver's, not the plugin's: `build.py` refuses a
 * `board` and a `target` together, warns on the deprecated `chip` key, and
 * cannot do anything without one of the three. Catching them in the editor
 * turns a failed build into a squiggle.
 *
 * WHY it works on raw text rather than TOML PSI: `org.toml.lang` is bundled in
 * PyCharm but is not guaranteed present in every IDE this plugin runs in, and an
 * optional dependency for three text ranges buys nothing. `checkFile` runs for
 * any file type, so this behaves identically with or without the TOML plugin.
 */
class PyMcuPyprojectInspection : LocalInspectionTool() {

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean
    ): Array<ProblemDescriptor>? {
        if (file.name != "pyproject.toml") return null
        val text = file.text
        val config = PyMcuConfigReader.parseContent(text) ?: return null

        val problems = mutableListOf<ProblemDescriptor>()

        fun report(range: IntRange?, message: String, error: Boolean, vararg fixes: LocalQuickFix) {
            val textRange = range?.let { TextRange(it.first, it.last + 1) }
                ?: TomlWriter.sectionHeaderRange(text, SECTION)
                    ?.let { TextRange(it.first, it.last + 1) }
                ?: TextRange(0, minOf(text.length, 1))
            problems += manager.createProblemDescriptor(
                file, textRange, message,
                if (error) ProblemHighlightType.GENERIC_ERROR
                else ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly, *fixes
            )
        }

        // `chip` is the pre-rename spelling. The driver still honours it and
        // prints a deprecation, so this is a warning with a one-click migration.
        if (config.usesDeprecatedChipKey) {
            report(
                TomlWriter.keyRange(text, SECTION, "chip"),
                "`chip` is deprecated — use `target` for a bare chip, or `board` for a known board.",
                error = false,
                RenameKeyFix("chip", "target"),
            )
        }

        // The driver exits with an error rather than guessing which one wins.
        if (config.hasConflictingTarget) {
            val implied = config.board?.let {
                PyMcuBoardCatalogService.getInstance(file.project).cachedOrFallback().chipOf(it)
            }
            val detail = implied?.let { " `board = \"${config.board}\"` already implies `$it`." } ?: ""
            val explicitKey = if (config.target != null) "target" else "chip"
            report(
                TomlWriter.keyRange(text, SECTION, "board"),
                "`board` and `$explicitKey` are mutually exclusive — the build will refuse both.$detail",
                error = true,
                RemoveKeyFix(explicitKey, "Remove `$explicitKey`, keep the board"),
                RemoveKeyFix("board", "Remove `board`, keep `$explicitKey`"),
            )
        }

        if (config.hasNoTarget) {
            report(
                null,
                "No target: set `board = \"…\"` for a known board, or `target = \"…\"` for a bare chip.",
                error = true,
                OpenConfigureDialogFix(),
            )
        }

        // A board the installed backends do not know fails at build time with
        // "Unknown board", which is a slow way to discover a typo.
        val board = config.board
        if (board != null && !config.hasConflictingTarget) {
            val catalog = PyMcuBoardCatalogService.getInstance(file.project).cachedOrFallback()
            if (catalog.boards.isNotEmpty() && catalog.chipOf(board) == null) {
                report(
                    TomlWriter.keyRange(text, SECTION, "board"),
                    "Unknown board `$board`. Run `pymcu boards` for the list this installation supports.",
                    error = false,
                    OpenConfigureDialogFix(),
                )
            }
        }

        return problems.toTypedArray()
    }
}

// ── fixes ────────────────────────────────────────────────────────────────────

/**
 * Rewrites the whole document through [TomlWriter], which preserves comments and
 * layout — the reason these fixes edit text rather than rebuild a PSI tree.
 */
private abstract class PyprojectFix(private val label: String) : LocalQuickFix {

    final override fun getName(): String = label
    final override fun getFamilyName(): String = "PyMCU configuration"

    abstract fun rewrite(content: String): String

    final override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        val updated = rewrite(document.text)
        if (updated == document.text) return
        WriteCommandAction.runWriteCommandAction(project, name, null, {
            document.setText(updated)
        }, file)
    }
}

private class RenameKeyFix(private val from: String, private val to: String) :
    PyprojectFix("Rename `$from` to `$to`") {
    override fun rewrite(content: String): String =
        TomlWriter.renameKey(content, SECTION, from, to)
}

private class RemoveKeyFix(private val key: String, label: String) : PyprojectFix(label) {
    override fun rewrite(content: String): String = TomlWriter.removeKey(content, SECTION, key)
}

/** For the cases with no single correct edit — let the user pick a board. */
private class OpenConfigureDialogFix : LocalQuickFix {

    override fun getName(): String = "Configure the PyMCU project…"
    override fun getFamilyName(): String = "PyMCU configuration"

    /** Opens a modal dialog, so it must not run inside the fix's write action. */
    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) PyMcuConfigureDialog.show(project)
        }, project.disposed)
    }
}
