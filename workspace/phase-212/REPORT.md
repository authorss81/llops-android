# Phase 212 — JVM Test Hardening: Deletion / Install / Shape-Snap Logic [TESTS]

**Date:** 2026-08-25 · **Type:** test coverage + bug fixes exposed by tests
**DoD:** `gradle testDebugUnitTest` green with the new suites · ≥40 new methods · REPORT table · every production fix listed with file:line.

## 1. What shipped

13 new pure-JVM test files (117 `@Test` methods, target was ≥40) under `app/src/test/java/com/authorss81/noteflow/services/`, covering the thirteen service classes that had ZERO prior behavioral test references (verified by repo grep before this phase; the single pre-existing `WetBrushEngine` hit in `Phase201StrokeInputPipelineTest.kt:113` is a comment).

| # | Suite | Service | Was-covered | Now-covered |
|---|-------|---------|-------------|-------------|
| 1 | `OrphanImportCleanupPolicyTest` (12) | `services/OrphanImportCleanupPolicy.kt` | 0 refs | tracked/uncommitted/unknown matrix; committed imports NEVER swept; sweep idempotence; missing-file delete tolerated; cancelled-run simulation; non-alarming notice pin |
| 2 | `ShapeRecognitionHelperTest` (16) | `services/ShapeRecognitionHelper.kt` | 0 refs | LINE/RECT/ELLIPSE/ARROW detectors accept true geometry; retrace + zigzag handwriting REJECTED (phase-03 requirement); speck/<6-point floors; tolerance boundaries (hooked-line 0.887 ratio straightens, 9-point arrow-band ignored); snapped strokes preserve identity/style; 3 source pins of the AnnotationCanvas call-site exclusions (`AnnotationCanvas.kt:1484-1487`, wet/LASER/DOTTED/NEON/CHARCOAL/OIL_PASTEL/DRY_BRUSH/PALETTE_KNIFE) |
| 3 | `SettingsPluginEnableStoreTest` (6) | `services/SettingsPluginEnableStore.kt` | 0 refs | default OFF; enable latches ever-enabled; disable keeps latch (DISABLED vs REGISTERED); wipe resets both; restart survival over shared prefs; per-plugin isolation |
| 4 | `SettingsPluginInstallStoreTest` (5) | `services/SettingsPluginInstallStore.kt` | 0 refs | absent key = installed (backward compat); delete/re-download round-trip; restart survival; per-plugin isolation |
| 5 | `SettingsPluginEntryStoreTest` (7) | `services/SettingsPluginEntryStore.kt` | 0 refs | REMOTE entries round-trip (codec); BUNDLED entries never persisted and actively remove stale blobs; remove/all enumeration; malformed blob fails closed; digests survive restart |
| 6 | `SettingsPluginInvocationJournalStoreTest` (6) | `services/SettingsPluginInvocationJournalStore.kt` + `PluginInvocationJournal` | 0 refs | fresh=null; wire round-trip; null write removes; append/cap at MAX_JOURNAL_ENTRIES=20 (oldest evicted, verified 25→20 with ids 6..25); survives Disable, wiped by Delete; own key family outside `plugins.<id>.*` |
| 7 | `SettingsPluginSettingsStoreTest` (5) | `services/SettingsPluginSettingsStore.kt` | 0 refs | string/int/boolean round-trips with defaults; per-plugin namespacing; containsKey; removeAll wipes only one namespace |
| 8 | `SettingsPluginUpdateStoreTest` (5) | `services/SettingsPluginUpdateStore.kt` | 0 refs | rollback-root round-trip; latest save wins; clearPrevious; restart survival + Delete wipe |
| 9 | `DownloadablePluginInstallerTest` (9) | `services/DownloadablePluginInstaller.kt` | 0 refs | consent persistence; happy path = Installed + REGISTERED-off + artifact on disk + entry persisted + verify-before-load ordering; failure atomicity for download-guard refusal, non-HTTPS URL, MISSING_SHA256 (pre-existing artifact), verify failure, load failure — each leaves NO entry/artifact/registry residue (`assertNoResidue`); deleteArtifact; B2-LOG-04 log never echoes the download host |
| 10 | `DownloadablePluginUpdaterTest` (5) | `services/DownloadablePluginUpdater.kt` | 0 refs | manifest forwarding; approved update forwards approval + re-loads new version + reports from/to versions; engine failure ⇒ RolledBack without reload; NotYetImplemented ⇒ Failed; post-update reload failure still reports Updated honestly |
| 11 | `HtmlToMarkdownConverterTest` (18) | `services/HtmlToMarkdownConverter.kt` | 0 refs | title chain (title → h1 → default); headings/pre/blockquote/table/hr/lists; external links, wikilinks, images, bold/italic/code; entity decode-once; script/style/comment stripping; 4000-deep nesting terminates; unclosed tags degrade; ~1 MB document converts <15 s |
| 12 | `WetBrushEngineTest` (16) | `services/WetBrushEngine.kt` | comment-only | tier math: AGSL-unsupported / override-off force vector fallback; thermal 3–4 degrade to 0.35 (2 does not); EMA formula; sustained-degradation ladder 1.0→0.5→0.35→fallback with exact 999 ms/1000 ms boundaries; recovery restores 1.0; throttle ≥6 px / ≥16 ms inclusive bounds; interpolation zero-length/endpoint/3-substep-cap/tiny-radius clamp |
| 13 | `WetCanvasEngineTest` (7) | `services/WetCanvasEngine.kt` | 0 refs | dry start; wet tools raise level to their tool peak; EVERY `isWetRenderedTool` drives the sheet wet with peak ∈ (0,1]; dry tools never wet it; Dry Sheet / Reset state machine; BrushStudioParams defaults ∈ [0,1] |

Shared harnesses (no tests): `FakeSharedPreferences.kt` (in-memory `SharedPreferences` + fake `Context` so the six `SettingsPlugin*Store` adapters run against a REAL `SettingsManager` — no Robolectric), `Phase212RuntimeFakes.kt` (scriptable `PluginRuntime`, writing `DownloadTransport`, `assertNoResidue`, remote-entry factory, `PluginContext` proxy).

## 2. Bugs found & fixed (production changes)

Writing these tests exposed three real defects + one fidelity defect; each is fixed, pinned, and listed here.

### BUG 1 (HIGH — silent ink corruption): doubled-back strokes were replaced by a zero-height rectangle
- **Where:** `ShapeRecognitionHelper.trySnapShape` RECT branch, `services/ShapeRecognitionHelper.kt:133-140`.
- **Evidence:** an out-and-back stroke along one line (double underline) is a closed loop whose every point sits on the collapsed bbox edge, so `perimeterFitRatio == 1.0 ≥ 0.72` — the stroke was REPLACED with a degenerate 5-point "rectangle" (height ≈ 0). Pinned by `an in-and-out retrace along one line is never replaced by a degenerate box`.
- **Fix:** RECT acceptance additionally requires a non-degenerate box: `minOf(width, height) >= max(4f, boundingDiag * 0.04f)` (`ShapeRecognitionHelper.kt:138-140`). Legit thin rectangles (e.g. 200×20 px, ratio 9.9%) still snap — pinned by `a long thin traced rectangle still snaps`.

### BUG 2 (MEDIUM — wrong-shape replacement): traced squares snapped to smooth ELLIPSEs
- **Where:** ellipse branch runs before the RECT branch; a uniformly-traced square perimeter averages `|dx²+dy²−1| = 1/3 < 0.35`, so it passed the ellipse fit and the user's square was replaced by a 37-point ellipse. This contradicts the class contract ("rectangles/squares" snap to rectangles).
- **Fix:** corner-evidence gate — an ellipse/circle touches its bbox at four tangent regions and NEVER approaches a corner, while a traced rectangle passes through all four; when ≥4% of points hug both axes simultaneously (`hasCornerEvidence`, `ShapeRecognitionHelper.kt:102-106` + `:242-262`) the ellipse branch defers to the rectangle detector. Circles still snap to ELLIPSE (pinned), squares now snap to crisp RECTANGLE corners (pinned).
- **Note:** my first attempt gated on `perimeterFitRatio >= 0.72` — WRONG: circles score ≈0.75 on that metric because each tangency window spans ~68° (caught by the circle test failing as RECTANGLE during development); the corner metric is the correct discriminator.

### BUG 3 (HIGH — corrupted imported links): internal links emitted a stray quote inside the wikilink target
- **Where:** `HtmlToMarkdownConverter.convertHtmlToMarkdown`, link branch — `services/HtmlToMarkdownConverter.kt:99-108`.
- **Evidence:** the format string carried an escaped quote (`\"]]"`), so `<a href="other.html">Other page</a>` produced `[[other|Other page"]]` — a corrupted wikilink on EVERY imported internal link.
- **Fix:** emit `[[target]]` / `[[target|text]]`; pinned by `internal links become wikilinks without stray characters` (incl. `assertFalse(body.contains("\""))`).

### FIX 4 (LOW — content mangling): `<br>` inside a blockquote glued its lines together
- **Where:** `HtmlToMarkdownConverter.kt:55-64`. `stripTags` silently deleted `<br>`, so a two-line quote became one line (`firstsecond`).
- **Fix:** convert `<br>` variants to newlines INSIDE the blockquote branch before the `"> "` prefix pass (scoped — global hoisting would have broken `<br>`-in-table-cell handling). Pinned by `blockquotes prefix every line`.

## 3. Verification

- Targeted: all 13 suites green — 117 completed / 0 failed (`--rerun-tags` forced rerun after each fix round).
- Full: `gradle :app:testDebugUnitTest` = **3123 completed / 3 failed**, all pre-existing & documented on clean HEAD (Phase148 UNC-path scrub test; PaparazziSmokeTest ×2 layoutlib env) — identical to the phase-210/211 baseline.
- `gradle :app:assembleDebug` green.
- No schema change. No new deps. `.github/workflows/` untouched. Base-APK-size rule intact (test-only code + 4 small production edits).

## 4. Notes / known limitations (documented, not actioned)

- The ARROW detector keys on path-length deficit (shaft+head lands below the LINE threshold); it has NO structural head check, so a wavy-but-mostly-straight mark in the 0.55–0.82 direct/path band can become an arrow. Changing this needs UX calibration against real arrow gestures — out of scope for a hardening phase; the unambiguous noise fixtures (ratio ≈ 0.27 zigzag) are pinned rejected.
- Nested HTML lists flatten into their parent bullet (`- parentchild`) and list numbering is dropped — regex single-pass limitation, now pinned as documented behavior so a future recursive parser shows up as an intentional diff.
- `DownloadablePluginInstallerTest` seeds `Build.SUPPORTED_ABIS` via reflective Unsafe static-field put (the mockable android.jar leaves it null; JDK 21 refuses plain `Field.set` on static finals). Test-only state.
