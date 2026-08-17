# Phase 50: B2-DOS-01 - Unbounded stroke geometry: no caps on stroke count or... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-01, HIGH) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-01` (HIGH)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `NoteRepository.kt:443-503` (decrypts + materializes the page's ENTIRE geometry at once), `EncryptionService.kt:127-135` (Gson parse with no caps), `Daos.kt:167` (no LIMIT), `NoteRepository.kt:533-587` (save never caps), `ImportExportService.kt:1414-1429` (restore transplants any `pointsJson` verbatim), `AnnotationCanvas.kt:2610,2217` (renderer walks every point per frame)
- **Exploit scenario:** A crafted backup (B1-DB-7) or organic heavy page carries a stroke whose `pointsJson` encodes ~2M points (~100 MB JSON). On open the app OOMs/ANRs; even if parsing survives, the canvas does O(total-points) work every frame. No size gate anywhere between DB bytes and the composable.

## The fix (where & how)

`NoteRepository.kt:533-587` (cap max points/stroke and max strokes/page at save - e.g. 200k points/page - truncate/reject with a notice), `ImportExportService.kt:1414-1429` (drop oversized rows at restore/import), `Daos.kt:167` + `NoteRepository.kt:443-503` (add LIMIT/lazy paging to `getStrokesForPage`), `AnnotationCanvas.kt:2610,2217` (viewport/zoom culling + off-main raster so point count does not scale per-frame cost linearly).


## Verification

- Unit test: a stroke row above the cap is rejected/truncated at save and stripped at restore; `getStrokesForPage` returns a bounded set. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-01 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-50/REPORT.md` committed: what changed (file:line), the
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
