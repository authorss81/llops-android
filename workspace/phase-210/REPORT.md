# Phase 210 — Report: Knowledge Graph Depth (Neighborhood Focus, Search Auto-Pan, TalkBack Access)

**Date:** 2026-08-25 · **Scope:** `KnowledgeGraphScreen.kt`, two new pure-JVM graph policies,
one SettingsManager pref, three new test classes. **No schema change, no new deps,
no `.github/workflows/` edits, no base-APK-size impact.**

## 1. Neighborhood focus (the dead `GraphSubgraphFilter` import is now live)

* New `services/graph/GraphNeighborhoodFocusPolicy.kt`: pure-JVM BFS from the selected
  node over the undirected edge list (`focus()`), hop depth sanitized into
  `[DEFAULT_HOPS=1 .. MAX_HOPS=3]`, plus a defensive `MAX_FOCUSED_NODES=400`
  frontier cap with a deterministic lexicographic trim so a hub node can never
  re-create the hairball. The focused EDGE set is scoped through
  `GraphSubgraphFilter.edgesWithin` — the same both-endpoints-inside rule the
  notebook subgraph view uses — which is what turns the previously
  imported-but-never-referenced service into a live dependency
  (`GraphNeighborhoodFocusPolicy.kt:118`).
* Screen wiring (`KnowledgeGraphScreen.kt`): `focusResult` is computed per
  `(selectedNodeId, graphEdgeRefs, focusEnabled, focusHops)`. In focus mode:
  * nodes outside the neighborhood ride the EXISTING dimming pipeline —
    `pageFiltered` now returns `isFilteredOut(page) || outOfFocus(id)`, so they fade
    to the same alpha as tag-filtered nodes (0.12 fill / 0.06 edges) and their labels dim too;
  * only the focused edges are drawn at all (`drawnEdges = focusResult?.focusedEdges ?: graphEdgeRefs`);
    cross-boundary links drop out exactly like cross-notebook links do in a notebook view;
  * the selected node itself is always in the focus set (BFS includes the center).
* The selected-node card gained a scrollable control row: **Focus** engages,
  **Clear focus** disengages, and when engaged three FilterChips pick the radius
  ("1 hop"/"2 hops"/"3 hops"). Hop choice persists instantly via
  `SettingsManager.graphFocusHopCount` (pref key `graph_focus_hop_count`,
  sanitized on read AND write). Whether focus is ON is session state by design:
  it stays sticky across selections (exploring a chain of notes re-computes the
  neighborhood per tap without re-dimming everything), deselecting (tap on empty
  space) lifts the dim while keeping the mode armed.
* Focus composes with the tag filter (both predicates feed one verdict) and is
  purely visual — no DB, no physics relayout.

## 2. Search auto-pan + Enter cycling

* New `services/graph/GraphSearchMatchPolicy.kt`:
  * `orderedMatches` — case-insensitive contains over titles, ranked
    prefix > word-start > elsewhere, tie-broken by id (deterministic traversal);
  * `panToCenter(worldX, worldY, viewportW, viewportH, zoom)` — solves the
    screen's own graphicsLayer identity
    `screen = center + (world − center)·zoom + pan` for `screen(world) = center`
    → `pan = (center − world)·zoom`; degenerate inputs (zoom ≤ 0, NaN/Inf,
    empty viewport) fail safe to `(0,0)`; |pan| > 10⁷ collapses to zero so a
    hostile layout can't fling the canvas out of float precision;
  * `nextIndex` — wrap-around Enter cycle, safe on empty lists.
* Screen wiring: matches recompute on query/node changes; editing the field
  resets the cycle index; `LaunchedEffect(activeMatchId)` pans the canvas so the
  active match lands centered (runs ONLY on match change — user zoom/pan is never
  fought). Enter / numpad-Enter on the search field cycles via `onPreviewKeyEvent`
  (consumed, so no newline is inserted); a `supportingText` line shows
  "Match k of n · Enter for next" when there is more than one hit; the active
  match draws a crisp tertiary ring on top of the soft match halo.

## 3. TalkBack access

* **Decorative Canvas emptied**: the raw `Canvas` (dots + drawn text labels, no
  real semantics) now carries `.clearAndSetSemantics { }`.
* **Invisible semantics overlay**: `GraphSemanticNodeOverlay` composes one Box per
  annotated node carrying ONLY `.offset { } .size() .semantics { }` — deliberately
  NO pointer-input modifier of any kind, so touches pass straight through to the
  gesture canvas underneath while TalkBack can still focus each target and its
  double-tap fires the announced click action. Position math mirrors the canvas
  transform exactly (`viewportCenter + (world − viewportCenter)·zoom + pan`) with
  the zoom/pan/settle-progress reads DEFERRED inside the offset lambda, so panning
  and the settle tween reposition targets during layout without recomposing the
  overlay stack; bounds size tracks composition-time zoom with a 24 dp floor so a
  far-zoomed-out node stays reachable. Content description is exactly
  `Note <title>, <k> connections`, role Button, click-action label "Open note"
  → TalkBack reads "Note Alice's draft, 5 connections … double-tap to open note".
  Activating opens the page directly (and selects it first, matching touch UX).
* **Coverage cap (documented v1 constraint)**: `SEMANTIC_NODE_CAP = 50` — the
  50 most-connected settled nodes get mirrors, then the list is RE-SORTED by
  (title.lowercase, id) so traversal order is stable across physics relayouts.
  Nodes beyond the cap are not screen-reader-reachable in v1; the search field +
  auto-pan is the intended navigation path for them (search matches are ranked
  and Enter-cycled regardless of the cap).
* **Backlinks surfaced**: the selected-node card gains a Backlinks button that
  composes `BacklinksInspectorBottomSheet` (import previously present but never
  composed here); opening a backlink dismisses the sheet and navigates.

## 4. TalkBack walkthrough (traversal order)

With a loaded vault, TalkBack reaches the graph screen as follows:

1. Top app bar: Back → "Re-run physics layout" → "Reset View" (standard M3 order).
2. Search field ("Search nodes in graph..."), then any visible tag filter chips
   (composition order), then the canvas region.
3. Inside the canvas region the decorative Canvas contributes nothing
   (cleared); TalkBack then walks the overlay targets in COMPOSITION order =
   title-alphabetical order of the capped 50-node set (ties broken by page id),
   NOT spatial order — spatial swipe order would thrash every physics relayout.
   Each target announces "Note <title>, <k> connections"; double-tap opens.
4. Low-RAM notice (when shown), then the selected-node card controls
   (Open Note, Focus/Clear focus + hop chips when armed, Backlinks).

Focus behavior walkthrough: select a hub node → card shows "Cluster N · tap again
to open note" → tap Focus → non-neighbors fade, boundary edges vanish, subtitle
becomes "Focused on k connected notes." and hop chips appear → tap another node →
neighborhood recomputes around it (mode stays armed) → tap Clear focus (or empty
space) → full graph returns. Hop chips persist across sessions via prefs.

## 5. Verification

* `gradle :app:testDebugUnitTest`: 3004 tests, 4 failures — ALL pre-existing /
  environmental, reproduced independently: `Phase148UiFailureTextScrubTest`
  (UNC-path, reproduced on clean stashed HEAD), `WikiLinkParserCacheUnitTest`
  (documented timing flake — passes in isolation with this diff applied),
  `PaparazziSmokeTest ×2` (layoutlib env, passes in isolation).
* New tests: `GraphNeighborhoodFocusPolicyTest` (14), `GraphSearchMatchPolicyTest`
  (13), `Phase210GraphDepthPinsTest` (12 source pins incl. the subgraph-filter
  wiring pin and the no-pointer-input a11y-target pin).
* `gradle :app:assembleDebug` green.
