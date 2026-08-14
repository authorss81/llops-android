# Phase 55: B1-DB-5 - HTML/Obsidian ZIP import reads entries with unbounded... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-DB-5, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-DB-5` (MEDIUM)
- **Area:** Batch 1 - Data-at-rest & DB
- **Evidence:** `ImportExportService.kt:1791-1792` (importHtmlZipOrFolder `zis.readBytes()` per entry), `ImportExportService.kt:1969-1972,1983-1985` (importObsidianVaultZip - unbounded `readBytes()` for every image and every .md, unlimited entry count), reachable from the import picker (`HomeScreen.kt:203-213`) and the exported `ACTION_SEND */*` handler (`MainActivity.kt:95,513-600`). Contrast the restore path's caps in `copyWithLimit` `ImportExportService.kt:1216-1253` (50MB/file, 200MB total, 100x ratio)
- **Exploit scenario:** A crafted zip (nested compression, many large entries) sent via the share sheet or picked from Downloads decompresses megabytes->gigabytes into heap via `readBytes()`, OOM/ANR-crashing the app. The code already defends the backup path but forgets the import paths that consume attacker-controlled archives.

## The fix (where & how)

`ImportExportService.kt:1791-1792,1969-1972,1983-1985` - reuse the `copyWithLimit`-style accounting (per-entry and total caps, expansion-ratio guard) for BOTH import zip readers; also cap the total entry count and the originating `readUriBytes` (`ImportExportService.kt:77-83`) stream size.


## Verification

- Unit test: a zip bomb (high compression ratio, many entries) fails the import with a clean error without growing heap beyond the cap. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-DB-5 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-55/REPORT.md` committed: what changed (file:line), the
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
