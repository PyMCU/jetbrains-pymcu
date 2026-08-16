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

Since then, the three remaining parity gaps — config validation, onboarding and the library
manager — have closed; see 0.2.0 below.

---

## 0.2.0 — what a JetBrains user expects next

### Done since 0.1.0

- **`pyproject.toml` inspection** — the deprecated `chip` key, a `board`/`target` conflict, a
  missing target and an unknown board, each with a quick fix. Implemented as a `LocalInspectionTool`
  over raw text rather than TOML PSI: `checkFile` runs for any file type, so it behaves the same
  whether or not `org.toml.lang` is present, and an optional dependency for three text ranges buys
  nothing.
- **Library manager** — index browsing, target filtering, install and remove, in a tool window tab.
  ⇄ Needed `--json` on `libraries` and `search`; that landed in the driver alongside it.
- **Guided onboarding** — a Get Started tab whose steps are computed from disk rather than narrated.
  A tour tells the same story to someone who has already synced and someone who has not installed
  the CLI; this does not, and it doubles as the answer to "why does `import board` not resolve".

### Still open

- **Flash-size feedback.** `pymcu build` already computes the report and knows each chip's capacity.
  Surfacing "1 248 / 32 768 B (3.8 %)" in the status bar turns the number into something you watch
  while working. ⇄ Cleaner with a machine-readable `[BUILD_SIZE]` line than by scraping the table.
- **Build progress from the compiler's phase tokens.** `pymcuc` emits `[PHASE_START]` /
  `[PHASE_END]` / `[BUILD_INFO]`, which are noise in a run console and a progress bar in an IDE.
- **Toolchain and backend management.** `pymcu toolchain list/install/update/clean` and
  `pymcu backend list/install/check` are pure install-state UI, including what `toolchain clean
  --dry-run` would reclaim.
- **Tool window availability.** `isApplicableAsync` is evaluated once at project open, so adding
  `[tool.pymcu]` to an open project leaves the window hidden until reopen. Register it dynamically
  from `PyMcuConfigListener` instead.

---

## 0.3.0 — the things only an IDE can do

### Debugging ⇄ — gated behind `pymcu.debugger.enabled`
`pymcu build --debug` emits debug symbols and a line map "for the emulator debugger". If that
emulator can speak a debug protocol, an `XDebuggerFramework` integration — breakpoints in Python
source, stepping over generated assembly, watching registers and MMIO — is the single largest
differentiator available, and the reason people pay for a JetBrains IDE.

⇄ Needs the debug transport specified and stable.

**No implementation exists yet.** The registry key is registered ahead of the feature so the first
slice merges to `main` behind a flag — reviewed and CI-covered — instead of living in a long branch.
Every dev build therefore ships with debugging invisible and inert. The flag is removed, not
defaulted on, when stepping works end to end on at least one AVR and one ARM target.

### Register and pin awareness
`pymcu.chips.<chip>` defines every register and bit for the selected target, and the compat layers
carry per-board pin constants. Completion on `board.` that shows the physical pin, a gutter icon on
`DDRB[DDB5] = 1` naming the port, and a hover that renders a register's bit layout would all read
directly from what is installed. No new CLI surface — it is stdlib introspection.

### Profiling and benchmarking
`pymcu profile` and `pymcu bench` exist (hidden commands). A results view comparing builds over
time — flash and SRAM per commit — fits a tool window well.

### Emulator run target
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
- [ ] Ship the driver's `libraries --json` / `search --json`, which the library manager needs.
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
6. Open a project whose `[tool.pymcu]` uses the deprecated `chip` key. It is still recognised, the
   inspection flags it, and the quick fix migrates it without touching the surrounding comments.
7. Libraries tab: install one, watch the row flip to Installed; pick one the index says will not fit
   this chip and confirm the reason is shown before you can install it.
8. Get Started tab on a freshly cloned project: every step reflects reality, and each action moves
   the one below it to green.
