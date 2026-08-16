package dev.begeistert.pymcu.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import dev.begeistert.pymcu.PyMcuIcons
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.config.PyMcuConfig
import dev.begeistert.pymcu.lint.LintFinding
import dev.begeistert.pymcu.lint.LintReport
import dev.begeistert.pymcu.lint.PyMcuLintResults
import dev.begeistert.pymcu.lint.PyMcuLintResultsListener
import dev.begeistert.pymcu.project.PyMcuConfigListener
import dev.begeistert.pymcu.project.PyMcuProjectService
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The "PyMCU" tool window: what the project targets, the actions that operate
 * on it, and the porting assistant's findings.
 *
 * The previous version ran commands itself and appended their output to a
 * `JTextArea` — no stop button, no exit code, no clickable diagnostics. Commands
 * now go through the run/debug framework
 * ([dev.begeistert.pymcu.run.PyMcuTaskRunner]) and this panel is left to do what
 * a tool window is good at: show state and offer navigation.
 */
class PyMcuToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()

        // Ordered by when you need them: set the project up, add what it needs,
        // then work through what the porting assistant found.
        val tabs = listOf<Pair<String, JPanel>>(
            "Get Started" to PyMcuSetupPanel(project),
            "Libraries" to PyMcuLibrariesPanel(project),
            "Porting" to PyMcuToolWindowPanel(project),
        )

        for ((title, panel) in tabs) {
            if (panel is Disposable) Disposer.register(toolWindow.disposable, panel)
            toolWindow.contentManager.addContent(
                factory.createContent(panel, title, false).apply { isCloseable = false }
            )
        }
    }

    override suspend fun isApplicableAsync(project: Project): Boolean =
        PyMcuProjectService.getInstance(project).isPyMcuProject
}

internal class PyMcuToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val targetLabel = JBLabel()
    private val findingsRoot = DefaultMutableTreeNode("PyMCU")
    private val findingsModel = DefaultTreeModel(findingsRoot)
    private val findingsTree = Tree(findingsModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = FindingRenderer()
    }

    init {
        border = JBUI.Borders.empty(4)

        val header = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, JBUI.scale(4), JBUI.scale(4), 0)
            add(targetLabel, BorderLayout.CENTER)
        }

        add(actionToolbar(), BorderLayout.WEST)
        add(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(findingsTree), BorderLayout.CENTER)
        }, BorderLayout.CENTER)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigateToSelection()
        }.installOn(findingsTree)

        subscribe()
        refreshTarget()
        renderFindings(PyMcuLintResults.getInstance(project).report)
    }

    // ── toolbar ──────────────────────────────────────────────────────────────

    private fun actionToolbar(): JPanel {
        val group = ActionManager.getInstance().getAction("PyMcu.ToolWindowToolbar") as? DefaultActionGroup
            ?: DefaultActionGroup()
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, false)
        toolbar.targetComponent = this
        return JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.NORTH) }
    }

    // ── state ────────────────────────────────────────────────────────────────

    private fun subscribe() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(PyMcuConfigListener.TOPIC, PyMcuConfigListener {
            SwingUtilities.invokeLater { refreshTarget() }
        })
        connection.subscribe(PyMcuLintResultsListener.TOPIC, PyMcuLintResultsListener { report ->
            SwingUtilities.invokeLater { renderFindings(report) }
        })
    }

    private fun refreshTarget() {
        val config = PyMcuProjectService.config(project)
        targetLabel.text = config?.let(::describe) ?: "No PyMCU project detected"
    }

    private fun describe(config: PyMcuConfig): String {
        val catalog = PyMcuBoardCatalogService.getInstance(project).cachedOrFallback()
        val chip = config.explicitChip ?: config.board?.let(catalog::chipOf)
        val parts = mutableListOf<String>()

        parts += when {
            config.board != null && chip != null -> "${config.board} ($chip)"
            config.board != null -> config.board
            chip != null -> chip
            else -> "no target set"
        }
        config.architecture(chip)?.let { parts += it }
        config.frequency?.let { parts += formatFrequency(it) }
        config.flavor?.let { parts += "$it compat" }
        if (config.hasFfi) parts += "C/C++ FFI"

        return parts.joinToString("  ·  ")
    }

    /** 16000000 → "16 MHz", 16500000 → "16.5 MHz", 32768 → "32768 Hz". */
    private fun formatFrequency(hz: Long): String {
        if (hz < 1_000_000) return "$hz Hz"
        val mhz = hz / 1_000_000.0
        return if (mhz == Math.floor(mhz)) "${mhz.toLong()} MHz" else "$mhz MHz"
    }

    // ── findings ─────────────────────────────────────────────────────────────

    private fun renderFindings(report: LintReport?) {
        findingsRoot.removeAllChildren()

        if (report == null) {
            findingsRoot.add(DefaultMutableTreeNode(MessageNode(
                "Run the porting assistant to list MicroPython / CircuitPython idioms that need a rewrite."
            )))
        } else if (report.allFindings.isEmpty()) {
            findingsRoot.add(DefaultMutableTreeNode(MessageNode(
                "No findings — this should port cleanly."
            )))
        } else {
            for (file in report.files) {
                if (file.findings.isEmpty()) continue
                val fileNode = DefaultMutableTreeNode(FileNode(file.path, file.findings.size))
                for (finding in file.findings) {
                    fileNode.add(DefaultMutableTreeNode(FindingNode(file.path, finding)))
                }
                findingsRoot.add(fileNode)
            }
        }

        findingsModel.reload()
        for (row in 0 until findingsTree.rowCount) findingsTree.expandRow(row)
    }

    private fun navigateToSelection(): Boolean {
        val node = findingsTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return false
        val finding = node.userObject as? FindingNode ?: return false
        // `pymcu lint` echoes back the path it was given, so it is relative when
        // the caller passed a relative one; resolve against the project either way.
        val reported = File(finding.path)
        val target = if (reported.isAbsolute) reported else File(project.basePath ?: "", finding.path)
        val file = LocalFileSystem.getInstance().findFileByIoFile(target) ?: return false
        OpenFileDescriptor(project, file, finding.finding.line - 1, (finding.finding.col - 1).coerceAtLeast(0))
            .navigate(true)
        return true
    }

    override fun dispose() = Unit

    // ── tree model ───────────────────────────────────────────────────────────

    private data class MessageNode(val text: String)
    private data class FileNode(val path: String, val count: Int)
    private data class FindingNode(val path: String, val finding: LintFinding)

    private class FindingRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            when (val payload = (value as? DefaultMutableTreeNode)?.userObject) {
                is MessageNode -> append(payload.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)

                is FileNode -> {
                    append(File(payload.path).name)
                    append("  ${payload.count} finding(s)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    icon = PyMcuIcons.PyMcu
                }

                is FindingNode -> {
                    val finding = payload.finding
                    val attributes = when (finding.severity) {
                        "error" -> SimpleTextAttributes.ERROR_ATTRIBUTES
                        "warn" -> SimpleTextAttributes.SYNTHETIC_ATTRIBUTES
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    }
                    append("${finding.line}:${finding.col}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(finding.code, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    append("  ${finding.message}", attributes)
                    if (finding.suggestion.isNotBlank()) {
                        append("  → ${finding.suggestion}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }
    }
}
