package dev.begeistert.pymcu.configure

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.begeistert.pymcu.actions.PyMcuSyncTask
import dev.begeistert.pymcu.cli.BoardCatalog
import dev.begeistert.pymcu.cli.PyMcuBoardCatalogService
import dev.begeistert.pymcu.cli.SerialPorts
import dev.begeistert.pymcu.config.PyMcuConfig
import dev.begeistert.pymcu.config.PyMcuConfigReader
import dev.begeistert.pymcu.config.TomlWriter
import dev.begeistert.pymcu.newproject.PyMcuClock
import dev.begeistert.pymcu.newproject.PyMcuTargets
import dev.begeistert.pymcu.notifications.PyMcuNotifications
import dev.begeistert.pymcu.project.PyMcuProjectService
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Board-first project configuration, writing the real `[tool.pymcu]` keys.
 *
 * The board list comes from `pymcu boards --json`, so it covers whatever the
 * installed backends support (AVR, RP2040/RP2350, PIC, RISC-V) instead of a
 * hardcoded set that goes stale.
 */
class PyMcuConfigureDialog private constructor(
    private val project: Project,
    private val config: PyMcuConfig,
    private val catalog: BoardCatalog,
) : DialogWrapper(project) {

    companion object {
        private const val BARE_CHIP = "— Bare chip (advanced) —"
        private const val AUTO_PROGRAMMER = "Auto (from chip)"

        /** Fetches the board catalog off the EDT, then opens the dialog. */
        fun show(project: Project) {
            val config = PyMcuProjectService.config(project) ?: run {
                PyMcuNotifications.error(
                    project, "PyMCU",
                    "No [tool.pymcu] section found in this project's pyproject.toml."
                )
                return
            }
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, "Loading the PyMCU board catalog", true) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        val catalog = PyMcuBoardCatalogService.getInstance(project).get()
                        ApplicationManager.getApplication().invokeLater({
                            if (!project.isDisposed) PyMcuConfigureDialog(project, config, catalog).show()
                        }, project.disposed)
                    }
                }
            )
        }
    }

    // ── model ────────────────────────────────────────────────────────────────

    /**
     * Board entries, from the same helper the New Project wizard uses.
     *
     * It was built from `groupedBoards()`, which only emits boards named in the
     * catalog's `groups` — so `pico`, `rp2040`, `pico2` and `rp2350` were
     * unreachable here, and any future board the driver adds without a group
     * entry would be too.
     */
    private val boardEntries: List<Pair<String, String?>> = buildList {
        add(BARE_CHIP to null)
        for (choice in PyMcuTargets.choices(catalog)) {
            val board = choice.board ?: continue
            val heading = choice.heading?.let { " ($it)" }.orEmpty()
            add("$board  ·  ${choice.chip}$heading" to board)
        }
    }

    private val boardCombo = ComboBox(boardEntries.map { it.first }.toTypedArray())
    private val chipCombo = ComboBox(
        (catalog.chips.takeIf { it.isNotEmpty() } ?: catalog.boards.map { it.chip }.distinct())
            .sorted().toTypedArray()
    ).apply { isEditable = true }
    private val frequencyField = JBTextField()
    private val stdlibCombo = ComboBox(arrayOf("None (native pymcu.hal)", "MicroPython compat", "CircuitPython compat"))
    // The names `get_programmer` resolves. "wlink" was wrong: the driver's
    // default_programmer returns "wch-link", and anything else is rejected at
    // flash time with "Unknown programmer".
    private val programmerCombo =
        ComboBox(arrayOf(AUTO_PROGRAMMER, "avrdude", "rp2040", "pk2cmd", "wch-link"))
    private val portField = JBTextField()
    private val baudField = JBTextField()
    private val fuseLowField = JBTextField()
    private val fuseHighField = JBTextField()
    private val fuseExtField = JBTextField()
    /**
     * `[tool.pymcu.config]` — the PIC configuration words. `pymcu new` writes the
     * table empty with a comment inviting the user to fill in FOSC and WDTE, and
     * this dialog offered AVR users a form for their fuses while leaving PIC
     * users to hand-edit TOML for the exact equivalent.
     */
    private val configWordsField = JBTextField()
    private val archLabel = JBLabel()

    private val stdlibValues = listOf("", "micropython", "circuitpython")

    init {
        title = "PyMCU Project Configuration"
        setOKButtonText("Save")
        initFromConfig()
        boardCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED) onTargetChanged() }
        chipCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED) onTargetChanged() }
        init()
        onTargetChanged()
    }

    private fun initFromConfig() {
        val boardIndex = boardEntries.indexOfFirst { it.second == config.board }
        boardCombo.selectedIndex = if (boardIndex >= 0) boardIndex else 0
        config.explicitChip?.let { chipCombo.selectedItem = it }
        // Not a constant: with no `frequency` key the build uses 4 MHz, so
        // pre-filling 16 and pressing Save silently quadrupled the clock of a
        // project the user opened this dialog for some unrelated reason. The
        // wizard's function gives what the driver's own scaffolder would have
        // chosen for this target, which beats either hardcoded number.
        frequencyField.text = (config.frequency
            ?: PyMcuClock.forTarget(config.board, config.explicitChip ?: catalog.chipOf(config.board.orEmpty())))
            .toString()
        stdlibCombo.selectedIndex = stdlibValues.indexOf(config.flavor ?: "").coerceAtLeast(0)
        programmerCombo.selectedItem = config.flash.programmer ?: AUTO_PROGRAMMER
        portField.text = config.flash.port.orEmpty()
        baudField.text = (config.flash.baud ?: 115200).toString()
        fuseLowField.text = config.flash.fuseLow.orEmpty()
        fuseHighField.text = config.flash.fuseHigh.orEmpty()
        fuseExtField.text = config.flash.fuseExt.orEmpty()
        configWordsField.text = config.configWords.entries
            .joinToString(", ") { "${it.key}=${it.value}" }
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val detectButton = JButton("Detect").apply {
            addActionListener { detectPort() }
        }
        val portRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(portField, BorderLayout.CENTER)
            add(detectButton, BorderLayout.EAST)
        }

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Board:"), boardCombo, 1, false)
            .addLabeledComponent(JBLabel("Chip:"), chipCombo, 1, false)
            .addLabeledComponent(JBLabel("Architecture:"), archLabel, 1, false)
            .addLabeledComponent(JBLabel("Clock frequency (Hz):"), frequencyField, 1, false)
            .addLabeledComponent(JBLabel("Compat stdlib:"), stdlibCombo, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Programmer:"), programmerCombo, 1, false)
            .addLabeledComponent(JBLabel("Serial port:"), portRow, 1, false)
            .addLabeledComponent(JBLabel("Baud rate:"), baudField, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Fuse low:"), fuseLowField, 1, false)
            .addLabeledComponent(JBLabel("Fuse high:"), fuseHighField, 1, false)
            .addLabeledComponent(JBLabel("Fuse extended:"), fuseExtField, 1, false)
            .addLabeledComponent(JBLabel("Config words:"), configWordsField, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        panel.preferredSize = JBUI.size(460, 420)
        return panel
    }

    /** Chip currently implied by the form, whichever way the user selected it. */
    private fun selectedChip(): String? {
        val board = boardEntries[boardCombo.selectedIndex].second
        return if (board != null) catalog.chipOf(board)
        else (chipCombo.editor.item as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun onTargetChanged() {
        val isBareChip = boardEntries[boardCombo.selectedIndex].second == null
        chipCombo.isEnabled = isBareChip

        val chip = selectedChip()
        val arch = PyMcuConfig(target = chip).architecture(chip)
        archLabel.text = arch ?: "Unknown"

        // Fuse bits are an AVR concept; avrdude is the only programmer that takes them.
        val isAvr = arch == "AVR"
        fuseLowField.isEnabled = isAvr
        fuseHighField.isEnabled = isAvr
        fuseExtField.isEnabled = isAvr
        // Config words are the PIC equivalent, passed to the compiler as
        // --config KEY=VALUE rather than to the programmer.
        configWordsField.isEnabled = arch == "PIC"
    }

    private fun detectPort() {
        val ports = SerialPorts.list()
        if (ports.isEmpty()) {
            setErrorText("No USB-serial device found. Connect the board and try again.", portField)
            return
        }
        setErrorText(null)
        portField.text = ports.first()
    }

    /** "FOSC=XT, WDTE=OFF" as the pairs the driver passes as --config KEY=VALUE. */
    private fun parseConfigWords(text: String): List<Pair<String, String>> =
        text.split(',')
            .mapNotNull { entry ->
                val (key, value) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                key.trim().takeIf { it.isNotEmpty() }?.let { it to value.trim() }
            }

    override fun doValidate(): ValidationInfo? {
        val frequency = frequencyField.text.trim().replace("_", "").toLongOrNull()
        if (frequency == null || frequency <= 0) {
            return ValidationInfo("Frequency must be a positive integer in Hz.", frequencyField)
        }
        if (baudField.text.isNotBlank() && baudField.text.trim().toIntOrNull() == null) {
            return ValidationInfo("Baud rate must be an integer.", baudField)
        }
        if (selectedChip().isNullOrBlank()) {
            return ValidationInfo("Pick a board, or type a chip id.", chipCombo)
        }
        val words = configWordsField.text.trim()
        if (configWordsField.isEnabled && words.isNotEmpty() && parseConfigWords(words).isEmpty()) {
            return ValidationInfo("Config words are KEY=VALUE pairs: FOSC=XT, WDTE=OFF.", configWordsField)
        }
        for (field in listOf(fuseLowField, fuseHighField, fuseExtField)) {
            val value = field.text.trim()
            if (value.isNotEmpty() && !Regex("""^0[xX][0-9a-fA-F]{1,2}$""").matches(value)) {
                return ValidationInfo("Fuse values are hex bytes, e.g. 0xFF.", field)
            }
        }
        return null
    }

    // ── write ────────────────────────────────────────────────────────────────

    override fun doOKAction() {
        val pyproject = PyMcuConfigReader.findPyproject(project) ?: run {
            PyMcuNotifications.error(project, "PyMCU", "No pyproject.toml found in the project root.")
            return
        }

        val board = boardEntries[boardCombo.selectedIndex].second
        val chip = selectedChip()
        val section = "tool.pymcu"
        val flashSection = "tool.pymcu.flash"

        val document = FileDocumentManager.getInstance().getDocument(pyproject) ?: return
        var content = document.text

        // `chip` is the deprecated spelling; writing the file is the moment to drop it.
        content = TomlWriter.removeKey(content, section, "chip")
        if (board != null) {
            content = TomlWriter.removeKey(content, section, "target")
            content = TomlWriter.setKey(content, section, "board", TomlWriter.quote(board))
        } else if (chip != null) {
            content = TomlWriter.removeKey(content, section, "board")
            content = TomlWriter.setKey(content, section, "target", TomlWriter.quote(chip))
        }

        content = TomlWriter.setKey(
            content, section, "frequency",
            frequencyField.text.trim().replace("_", "")
        )

        val stdlib = stdlibValues[stdlibCombo.selectedIndex]
        content = if (stdlib.isEmpty())
            TomlWriter.removeKey(content, section, "stdlib")
        else
            TomlWriter.setKey(content, section, "stdlib", "[${TomlWriter.quote(stdlib)}]")

        val programmer = programmerCombo.selectedItem as? String
        content = if (programmer == null || programmer == AUTO_PROGRAMMER)
            TomlWriter.removeKey(content, flashSection, "programmer")
        else
            TomlWriter.setKey(content, flashSection, "programmer", TomlWriter.quote(programmer))

        content = writeOptional(content, flashSection, "port", portField.text.trim(), quoted = true)
        val baud = baudField.text.trim()
        content = if (baud.isEmpty() || baud == "115200")
            TomlWriter.removeKey(content, flashSection, "baud")
        else
            TomlWriter.setKey(content, flashSection, "baud", baud)

        val isAvr = PyMcuConfig(target = chip).architecture(chip) == "AVR"
        for ((key, field) in listOf(
            "fuse_low" to fuseLowField, "fuse_high" to fuseHighField, "fuse_ext" to fuseExtField
        )) {
            val value = if (isAvr) field.text.trim() else ""
            content = writeOptional(content, flashSection, key, value, quoted = true)
        }

        val finalContent = content
        WriteCommandAction.runWriteCommandAction(project, "Configure PyMCU Project", null, {
            document.setText(finalContent)
            FileDocumentManager.getInstance().saveDocument(document)
        })

        PyMcuProjectService.getInstance(project).invalidate()
        super.doOKAction()

        // The generated board module depends on board + flavor, so both changing
        // means dist/_generated is stale until sync regenerates it.
        val flavorChanged = (config.flavor ?: "") != stdlib
        if (config.board != board || flavorChanged) {
            PyMcuSyncTask.launch(project)
        }
    }

    private fun writeOptional(
        content: String,
        section: String,
        key: String,
        value: String,
        quoted: Boolean
    ): String = if (value.isEmpty())
        TomlWriter.removeKey(content, section, key)
    else
        TomlWriter.setKey(content, section, key, if (quoted) TomlWriter.quote(value) else value)
}
