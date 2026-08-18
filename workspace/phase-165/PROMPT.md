# Phase 165: Beautify the Gallery page thumbnails (square box looks bad) [NOT STARTED]

You are working on **InkFlow/Noteflow**. User feedback: in the Gallery view, the
pages' square box/thumbnail does not look good — make it beautiful.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## Context
- `ui/components/GalleryView.kt` renders the gallery: `LazyVerticalGrid` with
  `GridCells.Adaptive(minSize = 160.dp)`, `GalleryCardItem` per page.
  Currently the card is a plain square box (flat background + title).
- There is NO real canvas thumbnail image today — pages are stored as
  strokes/`pointsJson` + text. Do NOT invent image generation; build a beautiful
  card from what exists (title, preview text, tag chips, pinned state via
  `Icons.Outlined.PushPin`, date).

## Definition of done (make it beautiful — but tasteful, Material 3)
- Redesign `GalleryCardItem`:
  - Rounded corners + tonal surface (surfaceVariant / primaryContainer hint),
    subtle elevation, consistent 16:10-ish ratio or adaptive height — no more
    flat square box.
  - Show a rich preview: first ~2 lines of page text (ellipsized), title
    (overflow ellipsis), date, pinned indicator, tag chips (max 2-3).
  - Pressed/ripple + a visible selection state if the app has multi-select
    (check for existing selection UI first).
- Keep `LazyVerticalGrid` layout but tune spacing/grid cells so the cards look
  balanced on phones AND tablets (adaptive).
- Respect phase-127: normal typography everywhere — no exaggerated text styles.
- Respect low-end/2-core device constraints (no heavy shadow/blur layers).

## Definition of done
- Gallery looks clearly better (before/after description + screenshots NOT
  required, but describe the visual change in the REPORT).
- Empty text page still shows a graceful card (placeholder).
- `workspace/phase-165/REPORT.md` describes the redesign and any layout
  decisions (spacing, ratio, overflow handling).
- Existing `GalleryView` API (pages, viewModel, onOpenPage, modifier) unchanged
  so callers (HomeScreen pageViewMode=1) keep working.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. Do NOT add new dependencies.
- No image/bitmap generation of canvas strokes — preview from text/title only.
- Respect AGENTS.md hardware-reality rule (graceful on low-RAM devices).