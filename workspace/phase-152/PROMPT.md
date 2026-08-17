# Phase 152: Knowledge-graph + feature-data bounds — edge culling, palette lowercasing cache, waveform finite-sample gate [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-b2b5-FEA-01, R2-b2b5-FEA-05, R2-b2b5-FEA-06) and
`docs/phase-status.md` + `docs/ARCHITECTURE.md`. This phase bounds three
feature-surface data paths that scale with crafted/corpus input.

## Source findings (all OPEN — MEDIUM, LOW, LOW)

1. **R2-b2b5-FEA-01** (MEDIUM) — Knowledge graph builds edges from the entire
   vault with no per-page link-count cap: `KnowledgeGraphScreen.kt:165` (`val
   graphEdges = wikiEdges.map { GraphEdge(...) }`), `:166-167` (edges/edgeRefs
   never culled — the node cull `:184-191` bounds nodes only), and the Canvas
   draw runs `for (edge in edges)` EVERY frame `:481` (+ pulse loop `:509-511`).
   `WikiLinkParser.kt:482` scans up to 2000 pages with no per-page link cap,
   `:186-200`, `:496` whole-edge-set materialization. A ~2k-page interlinked
   vault → ~10⁶ edges → frozen UI.
2. **R2-b2b5-FEA-05** (LOW) — Command palette lowercases the FULL corpus per
   keystroke with no cross-keystroke cache: `CommandPaletteMath.kt:97`
   (`doc.body.lowercase()`), `:128/:142` (`lowerLog` caches only within one
   `rank()` call); `NoteflowViewModel.kt:3496` runs per keystroke (debounced
   250 ms, IO). ~1500 pages × 50 KB → ~75 MB allocations per keystroke.
3. **R2-b2b5-FEA-06** (LOW) — Non-finite waveform samples propagate into bar
   geometry: `NoteRepository.kt:1204` (`JSONArray.getDouble(...).toFloat()`)
   accepts `NaN`/`Infinity` literals from crafted stored `waveformJson`;
   `WaveformPeakMath.kt:35-43` min/max decimation never filters non-finite
   values; `AudioPlaybackCard.kt:253` `coerceIn(0.1f, 1.0f)` does not remove
   NaN → `barHeight = canvasHeight * NaN` → NaN geometry to `drawRoundRect`.

## The fix (where & how)

- **R2-b2b5-FEA-01:** Build the rendered edge set only over edges whose BOTH
  endpoints survived `cullToCap`, cap the edge count (top-K by recency/degree),
  and memoize the per-frame tag-filter result instead of re-splitting per edge
  (`KnowledgeGraphScreen.kt:165-191,481,509-511`). Also add a per-page
  link-count cap in `WikiLinkParser.extractWikiLinks` (`:186-200`).
- **R2-b2b5-FEA-05:** Lowercase `title`/`body` ONCE when the `PaletteIndex` is
  built (cache it in `PaletteDoc`) so `rank()` never re-lowercases per
  keystroke (`CommandPaletteMath.kt:97,128-146`).
- **R2-b2b5-FEA-06:** Replace non-finite samples at parse/decimate time
  (`v.takeIf { it.isFinite() } ?: 0f` in `WaveformPeakMath.downsample` +
  `NoteRepository.parseWaveformJson`), and `amp.coerceIn(0.1f,
  1.0f).takeIf { it.isFinite() } ?: 0.1f` in `AudioPlaybackCard.kt:253`.

## Verification

- New/updated pure-JVM unit tests: KG edges are capped to pairs of culled
  survivors + top-K; link-count cap; a palette index that lowercases once
  (corpus-lowercase count source pin); waveform NaN/Infinity filtered at parse
  + downsample + renderer output.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-152/REPORT.md`.

## Definition of done

- All three findings closed with `file:line` before/after evidence.
- A crafted vault cannot freeze the graph, churn palette allocations, or draw
  NaN geometry.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep reduce-motion /
  low-end rules (graph particle pulses off under reduce-motion) intact.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.