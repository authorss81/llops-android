# Phase 152 — Feature-data bounds: knowledge-graph edges, palette lowercase cache, waveform gate (R2-b2b5-FEA-01 + R2-b2b5-FEA-05 + R2-b2b5-FEA-06)

All three findings are `CLOSED`. Source: `docs/security-report-round2.md`.

## R2-b2b5-FEA-01 — Knowledge graph: uncapped per-frame edge iteration over the whole vault (MEDIUM)

### Before
- `KnowledgeGraphScreen.kt:165-167`: `graphEdges` built from the **entire**
  vault's wiki-edge set (`wikiEdges.map { GraphEdge(it.sourcePageId, it.targetPageId) }`),
  while only the *nodes* were culled (`:184-191`, `cullToCap` → 120/220/400).
  `edgeRefs` (line 167) fed the physics layout un-culled.
- The Canvas draw block ran `for (edge in edges)` every frame (`:481`) and the
  pulse loop re-iterated all edges (`:509-511`), with a per-edge tag re-split
  (`isFilteredOut(src.page)`).
- `WikiLinkParser.kt:482` scanned up to `MAX_SCAN_PAGES` = 2000 pages with **no
  per-page link-count cap**, and `buildWikiLinkEdges` ended with
  `edgeList.distinct()` (`:496`) materializing the whole edge set.
- Reproducer: a ~2,000-page heavily interlinked vault → ~10⁶ edges → 10⁶+
  `drawLine`/tag-split ops per frame during pan/zoom/selection.

### After
`app/src/main/kotlin/com/authorss81/noteflow/services/graph/KnowledgeGraphEdgePolicy.kt` (new, pure JVM)
- `edgeCapFor(lowEnd, nodeCount)` (`:38`): low-end devices 300, >240 nodes 1000, otherwise 600.
- `cullEdgesToSurvivors(edges, survivorIds, pageUpdatedAt, maxEdges)` (`:57`):
  keeps only edges whose **both endpoints survived** node culling (drops
  self-edges), dedups deterministically via the target-type to a stable order,
  then keeps top-K by max(endpoint `updatedAt`) with a sourceId→targetId
  tie-break. Fails closed on empty input / `maxEdges <= 0`. Runs on a background
  dispatcher, not the frame loop.

`app/src/main/kotlin/com/authorss81/noteflow/services/WikiLinkParser.kt`
- `MAX_LINKS_PER_PAGE = 200` (`:81`) — `extractWikiLinks` caps its regex scan
  (`:202`) so a link-farm page cannot fan out.
- `MAX_TOTAL_EDGES = 100_000` (`:85`) — `buildWikiLinkEdges` dedups inline via
  a `HashSet<WikiLinkEdge>` (`:497`) and breaks as soon as the cap is hit
  (`:507`, `:514`). The whole-edge-set `edgeList.distinct()` materialization is
  gone from the code.

`app/src/main/kotlin/com/authorss81/noteflow/ui/screens/KnowledgeGraphScreen.kt`
- The `LaunchedEffect` culls nodes first (`:173` `cullToCap`, `:181`
  `pageUpdatedAt` from the kept nodes), then `culledEdges =
  KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(...)` (`:195`),
  `graphEdges = culledEdges.map { GraphEdge(...) }` (`:201`) and
  `edgeRefs = culledEdges` (`:203`) — **the same culled list feeds both the
  physics layout and the draw**, so per-frame drawn edges ≤ physics edges and
  both are bounded (≤ 1000).
- The Canvas draw block memoizes the tag-filter verdict in
  `filteredById = HashMap<String, Boolean>` (`:505`) with a local
  `pageFiltered(id, node)` helper (`:507`) — no per-edge tag re-split on the
  frame path.

## R2-b2b5-FEA-05 — Command palette per-keystroke full-corpus lowercasing (LOW)

### Before
- `CommandPaletteMath.kt:97`: `if (doc.body.lowercase().contains(lq)) …` — every
  non-title/tag-matching doc was fully lowercased **per keystroke**.
- `lowerLog` (`:128`, `:142`) cached only *within* a single `rank()` call, so
  nothing survived across keystrokes; `NoteflowViewModel.kt:3496` ran `rank()`
  per keystroke (debounced 250 ms, IO) → ~1500 pages × ~50 KB ≈ **~75 MB of
  allocations per keystroke**.

### After
`app/src/main/kotlin/com/authorss81/noteflow/services/graph/CommandPaletteMath.kt`
- `PaletteDoc` computes `lowerTitle`, `lowerBody`, `lowerTags` **once at
  construction / index build** (`:38-40`) — this is the corpus generation, not
  per keystroke.
- `score()` (`:96`) reads `doc.lowerTitle` / `doc.lowerBody` / `doc.lowerTags`;
  `rank()` (`:129`) consumes `matchesTagFilter(it.lowerTags, …)` and lowercases
  the query exactly once; the per-keystroke `lowerLog` HashMap is deleted.
- `makeSnippet(body, lowerBody, lq)` (`:114`) cuts the snippet from the
  **original-case** `body` (highlight text keeps the user's casing) while the hit
  location is found via the cached `lowerBody`.

## R2-b2b5-FEA-06 — Non-finite waveform samples into bar geometry (LOW)

### Before
- `NoteRepository.kt:1204`: `arr.getDouble(index).toFloat()` accepts `NaN` /
  `Infinity` literals from a crafted stored `waveformJson`.
- `WaveformPeakMath.kt:35-43`: min/max decimation never filtered non-finite
  values (NaN comparisons are all false → NaN survives to the output array).
- `AudioPlaybackCard.kt:253`: `coerceIn(0.1f, 1.0f)` does **not** remove NaN → a
  NaN width/height reached `drawRoundRect`.

### After
- `WaveformPeakMath.finiteOrZero(v)` (`:31`) replaces non-finite samples with
  `0f`; `downsample` sanitizes in both the identity pass (`:45`) and the min/max
  decimation loop (`:53-56`).
- `NoteRepository.parseWaveformJson` gates BOTH parse paths:
  `WaveformPeakMath.finiteOrZero(arr.getDouble(index).toFloat())` (`:1232`) and
  the split fallback `.map { WaveformPeakMath.finiteOrZero(it) }` (`:1237`).
- `AudioPlaybackCard.kt:256` uses `WaveformPeakMath.renderAmp(amplitudes[i])`
  (`WaveformPeakMath.kt:39`: clamp `(0.1f..1f)` then refuse non-finite →
  `0.1f`) so the bar geometry is always finite.
- Defense in depth: parse-time gate + decimation-time gate + render-time clamp,
  so even a value that slips past one layer cannot reach the canvas.

## Verification

- `gradle :app:testDebugUnitTest --tests "KnowledgeGraphEdgePolicyTest,
  CommandPaletteMathTest, WaveformPeakMathTest, Phase152FeatureDataBoundsWiringTest,
  WikiLinkParserCacheUnitTest"` — green.
- `gradle testDebugUnitTest` — **2105 tests, 1 failure**: the pre-existing
  `Phase148UiFailureTextScrubTest` UNC-path assertion (`\\fileserver\share\...`
  must be redacted), reproduced on a clean stash (git stash + isolated run) and
  untouched by this phase (see AGENTS.md).
- `gradle assembleDebug` — green (debug APK
  `app-debug.apk`, SHA-256 `e198848572ef6eb25234d551abaffc5e2449f372185d9456f38b405699180b54`).

New / updated tests:
- `app/src/test/java/com/authorss81/noteflow/services/graph/KnowledgeGraphEdgePolicyTest.kt` —
  14 tests: cull-to-survivors, self-edge drop, deterministic dedup, top-K by
  recency, tie-break stability, per-edge-cap tiers, and the WikiLinkParser
  per-page (200) + total (100k) discovery caps.
- `app/src/test/java/com/authorss81/noteflow/Phase152FeatureDataBoundsWiringTest.kt` —
  source-pin wiring tests (comment-stripped): the screen culls via
  `cullEdgesToSurvivors` and uses the culled list for BOTH `graphEdges` and
  `edgeRefs`; the palette never lowercases per keystroke; both `parseWaveformJson`
  paths use `finiteOrZero`; the renderer uses `renderAmp`.
- `CommandPaletteMathTest.kt` / `WaveformPeakMathTest.kt` — FEA-05 / FEA-06
  behavioural tests (case-insensitive rank contract preserved; `renderAmp`
  clamps finite values and refuses NaN/±Inf → `0.1f`).

No DB schema change, no `.github/workflows/` edits, no new dependencies. No keys,
passwords or decrypted note content are logged or touched.