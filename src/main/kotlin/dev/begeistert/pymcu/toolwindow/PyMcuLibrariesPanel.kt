package dev.begeistert.pymcu.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.begeistert.pymcu.cli.PyMcuLibraries
import dev.begeistert.pymcu.cli.PyMcuLibrary
import dev.begeistert.pymcu.project.PyMcuConfigListener
import dev.begeistert.pymcu.run.PyMcuLibraryChangeListener
import dev.begeistert.pymcu.run.PyMcuTaskRunner
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Browse, install and remove PyMCU libraries without leaving the IDE.
 *
 * The column that matters is Status. The index records, per library and per
 * chip, whether the thing actually *built* — so "incompatible" here is a
 * measured result with a reason attached, not a guess from a version range.
 * Installing something the compiler will choke on is the failure mode this
 * panel exists to prevent, which is why incompatible rows stay visible and
 * explain themselves instead of being filtered out.
 */
class PyMcuLibrariesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val model = LibraryTableModel()
    private val table = JBTable(model).apply {
        setShowGrid(false)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        rowHeight = JBUI.scale(22)
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
    }

    private val search = SearchTextField().apply {
        textEditor.emptyText.text = "Filter by name, summary or category"
    }
    private val onlyCompatible = JBCheckBox("Only what fits this target", false)
    private val installButton = JButton("Install")
    private val removeButton = JButton("Remove")
    private val statusLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    private var all: List<PyMcuLibrary> = emptyList()

    init {
        table.columnModel.getColumn(COL_STATUS).cellRenderer = StatusRenderer()
        for ((index, width) in COLUMN_WIDTHS.withIndex()) {
            if (width > 0) table.columnModel.getColumn(index).preferredWidth = JBUI.scale(width)
        }

        add(header(), BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(footer(), BorderLayout.SOUTH)

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = applyFilter()
        })
        onlyCompatible.addActionListener { applyFilter() }
        table.selectionModel.addListSelectionListener { updateButtons() }

        installButton.addActionListener { selected()?.let { install(it) } }
        removeButton.addActionListener { selected()?.let { remove(it) } }

        val connection = project.messageBus.connect(this)
        connection.subscribe(PyMcuConfigListener.TOPIC, PyMcuConfigListener { reload(refreshIndex = false) })
        // An install or uninstall finishing is the one event that certainly
        // invalidates this list.
        connection.subscribe(PyMcuLibraryChangeListener.TOPIC, PyMcuLibraryChangeListener {
            reload(refreshIndex = false)
        })

        updateButtons()
        reload(refreshIndex = false)
    }

    // ── layout ───────────────────────────────────────────────────────────────

    private fun header(): JPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        border = JBUI.Borders.empty(6, 8)
        add(search, BorderLayout.CENTER)
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(onlyCompatible, BorderLayout.WEST)
            add(ActionLink("Refresh index") { reload(refreshIndex = true) }, BorderLayout.EAST)
        }, BorderLayout.EAST)
    }

    private fun footer(): JPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        border = JBUI.Borders.empty(6, 8)
        add(statusLabel, BorderLayout.CENTER)
        add(JPanel().apply {
            add(installButton)
            add(removeButton)
        }, BorderLayout.EAST)
    }

    // ── data ─────────────────────────────────────────────────────────────────

    private fun reload(refreshIndex: Boolean) {
        statusLabel.text = if (refreshIndex) "Downloading the index…" else "Loading libraries…"
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread

            val installed = PyMcuLibraries.installed(project)
            val found = PyMcuLibraries.search(project, allTargets = true, refresh = refreshIndex)

            // An installed library is the authority on its own state, so it wins
            // over the index entry of the same name.
            val byName = LinkedHashMap<String, PyMcuLibrary>()
            found?.libraries?.forEach { byName[it.name.lowercase()] = it }
            installed?.libraries?.forEach { byName[it.name.lowercase()] = it }

            val note = when {
                installed == null && found == null ->
                    "Could not run `pymcu libraries`. Check the executable in Settings | Tools | PyMCU."
                found == null ->
                    "Index unavailable — showing installed libraries only."
                found.source == "cache" ->
                    "Index from cache. Use Refresh index to re-download."
                else -> null
            }

            SwingUtilities.invokeLater {
                if (project.isDisposed) return@invokeLater
                all = byName.values.sortedWith(
                    compareByDescending<PyMcuLibrary> { it.installed }.thenBy { it.name }
                )
                applyFilter(note)
            }
        }
    }

    private fun applyFilter(note: String? = null) {
        val needle = search.text.trim().lowercase()
        val rows = all.filter { library ->
            if (onlyCompatible.isSelected && !library.fits) return@filter false
            if (needle.isEmpty()) return@filter true
            val haystack = "${library.name} ${library.summary} ${library.categories.joinToString(" ")}"
            haystack.lowercase().contains(needle)
        }
        model.setRows(rows)
        updateButtons()

        statusLabel.text = note ?: when {
            all.isEmpty() -> "No libraries found."
            rows.size == all.size -> "${all.size} library(ies) · ${all.count { it.installed }} installed"
            else -> "${rows.size} of ${all.size} shown"
        }
    }

    private fun selected(): PyMcuLibrary? =
        table.selectedRow.takeIf { it >= 0 }?.let { model.rowAt(table.convertRowIndexToModel(it)) }

    private fun updateButtons() {
        val library = selected()
        installButton.isEnabled = library != null && !library.installed
        removeButton.isEnabled = library != null && library.installed
        installButton.toolTipText = library
            ?.takeIf { !it.fits }
            ?.let { "Installing anyway: ${it.reasons.first()}" }
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private fun install(library: PyMcuLibrary) {
        // The driver refuses an install that cannot serve the target and explains
        // why, so it stays the one making that call — the panel does not
        // pre-empt it, it just shows the verdict up front.
        PyMcuTaskRunner.run(project, "install", listOf(library.name))
    }

    private fun remove(library: PyMcuLibrary) {
        PyMcuTaskRunner.run(project, "uninstall", listOf(library.name))
    }

    override fun dispose() = Unit

    // ── table model ──────────────────────────────────────────────────────────

    private class LibraryTableModel : AbstractTableModel() {

        private var rows: List<PyMcuLibrary> = emptyList()

        fun setRows(value: List<PyMcuLibrary>) {
            rows = value
            fireTableDataChanged()
        }

        fun rowAt(index: Int): PyMcuLibrary? = rows.getOrNull(index)

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = COLUMN_NAMES.size
        override fun getColumnName(column: Int): String = COLUMN_NAMES[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val library = rows[rowIndex]
            return when (columnIndex) {
                COL_NAME -> library.name
                COL_VERSION -> library.version
                COL_LAYER -> library.layer
                COL_STATUS -> library
                else -> library.summary
            }
        }

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == COL_STATUS) PyMcuLibrary::class.java else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }

    /** Status as icon plus reason, so an unusable library says why in the row. */
    private class StatusRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(
                table, "", isSelected, hasFocus, row, column
            ) as DefaultTableCellRenderer
            val library = value as? PyMcuLibrary ?: return component

            when {
                library.installed && !library.fits -> {
                    component.icon = AllIcons.General.Warning
                    component.text = "Installed · ${library.reasons.first()}"
                }
                library.installed -> {
                    component.icon = AllIcons.General.InspectionsOK
                    component.text = "Installed"
                }
                !library.fits -> {
                    component.icon = AllIcons.General.Error
                    component.text = library.reasons.first()
                }
                else -> {
                    component.icon = null
                    component.text = "Available"
                }
            }
            component.toolTipText = library.reasons.joinToString("; ").ifEmpty { null }
            return component
        }
    }

    private companion object {
        const val COL_NAME = 0
        const val COL_VERSION = 1
        const val COL_LAYER = 2
        const val COL_STATUS = 3

        val COLUMN_NAMES = arrayOf("Library", "Version", "Layer", "Status", "Summary")
        val COLUMN_WIDTHS = intArrayOf(150, 70, 90, 260, 0)
    }
}
