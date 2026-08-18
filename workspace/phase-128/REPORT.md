# Phase 128 — Normal text style everywhere (no exaggerated typography)

Status: **DONE** (2026-08-17)

## Goal

Audit `Text(...)` usages across `ui/` (screens, components, dialogs, `theme/`)
for exaggerated/non-standard typography — oversized display/headline styles on
UI chrome, all-caps labels, extreme weights, inconsistent near-siblings — and
normalize them to the app's standard `MaterialTheme.typography.*` scale while
preserving hierarchy. No content-wording changes, no canvas ink UI touched,
no DB/schema/workflow/dependency changes.

## Audit method

- Read `theme/Type.kt` + `theme/TypeScale.kt` first: the app's type scale is a
  standard M3 ladder (display 57/45/36 · headline 32/28/24 · title 22/16/14 ·
  body 16/14/12 · label 14/12/11). That is the "standard" each candidate was
  compared against.
- Grep sweep of `ui/` + `MainActivity.kt` for every pattern: `display*`,
  `headline*`, `FontWeight.{Black,ExtraBold,Bold}`, `.uppercase()`/hardcoded
  ALL-CAPS labels, literal `fontSize` >= 20sp overrides, `letterSpacing >= 1.sp`,
  and a parallel subagent cross-check over the whole tree.
- Excluded as NOT exaggeration (kept / no action):
  - Markdown H1/H2 renderers (`MarkdownRenderer.kt:198`, `MarkdownPreviewScreen.kt:983`
    — `headlineSmall`/`titleLarge`/`titleMedium`/`titleSmall` heading ladder = document
    content semantics).
  - Full-screen gate/recovery titles: `LockScreen.kt:104` and the three
    `MainActivity.kt:874/937/1015` `headlineMedium` page titles — a *consistent
    family* of lone on-screen titles with `bodyMedium` copy under them, not
    oversized body content.
  - Canvas ink UI (`AnnotationCanvas` brush chips / drawn canvas text,
    `KnowledgeGraphScreen.kt:556` graph node labels), emoji sticker glyphs
    (`EditorScreen.kt:4852` `fontSize = 30.sp` = emoji rendering).
  - Data values rendered in caps on purpose: file-type extensions
    (`SpreadsheetTableView.kt:177`), protocol names (`LocalSendSendDialog.kt:430`),
    hex colors / blend-mode lookup (`AnnotationCanvas.kt:2047/2461/2464`),
    callout-type parse for logic (`MarkdownPreviewScreen.kt:1036`).
  - Understated single mismatch `VersionHistoryBottomSheet.kt:63` (`titleMedium`
    in a sheet whose siblings use `titleLarge`) — the smaller direction, not
    exaggeration.
  - `TemplateLibraryDialog.kt:118` `titleLarge+Bold` — equals the M3 default
    dialog title treatment (`TagManagerDialog.kt:64` does the same).

## Changes (file:line evidence)

### 1. `ui/components/InteractiveTutorial.kt` — the one genuinely exaggerated file (3 fixes)

- **`InteractiveTutorial.kt:210-214`** (was 210-216) — the tutorial kicker
  `"Step X of Y"` was the ONLY `FontWeight.ExtraBold` and the ONLY
  `letterSpacing >= 1.sp` in the app: `labelLarge + FontWeight.ExtraBold +
  letterSpacing = 1.sp`. A secondary progress caption read like a poster
  headline (weights 800 + 1 sp tracking on a 14 sp label).
  - **Fix:** plain `MaterialTheme.typography.labelLarge` (built-in 500 weight),
    primary colour kept; `FontWeight.ExtraBold` + `letterSpacing` removed.
- **`InteractiveTutorial.kt:218-223`** (was 218-226) — slide title `headlineSmall`
  (24 sp) `+ FontWeight.Bold` inside a 620 dp dialog card. `headlineSmall` is the
  same size the markdown H1 renderer uses, and stacking Bold on top of the
  headline's 400-weight base produced a 24 sp / 700-weight header — the largest
  heading in dialog chrome in the app.
  - **Fix:** `MaterialTheme.typography.titleLarge` (22 sp / built-in 500) with the
    explicit `fontWeight = FontWeight.Bold` override removed — matches every other
    dialog/sheet header in the app; hierarchy preserved (still the card's title),
    exaggeration removed.
- **`InteractiveTutorial.kt:181`** (was 181) — section chip label rendered the
  section `displayName` all-caps via `.uppercase()` (e.g. "CANVAS · 2/4"), an
  all-caps presentation label (not a data value).
  - **Fix:** `.uppercase()` removed — title case "Canvas · 2/4" (displayName values are
    already title-cased, e.g. "Canvas & Brushes"); chip styling
    (`labelMedium`, `FontWeight.Bold`, `secondaryContainer` surface) unchanged.

### 2. `ui/components/UnifiedSidebar.kt` — the only hardcoded ALL-CAPS labels in the app

- **`UnifiedSidebar.kt:124`** — `"QUICK NOTES"` section header. Fix: `"Quick Notes"`.
- **`UnifiedSidebar.kt:164`** — `"ALL NOTEBOOKS"` section header. Fix: `"All Notebooks"`.
- Both are the ONLY `text = "…ALL-CAPS…"` label strings in the app (grep-pinned);
  every sibling sidebar/section header elsewhere is title case ("Notebooks",
  "Sections", "Knowledge Graph"). `labelMedium + FontWeight.Bold + 70% alpha`
  styling untouched; only the shout-casing was removed.

### 3. `ui/screens/EditorScreen.kt` — near-sibling inconsistency

- **`EditorScreen.kt:3533`** — the width-picker "×" delete glyph rendered at
  `titleMedium` (16 sp) inside an 18 dp `IconButton`; its direct sibling pattern
  in the colour picker (`EditorScreen.kt:3179`) uses `labelSmall` for the same
  glyph. Fix: normalised to `labelSmall` (11 sp) — matches the sibling within the
  same screen and fits the 18 dp hit box. Style-only, error colour kept.

### 4. Pre-existing build blocker discovered during verification (Phase-127 fallout)

- **`ui/components/PluginStoreDescriptionBlock.kt:12-13` + `:72-74`** — committed by
  phase-127 with a non-compiling import: `Icons.AutoMirrored.Outlined.KeyboardArrowUp/Down`
  do not exist (those two icons have no auto-mirrored variant), so `:app:compileDebugKotlin`
  failed on `Unresolved reference` and blocked EVERY build on `main` (unit tests +
  `assembleDebug` alike). This file is untouched by the typography fix otherwise.
  - **Fix:** import from the plain `outlined` package
    (`androidx.compose.material.icons.outlined.KeyboardArrowUp/Down`) and reference
    them as `Icons.Outlined.KeyboardArrowUp/Down` — exactly how the already-compiling
    `UnifiedSidebar.kt:296/392` use them. Verified compiling + green suite below.

## Verification

- `gradle testDebugUnitTest` — **BUILD SUCCESSFUL**; the true count from an independent
  clean run is **1818 tests, 0 failures, 0 errors, 0 skipped** (163 `TEST-*.xml` suites
  across `app` + plugin modules). The first draft's `3536` figure was wrong — see the
  post-review corrections below.
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (first plain invocation hit the
  documented transient `IncrementalSplitterRunnable` incremental-packaging failure;
  the forced `--rerun-tasks` run rebuilt all 90 tasks green). Debug APK
  `app/build/outputs/apk/debug/app-debug.apk` 173,901,726 B, SHA-256
  `fa733fc6197020e714674b5997a5fefd3c17f365ff36e029b91409787f817df4`
  (re-verified 2026-08-18 with a fresh `--rerun-tasks` rebuild; size and hash reproduce
  exactly — the draft's `cfdb1b12…` was wrong, see below).

## Post-review corrections (review findings, `llops: phase-128 review fixes` commit)

Docs-only corrections; zero source changes, so this commit does not alter the verified
green code above.

- **F#4 — test-count accuracy:** the draft overstated the suite as `3536 tests`.
  The true count from an independent clean `gradle testDebugUnitTest` run is **1818
  tests, 0 failures / 0 errors / 0 skipped** (163 `TEST-*.xml` suites repo-wide).
  Corrected in the Verification section above and in `docs/phase-status.md`.
- **F#5 — APK hash accuracy:** the draft recorded SHA-256 `cfdb1b12…` for
  `app/build/outputs/apk/debug/app-debug.apk`. Fresh `gradle assembleDebug --rerun-tasks`
  rebuilds reproduce size 173,901,726 B and SHA-256 `fa733fc6…` deterministically; the
  recorded hash is corrected to the true artifact hash.
- **F#7 — wording:** the de-shouted labels are Title Case (the tutorial `displayName`
  values are already title-cased, e.g. "Canvas & Brushes"; sidebar headers are
  "Quick Notes"/"All Notebooks"), not strictly sentence case. Wording corrected; no
  code impact.
- **F#3 — process note (phase-127, not retroactively fixed):** phase-127 pushed the
  non-compiling `Icons.AutoMirrored.Outlined.KeyboardArrowUp/Down` import to `main` and
  shipped no `REPORT.md` (only `PROMPT.md` + `.done`); section 4 above (implemented in
  this phase) was required to unblock every build. Recommendation for future phases: a
  compile/CI gate before pushing.

## Constraints honoured

- NO DB schema change, no migration, no Room schema touched.
- No `.github/workflows/` edits.
- No new dependencies (the phase-127 icon fix uses icons already present from
  `material-icons-extended`, referenced the same way as the working call sites).
- Text COPY changed only by removing all-caps presentation of labels
  ("Quick Notes"/"All Notebooks", tutorial section name) — all other strings
  byte-identical; no wording, title, or message changed.
- No keys/decrypted content logged; `allowBackup=false`, `ClipboardGuard`,
  FLAG_SECURE untouched.
- Accessibility: no text was made smaller than the role minimum (Step-kicker
  stays `labelLarge` 14 sp; slide title stays `titleLarge` 22 sp; the "×" glyph
  joins its 11 sp sibling pattern; no contrast change anywhere).