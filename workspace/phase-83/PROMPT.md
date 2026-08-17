# Phase 83: B2-DOS-07 - Backup export builds the ENTIRE vault (whole DB +... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-07, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-07` (MEDIUM)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `ImportExportService.kt:1154-1177` (exportBackup zips DB + imports into a ByteArrayOutputStream, then `baos.toByteArray()` at :1176 - the entire backup in heap), `ImportExportService.kt:1188-1190` (`cipher.doFinal(zipData)` duplicates it for the v2 password path), `ImportExportService.kt:1210-1211` (device-keyed path: `encrypt(zipData, key)` -> Base64 - ~1.37x amplification)
- **Exploit scenario:** A vault reaching a few hundred MB makes every 'Create backup' a ~600MB+ peak-allocation operation on the IO thread -> crash, recurring, potentially aborted mid-write.

## The fix (where & how)

`ImportExportService.kt:1154-1211` - stream the zip to the temp file (zip incrementally via `ZipOutputStream(FileOutputStream)`), then encrypt the file streaming (file-to-file / CipherInputStream) or at least chunk it; never hold two full copies of the vault in heap.


## Verification

- Unit test (or documented memory measurement): backup of a large synthetic vault completes with bounded peak heap (no full byte[] of the archive). `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-07 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-83/REPORT.md` committed: what changed (file:line), the
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
