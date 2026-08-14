# Phase 38: Knowledge Graph & Spatial Navigation [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with wikilinks/tags/daily notes and a **`KnowledgeGraphScreen`**
(`ui/screens/KnowledgeGraphScreen.kt`) plus a knowledge-graph data layer.
**Read `docs/phase-status.md` first** — do not regress existing graph behavior.

**THE GOAL:** turn the knowledge graph into a **spatial, navigable universe** —
an interactive force-directed graph with clusters and physics, plus a **global
command palette (HUD)** that lets the user jump anywhere in milliseconds — real,
performant, low-end safe (AGENTS.md hardware rule).

## 1. Interactive 2D/3D Force-Directed Graph
- Elevate `KnowledgeGraphScreen`: **cluster grouping** (notes grouped by
  tag/wikilink communities), **tag filters** (show/hide by tag), **particle pulse
  effects on active links**, and **physics-based collision bounding** (nodes push
  apart, edges stay within bounds; spring/repulsion tuned — put constants in one
  place, not magic numbers).
- Keep it performant: node rendering should use Compose drawing with an
  efficient update loop; on low-RAM reduce physics iterations or cap node count
  with a clear, non-alarming notice (never silent degradation).
- Pure-JVM tests: graph-layout math (repulsion/collision/cluster assignment) with
  deterministic inputs.

## 2. Instant Command Palette (HUD)
- Implement a **global Quick Switcher / Command Palette** invoked by a shortcut
  (e.g. a two-finger swipe down, or a FAB/keyboard shortcut where available —
  document the discoverable gesture) for **rapid note searching, tag filtering,
  and running plugin actions** in milliseconds.
- Results from the existing cached decrypted corpus (search) + plugin actions
  (e.g. OCR, web search, dictation triggers) — reuse existing services, no new DB
  schema, no background scanning.
- Keyboard-aware where possible; results list with arrow-key navigation on
  hardware keyboards; debounced input on IO dispatcher (never block the UI
  thread); zero network.
- Pure-JVM tests: result ranking/ordering, tag-filter combination, action-routing
  to the plugin manager.

## Definition of done
- Force-directed graph with clusters, tag filters, link pulses, and collision
  bounding works; low-RAM fallback documented.
- Command palette opens via the documented gesture, searches the cached corpus +
  tags fast, filters by tag, and can trigger plugin actions via `PluginManager`.
- No background scanning, no network, no new permissions.
- `gradle testDebugUnitTest` + `gradle assembleDebug` pass.
- REPORT.md: layout-math evidence, perf numbers (keystroke→result latency),
  gesture discoverability note.

## Constraints
- No new permissions. No DB schema change. Do NOT edit `.github/workflows/`.
- Never log decrypted note content. Keep `ClipboardGuard` + FLAG_SECURE intact.
- No image assets (graph nodes/drawing via Compose primitives).
- No network for graph or palette. Respect reduce-motion for animations.