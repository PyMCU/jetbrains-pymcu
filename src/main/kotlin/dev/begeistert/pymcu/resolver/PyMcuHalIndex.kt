package dev.begeistert.pymcu.resolver

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyElsePart
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFromImportStatement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyMatchStatement
import com.jetbrains.python.psi.PyStatement
import java.util.concurrent.ConcurrentHashMap

/**
 * Which HAL modules the compiler would actually compile for this project's target.
 *
 * ## Why the architecture directory is not enough
 *
 * [PyMcuHalDispatch] reads the architecture off the path, and that decides every
 * facade that dispatches on `__CHIP__.arch`. It cannot decide the second layer,
 * where one architecture's own facade picks between chips:
 *
 * ```python
 * # pymcu/hal/avr/gpio/__init__.py
 * if __CHIP__.name == "atmega328p" or ...:
 *     from pymcu.hal.avr.gpio.atmega328p import board_pin_name
 * elif __CHIP__.name == "attiny85" or ...:
 *     from pymcu.hal.avr.gpio.attiny_b import board_pin_name
 * ...
 * ```
 *
 * All six candidates are under `hal/avr/`, so all six rate the same, and Go To
 * Declaration on `board_pin_name` in an Arduino Uno project landed in
 * `atmega32u4.py` — the last binding in the file, which is a Leonardo. The same
 * shape sends `uart_write` for an ATtiny2313 project to `avr/uart/avr.py`.
 *
 * ## What this does instead
 *
 * It reads the dispatch. Every `.py` under `pymcu/hal/` that mentions `__CHIP__`
 * is parsed, its `if` / `elif` / `else` chains and its `match __CHIP__.<field>`
 * statements are evaluated against the target by [PyMcuChipCondition], and the
 * module each branch imports from is recorded as live or dead. That is the
 * compiler's own rule rather than a guess from a file name, so it gets
 * `attiny_b.py` (which serves five ATtiny parts) and `attiny2313.py` (which
 * serves two) right, and no table here has to be kept in step with the stdlib.
 *
 * Function and class bodies are walked too, because the dispatch is not always at
 * module level: `PWM.set_duty` on PIC14 chooses its register map with a `match`
 * inside the method and imports the module from the case body.
 *
 * Two limits, both deliberate:
 *  - only imports **under** a `__CHIP__` conditional are recorded. An import that
 *    always runs says nothing about the target, and recording it as live would
 *    let a module reached only from dead code mark its own imports live.
 *  - a facade inside a foreign architecture directory is skipped entirely, so an
 *    RP2040 facade cannot have an opinion about an AVR build.
 */
@Service(Service.Level.PROJECT)
class PyMcuHalIndexService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PyMcuHalIndexService::class.java)

    /** Keyed by HAL root and target; both are stable for the life of a build setup. */
    private val cache = ConcurrentHashMap<Key, Index>()

    init {
        // The stdlib is editable in the projects that most need this — someone
        // adding a chip to a facade must see navigation follow within the session.
        // VFS events, like PyMcuProjectService's own listener: an in-editor edit
        // lands here when it is saved.
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { HAL_SEGMENT in it.path.replace('\\', '/') }) cache.clear()
                }
            }
        )
    }

    /**
     * Whether [file] is a module the compiler keeps for [target].
     *
     * [PyMcuBranch.UNKNOWN] for anything outside `pymcu/hal/`, and for a module no
     * conditional import names — the caller falls back to the architecture rule.
     */
    fun verdictFor(file: PsiFile, target: ChipIdentity): PyMcuBranch {
        val virtualFile = file.virtualFile ?: return PyMcuBranch.UNKNOWN
        val halRoot = halRootOf(virtualFile) ?: return PyMcuBranch.UNKNOWN
        val module = moduleNameOf(virtualFile, halRoot) ?: return PyMcuBranch.UNKNOWN
        val index = indexFor(halRoot, target)
        return when (module) {
            in index.live -> PyMcuBranch.LIVE
            in index.dead -> PyMcuBranch.DEAD
            else -> PyMcuBranch.UNKNOWN
        }
    }

    private fun indexFor(halRoot: VirtualFile, target: ChipIdentity): Index =
        cache.getOrPut(Key(halRoot.url, target)) { build(halRoot, target) }

    // ── building ─────────────────────────────────────────────────────────────

    private fun build(halRoot: VirtualFile, target: ChipIdentity): Index {
        val live = HashSet<String>()
        val dead = HashSet<String>()
        val psiManager = PsiManager.getInstance(project)

        VfsUtilCore.visitChildrenRecursively(halRoot, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) return true
                if (file.extension != "py" || file.length > MAX_FACADE_BYTES) return true
                // A foreign architecture's facade is not consulted at all: it
                // dispatches for silicon this project is not being built for, and
                // its branches would mark modules dead that nothing else names.
                if (!trusted(file, target)) return true
                // Cheapest filter, and it skips ~70% of the tree: reading the text
                // out of the VFS costs no PSI, and a file with no `__CHIP__` in it
                // has no compile-time dispatch to read.
                if (!LoadTextUtil.loadText(file).contains(CHIP)) return true
                val pyFile = psiManager.findFile(file) as? PyFile ?: return true
                scan(pyFile.statements, PyMcuBranch.LIVE, guarded = false, target, live, dead)
                return true
            }
        })

        // An import under a live branch settles it: a module a second facade also
        // names in a dead branch is still compiled, and must not be demoted.
        dead.removeAll(live)
        log.debug("PyMCU: HAL index for ${target.chip}: ${live.size} live, ${dead.size} dead module(s)")
        return Index(live, dead)
    }

    /**
     * Walks statements, carrying how the branch they sit in evaluated.
     *
     * [guarded] is false until a `__CHIP__` condition has been crossed, which is
     * what keeps unconditional imports out of the index — see the class docs.
     */
    private fun scan(
        statements: List<PyStatement>,
        verdict: PyMcuBranch,
        guarded: Boolean,
        target: ChipIdentity,
        live: MutableSet<String>,
        dead: MutableSet<String>,
    ) {
        for (statement in statements) {
            when (statement) {
                is PyFromImportStatement -> {
                    if (!guarded) continue
                    val module = statement.importSourceQName?.toString() ?: continue
                    when (verdict) {
                        PyMcuBranch.LIVE -> live += module
                        PyMcuBranch.DEAD -> dead += module
                        PyMcuBranch.UNKNOWN -> Unit
                    }
                }

                is PyIfStatement -> {
                    val parts = buildList {
                        add(statement.ifPart)
                        addAll(statement.elifParts)
                    }
                    val elsePart: PyElsePart? = statement.elsePart
                    val conditions = parts.map { it.condition }
                    val verdicts = PyMcuChipCondition.chainVerdicts(conditions, elsePart != null, target)
                    val chainGuarded = guarded || conditions.any { PyMcuChipCondition.mentionsTarget(it) }

                    for ((index, part) in parts.withIndex()) {
                        scan(
                            part.statementList.statements.toList(),
                            verdict.and(verdicts[index]),
                            chainGuarded,
                            target, live, dead,
                        )
                    }
                    if (elsePart != null) {
                        scan(
                            elsePart.statementList.statements.toList(),
                            verdict.and(verdicts.last()),
                            chainGuarded,
                            target, live, dead,
                        )
                    }
                }

                is PyMatchStatement -> {
                    val clauses = statement.caseClauses
                    val verdicts = PyMcuChipCondition.matchVerdicts(statement.subject, clauses, target)
                    val matchGuarded = guarded || PyMcuChipCondition.mentionsTarget(statement.subject)
                    for ((index, clause) in clauses.withIndex()) {
                        scan(
                            clause.statementList.statements.toList(),
                            verdict.and(verdicts[index]),
                            matchGuarded,
                            target, live, dead,
                        )
                    }
                }

                // The dispatch is not always at module level: `PWM.set_duty` on
                // PIC14 picks its register map with a `match` inside the method,
                // and the import that names the module sits in the case body.
                is PyFunction ->
                    scan(statement.statementList.statements.toList(), verdict, guarded, target, live, dead)

                is PyClass ->
                    scan(statement.statementList.statements.toList(), verdict, guarded, target, live, dead)

                else -> Unit
            }
        }
    }

    /** False for a facade that lives under an architecture this project is not building. */
    private fun trusted(file: VirtualFile, target: ChipIdentity): Boolean {
        val directory = PyMcuHalDispatch.architectureDirectoryOf(file.path) ?: return true
        return PyMcuHalDispatch.isLive(directory, target.chip, target.arch)
    }

    override fun dispose() = Unit

    private data class Key(val halRootUrl: String, val target: ChipIdentity)

    private class Index(val live: Set<String>, val dead: Set<String>)

    companion object {
        private const val CHIP = "__CHIP__"
        private const val HAL_SEGMENT = "/pymcu/hal/"

        /** No facade is anywhere near this; the cap stops a stray blob being read. */
        private const val MAX_FACADE_BYTES = 512L * 1024L

        fun getInstance(project: Project): PyMcuHalIndexService =
            project.getService(PyMcuHalIndexService::class.java)

        /**
         * The `pymcu/hal` directory [file] sits under, or null when it does not.
         *
         * Found by walking up rather than from a configured path, so it works the
         * same for a wheel under site-packages, an editable checkout the `.pth`
         * points at, and a `stdlib_path` working tree.
         */
        fun halRootOf(file: VirtualFile): VirtualFile? {
            var directory = file.parent
            while (directory != null) {
                if (directory.name == "hal" && directory.parent?.name == "pymcu") return directory
                directory = directory.parent
            }
            return null
        }

        /**
         * The dotted module name of [file], as an import statement would spell it:
         * `pymcu/hal/avr/uart/avr.py` is `pymcu.hal.avr.uart.avr`, and a package's
         * `__init__.py` is the package itself.
         */
        fun moduleNameOf(file: VirtualFile, halRoot: VirtualFile): String? {
            val relative = VfsUtilCore.getRelativePath(file, halRoot, '/') ?: return null
            val withoutExtension = relative.removeSuffix(".py").removeSuffix(".pyi")
            val parts = withoutExtension.split('/').filter { it.isNotEmpty() && it != "__init__" }
            return (listOf("pymcu", "hal") + parts).joinToString(".")
        }
    }
}
