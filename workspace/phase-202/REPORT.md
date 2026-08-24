# Phase 202 REPORT — Bug-Fix Batch: Mirror + Import + Backup + FD Leak

**Date:** 2026-08-24 · **Source audit:** `docs/report-2026-08-24.md` §6 (findings verified against `main@b0fee7d` before any code changed) · **Prompt:** `workspace/phase-202/PROMPT.md`

**Verification:** `gradle assembleDebug` green · full `gradle testDebugUnitTest` **2718 tests / 3 failures, all reproduced on a clean stash of this phase's diff** (`Phase148UiFailureTextScrubTest` UNC-path — the documented pre-existing failure; `PaparazziSmokeTest` ×2 layoutlib env — also fails identically on untouched HEAD, matching the phase-201 note). 25 phase-relevant tests green (12 new `Phase202BugFixBatchTest`, 13 updated `SymmetryHelperTest`). No DB schema change. No new dependencies. `.github/workflows/` untouched.

---

## Bug 1 (HIGH) — Mirror only works on page 0 — FIXED

**Audit claim → verification at HEAD:** CONFIRMED, mechanism exactly as audited.

* `AnnotationCanvas.kt` stores stroke points in **world** coordinates (capture coerces y into `[pageTopY, pageTopY+pageHeight]`, see the drag handlers).
* The page-local bitmap-cache recording ran
  `drawStrokeWithSymmetry(stroke, offsetY - pageTopY, m, symmetryCenterX, symmetryCenterY - pageTopY)`
  i.e. it converted the **axis centre to page-local** while [mirrorPoint] still mirrored the **raw world points** (the `-pageTopY` translation happens later, inside `drawSingleStroke`). Mirror-then-translate ≠ translate-then-mirror when only ONE side is converted.
* Numbers (audit's example, stride 1592): page 1 point `y=1692`, local centre `764` → mirrored `2·764−1692 = −164` → drawn at `−164 − 1592 = −1756`: off-bitmap. World centre `2356` → `3020 → local 1428`: on-page. Page 0 has `pageTopY = 0`, so local == world and the bug is invisible there — "mirror only page0".
* VERTICAL mode was never affected (x does not shift between world/local) — only HORIZONTAL/RADIAL broke, on pages ≥ 1, in both cache paths (no-layers `AnnotationCanvas.kt:3635`, per-layer `:3768`).

**Fix (2 lines + rationale comments):** both cache-recording sites now pass the **world** centre:
`drawStrokeWithSymmetry(stroke, offsetY - pageTopY, symmetryMode, symmetryCenterX, symmetryCenterY)`
The live-preview overlay, eraser hit-test and non-cached draw paths already used world centres — everything now agrees in one space.

**Regression tests** (`SymmetryHelperTest`, +5): page-1 HORIZONTAL mirror stays on-page with the world centre; the pre-fix local-centre argument provably violates the translate/mirror identity and lands off-bitmap; RADIAL contract; VERTICAL insensitivity documented; and a pages 0–3 × {HORIZONTAL, RADIAL} identity sweep (`mirror-then-translate == translate-then-mirror`) that fails if either render path ever drifts again. Source pins in `Phase202BugFixBatchTest` assert both call sites and forbid the old `symmetryCenterY - pageTopY)` form from returning.

**Repro before/after:** 2-page note, continuous + divided mode, HORIZONTAL or Point symmetry → draw mid-page-1: BEFORE the reflected ink was missing/off-screen while page 0 mirrored fine; AFTER page 1 mirrors about its own centre (pinned numerically by the tests above; CI has no device).

---

## Bug 3a→ first in fix order per prompt — Backup locked/rotation

### DEK poisoned by a mid-export auto-lock — FIXED (real, and slightly different shape than audited)

The audit said "keep pinnedDek through picker". At HEAD the picker itself needs no key (the archive is fully built BEFORE the SAF picker opens), but the underlying hazard is real one step earlier: `HomeScreen.kt:697/1589` hand `viewModel.repository.encryptionKey` — the **live** `VaultKeyHolder.dek` array — into `exportBackup`, and `VaultKeyHolder.zeroize()` FILLS that array with zeros in place. An auto-lock landing anywhere inside a long export zeroized the prune key AND (password path) the `encryptAad(key, …)` wrap key AND the device-keyed stream key mid-run → confusing failure / poisoned follow-up attempt.

**Fix:** `ImportExportService.exportBackup` now snapshot-copies the handed DEK at entry (`val key = vaultDek?.copyOf()`, parameter renamed to `vaultDek`; body moved verbatim to `exportBackupInternal`) and zeroizes the copy in a `finally`. Lifetime bounded to the export run — preserves the lock-time zeroization discipline; protects ALL producers (home backup, password backup, WebDAV, LocalSend). Phase-189 pinned strings (`pinnedPruneDek(key) { VaultKeyHolder.dek }`, `pruneStagedSnapshotVersions/Layers(stagedDb, pruneDek)`, `ExportSessionPolicy.zeroize(pruneDek)`) are intact, so the phase-149 source pins still hold.

### SaFExporter `rememberSaveable File` rotation crash — NOT REPRODUCIBLE; deliberately NOT "fixed" (no code change)

`SaFExporter.kt:80`. Two audit claims checked:

1. *"File is not Parcelable → IllegalArgumentException on rotation"* — wrong: `java.io.File` implements `Serializable`, which Compose's default save-checker accepts, so `rememberSaveable` saves/restores it fine. No crash exists at HEAD.
2. *Fix = change to `remember`* — that would REVERT the phase-141 review fix, which made these states saveable **on purpose**: a rotation/recreation while the SAF picker is open must keep the staging-cleanup contract alive (pending file still gets deleted/kept correctly). Reverting reintroduces the leak phase-141 fixed.

Documented here as an audit false positive; no change made.

### WAL race `KEEP_CHANGING_ERROR` — MITIGATED (retries + backoff, the prompt-sanctioned option)

`VaultSnapshotCopyPolicy.kt`: `MAX_VERIFY_ATTEMPTS` 3 → **5**, plus bounded inter-attempt backoff (`DEFAULT_RETRY_BACKOFF_MILLIS = 150L`, sleeps only BETWEEN attempts, `retryBackoffMillis=0` disables for tests). A ~500 ms autosave burst now finds the DB settled well before attempt #5. Tests: budget/backoff constants pinned; a writer that holds the DB busy for FOUR attempts now succeeds on #5 (pre-fix budget could not); perpetual mutation still fails closed after exactly 5 tries with no torn staging. All phase-137 pins preserved. (A global write fence would be an architecture change and was NOT done.)

---

## Bug 2 — Import creates nothing / blank pages

### PDF multi-page split renders slice 0 everywhere — FIXED (root cause; no schema change)

At HEAD the split path had no `pageIndex=0` param to fix (the audit line predates the current code): it created N page rows ALL pointing at the same full multi-page PDF, and since `NotePageEntity` has **no per-source-index column**, every created page rendered PDF page 1 in the editor ("Page 3" showed slice 0).

**Fix:** each split page now gets its OWN rasterized slice — `ImportExportService.renderPdfPageToPngFile(context, pdf, i, baseTitle)` writes `imports/<base>_p<i+1>_<token>.png` (1080×1528, PNG-compressed, bitmap recycled), and HomeScreen creates the page as a standalone `"image"` page. Correct slices everywhere the image pipeline already works (editor, export, thumbnails), deleting one split page can no longer delete the shared source under its siblings, and once all slices commit the consumed source PDF is deleted so backups stop packing dead bytes. Safety/honesty: `PDF_SPLIT_MAX_PAGES = 50` cap with a visible "first N of M pages" notice; any render failure stops the split with the fixed unreadable-PDF notice instead of half a note set; every slice is orphan-tracked until its row commits (phase-96 policy reused); zero slices ⇒ tracked artifacts swept. Repro: 3-page PDF → Split import → BEFORE: three notes all showing page 1; AFTER: notes show slices 1/2/3.

### Vault locked mid-import swallowed — FIXED at the loop level (+ honest per-entry guard)

`addPage` at HEAD is already lock-guarded internally (B2-UI-1 review-fix shows a notice and skips), so the audit's exact crash path doesn't exist — but the loop around it was still dishonest: the html/zip `runCatching` blocks silently swallowed EVERYTHING (including `VaultLockedWriteException`), plain branches counted locked-away pages as imported, and any other exception escaped `vaultScope.launch` uncaught. Fixes:

* Fail-fast gate per URI: `repository.encryptionKey == null` → skip with the fixed `UiFailureTextPolicy.IMPORT_LOCKED_TEXT`.
* Per-entry `try/catch` around the whole chain: `PdfImportException` → fixed skip text; `CancellationException` rethrown (never swallowed); anything else → classified via the fixed-text policy (`importSkippedMessage` gained locked/unreadable-PDF classifiers; raw `e.message` can never reach UI) + artifact sweep; remaining selected files continue.
* html/zip `getOrElse` now rethrows real failures to that guard instead of returning silent null/0.

### Corrupt-PDF errors propagate — FIXED

`getPdfPageCount` no longer returns a silent `0` for an unreadable document: missing/corrupt/encrypted PDFs throw the new `ImportExportService.PdfImportException` (an `IOException`), which the import loop turns into the fixed skip notice — no more blank template-only page masquerading as an import success. `renderPdfPageToBitmap` keeps its null-on-failure render contract (editor/export callers fall back to paper — rendering one bad page must not fail an export).

---

## Bug 4 (LOW) — PdfRenderer/PFD FD leaks — FIXED everywhere

All five open sites now close descriptor/renderer/page via `use{}` (exception-safe on every path):

| Site | Note |
|---|---|
| `ImportExportService.getPdfPageCount` / `renderPdfPageToBitmap` | the audited leaks |
| `EditorScreen.renderPdfPageToRawBitmap` | runs per visible page in continuous mode — worst offender; `BitmapPool.acquire` moved inside the page `use{}` |
| `EditorScreen` private `getPdfPageCount` | duplicate helper |
| `HomeScreen` landscape detection block | same pattern |
| `DocumentTextExtractor.extractText` | same pattern |

---

## Test summary

* New: `Phase202BugFixBatchTest` (12) — policy behaviour (5-attempt budget, late-settling writer succeeds, fail-closed exhaustion), fixed-text classification (PDF-unreadable, vault-locked), and source wiring pins (world-centre mirror call sites ×2, `PdfImportException` propagation, standalone-slice split import, lock gate + catch ordering incl. cancellation passthrough, exportBackup DEK pin + zeroize, backoff-between-attempts placement, `use{}` coverage across all five sites).
* Extended: `SymmetryHelperTest` 8 → 13 (page-1 mirror regression suite above).
* Full suite: 2718 / 3 failed — **all three reproduced on a clean stash** (Phase148 UNC-path; PaparazziSmokeTest ×2 layoutlib env, also failing on untouched HEAD).

## Honest deviations from the prompt

1. **SaFExporter `rememberSaveable` → `remember` NOT applied** — the claim is a false positive at HEAD and the requested change would regress phase-141 (evidence above).
2. **Split-import fix rasterizes instead of "pageIndex=i"** — no index column exists to pass; rasterization achieves the DoD ("3 pages correct slices") without a schema change.
3. **Write fence not implemented** — prompt offered "increase retries OR write fence"; retries+backoff chosen as the non-architectural option.
