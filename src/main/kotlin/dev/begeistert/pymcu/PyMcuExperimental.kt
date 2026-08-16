package dev.begeistert.pymcu

import com.intellij.openapi.util.registry.Registry

/**
 * Gates for work that is merged but not ready to be shipped on.
 *
 * WHY registry keys: they are off for everyone by default, they need no settings
 * UI, and a tester can flip one from **Help | Find Action | Registry…** without a
 * custom build. That lets debugger work land on `main` — reviewed, compiled and
 * covered by CI — while staying invisible in a release.
 *
 * A flag is a staging area, not a parking space. Each one below names the
 * condition that removes it. When a feature ships, the flag and its
 * `<registryKey>` in plugin.xml go with it.
 */
object PyMcuExperimental {

    /**
     * On-chip / emulator debugging: breakpoints in Python source, stepping,
     * register and MMIO watches, driven by `pymcu build --debug` output.
     *
     * **Status: no implementation exists yet.** This flag is registered ahead of
     * the feature so the first slice can merge behind it rather than in a
     * long-lived branch. Turning it on today changes nothing.
     *
     * Ships when: the emulator's debug transport is specified and stable, and
     * stepping works end to end on at least one AVR and one ARM target.
     */
    val isDebuggerEnabled: Boolean
        get() = Registry.`is`(DEBUGGER_KEY, false)

    const val DEBUGGER_KEY: String = "pymcu.debugger.enabled"
}
