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
  (`machine`, `board`, `digitalio`, …) and the generated `board` module for your target —
  **always to the source**, never to a stub. Ctrl-click `pin.value(1)` and you land on the code
  that toggles the register, not on `def value(...) -> int: ...`.
- **Project configuration dialog** — board, clock, compat stdlib, programmer, serial port and AVR
  fuses, written back to `[tool.pymcu]` without disturbing your comments or formatting. The board
  list comes from `pymcu boards --json`, so it matches whatever backends you have installed.
- **Library manager** — browse, install and remove PyMCU libraries in the tool window. The index
  records whether each library actually *built* for each chip, so an incompatible one says why
  instead of failing at build time.
- **`pyproject.toml` inspection** — the deprecated `chip` key, a `board`/`target` conflict, a
  missing target and an unknown board are flagged in the editor, each with the fix. Edits preserve
  your comments and formatting.
- **Get Started checklist** — the path from empty project to flashed board, with every step
  computed from what is actually on disk. It doubles as the place to look when an import will not
  resolve.
- **Status bar target indicator** that opens that dialog, a **serial-port picker** on flash, and a
  **New Project wizard** that scaffolds through `pymcu new` — the same project the CLI would
  create, toolchain and programmer included, so it builds and flashes without further edits.

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
| Export Type Stubs… | `pymcu stubs`, for type checkers outside the IDE |
| Configure Project… | the board / flash configuration dialog |

The tool window has three tabs: **Get Started** (setup checklist), **Libraries** (install and
remove) and **Porting** (assistant findings).

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

## Requirements on the CLI

Some features need a `pymcu` new enough to answer in JSON. Older CLIs degrade rather than break —
the affected panel says so and the rest keeps working.

| Feature | Needs |
|---|---|
| Board catalog | `pymcu boards --json` |
| Porting assistant | `pymcu lint --json` |
| Library manager | `pymcu libraries --json`, `pymcu search --json` |

## Reading the stdlib

PyMCU's claim is that the abstraction is free — `pin.value(1)` compiles to the same `SBI` a C
programmer would write by hand. That claim is only checkable if you can read the code, so the
plugin never resolves to a generated stub:

- **Go to declaration** on anything from `machine`, `digitalio` or `board` opens the real
  implementation, comments and `@inline` decorators included.
- The sync does **not** generate `.pyi` files. An earlier version did, and it made the stdlib a
  black box for a little tidiness in the type hints — a bad trade for this project.
- Types are not lost by it: annotations reference `pymcu.types`, an installed package the
  interpreter resolves on its own, so `uint8` reads as `uint8` rather than as a stubbed `int`.
- **Build and Explain** (`pymcu build --explain`) is the other half: it reports the setup the
  compiler injected implicitly — clock init, stdout UART, ISR vectors.

**Export Type Stubs…** still exists for mypy or pyright running outside the IDE. The editor
ignores what it writes.

## Relationship to the VS Code extension

The two extensions target the same workflow and the same CLI surface. Where they differ, it is
because the host does: PyCharm gets run configurations and a Swing dialog where VS Code gets tasks
and a webview; VS Code writes `python.analysis.extraPaths` for Pylance where PyCharm gets an
`AdditionalLibraryRootsProvider`. Behaviour, settings names and defaults are kept in step.

## License

MIT — see [LICENSE](LICENSE).
