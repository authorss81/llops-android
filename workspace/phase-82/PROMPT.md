# Phase 82: B2-DOS-06 - Multi-layer PSD export materializes N full-page ARGB... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-06, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-06` (MEDIUM)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `ImportExportService.kt:2095-2123` (exportPageToPsd creates one 1080x1528 ARGB_8888 Bitmap per layer ~6.6 MB each, all alive), `PsdExportService.kt:119-190` (every layer's 4 uncompressed channels into `layerPixelBlocks.add(chanBos.toByteArray())` at :190, plus a per-layer `IntArray(width*height)` at :131-132), `PsdExportService.kt:79-89` (composite getPixels). Layer count is unbounded (Layers panel + restored vaults)
- **Exploit scenario:** A ~25-layer note exported as PSD: 25x6.6MB bitmaps + 25x6.6MB channel blocks + composite ~= 350 MB peak heap -> OOM/ANR on 1-2 GB devices, recurring on every export.

## The fix (where & how)

`ImportExportService.kt:2095-2123` - cap export layer count (e.g. 16) with a user-facing notice; `PsdExportService.kt:119-190` - write each layer's channel data straight to the destination stream one channel at a time (drop `layerPixelBlocks`) and reuse a single `IntArray` buffer; or render at a lower resolution for export.


## Verification

- Unit test (or heap-ceiling measurement): exporting a >16-layer page caps at 16 layers with a notice and peak allocation stays bounded. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-06 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-82/REPORT.md` committed: what changed (file:line), the
  checksum/secrets handling, verification output, and any input you judged
  out-of-scope.

## Constraints

- NO DB schema change unless this fix requires one - then a migration-safe note
  in REPORT.md is MANDATORY, and the migration must never delete user data.
- Do NOT edit `.github/workflows/`. Do not add new dependencies unless required
  by the fix (then justify in the commit).
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`,
  `ClipboardGuard`, and FLAG_SECURE intact.
- Do not fix OTHER security findings in this phase - that is a different phase.
  If you find a new related bug, document it in REPORT.md, do not fix it here.
