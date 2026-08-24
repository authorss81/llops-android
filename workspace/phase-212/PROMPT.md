# Phase 212: JVM Test Hardening — Cover Untested Deletion/Install/Shape-Snap Logic [TESTS]

**Goal:** Thirteen service classes have ZERO test references (cross-checked against all test sources). Several can silently destroy user data or corrupt committed ink if they regress.

Priority order (by blast radius):

1. **`OrphanImportCleanupPolicy` DELETES user files** based on tracked-vs-committed logic (call sites `HomeScreen.kt:353`, `:523`). Tests: full tracked/uncommitted matrix — never deletes a committed import; sweep idempotence; malformed dir tolerated.
2. **`ShapeRecognitionHelper` runs on EVERY freehand commit** (`AnnotationCanvas.kt:1373-1377`) and may REPLACE user points. Tests: RECT/ARROW/LINE detectors accept true geometry, REJECT handwriting-like noise (the original phase-03 requirement), tolerance boundaries, wet/style-preserving tool exclusions honored at call site (source pin).
3. **Six plugin persistence stores** wired at `NoteflowViewModel.kt:228-261`: `SettingsPluginEnableStore`, `SettingsPluginEntryStore`, `SettingsPluginInstallStore`, `SettingsPluginInvocationJournalStore`, `SettingsPluginStore`, `SettingsPluginUpdateStore`. Tests against a fake-prefs impl: default-off enable state, install/delete lifecycle round-trip (mirrors `PluginStoreLifecycleTest` semantics but at store level), journal append/cap, update-state transitions.
4. **`DownloadablePluginInstaller` / `DownloadablePluginUpdater`**: happy path + failure atomicity — no partial state left after a failed install/update step (fault-injected fake transport).
5. **`HtmlToMarkdownConverter`**: nested lists, links, images, entities, tables passthrough; adversarial input (deep nesting → bounded recursion).
6. **`WetBrushEngine` / `WetCanvasEngine` parameter-boundary tests** (tier selection math, fallback thresholds) — render output itself stays visual/manual.

## Constraints
Pure-JVM only (no Robolectric needed for these classes — they are already dependency-light by design). Do NOT modify production code EXCEPT where writing a test exposes an actual bug: fix it, pin it, and list every such fix in REPORT.md with file:line.

## DoD
`gradle testDebugUnitTest` green with the new suites (target ≥40 new test methods total across the six groups); each new test file named `<Service>Test` next to existing conventions; `workspace/phase-212/REPORT.md` table: service | was-covered | now-covered | bugs found & fixed (if any).
