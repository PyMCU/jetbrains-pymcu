package dev.begeistert.pymcu.resolver

import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.PyGlobalStatement
import com.jetbrains.python.psi.PyImportedNameDefiner
import com.jetbrains.python.psi.PyNonlocalStatement
import com.jetbrains.python.psi.PyQualifiedExpression
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyUtil
import com.jetbrains.python.psi.resolve.PyOverridingReferenceResolveProvider
import com.jetbrains.python.psi.resolve.PyResolveProcessor
import com.jetbrains.python.psi.resolve.PyResolveUtil
import com.jetbrains.python.psi.resolve.RatedResolveResult
import com.jetbrains.python.psi.types.TypeEvalContext
import com.jetbrains.python.pyi.PyiUtil
import dev.begeistert.pymcu.venv.PyMcuVenv

/**
 * Applies the HAL verdict where the rater extension point cannot reach.
 *
 * [PyMcuResolveResultRater] is consulted by import and member resolution, but a
 * plain reference to a name a conditional import bound — `_PWM` in
 * `from pymcu.hal.pwm import PWM as _PWM`, used inside `__init__` — resolves
 * through `PyReferenceImpl`, which rates each candidate itself: import definers
 * get -1000 and everything else gets 0. The four HAL classes tie, the last
 * `elif` of the facade wins, and the user lands on `rp2350/pwm.py`.
 *
 * A `PyOverridingReferenceResolveProvider` runs before that machinery and its
 * results replace it, so this repeats the same scope crawl the reference was
 * about to run and hands back the same candidates rated the way the rater
 * would have. It only answers when the crawl actually produced HAL candidates;
 * every other name falls through to the platform's own resolution untouched.
 */
class PyMcuHalReferenceResolveProvider : PyOverridingReferenceResolveProvider {

    override fun resolveName(
        expression: PyQualifiedExpression,
        context: TypeEvalContext
    ): List<RatedResolveResult> {
        if (expression !is PyReferenceExpression) return emptyList()
        if (expression.qualifier != null) return emptyList()
        val name = expression.referencedName ?: return emptyList()
        // Private names crawl to a different roof; there is no HAL behind one.
        if (PyUtil.isClassPrivateName(name)) return emptyList()
        // The crawl below can resolve import definers; if that ever re-entered
        // this provider it must defer to the outer pass rather than recurse.
        if (resolving.get()) return emptyList()

        val project = expression.project
        val identity = PyMcuChipInfoService.getInstance(project).identity() ?: return emptyList()

        val elements = try {
            resolving.set(true)
            val processor = PyResolveProcessor(name)
            PyResolveUtil.scopeCrawlUp(processor, expression, name, null)
            // `processor.elements` is the result map's key set, and the platform
            // stores a null key for an import element that names the reference
            // but resolves no further (`from machine import Pin` in a project
            // with no `machine` shim). `PyReferenceImpl` turns that entry into
            // an ImportedResolveResult over the definer, so the definer is the
            // candidate to keep — reading the key set raw would NPE on the null.
            processor.results.entries.mapNotNull { (element, definer) -> element ?: definer }
        } finally {
            resolving.set(false)
        }
        if (elements.size < 2) return emptyList()
        if (elements.none { it.isHalArchitectureFile() }) return emptyList()

        val hal = PyMcuHalIndexService.getInstance(project)
        return elements.map { RatedResolveResult(rate(it, identity, hal, context), it) }
    }

    /**
     * The rates `PyReferenceImpl` would have produced, with the compile-time
     * dispatch verdict applied to the candidates it knows nothing about.
     */
    private fun rate(
        element: PsiElement,
        identity: ChipIdentity,
        hal: PyMcuHalIndexService,
        context: TypeEvalContext
    ): Int {
        if (element is PyImportedNameDefiner || element is PyReferenceExpression) return DEFINER

        val file = element.containingFile
        val path = file?.virtualFile?.path
        val directory = path?.let(PyMcuHalDispatch::architectureDirectoryOf)
        if (directory != null) {
            val base = when (hal.verdictFor(file, identity)) {
                PyMcuBranch.LIVE -> PyMcuHalDispatch.PREFERRED
                PyMcuBranch.DEAD -> PyMcuHalDispatch.FOREIGN
                PyMcuBranch.UNKNOWN -> PyMcuHalDispatch.rate(directory, identity.chip, identity.arch)
            }
            // A foreign SDK can serve a second `pymcu` tree: same files, same
            // verdicts, and the SDK's comes first, so the stale copy won the
            // tie and navigation died at members it lacks. The copy under the
            // project's own venv is the one the build compiles.
            return base + if (PyMcuVenv.isProjectStdlibFile(element.project, path)) PyMcuHalDispatch.PROJECT else 0
        }

        if (element is PyTargetExpression && context.maySwitchToAST(element)) {
            val parent = element.parent
            if (parent is PyGlobalStatement || parent is PyNonlocalStatement) return DEFINER
        }
        if (!PyiUtil.isInsideStub(element) && PyiUtil.isOverload(element, context)) return OVERLOAD
        return PyMcuHalDispatch.NEUTRAL
    }

    private fun PsiElement.isHalArchitectureFile(): Boolean {
        val path = containingFile?.virtualFile?.path ?: return false
        return PyMcuHalDispatch.architectureDirectoryOf(path) != null
    }

    private companion object {
        /** The weight `PyReferenceImpl.getRate` gives definers and globals/nonlocals. */
        const val DEFINER = -1000

        /** The weight `PyReferenceImpl.getRate` gives overloads outside stubs. */
        const val OVERLOAD = -200

        val resolving: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    }
}
