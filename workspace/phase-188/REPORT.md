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

**Guarantee holds; mechanism narrative corrected in the review-fix commit. The
`weight(1f, fill = false)` seat is a DEFENSIVE NO-OP under the current unbounded
layout — it does NOT pin the footer — and the docs no longer claim it does.**

- The real guarantee is structural: the card is CONTENT-DRIVEN with a MINIMUM
  floor and NO maximum. The `Card` (`GalleryView.kt:141`) and the body `Column`
  (`:196`) share `heightIn(min = minCardHeight)`; there is no
  `heightIn(max=...)`/`height(...)`/`aspectRatio` anywhere on the card path.
  A growing font scale therefore grows the card, so the date/tags footer can
  never be clipped. This min-floor + unbounded-height structure is what pins
  footer visibility.
- The `weight(1f, fill = false)` on both preview paths (`GalleryView.kt:349`,
  `:355`) is a DEFENSIVE slack seat. Compose only redistributes slack through a
  flex child when the parent's main axis is FINITE; with the card's height
  unbounded above it is inert today. It is retained (and source-pinned) because
  it becomes the actual enforcement point the day a finite card height is
  introduced. Phase-184 behavior preserved: `fill=false` never stretches the
  preview (the old 60% dead-band sponge is NOT reintroduced).
- `GalleryCardLayoutPolicy` holds the decision model — `measuredCardHeightDp`
  and `footerAlwaysFits` — as the regression guard's ORACLE (independent,
  pure-JVM, fully unit-tested), NOT as functions the composable calls; the
  composable enforces the equivalent invariant structurally. The phase-184 pins
  (`heightIn(min = minCardHeight)`, no `aspectRatio`, preview literal
  `maxLines = 3`) all still pass (verified in the full suite).
- Tests: `Phase188GalleryLayoutBoundsTest` (8) — pure-JVM
  `measuredCardHeightDp`/`footerAlwaysFits` oracle math across 1.0/1.3/1.5/2/3
  font scales and 140–1000dp bodies plus fail-safes AND the source-vs-structure
  pin that the card is MIN-FLOORED but never height-capped (no `Modifier.height(`,
  no `heightIn(max`, no `.aspectRatio(`), the body Column shares the floor, the
  line-budget constants equal the composable literals, and both preview paths
  carry the defensive `weight(1f, fill = false)` seat.

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
| `services/GalleryCardLayoutPolicy.kt` | Extended: decision model (`measuredCardHeightDp`, `footerAlwaysFits`, line-budget constants, dark-theme border constants). Reframed as the regression guard's ORACLE — the composable enforces the invariant structurally and calls only `minCardHeightDp` |
| `services/GalleryTagRowPolicy.kt` | NEW pure-JVM tag-cap policy (2 chips + `+N`, parse/cap/badge/chip-text) |
| `ui/components/GalleryView.kt` | Body Column shares the min floor; preview paths = defensive `weight(1f, fill=false)` seat (inert under the unbounded height — see Risk #2); `BorderStroke` on the card; single-line capped tag `Row`; `FlowRow`/`ExperimentalLayoutApi` removed; ALL tag math routed through the policy (no inline `.take(` survives) |
| `app/src/test/…/GalleryTagRowPolicyTest.kt` | NEW (10 pure-JVM tests) |
| `app/src/test/…/Phase188GalleryLayoutBoundsTest.kt` | NEW (8 tests: oracle math + border decision + structural no-height-cap pin + line-budget agreement) |
| `app/src/test/…/Phase188GalleryRobustnessTest.kt` | NEW (6 source pins) |

## Review-fix commit (this commit)

Applied the review findings on top of the three phase-188 steps:

1. **Risk #2 honesty (review finding #1/#2).** The `weight(1f, fill=false)` slack
   seat does NOT pin the footer under the current unbounded layout (Compose only
   redistributes flex slack when the parent's main axis is finite). The
   statement is corrected here: the footer guarantee actually rests on the
   min-floor + never-height-capped structure, and `measuredCardHeightDp`/
   `footerAlwaysFits` are the pure-JVM oracle, not functions the composable
   calls. `Phase188GalleryLayoutBoundsTest` now pins the real invariant
   (min-floored but never capped: no `Modifier.height(`, no `heightIn(max`, no
   `.aspectRatio(`).
2. **No inline tag math (review finding #4).** `GalleryView.kt` now calls
   `GalleryTagRowPolicy.visibleChips(tags)` instead of inlining
   `tags.take(…)`; the composable holds only `parseTags`/`visibleChips`/
   `hiddenChipCount`/`hiddenBadgeText`/`chipText` calls, and the source pin
   forbids any `.take(`.
3. **Line-budget constants (review finding #5).** KDocs corrected: the constants
   are the guard's single source cross-checked against the phase-184 literals —
   the composable deliberately keeps the literals (`maxLines = 3` etc.).
4. **Formatting (review finding #3).** Trailing newline restored in
   `GalleryTagRowPolicy.kt` (`.editorconfig` `insert_final_newline = true`).
5. Tests updated to the honest invariants and re-run green.

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
  2478 prior baseline + 24 new phase-188 tests (10 + 8 + 6). The review-fix
  edits re-run clean in isolation (`:app:testDebugUnitTest --tests
  GalleryTagRowPolicyTest --tests Phase188GalleryLayoutBoundsTest --tests
  Phase188GalleryRobustnessTest` → BUILD SUCCESSFUL). All prior gallery
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