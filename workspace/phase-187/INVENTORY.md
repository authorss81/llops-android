# Phase 187 — GalleryView ink-note placeholder → authentic notebook-paper card

Inventory of the current ink placeholder in the gallery grid card, read before
making changes.

## User need

Ink canvas notes without extracted OCR text show a generic pencil icon + "Ink &
canvas page" label — an unpopulated placeholder instead of an authentic sketch
page. The ink-note card should look like notebook paper (ruled/dot-grid) so it
reads as a real handwriting page, not a dead stub.

## Current code (`ui/components/GalleryView.kt`)

### Ink placeholder (preview block, `GalleryView.kt:286-328`)

The preview area chooses between text preview and a placeholder:

```kotlin
val preview = page.extractedText?.trim().orEmpty()
if (preview.isNotEmpty()) {
    Text(/* maxLines = 3, bodySmall, onSurfaceVariant */)
} else {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp),          // compact band, font-scale-safe floor
        contentAlignment = Alignment.Center
    ) {
        Column(...) {
            Box(44.dp circle clip, scheme.surface.copy(alpha = 0.6f) bg) {
                Icon(pageTypeIcon(page), tint = scheme.outline.copy(alpha = 0.7f), 22.dp)
            }
            Spacer(8.dp)
            Text(pageTypeLabel(page), labelMedium, scheme.outline)
        }
    }
}
```

Label source (`GalleryView.kt:407-412`):

```kotlin
private fun pageTypeLabel(page: NotePageEntity): String = when (page.sourceFileType) {
    "pdf" -> "PDF page"
    "image" -> "Image page"
    "text" -> "Empty page"
    else -> "Ink & canvas page"          // ← the generic stub label
}
```

Icon source (`GalleryView.kt:400-405`): `else -> Icons.Outlined.Brush` for ink.

### Card background (`GalleryView.kt:118-144`)

- `Card` container: `scheme.surfaceVariant.copy(alpha = 0.55f)`, 20dp rounded,
  3dp elevation.
- Inner `Box` (parent of content) has a `matchParentSize()` sibling Box that
  paints a `primaryContainer.copy(alpha = 0.40f)` → transparent vertical wash
  fading from the top edge.
- No `drawBehind`, no texture, no border (border arrives in phase-188).

### What defines an "ink" page

`Entities.kt:39`: `sourceFileType: String? = null // "pdf", "image", "text", null`.
Anything not in `{pdf, image, text}` (including `null`) is a canvas/ink page.
The same `when` drives `pageTypeIcon`.

## Constraints observed

- No canvas rasterization in grid items (phase-188 risk #1) — the placeholder
  uses only metadata fields.
- Draw path must be cheap: bounded loops (≤ ~96 dots at a 22dp grid), no
  per-frame allocations (`drawBehind`/`DrawScope` primitives only).
- Dark theme: the paper fill (paper-toned `scheme.surface.copy(alpha=0.7f)`)
  must not flatten the card into the near-black surface; the `surfaceVariant`
  container behind it (30% shows through) + the phase-188 card border keep it
  distinct.
- Honest copy: the label must NOT claim OCR text exists. "Handwritten note".

## Plan

1. New pure-JVM `services/InkCardPaperPolicy.kt`: honest label, pattern
   constants (paper bg alpha 0.7, grid alpha 0.3, 22dp spacing, dot radius),
   bounded grid geometry (capped row/col counts), `isInkCanvasPage`.
2. `GalleryView.kt`: for ink pages with no preview text, draw the dot-grid paper
   across the whole card via `drawBehind` (bounded nested loops over the policy
   geometry); keep the small Brush icon + policy label ("Handwritten note").
3. Tests: `InkCardPaperPolicyTest.kt` (pure JVM, bounded geometry + labels) +
   `Phase187GalleryInkPaperTest.kt` (source pins: drawBehind used, policy
   constants referenced, honest label, no pointsJson raster).