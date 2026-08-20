# Phase 178 — Reference-image layer — REPORT

Closed 2026-08-20. ROADMAP Phase-07 encouraged item ("reference image layer
... absent" per `docs/phase-status-gaps.md`) now **shipped**: a photo can be
inserted as a **dimmed, non-inking underlay** on the canvas — strokes draw
OVER it and never modify it.

## Design

### Storage (per-page, no schema change)
- The underlay persists as a single `media_embeds` row with
  `typeName = 'REFERENCE_IMAGE'` (`MediaEmbedType.REFERENCE_IMAGE`, appended at
  the enum's end so existing row ordinals/typeName strings are untouched).
- **Geometry** (`x/y/width/height`, `pdfPage`) lives in the row's plain columns,
  exactly like a `PHOTO` embed.
- **Opacity** lives in the **field-encrypted** `textContent` column via the new
  pure-JVM `services/ReferenceImagePolicy.kt` wire format
  `{"opacity":<0.30..0.50>}` (`encodeConfig`/`decodeOpacity`, `clampOpacity`
  collapses NaN/±Inf to the default). A captured/previewed vault can never leak
  the underlay config in the clear, and a corrupted stored value can never render
  a full-strength or NaN underlay.
- DAO: `MediaEmbedDao.getReferenceImageForPage` / `deleteReferenceImagesForPage`
  (`Daos.kt`), both scoped by `typeName = 'REFERENCE_IMAGE'`.
- Repository: `NoteRepository.getReferenceImageForPage` (decrypts the config via
  `decryptFieldForDisplay`; locked-vault/failed decrypt falls back to the
  in-range default opacity) and `saveReferenceImageForPage` (delete-then-insert
  inside one transaction, fails closed via `requireEncryptionKey`).

### The two invariants that keep page saves from erasing it
1. `getCanvasItemsForPage` **excludes** `REFERENCE_IMAGE` from the draggable
   embed set (`NoteRepository.kt` `else if (embed.type != MediaEmbedType.REFERENCE_IMAGE)`).
2. `saveMediaEmbedsForPage` (delete + reinsert used by every editor flush,
   autosave, dispose and layer/sticky/embed change) **captures the reference row
   BEFORE the delete and re-inserts it as a RAW entity pass-through** (its
   `textContent` is already-encrypted config — re-encrypting it would double-gauze
   the ciphertext). Covered in the empty-embeds branch too.

### Rendering order (below ink, never an inking target)
`AnnotationCanvas` now accepts `referenceImage` (ImageBitmap), `referenceImageOpacity`
and geometry/world-page params. A private `DrawScope.drawReferenceImage` paints a
plain `drawImage` (alpha = `clampOpacity`). It is drawn in **all three** render paths:
- **Single page** (`:1601`): above paper/template/page-bitmap, below the ink pass —
  traceable on scanned/PDF-backed pages.
- **Seamless** (`:1682`): above paper/template, below the ink pass.
- **Paginated** (`:1747`): per-page, offset by `pageTopY`, honoring the existing
  B2-DOS-01/B2-DOS-03 viewport culling loop.

The draggable-embed composable (`MediaEmbedType.REFERENCE_IMAGE` branch) is a
no-op — the underlay has no touch target, no drag, no resize, no rotation — and
the hit-test path never enters it.

### Export / back-save exclusion
The only surface that converts canvas embeds into exported artifacts is
`ImportExportService.drawEmbedsAndStickyNotesToCanvas`, which renders **PHOTO
embeds only** (`if (embed.type == MediaEmbedType.PHOTO && ...)`). The markdown
back-save body carries `page.extractedText` only. So the reference image is
never exported into the note body, the HTML/PDF/PNG/WebP/PSD renders, LocalSend,
or the Obsidian vault archive — it is reference-only, as the phase spec requires.

### Path confinement (B1-AUTH-05 contract)
The artwork is saved with `persistFile` into the app-private imports dir and
stored as the **relative file name**. Every read (render decode) and every delete
resolves it through `InlineImagePathPolicy.resolve(relative, importsDir)` — the
same policy as markdown inline images — so a crafted row can only resolve inside
the app-private subtree; absolute or `..`-traversing destinations render nothing.

### UI
`EditorScreen`:
- Overflow menu: **Insert Reference Image…** (SAF `GetContent` picker; bounded
  read via `AttachmentIngestPolicy.boundedReadBytes`; decode via the shared
  `decodeBoundedImage`; centered aspect-preserving fit via
  `ReferenceImagePolicy.fitForPage` against the current page world — the
  background bitmap's aspect or the 1080×1528 default) or **Remove Reference Image**.
- A compact **Reference Image** control card (auto-visible after insert,
  dismissible): opacity `Slider` in the 30–50% range (live persisted through the
  lock-safe VM gate) and **Remove Reference Image**.
- `insertPage` shifts the underlay's page index + world `y` alongside strokes/
  notes/embeds.

### ViewModel
`EditorCanvasData` gains `referenceImage`; `loadEditorCanvasPage` loads it via the
same guarded accessor as the rest of the canvas payload (a lock mid-load degrades
to armed-empty + notice, never a closed-pool crash); `saveReferenceImage` routes
through the `writeGuardedAgainstLock` gate (persist now / deferred-after-unlock,
never a plaintext row).

## Before / after

Before: no underlay — users could only paste photos as draggable cards.
After: one dim traceable underlay per page, stored encrypted, removed via the
controls, excluded from every export.

## Files

- NEW `app/src/main/kotlin/com/authorss81/noteflow/services/ReferenceImagePolicy.kt`
- `data/model/StrokeModels.kt` — `MediaEmbedType.REFERENCE_IMAGE`
- `data/db/Daos.kt` — reference DAO queries
- `data/repository/NoteRepository.kt` — get/save reference, embed-set exclusion, save-preservation
- `ui/components/AnnotationCanvas.kt` — render in 3 paths + no-op draggable branch
- `ui/screens/EditorScreen.kt` — picker, menu items, control card, insertPage shift, confined decode
- `ui/viewmodel/NoteflowViewModel.kt` — `EditorCanvasData.referenceImage` + gated save
- NEW `app/src/test/java/com/authorss81/noteflow/Phase178ReferenceImageUnderlayTest.kt`

## Tests

- `Phase178ReferenceImageUnderlayTest` — **11 tests**:
  6 pure-JVM `ReferenceImagePolicy` (range clamp incl. NaN collapse, wire round-trip,
  corrupt-codec fail-soft, aspect-preserving centered fit, zero-size guard,
  recenter) + 5 source-pins (draggable-set exclusion, save-preservation, export
  PHOTO-only/never-REFERENCE, relative-path + `InlineImagePathPolicy.resolve`
  read+delete + bounded decode + bounded ingest, field-encrypted row).
- `B2Dos05AttachmentIngestTest` source pin updated **4 → 5** `boundedReadBytes`
  sites (the phase-178 picker is the 5th bounded ingest; the pin's intent — every
  picker routes through the bounded reader — is preserved).

## Verification

- `gradle assembleDebug` — **green**.
- `gradle testDebugUnitTest` — **2396 total / 1 failed**: the pre-existing
  `Phase148UiFailureTextScrubTest` UNC-path failure (untouched, reproduced on a
  clean stash; the other phase-178-run failures were the stale B2Dos05 pin, fixed
  here).
- No schema change, no new dependencies, `.github/workflows/` untouched,
  base-APK-size rule intact.