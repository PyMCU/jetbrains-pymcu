package dev.begeistert.pymcu.newproject

import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ui.GroupedComboBoxRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import dev.begeistert.pymcu.cli.BoardCatalog
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.cli.PyMcuCli
import dev.begeistert.pymcu.settings.PyMcuSettings
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JEditorPane

/**
 * New Project wizard panel.
 *
 * The board list is loaded from `pymcu boards --json` in the background: the
 * original panel hardcoded four Arduino boards and eight AVR chips, which
 * silently hid every RP2040, PIC and RISC-V target the driver has since gained.
 *
 * The panel's only job is to produce a correct [PyMcuNewProjectSettings];
 * everything on disk is written by `pymcu new` through [PyMcuDriverScaffold].
 * What it can do beyond collecting five values is say what each of them means,
 * which is why the chip line, the clock line and the compat-layer descriptions
 * exist — a board alias on its own does not tell anyone which toolchain will
 * build the project or which API they are about to write against.
 */
class PyMcuProjectGeneratorPeer : ProjectGeneratorPeer<PyMcuNewProjectSettings> {

    /** One of the three APIs a PyMCU project can be written against. */
    private data class ApiChoice(val value: String, val label: String, val detail: String)

    private companion object {
        /**
         * In the order the project README recommends them: the compat layers
         * are community-specified and stable, the native HAL is not yet, and
         * the wizard should not lead with the one that will move under you.
         *
         * Each detail line is the import that choice produces, because that —
         * not the word "stdlib" — is what is actually being decided here.
         */
        val API_CHOICES = listOf(
            ApiChoice("circuitpython", "CircuitPython", "<code>import board, digitalio</code>"),
            ApiChoice("micropython", "MicroPython", "<code>from machine import Pin</code>"),
            ApiChoice("", "Native HAL", "<code>from pymcu.hal.gpio import Pin</code>"),
        )

        const val API_TRADE_OFF =
            "The two compat layers are community-specified and stable. The native HAL reaches the " +
                "metal with less in the way, but its API may change between alpha releases."

        const val CREATES =
            "<code>pymcu new</code> writes pyproject.toml, src/main.py, a Makefile and .gitignore, " +
                "then installs the dependencies."

        const val READING_CATALOG = "Reading the board list from the pymcu CLI…"

        const val CATALOG_UNAVAILABLE =
            "The pymcu CLI could not be read, so this is the built-in board list. Install pymcu for " +
                "the full set of boards and chips."
    }

    @Volatile
    private var catalog: BoardCatalog = PyMcuBoardCatalogService.FALLBACK
    private var choices: List<PyMcuTargets.Choice> = PyMcuTargets.choices(catalog)

    /** Set while the panel rewrites its own controls, so the listeners stay quiet. */
    private var updating = false

    /** The clock the box was last filled with on the panel's own initiative. */
    private var clockDefault = 0L

    private var stdlib: String = API_CHOICES.first().value

    private val boardModel = DefaultComboBoxModel(choices.toTypedArray())
    private val boardCombo = ComboBox(boardModel).apply {
        // A JBPopup list rather than the Swing one: it draws the catalog's group
        // headings and brings speed search, which is what turns forty aliases
        // across five architectures back into something scannable.
        isSwingPopup = false
    }
    private val chipCombo = ComboBox(PyMcuTargets.chips(catalog).toTypedArray()).apply { isEditable = true }
    private val clockModel = DefaultComboBoxModel<String>()
    private val clockCombo = ComboBox(clockModel).apply { isEditable = true }
    // Only what `pymcu new` can scaffold for; pipenv exists in Settings for
    // syncing existing projects, but the driver cannot create one.
    private val packageManagerCombo =
        ComboBox(PyMcuDriverScaffold.SUPPORTED_PACKAGE_MANAGERS.toTypedArray())

    private var chipComment: JEditorPane? = null
    private var clockComment: JEditorPane? = null
    private var catalogNotice: Cell<JEditorPane>? = null

    private val panel: DialogPanel = panel {
        row("Board:") {
            cell(boardCombo).align(AlignX.FILL)
        }
        row("Chip:") {
            chipComment = cell(chipCombo)
                .align(AlignX.FILL)
                .comment(describeTarget())
                .comment
        }
        row("CPU clock:") {
            clockComment = cell(clockCombo)
                .columns(COLUMNS_SHORT)
                .comment(describeClock())
                .comment
        }

        buttonsGroup("Python API:") {
            for (choice in API_CHOICES) {
                row {
                    radioButton(choice.label, choice.value).comment(choice.detail)
                }
            }
        }.bind({ stdlib }, { stdlib = it })

        // The trade-off is stated once here rather than repeated under each
        // radio, where two of the three lines would have said the same thing.
        row {
            comment(API_TRADE_OFF)
        }

        row("Package manager:") {
            cell(packageManagerCombo)
        }.topGap(TopGap.SMALL)

        row {
            comment(CREATES)
        }
        row {
            catalogNotice = comment(READING_CATALOG)
        }
    }

    init {
        panel.reset()
        PyMcuSettings.getInstance().packageManager
            .takeIf { it in PyMcuDriverScaffold.SUPPORTED_PACKAGE_MANAGERS }
            ?.let { packageManagerCombo.selectedItem = it }
        boardCombo.renderer = BoardRenderer(boardCombo)
        boardCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED) onTargetChanged() }
        // An action listener rather than an item listener: the chip box is
        // editable, and a chip typed straight into it never fires an item event.
        chipCombo.addActionListener { onTargetChanged() }
        refresh(resetClock = true)
        loadCatalogInBackground()
    }

    private fun onTargetChanged() {
        if (!updating) refresh(resetClock = true)
    }

    /**
     * The wizard must not block on a subprocess, so the panel opens with the
     * built-in fallback list and swaps in the real catalog when it arrives.
     */
    private fun loadCatalogInBackground() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = PyMcuCli.runIn(null, PyMcuCli.executable(null), listOf("boards", "--json"), 30_000)
            val fetched = if (result.ok) PyMcuBoardCatalogService.parse(result.stdout) else null
            ApplicationManager.getApplication().invokeLater {
                if (fetched != null) adopt(fetched)
                else catalogNotice?.component?.text = CATALOG_UNAVAILABLE
            }
        }
    }

    /** Swaps the fallback list for the real one without losing what was picked. */
    private fun adopt(fetched: BoardCatalog) {
        val previousBoard = selectedChoice().board
        val typedChip = chipText()

        catalog = fetched
        choices = PyMcuTargets.choices(fetched)

        withoutListeners {
            boardModel.removeAllElements()
            choices.forEach(boardModel::addElement)
            val restored = choices.indexOfFirst { it.board == previousBoard }
            boardCombo.selectedIndex = if (restored >= 0) restored else 0

            chipCombo.model = DefaultComboBoxModel(PyMcuTargets.chips(fetched).toTypedArray())
            chipCombo.selectedItem = typedChip
        }

        // The clock survives: the target is the one the user already chose, and
        // a value they typed while the list was loading is still theirs.
        refresh(resetClock = false)
        catalogNotice?.visible(false)
    }

    private fun refresh(resetClock: Boolean) = withoutListeners {
        val choice = selectedChoice()
        chipCombo.isEnabled = choice.board == null
        if (choice.board != null) chipCombo.selectedItem = choice.chip
        if (resetClock) refreshClock(choice.board, selectedChip())

        chipComment?.text = describeTarget()
        clockComment?.text = describeClock()
    }

    private fun refreshClock(board: String?, chip: String?) {
        val default = PyMcuClock.forTarget(board, chip)
        // Changing the target must not silently discard a clock the user typed,
        // so the box is only rewritten while it still holds the previous default.
        val typed = PyMcuClock.parse(clockText())?.takeIf { it != clockDefault }

        clockModel.removeAllElements()
        PyMcuClock.suggestions(board, chip).forEach { clockModel.addElement(PyMcuClock.format(it)) }
        clockCombo.selectedItem = PyMcuClock.format(typed ?: default)
        clockDefault = default
    }

    private fun withoutListeners(action: () -> Unit) {
        val outer = updating
        updating = true
        try {
            action()
        } finally {
            updating = outer
        }
    }

    // ── what the form currently says ─────────────────────────────────────────

    private fun selectedChoice(): PyMcuTargets.Choice =
        boardCombo.selectedItem as? PyMcuTargets.Choice ?: choices.last()

    private fun chipText(): String = (chipCombo.editor.item as? String)?.trim().orEmpty()

    /** The chip the form implies, whichever way it was specified. */
    private fun selectedChip(): String? =
        selectedChoice().chip ?: chipText().takeIf { it.isNotEmpty() }

    private fun clockText(): String = (clockCombo.editor.item as? String).orEmpty()

    private fun describeTarget(): String {
        val board = selectedChoice().board?.let { alias -> catalog.boards.firstOrNull { it.name == alias } }
        return PyMcuTargets.describe(selectedChip(), board?.toolchain, board?.programmer)
    }

    private fun describeClock(): String {
        val choice = selectedChoice()
        val target = choice.board ?: selectedChip() ?: return "The default follows from the chip."
        return "Default for $target: ${PyMcuClock.format(PyMcuClock.forTarget(choice.board, selectedChip()))}."
    }

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
        // The API choice is a bound radio group, so the panel has to push its
        // bindings into the backing field before it can be read.
        panel.apply()
        val board = selectedChoice().board
        return PyMcuNewProjectSettings(
            board = board,
            chip = if (board == null) chipText().takeIf { it.isNotEmpty() } else null,
            frequency = PyMcuClock.parse(clockText()) ?: PyMcuClock.forTarget(board, selectedChip()),
            packageManager = packageManagerCombo.selectedItem as? String ?: "uv",
            stdlib = stdlib,
            // The catalog is only on hand here, and the scaffolding needs the
            // chip family to pick the compiler's backend extra.
            resolvedChip = board?.let(catalog::chipOf),
        )
    }

    override fun validate(): ValidationInfo? {
        if (selectedChoice().board == null && chipText().isEmpty()) {
            return ValidationInfo("Pick a board, or type a chip id.", chipCombo)
        }
        val clock = PyMcuClock.parse(clockText())
            ?: return ValidationInfo(
                "Clock must be a number, with or without a unit: 16 MHz, 125 MHz or 16000000.",
                clockCombo,
            )
        if (clock < PyMcuClock.MINIMUM_HZ) {
            return ValidationInfo("Clock is in hertz. Write 16 MHz, or 16000000.", clockCombo)
        }
        return null
    }

    override fun isBackgroundJobRunning(): Boolean = false

    /**
     * Board alias first, chip alongside it in the dimmed column, the catalog's
     * own grouping as separators. The grouping is the catalog's rather than the
     * panel's so a backend that ships new boards groups them without a release
     * here.
     */
    private class BoardRenderer(combo: JComponent) : GroupedComboBoxRenderer<PyMcuTargets.Choice>(combo) {
        override fun getText(item: PyMcuTargets.Choice): String = PyMcuTargets.label(item)

        override fun getSecondaryText(item: PyMcuTargets.Choice): String =
            PyMcuTargets.secondaryLabel(item)

        override fun separatorFor(value: PyMcuTargets.Choice): ListSeparator? =
            value.heading?.let(::ListSeparator)
    }
}
