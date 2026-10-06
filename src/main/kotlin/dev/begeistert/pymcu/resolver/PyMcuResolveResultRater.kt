package dev.begeistert.pymcu.resolver

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.impl.PyResolveResultRater
import com.jetbrains.python.psi.types.PyType
import com.jetbrains.python.psi.types.TypeEvalContext
import dev.begeistert.pymcu.venv.PyMcuVenv

/**
 * Prefers the HAL implementation for the architecture the project targets.
 *
 * `from pymcu.hal.gpio import Pin` resolves to six candidates — one per
 * architecture branch of the facade — because an IDE cannot evaluate
 * `__CHIP__.arch == "avr"`. Go To Declaration then asks which of six the user
 * meant, when five of them are for silicon the project will never be compiled
 * for. The project's own configuration answers that question, so this rates the
 * live one up and the rest down.
 *
 * Two questions get asked, in this order:
 *
 *  1. [PyMcuHalIndexService] — did a branch the compiler keeps import this
 *     module? It has read the dispatch, so it settles the chip-level facades
 *     where every candidate shares one architecture directory and the directory
 *     rule below has nothing to go on.
 *  2. [PyMcuHalDispatch] — failing that, is this the project's architecture at
 *     all? A module no conditional import names still gets the coarse answer.
 *
 * Both stay silent for anything outside `pymcu/hal/` and for a project with no
 * known target, so the worst case is the behaviour there was before either.
 */
class PyMcuResolveResultRater : PyResolveResultRater {

    private val log = Logger.getInstance(PyMcuResolveResultRater::class.java)

    override fun getImportElementRate(target: PsiElement): Int = rate(target)

    override fun getMemberRate(
        member: PsiElement?,
        type: PyType?,
        context: TypeEvalContext?
    ): Int = if (member == null) PyMcuHalDispatch.NEUTRAL else rate(member)

    private fun rate(element: PsiElement): Int {
        val file = element.containingFile ?: return PyMcuHalDispatch.NEUTRAL
        val path = file.virtualFile?.path ?: return PyMcuHalDispatch.NEUTRAL

        // Cheapest possible rejection: this runs on every rated resolve in every
        // Python project, and almost none of them are under a PyMCU HAL.
        val directory = PyMcuHalDispatch.architectureDirectoryOf(path)
            ?: return PyMcuHalDispatch.NEUTRAL

        val identity = PyMcuChipInfoService.getInstance(element.project).identity()
        if (identity == null) {
            // A HAL candidate with no resolved target is how the six-way popup
            // comes back; it must leave a trace or the failure is invisible.
            log.debug("PyMCU: $path is under a HAL but the target cannot be resolved; staying neutral")
            return PyMcuHalDispatch.NEUTRAL
        }

        val verdict = PyMcuHalIndexService.getInstance(element.project).verdictFor(file, identity)
        val rate = when (verdict) {
            PyMcuBranch.LIVE -> PyMcuHalDispatch.PREFERRED
            PyMcuBranch.DEAD -> PyMcuHalDispatch.FOREIGN
            PyMcuBranch.UNKNOWN -> PyMcuHalDispatch.rate(directory, identity.chip, identity.arch)
        }
        // Two `pymcu` trees can answer at once — the SDK's and the project's own
        // venv. Same file, same verdict, and resolve() keeps whichever came
        // first, which is the SDK's — so navigation opened the stale copy and
        // died at the member the old class lacks. The project's copy is the one
        // the build compiles; it wins the tie.
        val bonus = if (PyMcuVenv.isProjectStdlibFile(element.project, path)) PyMcuHalDispatch.PROJECT else 0
        log.debug("PyMCU: $verdict (${rate + bonus}) $path")
        return rate + bonus
    }
}
