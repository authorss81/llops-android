# Phase 178 — Reference-image layer: Step 1 inventory

> Status: NOT STARTED (this phase ships ROADMAP Phase-07 "reference image layer",
> tracked in `docs/phase-status-gaps.md` as an encouraged-but-never-shipped item).
> This inventory commits the file:line map before any code change (workflow rule).

## 1. Canvas layering / composition (`AnnotationCanvas.kt`)

`ui/components/AnnotationCanvas.kt` (5167 lines). The single composable
`AnnotationCanvas` (`:104-105`) draws in one `Canvas` DrawScope whose coordinate
space is the page world space the strokes themselves live in (strokes render at
their raw `x/y`, `drawSingleStroke :3438`). Zoom/pan apply via a `graphicsLayer`
on the Canvas (`:1561-1567`, `scaleX/scaleY/translationX/translationY`,
`TransformOrigin(0,0)`).

Render order inside the `Canvas` body (THREE sibling branches by mode):

1. **Single page** (`!isContinuousMode`, `:1588-1659`):
   `drawPaperCard(:2630)` → `drawPaperTemplate(:2682)` → full-bleed
   `pdfPageBitmaps[pdfPageFilter] ?: backgroundImage` `drawImage` (`:1593-1608`)
   → `drawCompositedLayersStrokes(:2965)` (strokes + layers).
2. **Seamless infinite** (`!divideIntoPages`, `:1660-1715`): same order over the
   computed world `(canvasW, infiniteH)`.
3. **Paginated infinite** (`:1716-1845`): per-page loop with **B2-DOS-01 viewport
   culling** (`:1732-1748` — a page whose slab misses the visible world rect is
   skipped ENTIRELY: paper, template, page bitmap, stroke filter + layer raster)
   over `renderPageCount = dynamicPageCount`, each page again:
   `drawPaperCard(:1751)` → `drawPaperTemplate(:1752)` → page bitmap
   (`:1755-1790`) → `drawCompositedLayersStrokes(:1812)`.

Key drawing helper facts:
- `drawPaperCard` `:2630` — paper round-rect + border + page badge (visual only).
- `drawPaperTemplate` `:2682` — tiled `paperTexture` (`:2693-2711`) then the
  `template` grid/lines/dots/etc dependents (`:2712-2873`).
- `drawCompositedLayersStrokes` `:2965` — per-layer composited rendering through a
  per-page `LayerBitmapCache` (`ui/components/LayerBitmapCache.kt`, 11 lines: an
  `ImageBitmap` + its compose `Canvas` + `Paint` + content `hash`); the AGSL wet
  pass `drawWetLayerPass :3236`; per-stroke `drawSingleStroke :3438`. This is the
  INKING surface — strokes only ever render here (see "no-inking guarantee" in
  REPORT.md).
- `graphicsLayer` usage: canvas transform `:1561`; minimap `:4394`; page-stack
  card `:4725`. `RenderEffect` (hardware blur) is used only via reflection for
  the wet AGSL sigma blur `:3299` (API ≥ 31 guarded). The reference layer is a
  plain `drawImage` with `Paint`-alpha — no RenderEffect needed.
- Floating overlay pass `:1893+` (`DraggableStickyNoteCard`, `DraggableMediaEmbedCard :4657`)
  is a SEPARATE composition layer ABOVE the `Canvas` — the reference image must
  never arrive as one of these (see §3).

## 2. Editor screen CLI surfaces (`EditorScreen.kt`)

`ui/screens/EditorScreen.kt` (5830 lines). Relevant anchors:
- Paper texture picker precedent (`:279-367`): per-page `settings.paperTexturePathForPage`
  + SAF `GetContent` picker, bounded read via `AttachmentIngestPolicy.boundedReadBytes`
  (`services/AttachmentIngestPolicy.kt:88`, 25 MB cap), persisted via
  `ImportExportService.persistFile(context, name, bytes)` (`services/ImportExportService.kt:72`
  → `filesDir/noteflow/imports`, `getImportsDir :58`).
- Photo-embed picker precedent (`:1087-1131`): `photoPickerLauncher` SAF picker →
  bounded read → `persistFile` → `CanvasMediaEmbed(PHOTO, x/y/width/height,
  contentUrlOrPath=savedPath, pdfPage=activePage)` → `handleMediaEmbedsChange` →
  `viewModel.flushEditorPageSave`.
- Top-bar icon buttons `:1227+`; embed menu `showEmbedMenu` `:1317-1378`; **overflow
  menu `showOverflowMenu` `:1386+`** (the "More Options" ⋮ `DropdownMenu` where the
  "Insert reference image" action will live).
- `AnnotationCanvas(...)` call site `:1838-1917` (all params enumerated).
- Canvas page-flush funnel: `disposeEditorPageFlush(page.id, strokes, stickyNotes,
  mediaEmbeds, layers, pending)` `:711`; debounced autosave `:792`
  `viewModel.autosaveStrokes(...)`; `handleMediaEmbedsChange :954`.
- Initial load via `viewModel.loadEditorCanvasPage(page.id)` `:670-690`
  (→ `NoteflowViewModel.loadEditorCanvasPage :4167` → `repository.getCanvasItemsForPage`
  `NoteRepository.kt:1309`, which splits `STICKY_NOTE` vs everything-else).

## 3. Media-embed storage / decode path (the mechanism we reuse)

- Entity `MediaEmbedEntity` (`data/model/Entities.kt:91-110`): columns
  `id, pageId, typeName, x, y, width, height, contentUrlOrPath, textContent,
  codeLanguage, durationMs, waveformJson, pdfPage, rotationDegrees`.
- DAO `MediaEmbedDao` (`data/db/Daos.kt:236-257`): `getMediaEmbedsForPage`,
  `insertMediaEmbeds` (upsert), `deleteMediaEmbedsForPage`, `getAllEmbedsForReencrypt`,
  `updateTextContent`, `updateContentUrlOrPath` (B1-DB-3 legacy voice retarget).
- Field-encryption convention: `textContent` is AES-256-GCM field-encrypted with
  per-record AAD keyed on `media_embeds|<id>|textContent` — `saveMediaEmbedsForPage`
  (`NoteRepository.kt:1365-1401`, encrypt at `:1377`) and read-back decrypt
  `getMediaEmbedsForPage` (`:1262-1297`, `decryptFieldForDisplay :1275`,
  B1-DB-8 UNREADABLE_MARKER honored). `contentUrlOrPath` is an app-private path
  (never note-body content). Row lifecycle: `deletePagePermanently :873-900`
  (audio blobs deleted; PHOTO/STICKER/CODE files intentionally left in place),
  re-key sweeps (`getAllEmbedsForReencrypt`), backup export/restore already pack
  `media_embeds` rows (`ImportExportService.kt:1464-1466` imports tree, restore
  sanitizer `:2865-2875`).
- IMPORTANT (phase-178 constraint): `saveMediaEmbedsForPage` is a
  **delete-then-reinsert of the WHOLE page's embed set** under the per-page
  `pageSaveLocks` mutex (`NoteRepository.kt:1366-1401`). Any state we keep in
  `media_embeds` that is NOT part of `EditorScreen.mediaEmbeds` would be erased by
  every editor flush. The reference-image row must therefore be **re-merged** inside
  `saveMediaEmbedsForPage` (read current reference row → append to incoming set)
  so ordinary saves preserve it. Same consideration applies to `flushPendingEditorSaves`
  `NoteflowViewModel.kt:4066-4093` (it calls `saveCanvasItemsForPage`).
- Decode: `decodeBoundedImage(path, maxDim=1600)` (`ui/components/ImageViewer.kt:43-64`,
  `inSampleSize`-bounded `BitmapFactory.decodeFile`). Photo embed card:
  `ui/components/MediaEmbedComponents.kt` (`:92-97` decodes, `FullscreenImageDialog
  :275`).
- Path confinement: `services/InlineImagePathPolicy.kt` (B1-AUTH-04/phase-68) —
  `resolve(destination, baseDir)` returns a canonical File strictly inside
  `baseDir` or null; absolute paths + `..` segments blocked (`:43-92`). This is the
  policy the reference-image decode reuses so it can only resolve inside the
  app-private imports subtree.

## 4. Confirmation: NO existing reference-image / underlay feature

Repo-wide search for `referenceImage|reference_image|underlay|traceImage|REFERENCE_IMAGE|RefImage`
in `app/src/main/kotlin` → zero matches. `PhotoEmbedCard` (draggable, inking-adjacent,
full-opacity) is the closest analog but is a draggable overlay, never an underlay
below strokes. No DB column / table / setting stores a per-page underlay image.
The phase is genuinely greenfield against the existing embed + path-confinement +
field-encryption plumbing.

## 5. Test conventions to follow

- Pure-JVM decision-table tests live in
  `app/src/test/java/com/authorss81/noteflow/` (e.g. `B1Auth04InlineImagePathTest.kt`,
  `Phase166LayoutOverflowTest.kt`). New policy tests will sit here.
- Source pins in tests assert the exact `file:line` wiring (see
  `Phase166LayoutOverflowTest`, `B2Dos01StrokeGeometryTest` style).
- Build on CI: `gradle testDebugUnitTest` + `gradle assembleDebug` (system gradle,
  no wrapper jar). Do NOT run on the Windows dev box.