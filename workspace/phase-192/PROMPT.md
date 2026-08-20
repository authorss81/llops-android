# Phase 192: Fix — voice recording shows "The recording could not be saved securely" and never saves [NOT STARTED]

You are working on **InkFlow/Noteflow**. USER REPORT: voice recording does not work — it
shows "The recording could not be saved securely. Please try again." and the recording is
not saved. That exact string is produced in
`services/VoiceNoteManager.kt:263` when `encrypted == false`, i.e. when
`blobFile == null || dek == null || !VoiceNoteCrypto.encryptRecordingFile(...)`.

Read `docs/ARCHITECTURE.md`, `docs/phase-status.md`, and `workspace/phase-153/REPORT.md`
first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-192 step N: <desc>" && git push`
after EVERY step. Never sit on uncommitted work.

## Step 1 - Reproduce + trace (commit it)
- Read `services/VoiceNoteManager.kt` end-to-end (`:230-290` save path):
  - Where is `currentBlobFile` set at record start? Is it inside `filesDir`/`voice_notes`
    (must match what `BackupExportPolicy` + `VoiceNoteCrypto` expect)?
  - Where is `VaultKeyHolder.dek` set? Is it null at the time the recording STOPS
    (i.e. the vault locked / DEK zeroized mid-recording, or never set on a
    passwordless-vault path)? The message fires when `dek == null`.
  - `VoiceNoteCrypto.encryptRecordingFile` (`services/VoiceNoteCrypto.kt`) — why does it
    return false (directory missing? output blob path unwritable? cipher init fail?
    temp AAC missing?) — read it and pin the exact failing branch.
- Check the calling UI (`EditorScreen` recording start/stop) — does it set
  `currentBlobFile` from the SAME directory the encrypt step expects?
- COMMIT this step with the trace + the failing branch identified.

## Step 2 - Fix
- Whatever the failing branch: recording must save reliably for BOTH a passwordless
  vault and a password vault. If `dek` is null because the vault is passwordless,
  the DEK must be minted/available at stop time (never leave it null and never
  write plaintext). If the blob dir is missing, create it. If the cipher fails,
  surface the REAL reason (non-alarming) instead of the generic "cannot be saved
  securely" when it's a recoverable condition (e.g. storage full) — and keep the
  honest locked-vault message only when the vault actually locked.
- Preserve B1-DB-3: never persist plaintext audio at rest; if encryption cannot
  happen, delete the temp and fail closed.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests for the save-decision policy: passwordless vault (DEK available)
  encrypts, locked vault (DEK null) fails closed + deletes temp, missing blob dir is
  created, storage/cipher errors map to a truthful message (never "saved securely"
  false positive, never plaintext leak).

## Definition of done
- Voice recording saves successfully in normal use; the generic "cannot be saved
  securely" only appears when the vault is genuinely locked; no plaintext at rest.
- `workspace/phase-192/REPORT.md`: failing branch, fix, test list.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Never write plaintext audio to disk. Never log paths/keys/decrypted content.
- Keep the B2-DOS-03 ceiling-abort behavior (saves what was recorded).