package dev.begeistert.pymcu

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.begeistert.pymcu.project.PyMcuProjectService
import dev.begeistert.pymcu.resolver.ChipIdentity
import dev.begeistert.pymcu.resolver.PyMcuBranch
import dev.begeistert.pymcu.resolver.PyMcuChipInfoService
import dev.begeistert.pymcu.resolver.PyMcuHalIndexService
import dev.begeistert.pymcu.venv.PyMcuVenv
import java.io.File

/**
 * Where Ctrl-click lands when the code it is pointing at exists five times.
 *
 * This is the bug a user reports as "navigating the shims and the ZCAs, I end up
 * in rp2040, sometimes rp2350, sometimes RISC-V". The HAL dispatches at compile
 * time and the IDE cannot evaluate `__CHIP__`, so a name defined once per
 * architecture resolves to all of them and PyCharm picks whichever came last.
 *
 * Two layers of dispatch, and the second is the one that stayed broken longest:
 *
 *  - `hal/pwm.py` picks an architecture. The architecture directory in the path
 *    was enough to decide that one.
 *  - `hal/avr/gpio/__init__.py` picks a **chip**, out of six modules that all sit
 *    in `hal/avr/`. Nothing in the path separates them, and Go To Declaration on
 *    `board_pin_name` in an Arduino Uno project landed on `atmega32u4.py` — a
 *    Leonardo — because it is the last binding in the file.
 *
 * The tree below mirrors the real facades closely enough to reproduce both, down
 * to `uart_text.py` naming three AVR modules and `pic14/pwm.py` dispatching from
 * inside a method with `match`. Every assertion names the file the compiler would
 * compile for that target.
 */
class HalNavigationTest : BasePlatformTestCase() {

    private val uno = ChipIdentity("atmega328p", "avr", board = "arduino_uno", frequency = 16_000_000)
    private val tiny = ChipIdentity("attiny2313", "avr")
    private val pico = ChipIdentity("rp2040", "arm", board = "pico")
    private val pic = ChipIdentity("pic16f18877", "pic14")

    private fun add(path: String, text: String) = myFixture.addFileToProject(path, text.trimIndent())

    private fun target(identity: ChipIdentity?) {
        PyMcuChipInfoService.getInstance(project).overrideIdentity(identity)
    }

    private var counter = 0

    /** Where Ctrl-click goes, and where everything that resolves a reference goes. */
    private fun assertLandsIn(expected: String, program: String) {
        val file = myFixture.configureByText("probe${counter++}.py", program)
        val offset = myFixture.caretOffset
        val goto = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, offset)
            ?.toList().orEmpty()

        assertEquals("Go To Declaration must not ask which of several", 1, goto.size)
        assertEquals("Go To Declaration", expected, where(goto.single()))
        assertEquals("resolve", expected, where(file.findReferenceAt(offset)?.resolve()))
    }

    /**
     * Same landing assertion without the Go-To-Declaration half: for a member
     * access PyCharm may legitimately answer more than one element (a property's
     * getter and setter), while the question here is always "which file".
     */
    private fun assertResolvesTo(expected: String, program: String) {
        val file = myFixture.configureByText("probe${counter++}.py", program)
        assertEquals("resolve", expected, where(file.findReferenceAt(myFixture.caretOffset)?.resolve()))
    }

    /** Puts the caret inside [needle] in a fixture file and resolves what is under it. */
    private fun assertFileReferenceLandsIn(filePath: String, needle: String, expected: String) {
        val virtualFile = myFixture.findFileInTempDir(filePath)
        myFixture.openFileInEditor(virtualFile)
        val text = myFixture.editor.document.text
        val offset = text.indexOf(needle).let {
            check(it >= 0) { "$needle not found in $filePath" }
            it + needle.length / 2
        }
        assertEquals("resolve of $needle in $filePath", expected, where(myFixture.file.findReferenceAt(offset)?.resolve()))
    }

    private fun where(element: PsiElement?): String =
        element?.containingFile?.virtualFile?.path?.removePrefix("/src/") ?: "<unresolved>"

    // ── a stdlib shaped like the real one ────────────────────────────────────

    override fun setUp() {
        super.setUp()
        stdlib()
    }

    override fun tearDown() {
        try {
            target(null)
            // `project.basePath` is shared by every test in the class: anything
            // written for the venv scenario must go, or the next test resolves
            // `pymcu` against a tree it never created.
            for (path in listOf("editable-src", ".venv", "pyproject.toml")) {
                File(projectBase, path).deleteRecursively()
            }
            refreshRealFs()
        } finally {
            super.tearDown()
        }
    }

    private fun stdlib() {
        add("pymcu/__init__.py", "")
        add("pymcu/chips/__init__.py", "class _Chip:\n    name = \"\"\n    arch = \"\"\n__CHIP__ = _Chip()")
        add("pymcu/exceptions.py", "class CompileError(Exception): pass")
        add("pymcu/hal/__init__.py", "")

        // hal/pwm.py — the architecture-level facade.
        add("pymcu/hal/pwm.py", """
            from pymcu.chips import __CHIP__
            from pymcu.exceptions import CompileError

            if __CHIP__.arch == "avr":
                from pymcu.hal.avr.pwm import PWM
            elif __CHIP__.arch == "pic14":
                from pymcu.hal.pic14.pwm import PWM
            elif __CHIP__.name == "rp2040":
                from pymcu.hal.rp2040.pwm import PWM
            elif __CHIP__.name == "rp2350":
                from pymcu.hal.rp2350.pwm import PWM
            else:
                raise CompileError("PWM not supported on this architecture")
        """)
        for (arch in listOf("rp2040", "rp2350")) {
            add("pymcu/hal/$arch/__init__.py", "")
            add("pymcu/hal/$arch/pwm.py", """
                class PWM:
                    def __init__(self, pin, duty=0, freq=0, duty_u16=0): pass
                    def set_duty(self, d): pass
                    def set_duty_u16(self, d): pass
                    def set_freq(self, f): pass
                    def frequency(self): pass
            """.trimIndent())
            add("pymcu/hal/$arch/gpio.py", "class Pin:\n    def value(self, v=255): pass")
        }
        add("pymcu/hal/rp/__init__.py", "")
        add("pymcu/hal/rp/console.py", "def uart_write(b): pass")

        // hal/avr/pwm/ — the chip-level facade under one architecture.
        add("pymcu/hal/avr/__init__.py", "")
        add("pymcu/hal/avr/pwm/__init__.py", """
            from pymcu.chips import __CHIP__

            if __CHIP__.name == "attiny85" or __CHIP__.name == "attiny45":
                from pymcu.hal.avr.pwm.attiny85 import pwm_init
            else:
                from pymcu.hal.avr.pwm.atmega328p import pwm_init

            class PWM:
                def __init__(self, pin, duty=0, freq=0, duty_u16=0): pass
                def set_duty(self, d): pass
                def set_duty_u16(self, d):
                    return pwm_init(d)
                def set_freq(self, f): pass
                def frequency(self): pass
        """)
        add("pymcu/hal/avr/pwm/atmega328p.py", "def pwm_init(d): pass")
        add("pymcu/hal/avr/pwm/attiny85.py", "def pwm_init(d): pass")

        add("pymcu/hal/gpio.py", """
            from pymcu.chips import __CHIP__
            from pymcu.exceptions import CompileError

            if __CHIP__.arch == "avr":
                from pymcu.hal.avr.gpio import Pin
            elif __CHIP__.name == "rp2040":
                from pymcu.hal.rp2040.gpio import Pin
            elif __CHIP__.name == "rp2350":
                from pymcu.hal.rp2350.gpio import Pin
            else:
                raise CompileError("GPIO not supported on this architecture")
        """)
        add("pymcu/hal/avr/gpio/__init__.py", """
            from pymcu.chips import __CHIP__

            if __CHIP__.name == "atmega328p" or __CHIP__.name == "atmega168p":
                from pymcu.hal.avr.gpio.atmega328p import board_pin_name
            elif __CHIP__.name == "attiny85" or __CHIP__.name == "attiny45":
                from pymcu.hal.avr.gpio.attiny_b import board_pin_name
            elif __CHIP__.name == "attiny2313" or __CHIP__.name == "attiny4313":
                from pymcu.hal.avr.gpio.attiny2313 import board_pin_name
            elif __CHIP__.name == "atmega2560":
                from pymcu.hal.avr.gpio.atmega2560 import board_pin_name
            elif __CHIP__.name == "atmega32u4":
                from pymcu.hal.avr.gpio.atmega32u4 import board_pin_name

            class Pin:
                def value(self, v=255): pass
        """)
        for (chip in listOf("atmega328p", "attiny_b", "attiny2313", "atmega2560", "atmega32u4")) {
            add("pymcu/hal/avr/gpio/$chip.py", "def board_pin_name(n): return n")
        }

        // hal/uart_text.py — three AVR modules behind __CHIP__.name.
        add("pymcu/hal/uart_text.py", """
            from pymcu.chips import __CHIP__
            from pymcu.exceptions import CompileError

            if __CHIP__.name == "attiny2313" or __CHIP__.name == "attiny4313":
                from pymcu.hal.avr.uart.attiny2313 import uart_write
            elif __CHIP__.name == "atmega32u4":
                from pymcu.hal.avr.uart.atmega32u4 import uart_write
            elif __CHIP__.arch == "avr":
                from pymcu.hal.avr.uart.avr import uart_write
            elif __CHIP__.arch == "pic14":
                from pymcu.hal.pic14.pic14_uart import uart_write
            elif __CHIP__.name == "rp2040" or __CHIP__.name == "rp2350":
                from pymcu.hal.rp.console import uart_write
            else:
                raise CompileError("this architecture has no uart_write")
        """)
        add("pymcu/hal/avr/uart/__init__.py", "")
        for (chip in listOf("avr", "attiny2313", "atmega32u4")) {
            add("pymcu/hal/avr/uart/$chip.py", "def uart_write(b): pass")
        }

        // hal/pic14/ — the dispatch inside a method, with match.
        add("pymcu/hal/pic14/__init__.py", "")
        add("pymcu/hal/pic14/pic14_uart.py", "def uart_write(b): pass")
        add("pymcu/hal/pic14/pwm.py", """
            from pymcu.chips import __CHIP__

            class PWM:
                def set_duty(self, duty):
                    match __CHIP__.name:
                        case "pic16f18877":
                            from pymcu.hal.pic14.pic16f18877_pwm import pwm_set_duty
                            pwm_set_duty(duty)
                        case _:
                            from pymcu.hal.pic14.pic16f877a_pwm import pwm_set_duty
                            pwm_set_duty(duty)
        """)
        for (chip in listOf("pic16f18877", "pic16f877a")) {
            add("pymcu/hal/pic14/${chip}_pwm.py", "def pwm_set_duty(d): pass")
        }

        // The CircuitPython layer, at the root so a bare `import pwmio` resolves.
        // Shaped like the real pymcu_circuitpython/pwmio.py: `_PWM` bound under
        // a single `__CHIP__` branch, `self._pwm` typed by the constructor call,
        // and the property setters delegating to HAL methods.
        add("pwmio.py", """
            from pymcu.chips import __CHIP__
            if __CHIP__.arch == "avr":
                from pymcu.hal.pwm import PWM as _PWM
            elif __CHIP__.name == "rp2040":
                from pymcu.hal.rp2040.pwm import PWM as _PWM

            class PWMOut:
                def __init__(self, pin, duty_cycle=0, frequency=500):
                    self._duty_cycle_16 = duty_cycle
                    self._pwm = _PWM(pin, freq=frequency, duty_u16=duty_cycle)
                    self._real_frequency = self._pwm.frequency()

                @property
                def duty_cycle(self):
                    return self._duty_cycle_16

                @duty_cycle.setter
                def duty_cycle(self, val):
                    self._duty_cycle_16 = val
                    self._pwm.set_duty_u16(val)

                @property
                def frequency(self):
                    return self._real_frequency

                @frequency.setter
                def frequency(self, val):
                    self._pwm.set_freq(val)
                    self._real_frequency = self._pwm.frequency()

                def set_duty_cycle(self, val):
                    self._pwm.set_duty(val)
        """)
    }

    // ── the architecture layer ───────────────────────────────────────────────

    fun testTheFacadePicksTheProjectsArchitecture() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/pwm/__init__.py",
            "from pymcu.hal.pwm import PWM\nx = PW<caret>M\n"
        )
    }

    fun testTheSameFacadePicksRpForAPico() {
        target(pico)
        assertLandsIn(
            "pymcu/hal/rp2040/pwm.py",
            "from pymcu.hal.pwm import PWM\nx = PW<caret>M\n"
        )
    }

    /** The name inside the import itself — where `resolve()` meets the facade's own bindings. */
    fun testTheNameInsideTheImportPicksTheArchitectureToo() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/pwm/__init__.py",
            "from pymcu.hal.pwm import PW<caret>M\n"
        )
    }

    /** The alias the compat layer binds, reached from a usage site. */
    fun testTheCompatAliasReachesTheArchitecturesPwm() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/pwm/__init__.py",
            """
            from pymcu.chips import __CHIP__
            if __CHIP__.arch == "avr":
                from pymcu.hal.pwm import PWM as _PWM
            elif __CHIP__.name == "rp2040":
                from pymcu.hal.rp2040.pwm import PWM as _PWM

            p = _PW<caret>M()
            """
        )
    }

    /**
     * The real `pwmio.py` shape: a single `if` branch binding `PWM as _PWM`,
     * used inside `__init__`. In the sandbox this landed on `rp2350/pwm.py` —
     * the last elif of the facade — while a member lookup (`self._pwm.frequency()`)
     * correctly reached AVR.
     */
    fun testTheSingleBranchCompatAliasReachesTheArchitecturesPwm() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/pwm/__init__.py",
            """
            from pymcu.chips import __CHIP__
            if __CHIP__.arch == "avr":
                from pymcu.hal.pwm import PWM as _PWM

            class PWMOut:
                def __init__(self, pin):
                    self._pwm = _PW<caret>M(pin)
            """
        )
    }

    /**
     * The same aliased-name-in-a-method shape against a facade whose answer is
     * a package, `avr/gpio/__init__.py`, rather than a module — `digitalio.py`
     * binds `Pin as _Pin` exactly the way `pwmio.py` binds `PWM as _PWM`.
     */
    fun testTheDigitalioStyleAliasReachesTheArchitecturesGpio() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/gpio/__init__.py",
            """
            from pymcu.chips import __CHIP__
            if __CHIP__.arch == "avr":
                from pymcu.hal.gpio import Pin as _Pin
            elif __CHIP__.name == "rp2040":
                from pymcu.hal.rp2040.gpio import Pin as _Pin

            class DigitalInOut:
                def __init__(self, pin):
                    self._pin = _Pi<caret>n(pin)
            """
        )
    }

    /**
     * An import that names the reference but resolves no further — `machine`
     * does not exist in this fixture, the way CircuitPython names go unresolved
     * in a project with no compat shim — stores a null key in the processor's
     * result map. Iterating `processor.elements` NPEs on it; the definer it maps
     * to is the candidate the platform itself keeps.
     */
    fun testAnUnresolvedImportBindingTheSameNameDoesNotCrash() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/pwm/__init__.py",
            """
            from machine import PWM
            from pymcu.hal.pwm import PWM
            x = PW<caret>M
            """
        )
    }

    /** A `from` import without an alias, used from inside a function body. */
    fun testAPlainImportUsedInsideAFunctionPicksTheArchitecture() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/gpio/__init__.py",
            """
            from pymcu.hal.gpio import Pin

            def make(pin):
                return Pi<caret>n(pin)
            """
        )
    }

    /**
     * `pymcu.hal.pwm.PWM` spelled out. `PyFile.multiResolveName` answers with a
     * single last-binding-wins element — the last `elif` — so this goes through
     * the module-members provider, not the rater.
     */
    fun testAFullyQualifiedReferencePicksTheArchitecture() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/pwm/__init__.py",
            """
            import pymcu.hal.pwm
            x = pymcu.hal.pwm.PW<caret>M()
            """
        )
    }

    /** The chip-level facade reached the same way: a usage inside a function. */
    fun testTheChipLevelNameUsedInsideAFunctionPicksTheChip() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/pwm/atmega328p.py",
            """
            from pymcu.hal.avr.pwm import pwm_init

            def start(pin):
                pwm_in<caret>it(pin)
            """
        )
    }

    /**
     * The `servo.py` shape, end to end: `pwm_out: "PWMOut"` is a string
     * annotation resolved through a try/except import whose fallback —
     * `circuitpython_typing.pwmio`, the stub-only package that does not exist in
     * a PyMCU environment — stores the null result-map key that crashed the
     * resolve provider and dropped every reference that touched the annotation
     * into "find usages". A write through the attribute must reach the compat
     * layer's setter.
     */
    fun testAWriteThroughAStringAnnotatedAttributeReachesTheSetter() {
        target(uno)
        assertResolvesTo(
            "pwmio.py",
            """
            try:
                from pwmio import PWMOut
            except NotImplementedError:
                from circuitpython_typing.pwmio import PWMOut

            class Servo:
                def __init__(self, pwm_out: "PWMOut"):
                    self._pwm_out = pwm_out

                def set(self, duty_cycle):
                    self._pwm_out.duty_cyc<caret>le = duty_cycle
            """
        )
    }

    /** The same attribute read — `self._pwm_out.frequency` in `set_pulse_width_range`. */
    fun testAReadThroughAStringAnnotatedAttributeReachesTheGetter() {
        target(uno)
        assertResolvesTo(
            "pwmio.py",
            """
            try:
                from pwmio import PWMOut
            except NotImplementedError:
                from circuitpython_typing.pwmio import PWMOut

            class Servo:
                def __init__(self, pwm_out: "PWMOut"):
                    self._pwm_out = pwm_out

                def read(self):
                    return self._pwm_out.frequen<caret>cy
            """
        )
    }

    /**
     * The last hop of the chain — `self._pwm.set_duty_u16(val)` inside the
     * `duty_cycle` setter of the real pwmio.py shape. `self._pwm` is typed by
     * the `_PWM(pin, ...)` call in `__init__`, and `_PWM` resolves through the
     * single `__CHIP__` branch, so the member lands on the project's HAL.
     */
    fun testTheCompatDutySetterReachesTheHalMethod() {
        target(uno)
        assertFileReferenceLandsIn("pwmio.py", "set_duty_u16", "pymcu/hal/avr/pwm/__init__.py")
    }

    /** And for an RP target the same line must land on the RP HAL, not AVR. */
    fun testTheCompatDutySetterReachesTheRpHalForAnRpTarget() {
        target(pico)
        assertFileReferenceLandsIn("pwmio.py", "set_duty_u16", "pymcu/hal/rp2040/pwm.py")
    }

    /** The `frequency` setter delegates too — `self._pwm.set_freq(val)`. */
    fun testTheCompatFrequencySetterReachesTheHalMethod() {
        target(uno)
        assertFileReferenceLandsIn("pwmio.py", "set_freq", "pymcu/hal/avr/pwm/__init__.py")
    }

    /**
     * The last hop of all — inside the AVR HAL's own `set_duty_u16` the module
     * calls `pwm_init`, a name the chip facade bound under `__CHIP__.name`. The
     * Uno project's chain servo.py → pwmio.py → avr/pwm → ends on the
     * atmega328p register map, the file the compiler actually compiles.
     */
    fun testInsideTheHalMethodTheChipModuleWins() {
        target(uno)
        assertFileReferenceLandsIn(
            "pymcu/hal/avr/pwm/__init__.py", "pwm_init(d)", "pymcu/hal/avr/pwm/atmega328p.py"
        )
    }

    /** Through the CircuitPython shim, which is how the user reaches it. */
    fun testAMethodCalledThroughTheCompatLayerReachesTheAvrZca() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/pwm/__init__.py",
            "from pymcu.hal.pwm import PWM\np = PWM()\np.set_d<caret>uty(4)\n"
        )
    }

    // ── the chip layer, which the architecture directory cannot decide ───────

    /**
     * The regression this whole mechanism exists for. All five candidates are in
     * `hal/avr/gpio/`, so the path says nothing, and this landed on `atmega32u4`.
     */
    fun testTheChipLevelFacadePicksTheProjectsChip() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/gpio/atmega328p.py",
            "from pymcu.hal.avr.gpio import board_pin_na<caret>me\n"
        )
    }

    fun testTheSameFacadePicksTheAttinyForAnAttinyProject() {
        target(tiny)
        assertLandsIn(
            "pymcu/hal/avr/gpio/attiny2313.py",
            "from pymcu.hal.avr.gpio import board_pin_na<caret>me\n"
        )
    }

    /** `uart_text.py` names three AVR modules; only one is the ATtiny2313's. */
    fun testTheGenericAvrWriterLosesToTheChipsOwn() {
        target(tiny)
        assertLandsIn(
            "pymcu/hal/avr/uart/attiny2313.py",
            "from pymcu.hal.uart_text import uart_wri<caret>te\n"
        )
    }

    /** And the Uno takes the generic one, by decision rather than by file order. */
    fun testTheUnoTakesTheGenericAvrWriter() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/uart/avr.py",
            "from pymcu.hal.uart_text import uart_wri<caret>te\n"
        )
    }

    fun testTheElseBranchOfAChipFacadeIsTakenWhenNothingElseMatches() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/pwm/atmega328p.py",
            "from pymcu.hal.avr.pwm import pwm_in<caret>it\n"
        )
    }

    // ── what the index concluded, module by module ───────────────────────────

    private fun verdict(path: String, identity: ChipIdentity): PyMcuBranch {
        val file = myFixture.findFileInTempDir(path)
        return PyMcuHalIndexService.getInstance(project)
            .verdictFor(myFixture.psiManager.findFile(file)!!, identity)
    }

    /**
     * The dispatch inside a method body, written with `match`. It never shows up
     * as a five-way Ctrl-click — the imports are local to the method — but it is
     * how PIC14 picks its register map, and the modules it names have to be
     * classified or `pwm_set_duty` is a coin flip between two files in one folder.
     */
    fun testAMatchInsideAMethodDecidesToo() {
        assertEquals(PyMcuBranch.LIVE, verdict("pymcu/hal/pic14/pic16f18877_pwm.py", pic))
        assertEquals(PyMcuBranch.DEAD, verdict("pymcu/hal/pic14/pic16f877a_pwm.py", pic))

        val other = ChipIdentity("pic16f877a", "pic14")
        assertEquals(PyMcuBranch.LIVE, verdict("pymcu/hal/pic14/pic16f877a_pwm.py", other))
        assertEquals(PyMcuBranch.DEAD, verdict("pymcu/hal/pic14/pic16f18877_pwm.py", other))
    }

    /**
     * A facade for silicon this project is not building must not classify
     * anything: its branches would mark modules dead that no live facade names.
     */
    fun testAForeignArchitecturesFacadeIsNotConsulted() {
        assertEquals(PyMcuBranch.UNKNOWN, verdict("pymcu/hal/pic14/pic16f18877_pwm.py", uno))
        assertEquals(PyMcuBranch.UNKNOWN, verdict("pymcu/hal/pic14/pic16f877a_pwm.py", uno))
    }

    /** Nothing outside `pymcu/hal/` is the index's business. */
    fun testAModuleOutsideTheHalHasNoVerdict() {
        assertEquals(PyMcuBranch.UNKNOWN, verdict("pwmio.py", uno))
    }

    // ── where it must keep quiet ─────────────────────────────────────────────

    /**
     * A project with no target gets the behaviour there was before any of this.
     * Being wrong here would break every non-PyMCU Python project in the IDE.
     */
    fun testWithNoTargetNothingIsPreferred() {
        target(null)
        val file = myFixture.configureByText(
            "untargeted.py",
            "from pymcu.hal.avr.gpio import board_pin_na<caret>me\n"
        )
        val resolved = where(file.findReferenceAt(myFixture.caretOffset)?.resolve())
        assertTrue(
            "an untargeted project must not be steered to a chip, got $resolved",
            resolved.startsWith("pymcu/hal/avr/gpio/")
        )
    }

    /** A foreign architecture's own facade must not have an opinion about this build. */
    fun testAnArchitectureThisProjectDoesNotBuildIsStillDemotedAsAWhole() {
        target(uno)
        assertLandsIn(
            "pymcu/hal/avr/gpio/__init__.py",
            "from pymcu.hal.gpio import Pin\nx = Pi<caret>n\n"
        )
    }

    // ── two `pymcu` trees answering at once ─────────────────────────────────

    private val projectBase get() = File(project.basePath!!)

    /**
     * The venv and its `.pth` files are read through `java.io.File` and
     * `LocalFileSystem`, so they must be real files under `project.basePath` —
     * `addFileToProject` would put them under `temp:///root`, invisible to
     * every path the resolver walks.
     */
    private fun writeReal(path: String, text: String = "") {
        val file = File(projectBase, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun refreshRealFs() {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(projectBase)
            ?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        PyMcuVenv.clearCaches()
        // config() may already have been read and cached as absent; the real
        // VFS listener invalidates it on the pyproject.toml event.
        PyMcuProjectService.getInstance(project).invalidate()
    }

    /** Like [assertFileReferenceLandsIn] but compares on a path suffix — venv files live outside the fixture tree. */
    private fun assertFileReferenceEndsWith(filePath: String, needle: String, suffix: String) {
        val virtualFile = myFixture.findFileInTempDir(filePath)
        myFixture.openFileInEditor(virtualFile)
        val text = myFixture.editor.document.text
        val offset = text.indexOf(needle).let {
            check(it >= 0) { "$needle not found in $filePath" }
            it + needle.length / 2
        }
        val landed = myFixture.file.findReferenceAt(offset)?.resolve()
            ?.containingFile?.virtualFile?.path
        assertTrue(
            "resolve of $needle in $filePath must land on $suffix, got $landed",
            landed?.endsWith(suffix) == true
        )
    }

    /**
     * The cp-servo failure, reproduced: `pymcu.hal.pwm` resolves twice — the
     * fixture tree stands in for the stale copy a foreign SDK serves (its AVR
     * `PWM` predates `set_duty_u16`), and the project's own `.venv` holds the
     * editable checkout the build compiles. Same facades, same verdicts — and
     * `resolve()` used to keep the first, the SDK's, so `_PWM` came out typed
     * by a class without the member and every hop after it died.
     */
    fun testTheProjectsOwnStdlibWinsWhenTwoPymcuTreesAnswer() {
        target(uno)
        writeReal("pyproject.toml", "[tool.pymcu]\nboard = \"arduino_uno\"\nstdlib = [\"circuitpython\"]\n")
        writeReal("editable-src/pymcu/chips/__init__.py", "class _Chip:\n    name = \"\"\n    arch = \"\"\n__CHIP__ = _Chip()")
        writeReal("editable-src/pymcu/exceptions.py", "class CompileError(Exception): pass")
        writeReal("editable-src/pymcu/hal/__init__.py", "")
        writeReal("editable-src/pymcu/hal/pwm.py", """
            from pymcu.chips import __CHIP__
            from pymcu.exceptions import CompileError

            if __CHIP__.arch == "avr":
                from pymcu.hal.avr.pwm import PWM
            elif __CHIP__.name == "rp2040":
                from pymcu.hal.rp2040.pwm import PWM
            elif __CHIP__.name == "rp2350":
                from pymcu.hal.rp2350.pwm import PWM
            else:
                raise CompileError("PWM not supported on this architecture")
        """)
        writeReal("editable-src/pymcu/hal/avr/__init__.py", "")
        writeReal("editable-src/pymcu/hal/avr/pwm/__init__.py", """
            class PWM:
                def __init__(self, pin, duty=0, freq=0, duty_u16=0): pass
                def set_duty_u16(self, d): pass
                def set_freq(self, f): pass
                def frequency(self): pass
        """)
        for (arch in listOf("rp2040", "rp2350")) {
            writeReal("editable-src/pymcu/hal/$arch/__init__.py", "")
            writeReal("editable-src/pymcu/hal/$arch/pwm.py", "class PWM:\n    def set_duty_u16(self, d): pass")
        }
        writeReal(".venv/lib/python3.14/site-packages/_editable_impl_pymcu_stdlib.pth", "$projectBase/editable-src")
        refreshRealFs()

        val ownHal = "editable-src/pymcu/hal/avr/pwm/__init__.py"
        assertFileReferenceEndsWith("pwmio.py", "_PWM(", ownHal)
        assertFileReferenceEndsWith("pwmio.py", "set_duty_u16", ownHal)
        assertFileReferenceEndsWith("pwmio.py", "set_freq", ownHal)
    }
}
