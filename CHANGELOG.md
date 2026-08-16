# Changelog

All notable changes to the PyMCU PyCharm plugin are documented here.

## [Unreleased]

### Added
- **The New Project wizard was redesigned.** Board picker with the catalog's own
  group headings and speed search instead of a flat 40-entry list; a line under
  the chip saying what it actually is (`AVR · avr toolchain · flashed with
  avrdude`); the Python API promoted from the fourth combo to a titled radio
  group whose options are labelled with the import each one produces; an
  editable, per-architecture clock; and a footer naming the files that will be
  created. Built with the Kotlin UI DSL rather than `FormBuilder`.
- **Go To Declaration on the HAL lands on the architecture the project builds
  for.** `pymcu/hal/gpio.py` dispatches on `__CHIP__` across six branches, and
  nine facades do the same, so `Pin` resolved to every architecture at once. A
  `PyResolveResultRater` now prefers the live one, reading the architecture from
  the chip's own `device_info(...)` line rather than guessing it from the name.
  Conservative by design: anything it does not recognise rates neutral, and it
  does not filter within the PIC family, where the facades share code unevenly.

### Fixed
- **The wizard's clock defaults disagreed with the driver**, and because it
  always passes `--freq`, the wrong value went straight into the project. A bare
  ATmega was offered 16 MHz where the driver says 8; the Digispark and Trinket 8
  where they ship a 16.5 MHz crystal; PIC 16 where it is 4; RISC-V 16 where it
  is 48 or 144.
- The configuration dialog offered a programmer called `wlink`. The driver
  resolves `wch-link`, and anything else is refused at flash time.
- The configuration dialog and the wizard both hid boards the catalog lists
  without a group — `pico`, `rp2040`, `pico2`, `rp2350`, and any future board
  the driver adds before grouping it.

### Fixed (earlier)
- **The New Project wizard now scaffolds with `pymcu new` instead of a template
  of its own.** The hand-written one omitted `[tool.pymcu.toolchain]` and
  `[tool.pymcu.flash]`, so generated projects had no programmer to flash with;
  it omitted the Makefile; and it pinned dependencies to a generic floor rather
  than the versions actually installed. All of that is `new.py`'s job and it
  already does it. The built-in template survives only as the fallback for
  creating a project before the CLI is installed, and says so when it is used.
- **Build could fail with "Cannot run program pymcu" on a machine where pymcu
  works.** An IDE launched from the Dock has no login shell's PATH, and the
  documented install puts the binary in `~/.local/bin`. The lookup now covers
  the project virtualenv, PATH, `~/.local/bin` and the Homebrew prefixes, and a
  configuration that still cannot find it says what to do instead of failing at
  the process layer.
- The run-configuration icon is the project logo. It was the monochrome
  tool-window glyph, which is the wrong register for a list of product marks.

### Changed
- **Resolution lands on source, never on a generated stub.** Go to declaration on `pin.value(1)`
  now opens the implementation — the sentinel-255 trick, the `@inline`, the register write —
  instead of `def value(self, x: int = 255) -> int: ...`. Reading down to the hardware is most of
  why this compiler is interesting, and the stub tree was hiding it for the sake of marginally
  tidier type hints. Sync no longer generates `.pyi` files and the stub tree is no longer indexed;
  annotations still type correctly because they reference `pymcu.types`, which the interpreter
  resolves by itself.
- "Regenerate IDE Stubs" is now **Export Type Stubs…**, for type checkers running outside the IDE.
  The editor does not read its output.

### Fixed
- The New Project wizard collected a package manager and nothing read it, so
  choosing pip still ran `uv sync`. The choice is now passed to the sync, and
  every later sync reads the project instead of the application-wide setting: a
  `poetry.lock` makes it a Poetry project whatever that setting says.
- `import machine` and the other bare compat imports did not resolve.
  `AdditionalLibraryRootsProvider` indexes directories but does not make them
  import roots — Python resolution walks the interpreter's paths and the
  `Pythonid.importResolver` extension point, so the plugin now implements that.
  The search order mirrors the compiler's include order, so what the editor
  resolves to is the file the build compiles.
- The generated `pyproject.toml` came out indented twelve spaces with the
  interpolated lines flush left: `trimIndent()` runs after interpolation, and a
  multi-line `$value` at column 0 drags the common indent to zero. The templates
  build lines instead, which cannot fail that way, and are covered by tests.
- Scaffolded dependencies were bare names, which pip refuses for alpha packages
  because it only considers pre-releases when the specifier names one. They now
  carry the driver's prerelease floor, and `pymcu-compiler` carries the backend
  extra for the chip family (`[avr]`, `[arm]`) — without it a fresh install
  arrives with no backend to build with.
- The New Project wizard showed the monochrome tool-window glyph as its logo,
  scaled past the size it was drawn for. It now shows the project's own mark;
  the mono snake stays where the platform wants one colour.

### Added
- **Library manager** in the tool window: browse the index, filter by target, install and remove.
  The compatibility verdict is the driver's measured per-chip build result, with the reason shown
  in the row — incompatible libraries stay visible and explain themselves rather than being hidden.
- **`pyproject.toml` inspection** with quick fixes: the deprecated `chip` key (rename to `target`),
  a `board`/`target` conflict (remove either side), a missing target and an unknown board. Fixes go
  through the format-preserving writer, so comments and layout survive.
- **Get Started checklist** in the tool window — CLI, target, dependencies, generated files, first
  build, flash — each computed from what is on disk, each with the action that advances it. It is
  the plugin's answer to the VS Code walkthrough, and doubles as a diagnostic for unresolved imports.
- The tool window now has three tabs: Get Started, Libraries and Porting.
- `./gradlew runIde -PrunIdeProject=<path>` opens a project instead of the welcome screen, so the
  panels can actually be smoke-tested.

### Changed
- The plugin icon is the project's own snake-on-a-chip logo; the tool window icon is a monochrome
  snake derived from it, as the platform requires at that size.

### Requires
- `pymcu libraries --json` and `pymcu search --json` for the library manager. Older CLIs degrade —
  the panel reports the index is unavailable and every other feature keeps working.

## [0.1.0]

First public release. The plugin was rebuilt around the current `pymcu` CLI surface.

### Added
- Porting assistant: `pymcu lint` findings as editor annotations (`onSave` / `onType` / `off`), a
  navigable findings list in the PyMCU tool window, and a **Lint Project** action.
- Project configuration dialog: board, clock, compat stdlib, programmer, serial port and AVR fuses.
  The board list is loaded from `pymcu boards --json`, so RP2040 / RP2350, PIC, RISC-V and the
  ATtiny family are all selectable, not just four Arduino boards.
- Format-preserving `[tool.pymcu]` writes — comments, key order and blank lines survive an edit.
- Serial-port picker when flashing a project that has not pinned `[tool.pymcu.flash] port`.
- Run configuration options for `--verbose`, `--explain` and `--debug`, and configurations for
  `sync` and `stubs` alongside `build` / `flash` / `clean`.
- **Regenerate IDE Stubs** action, and stub generation on sync.
- Tools | PyMCU menu, a tool-window toolbar and a ⌃⇧B shortcut for Build.
- Plugin and tool-window icons; Marketplace description and change notes.

### Changed
- `pyproject.toml` is read with a real TOML reader. The previous regex reader could not handle
  `frequency = 16_000_000`, trailing comments, nested tables or multi-line arrays, and silently
  reported such projects as having no target.
- The canonical `target` key is read. Projects created by a current `pymcu new` in advanced mode
  were previously not recognised at all.
- `[tool.pymcu.flash]`, `[tool.pymcu.toolchain]`, `[tool.pymcu.config]`, `stdout` / `stdout_baud`
  and the pre-0.15 `[tool.pymcu.programmer]` fallback are all read.
- Config parsing is cached per project and invalidated on file change; it used to re-read and
  re-parse the file on every resolve, status bar hover and context menu.
- Commands run through the run/debug framework: stop button, exit code and clickable diagnostics,
  instead of text appended to a read-only text area.
- Opening a project no longer runs `uv sync` unprompted. Missing generated files produce an
  offer with a "Don't ask again" option.
- Project open uses `ProjectActivity` rather than a deprecated application-level
  `ProjectManagerListener`.
- The New Project wizard scaffolds the current schema (`board` / `target`, `[tool.pymcu.flash]`),
  loads its board list from the CLI, and defaults the clock to the chip family.
- Status bar widget shows the architecture and opens the configuration dialog on click.
- Built against the IntelliJ Platform Gradle Plugin 2.x with Gradle 9 and Kotlin 2.1.

### Removed
- The Kotlin `.pyi` stub generator (~420 lines) and its installer, superseded by `pymcu stubs`,
  which the driver ships specifically for IDE plugins to consume.
- Writing generated `.pyi` files into `site-packages`. Stubs now live in `dist/_generated/stubs`,
  outside the compiler's include path.

### Fixed
- The tool window referenced `/icons/pymcu.svg`, which did not exist in the plugin.
- Status bar and tool window subscribed to the message bus without a parent disposable, leaking a
  listener per project open.
- The run configuration producer constructed a second, unregistered configuration type, so the
  configurations it produced did not round-trip.
