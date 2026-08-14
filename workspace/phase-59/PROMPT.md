# Phase 59: B1-PLAT-3 - Whole-vault exports (Obsidian .zip / HTML site .zip)... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-PLAT-3, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-PLAT-3` (MEDIUM)
- **Area:** Batch 1 - Android platform surface
- **Evidence:** `HomeScreen.kt:479-489` (onExportObsidianVault -> plaintext .md vault zip), `HomeScreen.kt:490-500` (onExportHtmlVault -> plaintext HTML zip), both to `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`; `PsdExportService.kt:95-102` (rendered ink layers copied to public Downloads); `HomeScreen.kt:451-475,1191-1202` (backup archives also land in public Downloads)
- **Exploit scenario:** One tap writes the ENTIRE vault in decrypted plaintext into world-readable shared Downloads with no password, no confirm dialog, no 'unencrypted' warning. Any app with storage permission, an MTP/USB computer, or anyone picking up an unlocked device reads every note. The files persist after the vault is cleared.

## The fix (where & how)

`HomeScreen.kt:451-500,1191-1202` - show a bold pre-export warning that exports are unencrypted and land in public storage (suggest transfer-then-delete); better, write exports via Storage Access Framework (`ACTION_CREATE_DOCUMENT`) so the user consciously picks the destination, keeping everything else in `filesDir`/`cacheDir`.


## Verification

- Manual + unit verification: exports require consent and either go through SAF or remain in app-private storage; assert no plaintext vault lands in Downloads without a user action. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-PLAT-3 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-59/REPORT.md` committed: what changed (file:line), the
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
