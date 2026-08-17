# Phase 154: Knowledge graph & wiki navigation power-ups [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` +
`docs/ARCHITECTURE.md` + `docs/PLUGINS.md` first.** This is a PRODUCT feature
phase (not a security fix). Scope is one coherent slice of the knowledge-graph /
wiki-navigation experience, born out of the round-2 audit's phase-38 success and
the existing `KnowledgeGraphScreen` + `WikiLinkParser` foundations.

## Features (2-3 related, bundle deeply)

1. **Node peek/quick-preview + click-to-open:** Tapping a graph node opens a
   small preview card (title, tags, first lines of `extractedText` decrypted
   only while authenticated) with "Open" + "Copy wikilink `[[title]]`" actions.
   Reuse the existing `onOpenPage(node.page)` flow
   (`KnowledgeGraphScreen.kt:634`) — the preview is an additive layer above it.
   Respect the auth gate (no decrypt when the vault is locked) and never let the
   preview render under the LockScreen (see phase-153's gating pattern).
2. **Per-notebook / cluster subgraph filter:** Beyond the existing tag
   FilterChips (`KnowledgeGraphScreen.kt:386-411`), filter nodes+edges by
   notebook (reuse `_notebooks`/`NoteflowViewModel` state) so a large vault
   doesn't force one 120-400-node cull — the subgraph stays focused. Also show
   an "isolated" toggle (nodes with zero surviving edges).
3. **Backlinks breadcrumb + unlinked-mentions jump:** In the graph and/or note
   preview, show inbound wikilinks as a breadcrumb and jump to "unlinked
   mentions" (pages whose text contains the title but no wikilink) using the
   existing `BacklinksInspector` groundwork (`BacklinksInspector.kt:48-64`).

## UI/UX + plugin ideas

- Long-press a node → context menu (Open / Preview / Copy wikilink / Rename
  via existing `renamePage`).
- A "Graph pulses" use of the existing particle-pulse only under reduce-motion
  OFF (already true — keep it).
- Keep base-APK size: here we need NO heavy native dep — this is pure-Compose +
  existing services.

## Verification

- Unit tests for any new pure-JVM logic (e.g. a `GraphSubgraphFilter` that
  keeps only nodes/edges within a notebook + survivors, nav commands tested with
  a fake `WikiLinkParser`/`BacklinksInspector` seam). Follow repo test layout
  `app/src/test`.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-154/REPORT.md`.

## Definition of done

- All three features shipped with `file:line` evidence, reached from
  `KnowledgeGraphScreen.kt` and wired through `NoteflowViewModel` (real
  `file:line` anchors in REPORT.md). Non-alarming one-time messaging where a
  large vault needs the cull (hardware-reality rule).
- New tests green + no existing test regressed.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log/display decrypted content when the vault is locked. Keep
  reduce-motion + low-end rules intact.
- No network permission additions (everything is local encrypted data).