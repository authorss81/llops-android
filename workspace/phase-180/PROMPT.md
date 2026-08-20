# Phase 180: Screenshot render suite — Paparazzi for every screen × state × theme [NOT STARTED]

You are working on **InkFlow/Noteflow**. This phase adds a JVM screenshot-render
suite (Paparazzi — NO emulator needed) that produces PNGs for the key screens,
states, and themes, and SAVES them so a human can review them visually.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-178 step N: <desc>" && git push`
after EVERY step. Never sit on uncommitted work.

## Step 1 - Wire Paparazzi
- Add the Paparazzi plugin to the app module (`app/build.gradle.kts`:
  `id("app.cash.paparazzi")`, version per `gradle/libs.versions.toml`) — do NOT add
  to the base APK, it is test-only.
- Configure it to render at a representative phone size (e.g. 360×800dp, density
  xxhdpi) and verify `./gradlew :app:recordPaparazziDebug` (or `gradle recordPaparazziDebug`)
  runs and writes PNGs to `app/build/outputs/paparazzi/`.
- COMMIT this step (build files + a trivial smoke test).

## Step 2 - Screens × states × themes matrix
Create Paparazzi tests (one test class per screen) covering at least:
- **Screens**: HomeScreen (list view), GalleryView, CalendarView, Kanban,
  KnowledgeGraphScreen, MarkdownPreviewScreen, EditorScreen (canvas),
  TagExplorerView, PluginStoreDialog, PluginSettingsDialog, WebDavSyncDialog,
  Onboarding/FirstRun, CorruptionRecoveryScreen (static only), empty-vault state.
- **States** (per screen): empty / populated, loading vs loaded where it exists,
  selected/pinned items, plugin-off vs on.
- **Themes**: light AND dark (parameterize — one test, N renders).
- Each render goes to its own PNG under a screen/state/theme subfolder.

## Step 3 - SAVE the screenshots
- Verify the PNGs land in `app/build/outputs/paparazzi/<test>/<class>/`.
- Do NOT commit the raw output folder (large, churns every run). Instead:
  - Ensure a CI/phase artifact upload exists OR document the exact path so the
    workflow's upload-artifact step can pick them up (do NOT edit workflows
    yourself — document the path + a ready-to-paste step in the REPORT).
  - Commit a SMALL curated set (~5-8 representative PNGs: Home light/dark,
    Gallery, Editor, empty state) under `visual-qa/screenshots/` so there is a
    permanent reviewed baseline.

## Definition of done
- `gradle recordPaparazziDebug` green; PNGs produced for every screen × state × theme.
- `workspace/phase-178/REPORT.md`: the full PNG inventory (path × screen × state ×
  theme), total count, and the exact upload-artifact snippet for the workflow.
- Curated baseline committed to `visual-qa/screenshots/`.
- No base-APK size change (Paparazzi is test-only), no `.github/workflows/` edits.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. Do NOT add runtime deps to the base app.
- Paparazzi renders with a fake device — no real runtime; any screen that requires
  a real device (camera, audio playback UI) gets a static-state render only.
- Keep tests fast (2-core runner): keep total renders ≤ ~90.