# PyMCU JetBrains plugin — roadmap

Where the plugin stands against the VS Code extension and the current `pymcu` CLI, and what ships
next. Written 2026-08-16, against `pymcu` at `18415da1`.

## Guiding principle

**The CLI is the source of truth.** The board list, the language subset, the stub shapes and the
diagnostics all live in the driver. Anything the plugin reimplements in Kotlin drifts the first time
a backend ships. Every feature below should be phrased as "surface what `pymcu <x> --json` already
knows", and where that command does not exist yet, the roadmap entry belongs partly in the driver.

Two consequences worth stating:

- **A hardcoded list is a bug.** The 0.0.1 plugin knew four Arduino boards; the driver had grown
  ATtiny, RP2040/RP2350, PIC and RISC-V. That was invisible to the user as "my board is not
  supported".
- **A feature that needs new CLI surface is a two-repo change.** Those are marked ⇄ below.

---

## 0.1.0 — parity and correctness ✅ done

The plugin was rebuilt on the current CLI surface. See [CHANGELOG.md](CHANGELOG.md) for the full
list; the load-bearing changes:

- Real TOML reading and format-preserving writing; the canonical `target` key; `[tool.pymcu.flash]`,
  `[tool.pymcu.toolchain]`, `[tool.pymcu.config]`, `stdout` / `stdout_baud`.
- Porting assistant (`pymcu lint --json`) as editor annotations plus a findings list.
- Board catalog from `pymcu boards --json` in both the configure dialog and the new-project wizard.
- IDE stubs from `pymcu stubs` instead of a Kotlin reimplementation.
- Commands through the run/debug framework; no more unprompted `uv sync` on project open.
- Verified compatible with PyCharm 2024.3, 2025.2 and 2026.1.

---

## 0.2.0 — what a JetBrains user expects next

Ordered by (value to a user trying to ship firmware) ÷ (effort).

### 1. `pyproject.toml` inspection with quick fixes
The VS Code extension validates the config and offers a `chip` → `target` migration. The plugin
reads the same conditions already (`usesDeprecatedChipKey`, `hasConflictingTarget`, `hasNoTarget`)
and `TomlWriter.renameKey` performs the fix — what is missing is the `LocalInspectionTool` that
surfaces them on the file.

Needs a PSI to anchor on. `org.toml.lang` is bundled in PyCharm; depend on it optionally
(`<depends optional="true" config-file="pymcu-toml.xml">`) so the plugin still loads without it.

### 2. Library manager UI ⇄
`pymcu libraries` / `search` / `install` / `uninstall` back an index with *measured* per-chip
compatibility (`entry_verdict` refuses a library that cannot serve your target, with the reason).
Neither IDE surfaces any of it. A tool-window tab listing installed libraries, with search and
one-click install, is the clearest place where the JetBrains plugin can be better than the VS Code
one rather than merely equal.

⇄ Needs `--json` on `libraries`, `search` and `install` — today they print Rich tables.

### 3. Flash-size and memory feedback
`pymcu build` already computes the flash report (`_flash_report_lines`, `_parse_hex_flash_bytes`)
and knows each chip's capacity. Parsing it out of the console and showing "1 248 / 32 768 B (3.8 %)"
in the status bar after a build turns the number into something the user watches while working.

⇄ Cleaner with a machine-readable line from the driver (`[BUILD_SIZE] flash=1248 total=32768`)
rather than scraping the rendered table.

### 4. Build progress from the compiler's phase tokens
`pymcuc` emits `[PHASE_START]` / `[PHASE_END]` / `[BUILD_INFO]` tokens that the driver renders as a
progress bar. In a run console those are noise. Consuming them into an IDE progress indicator —
"Building atmega328p @ 16 MHz · lowering IR" — matches what the VS Code driver output already shows.

### 5. Toolchain and backend management
`pymcu toolchain list/install/update/clean` and `pymcu backend list/install/check` are pure
install-state UI: a settings page with an install status per toolchain, a "reclaim tool cache"
button showing what `toolchain clean --dry-run` would remove, and license status per backend.
Nothing to invent, just surface it.

### 6. Tool window availability
`ToolWindowFactory.isApplicableAsync` is evaluated once at project open, so adding `[tool.pymcu]` to
an already-open project leaves the window hidden until reopen. Register it dynamically from
`PyMcuConfigListener` instead.

---

## 0.3.0 — the things only an IDE can do

### 7. Debugging ⇄ — gated behind `pymcu.debugger.enabled`
`pymcu build --debug` emits debug symbols and a line map "for the emulator debugger". If that
emulator can speak a debug protocol, an `XDebuggerFramework` integration — breakpoints in Python
source, stepping over generated assembly, watching registers and MMIO — is the single largest
differentiator available, and the reason people pay for a JetBrains IDE.

⇄ Needs the debug transport specified and stable.

**No implementation exists yet.** The registry key is registered ahead of the feature so the first
slice merges to `main` behind a flag — reviewed and CI-covered — instead of living in a long branch.
Every dev build therefore ships with debugging invisible and inert. The flag is removed, not
defaulted on, when stepping works end to end on at least one AVR and one ARM target.

### 8. Register and pin awareness
`pymcu.chips.<chip>` defines every register and bit for the selected target, and the compat layers
carry per-board pin constants. Completion on `board.` that shows the physical pin, a gutter icon on
`DDRB[DDB5] = 1` naming the port, and a hover that renders a register's bit layout would all read
directly from what is installed. No new CLI surface — it is stdlib introspection.

### 9. Profiling and benchmarking
`pymcu profile` and `pymcu bench` exist (hidden commands). A results view comparing builds over
time — flash and SRAM per commit — fits a tool window well.

### 10. Emulator run target
`avr8sharp` / `rp2040js` sit in the same ecosystem. A "Run in emulator" executor alongside Flash
would close the loop for users without hardware on the desk.

---

## Cross-cutting, unscheduled

| Item | Note |
|---|---|
| **i18n** | The unused `PyMcuBundle` was removed rather than left to drift. Reintroduce it properly if a non-English audience appears. |
| **`runIde` UI tests** | The unit tests cover parsing and TOML writing. Nothing covers the dialog, the annotator or the actions; `IdeaTestFixture` would. |
| **Windows serial ports** | `SerialPorts.list()` returns empty on Windows — the field stays free-text. Enumerating `COM*` needs a registry read or a native call. |
| **Marketplace assets** | Screenshots and an animated porting-assistant demo. The listing text is written; the images are not. |
| **Signing** | `release.yml` expects `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` and `PUBLISH_TOKEN` as repository secrets. They do not exist yet. |

---

## Release checklist

Before tagging `v0.1.0`:

- [ ] Generate a signing certificate and add the four secrets to the repository.
- [ ] Create the plugin listing on the JetBrains Marketplace and obtain the publish token.
- [ ] Run through the manual smoke path below on a machine with hardware attached.
- [ ] Screenshots for the listing.
- [ ] Decide the repository home — `PyMCU/jetbrains-pymcu`, matching `PyMCU/vscode-pymcu`.

Manual smoke path (nothing here is covered by automated tests):

1. New Project → PyMCU → Arduino Uno → CircuitPython. Project scaffolds, sync runs, `import board`
   resolves without a red squiggle.
2. Paste MicroPython code using a list comprehension. The porting assistant squiggles it with a
   suggestion; **Lint Project** lists it and double-click navigates.
3. Configure Project… → switch to Raspberry Pi Pico. `pyproject.toml` keeps its comments, the status
   bar changes to `raspberry_pi_pico (ARM)`, and a sync regenerates the board module.
4. Build with an intentional type error. The console link opens the right line.
5. Flash with two boards connected. The port picker appears and lists both.
6. Open a project whose `[tool.pymcu]` uses the deprecated `chip` key. It is still recognised.
