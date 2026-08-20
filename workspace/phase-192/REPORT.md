# Phase 192 — Final Report: voice recording never-saves fix

**Date:** 2026-08-20
**Repro string:** "The recording could not be saved securely. Please try again."
**Root cause:** `services/VoiceNoteManager.kt:263` `finalizeRecording`'s `!encrypted`
branch — it fired whenever the **stop-time** `VaultKeyHolder.dek` was null (or the
cryptor failed) and always printed the SAME generic string for every cause.
**Status: FIXED, regression green.**

## Failing branch (from STEP1_TRACE.md)

```kotlin
val dek = VaultKeyHolder.dek
val encrypted = blobFile != null && dek != null &&
    VoiceNoteCrypto.encryptRecordingFile(tempFile, blobFile, dek)
if (!encrypted) { ..._recordingError.value = "The recording could not be saved securely..." }
```

Three ways to land in this branch:
- **A. `blobFile == null`** — defensive, effectively unreachable (`currentBlobFile` is set at
  record start from `filesDir/voice_notes/voice_<pageId>_<stamp>.enc`, the dir every consumer
  expects).
- **B. `dek == null` at stop time** — the REAL driver. Either (B1) a **password vault locked
  mid-recording** (ON_STOP/screen-off/idle lock zeroizes the DEK, `NoteflowViewModel.kt:4747`)
  — the genuinely-locked case, or (B2) an anomalous **passwordless** session where the
  in-memory holder was cleared without a re-arm — but the passwordless design says the
  device-wrapped copy IS the boot credential (the DB factory re-reads it on every open,
  `NoteflowDatabase.kt:440-444`), so the save could still legitimately succeed.
- **C. `!encryptRecordingFile(...)` with DEK present** — cipher/I-O failure (`EncryptionService`
  JCE init, `blob.writeBytes` I/O, e.g. ENOSPC/storage-full), a **recoverable** condition the
  generic string hid.

Bonus defect: in the `!encrypted` branch the plaintext `cacheDir` temp was left on disk until
the next record-start sweep or `release()` — a B1-DB-3 fail-closed leak window on recoverable
failures.

## Fix (Step 2, commit 9696713)

1. **New pure-JVM decision policy `services/VoiceRecordingSavePolicy.kt`:**
   - Sealed `StopTimeKey`: `InMemory(key)` · `PasswordlessReread(key)` · `LockedVault` ·
     `KeyUnavailable` — chosen by `resolveStopTimeKey(inMemoryDek, vaultHasPassword,
     passwordlessReader)`.
   - **Passwordless vault** → the device-wrapped DEK is RE-READ at stop via
     `SecurityService.forDevice(context).readDek()` (mirrors the DB factory's every-open
     re-read; never mints a key, never runs for a password vault). Missing key → `KeyUnavailable`.
   - **Password vault** with DEK still null after the re-read → `LockedVault` (fail closed).
2. **`VoiceNoteManager.finalizeRecording`** resolves the stop key, syncs any re-read key back
   into `VaultKeyHolder.dek` (`stopTimeKey.key?.let { VaultKeyHolder.dek = it }`), and encrypts
   via `encryptRecordingFileDetailed`. On a failed save:
   - the plaintext temp is **deleted immediately** (`tempFile.delete()` in `try/catch`),
   - `discardOnRelease = true` is kept (phase-153/UI-05 discard pipeline intact),
   - the message is the **exact historic string only when `stopTimeKey is LockedVault`**;
     every other cause gets a truthful, non-alarming message from
     `VoiceRecordingSavePolicy.messageFor(...)` (`KEY_UNAVAILABLE_MESSAGE`,
     `STORAGE_FULL_MESSAGE`, `SOURCE_MISSING_MESSAGE`, `TRANSIENT_FAILURE_MESSAGE`).
3. **`VoiceNoteCrypto.encryptRecordingFileDetailed`** returns a typed
   `VoiceEncryptOutcome` (`Saved` / `Failed(reason)`) with `VoiceEncryptFailure` =
   `SOURCE` (missing/>40 MB temp) · `BLOB_TARGET` (non-`.enc` name) · `ENOSPC`
   (`IOException` containing "No space left") · `IO_OR_CIPHER` (anything else). The old
   boolean `encryptRecordingFile` is preserved as a delegate. `blob.parentFile?.mkdirs()`
   is kept (missing-dir case pinned by test).

## Why this is safe

- **Never mint, never bypass:** the passwordless re-read only returns the device-wrapped
  copy's DEK — the same credential the DB open uses; nothing new is created and the plaintext
  temp recorded under the session `_recordingElapsedMs` is still encrypted with the loaded DEK
  in the same AES-256-GCM blob format (B1-DB-3, phase-54).
- **A genuinely-locked password vault keeps failing closed** with the exact user-facing string;
  the phase-153 locked-snackbar contract (`discardOnRelease` + `notifyVoiceRecordDiscarded`)
  is unchanged.
- **B2-DOS-03 ceilings keep their behavioral post: `finalizeRecording(limitMessage)` still
  saves what was recorded** (30 min / 32 MB caps).

## Tests

- **New `Phase192VoiceRecordingSavePolicyTest` (17)** — pure-JVM policy + source pins:
  DEK decision table over all `StopTimeKey` combinations, passwordless re-read
  (`PasswordlessReread` resolved, `InMemory` preferred when present, `KeyUnavailable`
  when the reader yields null), passwordless `lock()` no-op context, locked password vault
  fail-closed + `LOCKED_VAULT_MESSAGE` routing, `KeyUnavailable` → truthful unlock-prompt
  message, message table (disk-full ↔ `STORAGE_FULL_MESSAGE` etc.), ENOSPC classification
  from `IOException("No space left on device")`, boolean `encryptRecordingFile` still
  delegates to the detailed path, `mkdirs` creation when the blob dir is missing, and
  `finalizeRecording` source pins: temp deleted in the failed branch, policy message routing,
  locked-only gating of the legacy string, same-key sync into `VaultKeyHolder`.
- **Updated pins:** `B1Db03VoiceNoteEncryptionTest` now pins
  `VoiceNoteCrypto.encryptRecordingFileDetailed(tempFile, blobFile, dek)`; re-ran
  `Phase153LockedSnackbarPolicyTest` (locked string + discard contract), `B2Dos03VoiceRecordingTest`,
  `VoiceRecordingPolicyTest` — all green.

## Verification

- `gradle :app:compileDebugKotlin` green; `gradle :app:compileDebugUnitTestKotlin` green.
- Targeted: the five voice/save classes, 65 tests green.
- `gradle assembleDebug` green.
- `gradle testDebugUnitTest` **2539 total / 1 pre-existing `Phase148UiFailureTextScrubTest`
  UNC-path failure** (fails in isolation, untouched) + `WikiLinkParserCacheUnitTest` timing
  flake (passes in isolation — re-verified this run).

## Definition of done

- [x] Recording saves reliably in normal use (passwordless DEK re-read at stop; password
      vault unlocked at stop unchanged).
- [x] Generic "could not be saved securely" appears ONLY for a genuinely-locked vault.
- [x] No plaintext at rest: temp deleted on any failed save; blob always `*.enc`.
- [x] `docs/ARCHITECTURE.md` + `docs/phase-status.md` updated.
- [x] No `.github/workflows/` change, no new dependencies, no schema change; base-APK-size
      rule intact.

Commits: `e6a2cde` (step 1, trace) · `9696713` (step 2, fix) · step 3 (this report + docs).