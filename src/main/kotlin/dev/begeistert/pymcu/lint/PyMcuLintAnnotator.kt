package dev.begeistert.pymcu.lint

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.settings.PyMcuSettings
import java.nio.file.Files
import java.nio.file.Path

/** What [PyMcuLintAnnotator] needs from the EDT before going to the background. */
data class LintInput(
    val project: Project,
    val filePath: String,
    val text: String,
    val isDirty: Boolean,
)

/**
 * Surfaces `pymcu lint` findings as editor annotations while you type or save.
 *
 * This is the porting workflow the VS Code extension is built around: paste
 * MicroPython code, follow the squiggles until nothing is left, then build.
 *
 * An [ExternalAnnotator] is the right shape for it — [doAnnotate] runs off the
 * EDT with no read action held, which is exactly what spawning a CLI needs.
 */
class PyMcuLintAnnotator : ExternalAnnotator<LintInput, LintReport>() {

    private val directoriesToSkip = setOf(".venv", "venv", "dist", "build", "node_modules", "__pycache__")

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): LintInput? =
        collectInformation(file)

    override fun collectInformation(file: PsiFile): LintInput? {
        val settings = PyMcuSettings.getInstance()
        if (settings.lintRun == PyMcuSettings.LINT_OFF) return null

        val project = file.project
        if (!PyMcuProjectService.getInstance(project).isPyMcuProject) return null

        val virtualFile = file.virtualFile ?: return null
        if (!isLintable(project, virtualFile)) return null

        // isFileModified is the supported "buffer differs from disk" check;
        // comparing Document.modificationStamp to VirtualFile.modificationStamp
        // is not, and reads as dirty right after a reload.
        val isDirty = FileDocumentManager.getInstance().isFileModified(virtualFile)

        // onSave mode: skip while the buffer differs from disk, so the squiggles
        // reflect saved state instead of half-typed lines.
        if (settings.lintRun == PyMcuSettings.LINT_ON_SAVE && isDirty) return null

        return LintInput(project, virtualFile.path, file.text, isDirty)
    }

    override fun doAnnotate(input: LintInput?): LintReport? {
        if (input == null) return null
        // Lint what is in the editor, not what is on disk: write the buffer to a
        // temp file, then map the reported path back to the real document.
        if (!input.isDirty) return PyMcuLint.lintFile(input.project, input.filePath)

        var temp: Path? = null
        return try {
            temp = Files.createTempFile("pymcu-lint-", "-" + Path.of(input.filePath).fileName)
            Files.writeString(temp, input.text)
            PyMcuLint.lintFile(input.project, temp.toString())
        } catch (_: Exception) {
            null
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    override fun apply(file: PsiFile, report: LintReport?, holder: AnnotationHolder) {
        val findings = report?.allFindings ?: return
        val document = file.viewProvider.document ?: return

        for (finding in findings) {
            val range = rangeOf(document, finding) ?: continue
            val message = if (finding.suggestion.isNotBlank())
                "${finding.message}\n→ ${finding.suggestion}"
            else
                finding.message

            holder.newAnnotation(severityOf(finding.severity), message)
                .range(range)
                .tooltip(tooltipOf(finding))
                .create()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun isLintable(project: Project, file: VirtualFile): Boolean {
        if (file.extension != "py") return false
        val basePath = project.basePath ?: return false
        if (!file.path.startsWith(basePath)) return false
        val relative = file.path.removePrefix(basePath).trim('/')
        return relative.split('/').none { it in directoriesToSkip }
    }

    private fun severityOf(severity: String): HighlightSeverity = when (severity) {
        "error" -> HighlightSeverity.ERROR
        "warn" -> HighlightSeverity.WARNING
        else -> HighlightSeverity.WEAK_WARNING
    }

    /**
     * The finding's 1-based line/column mapped onto the document, widened to the
     * rest of the line so the squiggle is grabbable rather than one character wide.
     */
    private fun rangeOf(document: Document, finding: LintFinding): TextRange? {
        val line = finding.line - 1
        if (line < 0 || line >= document.lineCount) return null
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val start = (lineStart + (finding.col - 1).coerceAtLeast(0)).coerceIn(lineStart, lineEnd)
        if (start >= lineEnd) {
            // Finding on a blank or fully-trimmed line: highlight the line break.
            return if (lineStart == lineEnd) null else TextRange(lineStart, lineEnd)
        }
        return TextRange(start, lineEnd)
    }

    private fun tooltipOf(finding: LintFinding): String {
        val escaped = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(finding.message)
        val suggestion = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(finding.suggestion)
        return buildString {
            append("<html><body>")
            append("<b>pymcu</b> <code>${finding.code}</code><br/>")
            append(escaped)
            if (suggestion.isNotBlank()) append("<br/><br/>→ $suggestion")
            append("<br/><br/><a href=\"${PyMcuLint.DOCS_URL}\">Porting guide</a>")
            append("</body></html>")
        }
    }
}
