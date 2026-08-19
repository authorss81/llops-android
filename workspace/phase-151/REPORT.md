# Phase 151 — Markdown main-thread performance (R2-b2b5-FEA-02 + R2-b2b5-FEA-03)

Both findings are `CLOSED`. Source: `docs/security-report-round2.md`.

## R2-b2b5-FEA-02 — `MarkdownInlineMath` quadratic scans on adversarial paragraphs (MEDIUM)

### Before
- `MarkdownInlineMath.kt` (pre-phase-151): every backtick-run opener called
  `findClosingBackticks(text, i + run, run)`, which did a **linear scan to
  end-of-string per opener** (`text.substring(i, i + run)` per step, with a
  `substring(...).all {}` allocation per candidate) → O(n²) on lines with many
  uncloseable distinct-length runs.
- Every code-span membership test in `findMathRuns` / `findClosingDollar` was
  `insideAny(text, index, codeRanges) = codeRanges.any { index in it }` →
  O(codeRanges) per `$` (O(n·R)).
- Reachable on the MAIN THREAD during composition: `MarkdownRenderer.kt:574-576`
  `remember(text, primaryColor)` per paragraph → `findCodeRanges`/`findMathRuns`
  (`:634-635`). A 100 KB adversarial paragraph stalled the UI for seconds.

### After
`app/src/main/kotlin/com/authorss81/noteflow/services/MarkdownInlineMath.kt`
- `findBacktickRuns` (`:86`) pre-computes every maximal backtick run in ONE
  left-to-right pass (O(n) total).
- `closingPositionIndex` (`:110`) buckets closer positions by run length — a
  single binary search per opener lookup; total bucket entries == total
  backtick characters, so building is O(n).
- `findClosingBacktick` (`:120`) is the binary search; the per-opener
  end-of-string scan is deleted.
- `CodeRangeIndex` (`:40`) is the interval index: membership = one
  `Arrays.binarySearch` + one comparison → O(log R). `findMathRuns` (`:158-160`)
  and `findClosingDollar` (`:185`) use it; the linear `insideAny` is deleted.
- Net: adversarial distinct-length backtick paragraphs parse in linear time.

Equivalence with the old scanner is enforced by a reference implementation,
`Phase151MarkdownMainThreadPerfTest` (`findCodeRanges matches the reference…`,
`…matches the reference on randomized texts` — 2000 random docs, adversarial
reproducer ` ``x```x````x… ` and sub-run-closer cases), plus length-scaling
assertions (256 KB vs 2 MB, ratio < 24; 2 MiB parse < 5000 ms).

## R2-b2b5-FEA-03 — hybrid editor re-tokenizes the whole document per keystroke (MEDIUM)

### Before
`HybridMarkdownEditor.kt:86-87`: BOTH `remember(text) { blocks(text) }` and
`checkboxCandidates(text)` recomputed per keystroke, and `checkboxCandidates`
internally called `blocks(content)` a SECOND time (`MarkdownBlockTokenizer.kt:239`);
`:117` `replaceBlockSource` did `content.lines()` + `ArrayList(lines)` +
`joinToString("\n")` over the whole document; `:133` `cursorOrder =
remember(index) { candidates.filter { it.blockIndex == index } }` →
O(blocks × candidates) per keystroke.

### After
- `MarkdownBlockTokenizer.tokenize` (`class MarkdownDocument`, tokenizer `:100`)
  is the ONE-pass tokenization: lines + blocks + checkbox candidates +
  `candidatesByBlock` preindex computed together.
- `HybridMarkdownEditor` holds one `MarkdownDocument` (`:71`) instead of two
  separate `remember` passes; external changes re-tokenize once (`:81`).
- The keystroke path is `replaceBlock(doc, blockIndex, newRaw)` (`editor :91`,
  tokenizer `:412`): reuses `MarkdownDocument.lines` (no full `content.lines()`),
  re-classifies ONLY the edited window (`classifyWindow`), shifts untouched later
  blocks by the line delta, and recomputes checkbox candidates around the window
  only (`incrementalCandidates` `:313` reuses the byte-unchanged before/after
  candidates, shifting their line numbers by `delta` and renumbering block
  indexes in one pass). The old full-document `lines()` + `ArrayList` + join on
  the keystroke path is gone.
- `cursorOrder = doc.candidatesByBlock[index] ?: emptyList()` (`:133`) replaces
  the per-block `filter` — O(1).
- `toggleCheckbox(doc, …)` (`:367`) flips the marker line in the cached lines and
  mirrors `checked` with **zero** re-tokenization.

Output is byte-identical: `replaceBlock` content == `replaceBlockSource` + fresh
`tokenize` blocks == full pipeline, enforced by reference-equivalence tests
(3000 randomized docs + explicit region-merge/multi-block/fence/table/callout
edge cases + a 5k-line typing simulation).

## Verification

- `gradle testDebugUnitTest` — 2075 tests, 0 new failures. The single failure is
  the pre-existing `Phase148UiFailureTextScrubTest` UNC-path assertion
  (`\\fileserver\share\...` must be redacted), reproduced on a clean stash and
  untouched here (see AGENTS.md).
- `gradle assembleDebug` — green (debug APK built).
- New test class: `app/src/test/java/com/authorss81/noteflow/Phase151MarkdownMainThreadPerfTest.kt` (18 tests).

## Review-fix note (2026-08-19)

The first phase-151 submission was reviewed and returned findings; this report
is part of the follow-up `llops: phase-151 review fixes` commit:
1. The new test class did not compile (`stripComments` helper missing; missing
   `MarkdownBlock` import) — fixed, so the whole class now runs.
2. `replaceBlock` still did a full-document `joinToString("\n")` + full candidate
   rescan per keystroke; candidates are now recomputed window-only via
   `incrementalCandidates` (unchanged before/after candidates reused). The
   per-keystroke path no longer scans the whole document for candidates.
3. Flaky absolute wall-clock bounds were widened to margins that separate
   linear from quadratic with room for CI jitter (ratio < 24 on an 8x input
   length; relative incremental-vs-old comparison retained on the 5k-line test).
4. `workspace/phase-151/REPORT.md`, the `docs/phase-status.md` row and the
   `docs/ARCHITECTURE.md` "Implemented in phase-151" note — all produced here;
   the phase had been marked `.done` without them.

No DB schema change, no `.github/workflows/` edits, no new dependencies. No keys,
passwords or decrypted note content are logged or touched.