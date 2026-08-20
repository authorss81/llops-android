# Phase 184 — Inventory: gallery card proportions & the rigid 10:16 aspect ratio

## Scope
Gallery card layout = `app/src/main/kotlin/com/authorss81/noteflow/ui/components/GalleryView.kt`
(`GalleryView` public wrapper + private `GalleryCardItem`). Grid =
`LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 168.dp))` (`:62-77`), horizontal
spacing 12dp, vertical 14dp, 12dp sheet padding. Public API
`GalleryView(pages, viewModel, onOpenPage, modifier)` — consumed exactly once at
`HomeScreen.kt:1343` (page view mode 1).

## The defect (user visual review)
`GalleryCardItem` pins the Card to a fixed portrait ratio
`aspectRatio(10f / 16f)` (`GalleryView.kt:102`):

- At the 168dp min cell width the card height is `168 / (10/16) = 268.8dp`
  regardless of content. A short title + 1-2 preview lines + footer occupies a
  small top band; `fillMaxHeight(0.5f)` wash (`:112-114`) + `weight(1f)` preview
  box (`:182-226`) stretch to fill the rest — the lower 60%+ is dead empty space
  ("empty bookmark" look).
- The ratio is **strict and unyielding**: at accessibility font scales the fixed
  268.8dp height is independent of the text metrics, so the footer (tag chips +
  date, `:230-292`) can be clipped by `Card`'s shape clip if large-font content
  needs more vertical room.

## Layout structure inventory (`GalleryCardItem`, `:80-296`)
| Block | File:line | Current size behavior |
|-------|-----------|-----------------------|
| `Card` (onClick) | `:98-108` | `fillMaxWidth()` + **`aspectRatio(10f/16f)`** — the phase-184 fix target; shape `RoundedCornerShape(20.dp)`; clip follows the card bounds. |
| Wash box | `:111-124` | `fillMaxWidth()` + `fillMaxHeight(0.5f)` + `align(TopCenter)` + vertical gradient `primaryContainer → transparent`. |
| Content `Column` | `:126-130` | `fillMaxSize()` + `padding(14.dp)` — depends on the fixed ratio for its height. |
| Header row (type badge, title, pin) | `:132-177` | Title `weight(1f)`, `maxLines=2` (phase-183: `GalleryTitleDisplayPolicy.displayTitle`, `Hyphens.None` via style, `lineHeight=18.sp`). |
| Preview block | `:182-226` | `Box(weight(1f).fillMaxWidth())` — the flexible spacer that soaks up the dead band; text `bodySmall`, `maxLines=3`; ink-else placeholder centers a 44dp circle + label in the whole remaining height. |
| Footer | `:230-292` | `HorizontalDivider` + `Column` of ≤3 tag chips (`FlowRow`) + updated-date `Row` (`maxLines=1`, phase-183). |
| Card metrics (policy inputs) | — | cell width ≈ 168dp (Adaptive min), 14dp inner padding, `titleSmall` 18sp/18sp lineHeight, `bodySmall` maxLines=3, `labelSmall`/`labelMedium` footer. |

## Accessibility constraint (PROMPT)
Prefer `Modifier.heightIn(min = 180.dp)` over a strict unyielding ratio so large font
scaling does not clip the date/tags. Balanced notebook-like ratio (4:5 or 3:4) is the
preferred option over switching to `LazyVerticalStaggeredGrid`.

## Memory bound (PROMPT) — honored by keeping the plain grid
The phase stays on `LazyVerticalGrid`: filters, `key = { it.id }`, per-row height
equalization and Recycler-style lazy item pool remain intact; no
per-card stroke rasterization (the preview is derived purely from the existing
title/extractedText/tags/date fields, per phase-165 design). A staggered grid would
change recycle semantics and is NOT required to fix the dead band.

## Consumers / regressions at risk
- `HomeScreen.kt:1343` — only GalleryView call site; signature must be unchanged.
- `docs/ARCHITECTURE.md:28-37` (phase-165 note claims "fixed portrait 10:16 aspect
  ratio") and `phase-status.md` phase-183 row prose — stale after this phase; both
  updated in step 3.
- No DB, no schema, no settings surface, no image rasterization involved.