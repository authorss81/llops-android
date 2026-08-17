# Phase 54: B1-DB-3 - Voice notes are recorded as UNENCRYPTED .m4a files and... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-DB-3, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-DB-3` (MEDIUM)
- **Area:** Batch 1 - Data-at-rest & DB
- **Evidence:** `VoiceNoteManager.kt:65-66` (MediaRecorder writes raw MPEG-4/AAC to `filesDir/voice_notes`), the path in `media_embeds.contentUrlOrPath` is NOT in the field-encryption map (`ImportExportService.kt:1107-1112`) nor in `reencryptPlaintextFields` (`NoteRepository.kt:165-230`), `NoteRepository.kt:422-434` (deletePagePermanently only deletes paths containing `imports/`/`exports/` - voice audio survives), `ImportExportService.kt:1154-1214` (exportBackup packs only DB + imports/, never voice_notes/)
- **Exploit scenario:** Recordings under `filesDir/voice_notes/` are raw audio with zero encryption - a debuggable build's `run-as`/adb or a rooted forensic image yields every private voice memo in cleartext without touching the SQLCipher vault. Deleted pages leave orphaned audio, and no backup includes it - simultaneously unprotected and unrecoverable.

## The fix (where & how)

`VoiceNoteManager.kt:65-66` - encrypt recorded audio at rest: record to a temp, AES-GCM-encrypt with the DEK and store the `.enc` blob (or use an encrypted file provider). `NoteRepository.kt:422-434` - also delete `voice_notes/` files on `deletePagePermanently`/`emptyTrash`. `ImportExportService.kt:1154-1214` - include the encrypted audio in `exportBackup`.


## Verification

- Unit test: a recorded-then-stopped voice note produces no plaintext `.m4a` on disk (only an encrypted blob); page permanent-delete removes the audio file; a backup round-trip carries the audio. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-DB-3 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-54/REPORT.md` committed: what changed (file:line), the
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
