# Phase 202: Bug-Fix Batch — Mirror + Import + Backup + FD Leak [BUGS HIGH]

**Goal:** Fix 3 high bugs found in 2026-08-24 audit that block real-device use.

**Bugs:**
1. **Mirror only page0** `AnnotationCanvas.kt:3144,3259` world vs local centre mismatch → fix `drawStrokeWithSymmetry` to use world `symmetryCenterY` (or convert points to local) + regression `SymmetryHelperTest` page1.
2. **Import blank** `HomeScreen.kt:484` `pageIndex=0` always + `HomeScreen.kt:343` lock swallowed → fix `pageIndex=i` per page + wrap `addPage` in `runCatching` + snackbar locked, `ImportExportService.kt:130` propagate PDF errors.
3. **Backup locked/rotation** `SaFExporter.kt:80` `rememberSaveable File` → `remember`, `HomeScreen.kt:680` keep `pinnedDek` through picker, `VaultSnapshotCopyPolicy.kt:76` increase retries or write fence.
4. **FD leak** `ImportExportService.kt:131,149` `PdfRenderer`/`Pfd` `use{}`.

**Steps:** Fix in order 1→3a→2c→3b, verify on 2-page note continuous mode, short auto-lock + rotation, 3-page PDF import.

**DoD:** Mirror works page2, PDF import shows 3 pages correct slices, backup succeeds with lock+rotation, `gradle assembleDebug`/`test` green, REPORT.md with repro before/after. No DB schema change.
