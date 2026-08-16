package dev.begeistert.pymcu.newproject

import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.begeistert.pymcu.cli.BoardCatalog
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.cli.PyMcuCli
import dev.begeistert.pymcu.settings.PyMcuSettings
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * New Project wizard panel.
 *
 * The board list is loaded from `pymcu boards --json` in the background: the
 * previous panel hardcoded four Arduino boards and eight AVR chips, which
 * silently hid every RP2040, PIC and RISC-V target the driver has since gained.
 */
class PyMcuProjectGeneratorPeer : ProjectGeneratorPeer<PyMcuNewProjectSettings> {

    private companion object {
        const val BARE_CHIP = "— Bare chip (advanced) —"
        val FREQUENCIES = listOf(
            "16 MHz" to 16_000_000L,
            "8 MHz" to 8_000_000L,
            "16.5 MHz (Digispark / Trinket)" to 16_500_000L,
            "125 MHz (RP2040)" to 125_000_000L,
            "150 MHz (RP2350)" to 150_000_000L,
            "1 MHz" to 1_000_000L,
        )
        val STDLIB_LABELS = arrayOf(
            "CircuitPython compat  (board, digitalio, busio…)",
            "MicroPython compat  (machine, utime…)",
            "None — bare PyMCU (pymcu.hal)",
        )
        val STDLIB_VALUES = arrayOf("circuitpython", "micropython", "")
    }

    @Volatile
    private var catalog: BoardCatalog = PyMcuBoardCatalogService.FALLBACK
    private var boardValues: List<String?> = emptyList()

    private val boardCombo = ComboBox<String>()
    private val chipField = JBTextField()
    private val frequencyCombo = ComboBox(FREQUENCIES.map { it.first }.toTypedArray())
    private val stdlibCombo = ComboBox(STDLIB_LABELS)
    private val packageManagerCombo = ComboBox(PyMcuSettings.PACKAGE_MANAGERS)

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Board:"), boardCombo, 1, false)
        .addLabeledComponent(JBLabel("Chip:"), chipField, 1, false)
        .addLabeledComponent(JBLabel("CPU frequency:"), frequencyCombo, 1, false)
        .addLabeledComponent(JBLabel("Compat stdlib:"), stdlibCombo, 1, false)
        .addLabeledComponent(JBLabel("Package manager:"), packageManagerCombo, 1, false)
        .addComponentToRightColumn(
            JBLabel("Creates pyproject.toml and a blink starter, then installs dependencies.")
        )
        .panel

    init {
        packageManagerCombo.selectedItem = PyMcuSettings.getInstance().packageManager
        populateBoards(catalog)
        boardCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED) onBoardChanged() }
        onBoardChanged()
        loadCatalogInBackground()
    }

    /**
     * The wizard must not block on a subprocess, so the panel opens with the
     * built-in fallback list and swaps in the real catalog when it arrives.
     */
    private fun loadCatalogInBackground() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = PyMcuCli.runIn(null, PyMcuCli.executable(null), listOf("boards", "--json"), 30_000)
            val fetched = if (result.ok) PyMcuBoardCatalogService.parse(result.stdout) else null
            if (fetched != null) {
                ApplicationManager.getApplication().invokeLater {
                    catalog = fetched
                    val previous = boardValues.getOrNull(boardCombo.selectedIndex)
                    populateBoards(fetched)
                    val restored = boardValues.indexOf(previous)
                    if (restored >= 0) boardCombo.selectedIndex = restored
                    onBoardChanged()
                }
            }
        }
    }

    private fun populateBoards(catalog: BoardCatalog) {
        val labels = mutableListOf<String>()
        val values = mutableListOf<String?>()
        for ((group, boards) in catalog.groupedBoards()) {
            for (board in boards) {
                labels += "${board.name}  ·  ${board.chip}  ($group)"
                values += board.name
            }
        }
        labels += BARE_CHIP
        values += null

        boardValues = values
        boardCombo.removeAllItems()
        labels.forEach(boardCombo::addItem)
        if (labels.isNotEmpty()) boardCombo.selectedIndex = 0
    }

    private fun selectedBoard(): String? = boardValues.getOrNull(boardCombo.selectedIndex)

    private fun onBoardChanged() {
        val board = selectedBoard()
        chipField.isEnabled = board == null
        if (board != null) {
            chipField.text = catalog.chipOf(board).orEmpty()
            defaultFrequencyIndex(catalog.chipOf(board))?.let { frequencyCombo.selectedIndex = it }
        }
    }

    /** A sensible clock for the chip family, so the default is not wrong out of the box. */
    private fun defaultFrequencyIndex(chip: String?): Int? = when {
        chip == null -> null
        chip == "rp2040" -> FREQUENCIES.indexOfFirst { it.second == 125_000_000L }
        chip == "rp2350" -> FREQUENCIES.indexOfFirst { it.second == 150_000_000L }
        chip.startsWith("attiny") -> FREQUENCIES.indexOfFirst { it.second == 8_000_000L }
        else -> FREQUENCIES.indexOfFirst { it.second == 16_000_000L }
    }?.takeIf { it >= 0 }

    // ── ProjectGeneratorPeer ─────────────────────────────────────────────────

    /**
     * Deprecated upstream in favour of the two-argument overload, but it is still
     * what PyCharm's directory-project wizard calls, so it has to stay.
     */
    @Deprecated("Superseded by getComponent(TextFieldWithBrowseButton, Runnable)")
    override fun getComponent(): JComponent = panel

    override fun buildUI(settingsStep: SettingsStep) {
        settingsStep.addSettingsComponent(panel)
    }

    override fun getSettings(): PyMcuNewProjectSettings {
        val board = selectedBoard()
        return PyMcuNewProjectSettings(
            board = board,
            chip = if (board == null) chipField.text.trim().takeIf { it.isNotEmpty() } else null,
            frequency = FREQUENCIES[frequencyCombo.selectedIndex].second,
            packageManager = packageManagerCombo.selectedItem as? String ?: "uv",
            stdlib = STDLIB_VALUES[stdlibCombo.selectedIndex],
        )
    }

    override fun validate(): ValidationInfo? {
        if (selectedBoard() == null && chipField.text.isBlank()) {
            return ValidationInfo("Pick a board, or type a chip id.", chipField)
        }
        return null
    }

    override fun isBackgroundJobRunning(): Boolean = false
}
