package dev.begeistert.pymcu

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.venv.PyMcuVenv
import java.io.File

/**
 * `import pymcu.*` against the project's own `.venv`.
 *
 * The failure this pins: a project whose configured interpreter belongs to
 * another project — cp-servo pointing at `Python 3.10 (sevensegments)` —
 * resolved `import pymcu` through that SDK alone, into a stdlib whose `PWM`
 * predates `set_duty_u16`. The compat layer still came from the project's
 * `.venv`, so navigation died exactly at the member the old class lacked, one
 * hop before the HAL. A `PyImportResolver` result is always unioned into the
 * platform's candidates, so making the project's own `pymcu` visible here is
 * enough for the member lookup to find the class the build compiles.
 */
class VenvStdlibResolutionTest : BasePlatformTestCase() {

    private val base get() = File(project.basePath!!)

    /**
     * The venv is resolved through `java.io.File` and `LocalFileSystem`, not
     * through the in-memory fixture tree — `addFileToProject` would put these
     * under `temp:///root`, where no `.pth` reader could ever see them.
     */
    private fun write(path: String, text: String = "") {
        val file = File(base, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun refresh() {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(base)
            ?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        PyMcuVenv.clearCaches()
        // The service caches config() on first call, which fixture setup makes
        // before pyproject.toml exists; the real VFS listener would invalidate
        // it on the file event.
        PyMcuProjectService.getInstance(project).invalidate()
    }

    /** What Ctrl-click on the last component of `import a.b.c` resolves to. */
    private fun resolve(vararg components: String): PsiElement? {
        val file = myFixture.configureByText(
            "probe.py", "import ${components.joinToString(".")}\n"
        )
        val offset = file.text.lastIndexOf(components.last()) + 1
        return file.findReferenceAt(offset)?.resolve()
    }

    private fun where(element: PsiElement?): String =
        element?.containingFile?.virtualFile?.path ?: "<unresolved>"

    private fun venv() = ".venv/lib/python3.14/site-packages"

    override fun setUp() {
        super.setUp()
        write("pyproject.toml", "[tool.pymcu]\nboard = \"arduino_uno\"\nstdlib = [\"circuitpython\"]\n")
        refresh()
    }

    override fun tearDown() {
        try {
            // `project.basePath` is shared by every test in the class: the venv
            // tree one test builds must not leak into the next one's roots.
            for (path in listOf(".venv", "editable-src", "avr-src", "lib-src", "pyproject.toml")) {
                File(base, path).deleteRecursively()
            }
            refresh()
        } finally {
            super.tearDown()
        }
    }

    /**
     * The editable checkout — `lib/src` named by a `.pth`, exactly what
     * `uv pip install -e lib/` leaves — answers `pymcu.hal.pwm` even though
     * nothing under site-packages mentions it.
     */
    fun testTheProjectVenvSuppliesPymcuWhenTheSdkDoesNot() {
        write("editable-src/pymcu/hal/pwm.py", "class PWM: pass")
        write("${venv()}/_editable_impl_pymcu_stdlib.pth", "$base/editable-src")
        refresh()

        assertTrue(
            "the project venv's editable pymcu must resolve, got ${where(resolve("pymcu", "hal", "pwm"))}",
            where(resolve("pymcu", "hal", "pwm")).endsWith("editable-src/pymcu/hal/pwm.py")
        )
    }

    /** A physical copy under site-packages wins over a `.pth`, as in Python. */
    fun testAnInstalledPymcuOutranksTheEditableOne() {
        write("${venv()}/pymcu/hal/pwm.py", "class PWM: pass")
        write("editable-src/pymcu/hal/pwm.py", "class PWM: pass")
        write("${venv()}/_editable_impl_pymcu_stdlib.pth", "$base/editable-src")
        refresh()

        assertTrue(
            "an installed pymcu must win over the editable one, got ${where(resolve("pymcu", "hal", "pwm"))}",
            where(resolve("pymcu", "hal", "pwm")).endsWith("site-packages/pymcu/hal/pwm.py")
        )
    }

    /**
     * `pymcu` is a namespace package: the stdlib carries `hal`, a backend
     * carries `backend`, each behind its own `.pth`. A submodule resolves in
     * whichever portion holds it — stopping at the first portion finds a
     * directory without `hal/` and reports nothing.
     */
    fun testAPymcuNameIsFoundInWhicheverPortionCarriesIt() {
        write("avr-src/pymcu/backend/avr.py", "def build(): pass")
        write("lib-src/pymcu/hal/pwm.py", "class PWM: pass")
        write("${venv()}/_editable_impl_pymcu_avr.pth", "$base/avr-src")
        write("${venv()}/_editable_impl_pymcu_stdlib.pth", "$base/lib-src")
        refresh()

        assertTrue(
            "hal resolves in the stdlib portion, got ${where(resolve("pymcu", "hal", "pwm"))}",
            where(resolve("pymcu", "hal", "pwm")).endsWith("lib-src/pymcu/hal/pwm.py")
        )
        assertTrue(
            "backend resolves in the backend portion, got ${where(resolve("pymcu", "backend", "avr"))}",
            where(resolve("pymcu", "backend", "avr")).endsWith("avr-src/pymcu/backend/avr.py")
        )
    }


    /** A `pymcu` name no portion carries is not invented. */
    fun testAPymcuModuleThatExistsNowhereIsNotInvented() {
        write("editable-src/pymcu/hal/pwm.py", "class PWM: pass")
        write("${venv()}/_editable_impl_pymcu_stdlib.pth", "$base/editable-src")
        refresh()

        assertNull(resolve("pymcu", "hal", "stepper"))
    }

    /**
     * A bare name that happens to live under `site-packages/pymcu` must not
     * leak: the namespace parents are roots for `pymcu.*` only, never for the
     * compat names, which resolve through `pymcu_<flavor>` roots alone.
     */
    fun testANonPymcuNameDoesNotReachIntoThePymcuRoots() {
        write("${venv()}/pymcu/machine.py", "class Pin: pass")
        refresh()

        assertNull(resolve("machine"))
    }
}
