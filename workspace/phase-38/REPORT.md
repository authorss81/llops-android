# Phase 38 — Knowledge Graph & Spatial Navigation — REPORT

Status: **DONE** (2026-08-15). `gradle testDebugUnitTest` (875 tests, 0 failures) and
`gradle assembleDebug` both pass. 37 new pure-JVM tests (838 baseline → 875).

## What shipped

### 1. Interactive force-directed graph (`ui/screens/KnowledgeGraphScreen.kt`)
The in-screen ad-hoc physics loop was replaced with the deterministic pipeline in
`services/graph/GraphLayoutMath.kt`:

- **Clusters** — pages grouped by tag communities + wikilink communities via
  union-find (`GraphLayoutMath.assignClusters`), coloured per cluster from a
  small deterministic tonal ladder. Legend card shows the selected node's cluster.
- **Tag filters** — `FilterChip` row (top 24 tags from the indexed tags column —
  never a fresh full-text scan), AND/OR toggle + Clear; non-matching nodes fade to
  0.12 alpha and their edges fade to 0.06; physics never restarts.
- **Link pulses** — moving particle dots travel along edges touching the selected
  node; driven by a single `rememberInfiniteTransition` phase (no per-edge
  timers). Disabled under reduce-motion.
- **Physics-based collision bounding** — `resolveCollisionsAndBounds` pushes
  overlapping nodes apart (+3px padding) and clamps every node inside the
  `BOUNDS_HALF_EXTENT` world box; spring/repulsion/gravity constants centralized
  in `GraphPhysicsConfig` (single source of truth, no magic numbers).
- **Low-RAM fallback** — `DeviceCompatibilityManager.getDeviceTier` selects a
  `GraphTierProfile` (iterations + node cap); `GraphTierSelector.cullToCap` keeps
  the most-recent N pages BEFORE layout on low-end devices. The reduction is
  **surfaced** in a dismissible chip ("Showing the N most recent notes for this
  device's memory" / "Reduced physics for this device") and in the top bar text —
  never silent (AGENTS.md hardware rule).

Layout is computed **once** off the main thread (deterministic); the UI only tweens
`Animatable` 0→1 (900 ms) from the orbital ring to the settled layout, then goes
idle — far cheaper than the previous per-frame N² sim. Re-run physics (⚡) and
reset view (⌖) live in the top bar.

### 2. Global Command Palette HUD
- `ui/components/CommandPaletteOverlay.kt` — a global quick-switcher dialog over
  the cached decrypted corpus + tag filter chips + plugin quick actions.
- **Gesture**: two-finger swipe down anywhere in the unlocked vault
  (`MainActivity.detectTwoFingerSwipeDown`, 90px downward / <220px x-drift) —
  plus a discoverable keyboard icon in the Home top bar (`HomeScreen.kt`,
  contentDescription "Command Palette (two-finger swipe down)").
- Searches the existing `NoteRepository.cachedCorpus()` decrypted cache
  (never a per-keystroke decrypt), reuses `WikiLinkParser` epoch-cached tag
  hierarchy, and runs plugin actions through `PluginManager`
  (`NoteflowViewModel.commandPaletteSearch` / `runPaletteAction`).
- **Keyboard-aware**: text field autofocus, ArrowUp/Down highlight + Enter
  invoke + Escape close via `onPreviewKeyEvent`; results list scrolls to the
  highlight. Touch: tap a row.
- **Debounced** input (250 ms) on the IO dispatcher — never blocks the UI thread.
- Zero network, zero new permissions, no new DB schema, respects reduce-motion.

### 3. ViewModel / repository glue
- `NoteRepository.currentSearchCorpusGeneration` (monotonic counter bumped on every
  mutation/lock/re-key) — the palette index keys off it, so a stale cached index is
  rebuilt exactly when the corpus changes and never re-scanned per keystroke.
- `NoteflowViewModel.buildPaletteIndex/commandPaletteSearch/runPaletteAction` +
  `invalidatePaletteIndex`.

## Layout-math evidence (deterministic, pure-JVM tests)

`services/graph/GraphLayoutMathTest.kt` (21 tests) + `CommandPaletteMathTest.kt` (16 tests):

- Repulsion separates coincident nodes without producing non-finite velocities
  (MIN_DIST_SQ floor verified).
- Spring pulls nodes 2000px apart below 700px toward the 140px rest length.
- Collision resolution separates overlapping pairs to ≥ `(r1+r2)/2 + 3` and
  never disturbs an already-separated pair.
- Clamping pins all nodes inside the `±900` world box.
- Layout is byte-identical for identical inputs (determinism), reports progress.
- Clusters: shared tag → same cluster; wikilink-only pair → same cluster; isolated
  page → own cluster; cluster numbering independent of input order.
- Cull keeps the most-recent N pages; tier selection honours low-end + node count.
- Palette: score tiers (prefix > contains > tag > body), case-insensitive,
  deterministic ordering (score → updatedAt desc → id asc), AND/OR tag
  combination, body snippets around the hit, maxResults cap.
- Action routing: bare keyword, `kw: arg`, `kw arg`, `kw\targ`, longest-keyword
  wins, null for non-keyword/blank.

## Perf numbers (phase38-bench captured to `app/build/phase38-bench.txt`)

| Measurement | Value |
|---|---|
| `rank` of a 1,500-doc corpus (NoteRepository cache ceiling), avg over 5 runs | **2.86 ms** |
| Keystroke → first result (250 ms debounce + rank) | **~253 ms** |
| Force-directed layout, 240 nodes × 60 iterations (once, off main thread) | **31.9 ms** |
| Layout 120-node LOW_END tier (35 iterations) | ~7 ms (extrapolated, O(n²)·iter) |
| Layout 400-node DEFAULT tier (90 iterations) | ~134 ms (extrapolated, off-thread, one-shot) |

The palette ranking is comfortably sub-3ms on a 1,500-note vault; the debounce
dominates and keeps IO on one shot per settled keystroke. The graph layout is a
one-shot off-thread cost that never repeats during pan/zoom.

## Gesture discoverability

- Two-finger swipe down anywhere in the unlocked vault (documented in the palette
  header row: "two-finger swipe down to open").
- Keyboard icon in the Home top bar (with the gesture in its content description).
- In-palette hint bar: "Tip: #tag filters combine with your query · actions run
  installed plugins only".

## Constraints honoured

- No new permissions (no `INTERNET`, no sensors). No DB schema change. No network
  for graph or palette. `.github/workflows/` untouched. FLAG_SECURE + ClipboardGuard
  untouched. Reduce-motion respected (pulses + tween disabled). Node/edge drawing is
  pure Compose primitives (no image assets). No decrypted content is ever logged.
- Low-end fallback is explicit and user-visible (never silent degradation).

## Docs touched
- `docs/ARCHITECTURE.md` — knowledge-graph + palette sections updated with Phase 38
  anchors.
- `docs/phase-status.md` — added phase-38 row.