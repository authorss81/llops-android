# Phase 188 — GalleryView robustness: perf, large-font scaling, dark-theme contrast, tag-chip overflow

**Status: DONE** (2026-08-20) · commits on `main` pushed incrementally per the
WORKFLOW RULE: `7e62074` (step 1 inventory), `e915e61` (step 2 mitigations),
`<step-3>` (regression proof + REPORT + docs + `.done`).

## Per-risk evidence

### Risk #1 — Canvas stroke deserialization overhead (grid must never rasterize `pointsJson`)

**Already clean after phases 183–187, now hard-pinned.**

- The grid item in `ui/components/GalleryView.kt` builds its preview exclusively
  from already-loaded `NotePageEntity` metadata: `page.title`, `page.extractedText`
  (line 128 `val preview = page.extractedText?.trim().orEmpty()`), `page.tags`,
  `page.pinned`, `page.sourceFileType`, `page.updatedAt`. Zero calls to the stroke
  store, zero `pointsJson` tokens, zero thumbnail/rasterizer paths.
- New source-pin test `Phase188GalleryRobustnessTest`:
  - `gallery never references stroke geometry or a thumbnail rasterizer` —
    scans `GalleryView.kt` for `pointsJson`, `getStrokesForPage`, `strokesForPage`,
    `StrokeDao`, `deserializeStrokes`, `serializeStrokes`, `loadStrokes`,
    `saveStrokesForPage`, `thumbnail`, `rasterize`, `StrokeEntity` — any hit fails.
  - `gallery preview derives only from NotePageEntity metadata fields` — asserts
    every preview input is a light metadata field and forbids `repository.`,
    `.dao`, `loadPageStrokes` in the grid item.
  - `grid is a lazy grid keyed by id so big galleries stay memory-bounded` —
    `LazyVerticalGrid` + `GridCells.Adaptive(168.dp)` + `items(pages, key={it.id})`.

### Risk #2 — Large font scaling (1.3x–1.5x) must not clip the date/tags footer

**Fixed: the prompt's exact mechanism (`Column(weight(1f, fill=false))` +
`Modifier.heightIn(min=180.dp)`) is now wired, on top of the phase-184 floor.**

- `services/GalleryCardLayoutPolicy.kt` (extended): the large-font layout-bounds
  policy —
  - `measuredCardHeightDp(contentHeightDp, fontScale)` = `max(content, floor)`
    — the card is content-driven with a MINIMUM and NO maximum, so growing font
    scales grow the card instead of clipping the footer (fail-safe on NaN/negative
    content → floor alone);
  - `footerAlwaysFits(contentHeightDp, fontScale)` = the guarantee decision
    (false only for garbage input, mirroring `minCardHeightDp`'s fail-safe);
  - line budgets pinned in one place: `TITLE_MAX_LINES = 2`,
    `PREVIEW_MAX_LINES = 3`, `TAG_ROW_MAX_LINES = 1`, `FOOTER_DATE_MAX_LINES = 1`.
- `ui/components/GalleryView.kt`:
  - the body `Column` now carries the SAME `heightIn(min = minCardHeight)` floor
    as the `Card` (`:188-198`), so the slack seat below has a definite bound;
  - both preview paths (text `Text(:338-350)` and ink/empty placeholder
    `Box(:351-357)`) are wrapped in `Modifier.weight(1f, fill = false)` — the
    prompt's `Column(weight(1f, fill=false))`. `fill=false` preserves the
    phase-184 fix (the preview never *stretches* tall — the old 60% dead-band
    sponge is NOT reintroduced); it only seats the floor's slack BETWEEN the
    preview and the footer, pinning the footer visible at any font scale.
- Regression note vs phase-184: the old `weight(1f)` was removed because under a
  **rigid ratio** it soaked up ~60% dead band; re-adding it as `weight(1f,
  fill = false)` under a **content-driven floor** has the opposite effect (slack
  is bounded by the floor — max ~21dp at 1.0 scale — and lives before the footer).
  The phase-184 pins (`heightIn(min = minCardHeight)`, no `aspectRatio`, preview
  literal `maxLines = 3`) all still pass (verified in the full suite).
- Tests: `Phase188GalleryLayoutBoundsTest` (8) — pure-JVM
  `measuredCardHeightDp`/`footerAlwaysFits` math across 1.0/1.3/1.5/2/3 font
  scales and 140–1000dp bodies, fail-safes, line-budget constants,
  source-vs-policy agreement (composable keeps the phase-184-required literal
  `maxLines = 3` == `PREVIEW_MAX_LINES`, date `maxLines = 1` ==
  `FOOTER_DATE_MAX_LINES`), both preview paths carry `weight(1f, fill = false)`,
  the body Column shares the floor.

### Risk #3 — Dark theme: cards blend into near-black surfaces

**Fixed: explicit policy-driven hairline border.**

- `GalleryCardLayoutPolicy.GALLERY_CARD_BORDER_WIDTH_DP = 1f` +
  `GALLERY_CARD_BORDER_ALPHA = 0.35f` (pure-JVM decision — no inline literals).
- `GalleryView.kt` `Card(...)` now sets
  `border = BorderStroke(GALLERY_CARD_BORDER_WIDTH_DP.dp, scheme.outlineVariant.copy(alpha = GALLERY_CARD_BORDER_ALPHA)))`
  (`:147-154`) — visible on dark themes where `surfaceVariant@0.55` +
  phase-187 paper fill sit close to the near-black `surface`.
- Materials: verified the clickable material3 1.3.1 `Card` overload accepts
  `BorderStroke` (`androidx.compose.material3.CardKt` signature).
- Tests: `Phase188GalleryLayoutBoundsTest` `dark-theme border decision…` (1dp@0.35)
  + `composable border is the policy decision not an inline literal` (pins
  `BorderStroke`, `GALLERY_CARD_BORDER_WIDTH_DP.dp`, the
  `outlineVariant.copy(alpha = …GALLERY_CARD_BORDER_ALPHA)` spelling; forbids an
  inline `outlineVariant.copy(alpha = 0.35f)` literal; no `aspectRatio` backsliding).

### Risk #4 — Tag chip overflow on multi-tag notes

**Fixed: capped at 2 chips + `+N` badge, single line, timestamp always visible.**

- New pure-JVM `services/GalleryTagRowPolicy.kt`:
  - `MAX_VISIBLE_TAGS = 2`;
  - `parseTags(raw)` (split/trim/strip one `#`/drop empties),
    `visibleChips(tags|raw)`, `hiddenChipCount(tags|raw)` (never negative),
    `hiddenBadgeText(hidden)` → `null` or `"+N"`, `chipText(tag)` → `#tag`.
- `GalleryView.kt`:
  - tag parsing/capping routed through the policy (`:116-120`);
  - the WRAPPING `FlowRow` is replaced by a single-line `Row` (`:412-446`): each
    chip `Surface` is `Modifier.weight(1f, fill = false)` so long tags ellipsize
    (`maxLines = 1` + `TextOverflow.Ellipsis`) instead of wrapping; the `+N`
    badge is last and unweighted — a `Row` measures unweighted children first, so
    the badge can never be pushed out; the update timestamp row sits directly below.
  - `ExperimentalLayoutApi` opt-in + import removed (nothing left that uses it).
- Tests: `GalleryTagRowPolicyTest` (10, pure JVM) + `Phase188GalleryRobustnessTest`
  bounded-tag-row pins (policy-driven cap/parse/badge/chip-text; no `FlowRow`; no
  `.take(3)`; `maxLines = 1`/Ellipsis; badge conditional; date row preserved).

## Files touched (step 2)

| File | Change |
|---|---|
| `services/GalleryCardLayoutPolicy.kt` | Extended: large-font bounds (`measuredCardHeightDp`, `footerAlwaysFits`, line budgets) + dark-theme border constants |
| `services/GalleryTagRowPolicy.kt` | NEW pure-JVM tag-cap policy (2 chips + `+N`, parse/cap/badge/chip-text) |
| `ui/components/GalleryView.kt` | Body Column shares the min floor; preview paths = `weight(1f, fill=false)` slack seat; `BorderStroke` on the card; single-line capped tag `Row`; `FlowRow`/`ExperimentalLayoutApi` removed; tag math routed through the policy |
| `app/src/test/…/GalleryTagRowPolicyTest.kt` | NEW (10 pure-JVM tests) |
| `app/src/test/…/Phase188GalleryLayoutBoundsTest.kt` | NEW (8 tests: bounds math + border decision + source-vs-policy agreement) |
| `app/src/test/…/Phase188GalleryRobustnessTest.kt` | NEW (6 source pins) |

## Regression proof

- `gradle assembleDebug` — **BUILD SUCCESSFUL in 2m 57s** (no `e:` errors; only
  pre-existing deprecation warnings in `HomeScreen.kt`/`MarkdownPreviewScreen.kt`).
- `gradle testDebugUnitTest` — **2502 total, 1 failure** = the pre-existing
  `Phase148UiFailureTextScrubTest` UNC-path failure
  (`UNC path must be redacted: err \\fileserver\share\secret-wills.docx`,
  untouched across phases 146/149/151/153/158/166/177/183/184/186/187). The two
  known timing/concurrency flakes appeared once in the first full run
  (`Phase151MarkdownMainThreadPerfTest`, `WikiLinkParserCacheUnitTest`) and both
  pass in isolation and in the final clean full run (`failures=0`). 2502 =
  2478 prior baseline + 24 new phase-188 tests (10 + 8 + 6). All prior gallery
  pins green: `GalleryCardLayoutPolicyTest`, `Phase184GalleryProportionTest`,
  `Phase183GalleryTypographyTest`, `Phase186GalleryQuickActionsTest`,
  `Phase187GalleryInkPaperTest`, `GalleryTitleDisplayPolicyTest`,
  `GalleryCardActionsPolicyTest`, `InkCardPaperPolicyTest`.

## Constraints honored

- No `.github/workflows/` edits. No new dependencies. No DB schema change /
  migration. No settings/permissions changes. Base-APK-size rule intact.
- Gallery stays lightweight: no per-frame allocations (paper texture + tag row
  unchanged from phase-187; only layout-seat + border + chip-cap changed), no
  stroke parsing on the main thread (source-pinned).
- `allowBackup="false"`/`data_extraction_rules.xml` untouched; no logging added.
- `.done` marker + REPORT + docs committed and pushed.