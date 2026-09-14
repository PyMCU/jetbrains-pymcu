package dev.begeistert.pymcu

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.begeistert.pymcu.inspection.PyMcuDeadBranchInspection
import dev.begeistert.pymcu.resolver.ChipIdentity
import dev.begeistert.pymcu.resolver.PyMcuChipInfoService

/**
 * Which `__CHIP__` branches the editor greys out.
 *
 * Reading a facade means reading six implementations and working out by hand
 * which one this project gets. The conditions are in the file and the target is
 * known, so the answer is shown instead: the branches for other silicon are
 * dimmed like dead code and what is left is what reaches the firmware.
 *
 * The rules that matter are the ones about keeping quiet. A project with no
 * target, a conditional that is not about the target, and a condition that
 * cannot be read must all dim nothing at all, because a wrongly greyed branch
 * is a reader being told a lie about their own build.
 */
class DeadBranchInspectionTest : BasePlatformTestCase() {

    private val uno = ChipIdentity("atmega328p", "avr", board = "arduino_uno")

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(PyMcuDeadBranchInspection())
    }

    override fun tearDown() {
        try {
            PyMcuChipInfoService.getInstance(project).overrideIdentity(null)
        } finally {
            super.tearDown()
        }
    }

    private var counter = 0

    /** The source text of every branch the inspection dimmed, in file order. */
    private fun dimmed(source: String): List<String> {
        myFixture.configureByText("facade${counter++}.py", source.trimIndent())
        return myFixture.doHighlighting()
            .filter { it.description == "Not compiled for atmega328p" }
            .sortedBy { it.startOffset }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset).trim() }
    }

    fun testEveryBranchButTheLiveOneIsDimmed() {
        PyMcuChipInfoService.getInstance(project).overrideIdentity(uno)
        assertEquals(
            listOf("pic14()", "rp2040()", "fail()"),
            dimmed(
                """
                if __CHIP__.arch == "avr":
                    avr()
                elif __CHIP__.arch == "pic14":
                    pic14()
                elif __CHIP__.name == "rp2040":
                    rp2040()
                else:
                    fail()
                """
            )
        )
    }

    fun testTheElseIsTheOneLeftStandingWhenNothingMatches() {
        PyMcuChipInfoService.getInstance(project).overrideIdentity(uno)
        assertEquals(
            listOf("attiny()", "leonardo()"),
            dimmed(
                """
                if __CHIP__.name == "attiny85":
                    attiny()
                elif __CHIP__.name == "atmega32u4":
                    leonardo()
                else:
                    uno()
                """
            )
        )
    }

    fun testTheCasesOfAMatchAreDimmedToo() {
        PyMcuChipInfoService.getInstance(project).overrideIdentity(uno)
        assertEquals(
            listOf("pic()", "riscv()"),
            dimmed(
                """
                match __CHIP__.arch:
                    case "avr":
                        avr()
                    case "pic18":
                        pic()
                    case "riscv":
                        riscv()
                """
            )
        )
    }

    // ── keeping quiet ────────────────────────────────────────────────────────

    fun testAProjectWithNoTargetIsToldNothing() {
        PyMcuChipInfoService.getInstance(project).overrideIdentity(null)
        assertEquals(
            emptyList<String>(),
            dimmed(
                """
                if __CHIP__.arch == "avr":
                    avr()
                else:
                    other()
                """
            )
        )
    }

    fun testAnOrdinaryConditionalIsLeftAlone() {
        PyMcuChipInfoService.getInstance(project).overrideIdentity(uno)
        assertEquals(
            emptyList<String>(),
            dimmed(
                """
                if DEBUG:
                    noisy()
                else:
                    quiet()
                """
            )
        )
    }

    /** One unreadable condition and the branches after it are nobody's business. */
    fun testAnUnreadableConditionDimsNothingAfterIt() {
        PyMcuChipInfoService.getInstance(project).overrideIdentity(uno)
        assertEquals(
            listOf("attiny()"),
            dimmed(
                """
                if __CHIP__.name == "attiny85":
                    attiny()
                elif SOME_BUILD_FLAG:
                    flagged()
                elif __CHIP__.arch == "avr":
                    avr()
                """
            )
        )
    }
}
