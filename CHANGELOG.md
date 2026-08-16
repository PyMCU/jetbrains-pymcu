# Changelog

All notable changes to the PyMCU PyCharm plugin are documented here.

## [Unreleased]

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
