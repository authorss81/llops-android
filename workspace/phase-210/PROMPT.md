# Phase 210: Knowledge Graph Depth — Neighborhood Focus, Search Auto-Pan, TalkBack Access [UX/A11Y]

**Goal:** Make the flagship graph feature explorable and screen-reader reachable. All anchors verified.

1. **Neighborhood focus is absent although the filter service ALREADY exists unused.** `GraphSubgraphFilter` imported at `KnowledgeGraphScreen.kt:56` — never referenced; node interaction is tap=select / tap-again=open only (`:466-492`); big vaults render an unreadable hairball.
   **Fix:** on node selection apply `GraphSubgraphFilter` to keep selected node + N-hop neighbors visible and dim the rest via the EXISTING dimming pipeline (`:523-571`); add Focus/Clear-focus buttons to the selected-node card (`:650+`); persist hop count (default 1) in SettingsManager.

2. **Graph search only recolors matches** (`:530-533`, `:567-568`) without navigating.
   **Fix:** auto-pan (`panOffset`) to the top match when results change; Enter cycles matches; pure-JVM pan-target computation + test.

3. **The canvas is invisible to TalkBack.** Raw `Canvas` + `pointerInput`, zero semantics (`:460-502`); repo-wide exactly ONE `.semantics{}` exists (`AnnotationCanvas.kt:4893`); `BacklinksInspectorBottomSheet` imported at `:61` but never composed here.
   **Fix:** overlay invisible semantics nodes for the top-N (50) settled-layout nodes: "Note <title>, <k> connections, double-tap to open", focusable in stable layout order; decorative Canvas marked non-focusable; surface Backlinks sheet entry from the selected-node card. Partial coverage acceptable v1 — document the cap.

## DoD
`gradle assembleDebug` green; `testDebugUnitTest` green incl. subgraph-filter wiring pins + pan-target tests; `workspace/phase-210/REPORT.md` documenting the TalkBack traversal order + focus behavior walkthrough. No schema change, no workflow edits.
