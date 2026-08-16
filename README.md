# PyMCU for PyCharm

Official PyCharm / IntelliJ support for [PyMCU](https://github.com/PyMCU/PyMCU) — a compiler that
turns a statically-typed subset of Python into bare-metal firmware for **AVR, RP2040 / RP2350, PIC
and RISC-V** microcontrollers. No runtime, no interpreter, no VM.

The plugin is a thin layer over the `pymcu` CLI: everything it knows about your boards, your
sources and the language subset comes from the driver, so the two never drift.

## Features

- **Build, flash and clean** as run configurations, with `pymcuc` diagnostics turned into clickable
  `file:line:col` links in the console. `--verbose`, `--explain` and `--debug` are checkboxes.
- **Porting assistant** — `pymcu lint` findings appear as editor annotations as you type or save,
  each with a concrete rewrite, plus a navigable list in the PyMCU tool window. Paste MicroPython
  code and follow the squiggles until it compiles.
- **Resolution and completion** for `pymcu.*`, the MicroPython / CircuitPython compat layers
  (`machine`, `board`, `digitalio`, …) and the generated `board` module for your target. Typed
  signatures come from `pymcu stubs`, so they track the installed stdlib.
- **Project configuration dialog** — board, clock, compat stdlib, programmer, serial port and AVR
  fuses, written back to `[tool.pymcu]` without disturbing your comments or formatting. The board
  list comes from `pymcu boards --json`, so it matches whatever backends you have installed.
- **Status bar target indicator** that opens that dialog, a **serial-port picker** on flash, and a
  **New Project wizard** that scaffolds a working blink for the board you pick.

## Requirements

- PyCharm (Community or Professional) 2024.3 or newer, or IntelliJ IDEA with the Python plugin.
- The PyMCU CLI: `pipx install pymcu-compiler`, or add `pymcu-compiler` to your project with `uv`.
  The plugin looks for `.venv/bin/pymcu` first, then `PATH`.

## Actions

All under **Tools | PyMCU**, and on the PyMCU tool window's toolbar.

| Action | Runs |
|---|---|
| Build | `pymcu build` (⌃⇧B / Ctrl+Shift+B) |
| Flash | `pymcu flash`, asking which port when none is pinned |
| Clean | `pymcu clean` |
| Build and Explain | `pymcu build --explain` — reports the setup the compiler injected implicitly |
| Lint Project (Porting Assistant) | `pymcu lint` over the configured sources |
| Sync Project | dependency install, then `pymcu sync` and `pymcu stubs` |
| Regenerate IDE Stubs | `pymcu stubs` alone |
| Configure Project… | the board / flash configuration dialog |

## Settings

**Settings | Tools | PyMCU**

| Setting | Default | Meaning |
|---|---|---|
| PyMCU executable | `pymcu` | Empty or `pymcu` resolves `.venv/bin/pymcu`, then `PATH` |
| Package manager | `uv` | Used by Sync: `uv sync`, `pip install -e .`, `poetry install`, `pipenv install` |
| Run porting assistant | `onSave` | `onSave`, `onType` or `off` |
| Compat flavor | `auto` | `auto` derives it from the project's `stdlib` setting |
| Report only hard blockers | off | Passes `--errors-only` to `pymcu lint` |
| Offer to sync on open | on | Offers a sync when generated files are missing; never syncs unasked |

## Getting started

1. **New Project | PyMCU**, or open a folder whose `pyproject.toml` has a `[tool.pymcu]` section.
2. Pick your board in **Tools | PyMCU | Configure Project…** — it shows in the status bar.
3. Write Python, or paste MicroPython / CircuitPython code and follow the porting assistant.
4. **Build**, then **Flash**.

## Installing a build

Until the plugin is on the Marketplace, install it from a `.zip`:

**PyCharm → Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the zip → restart.

- **Latest `main`:** the [`dev` prerelease](https://github.com/PyMCU/jetbrains-pymcu/releases/tag/dev),
  rebuilt on every push. Direct link:
  `https://github.com/PyMCU/jetbrains-pymcu/releases/download/dev/jetbrains-pymcu-dev.zip`
- **Built yourself:** `./gradlew buildPlugin` → `build/distributions/jetbrains-pymcu-<version>.zip`

The target machine needs PyCharm 2024.3+ and the PyMCU CLI. It does **not** need a JDK or Gradle.

## Experimental features

Work that is merged but not ready is off behind a registry key. To try one:
**Help → Find Action → Registry…**, then search for the key.

| Key | Default | What it gates |
|---|---|---|
| `pymcu.debugger.enabled` | off | Emulator debugging. **Not implemented yet** — turning it on currently changes nothing. |

## Building from source

```bash
./gradlew buildPlugin      # → build/distributions/jetbrains-pymcu-<version>.zip
./gradlew test             # unit tests
./gradlew runIde           # a sandboxed PyCharm with the plugin loaded
./gradlew verifyPlugin     # JetBrains plugin verifier, as run in CI
```

Requires JDK 21. The Gradle daemon's JDK is pinned in `gradle.properties`; change
`org.gradle.java.home` if yours lives elsewhere.

## Relationship to the VS Code extension

The two extensions target the same workflow and the same CLI surface. Where they differ, it is
because the host does: PyCharm gets run configurations and a Swing dialog where VS Code gets tasks
and a webview; VS Code writes `python.analysis.extraPaths` for Pylance where PyCharm gets an
`AdditionalLibraryRootsProvider`. Behaviour, settings names and defaults are kept in step.

## License

MIT — see [LICENSE](LICENSE).
