# Phase 192 — Step 1: reproduce + trace (commit c71c059-style, source-level)

USER REPORT: voice recording shows "The recording could not be saved securely.
Please try again." and the recording is never saved.

## The exact string

`app/src/main/kotlin/com/authorss81/noteflow/services/VoiceNoteManager.kt:263`
(`finalizeRecording`, the `!encrypted` branch):

```kotlin
val dek = VaultKeyHolder.dek
val encrypted = blobFile != null && dek != null &&
    VoiceNoteCrypto.encryptRecordingFile(tempFile, blobFile, dek)
...
if (!encrypted) {
    Log.w("VoiceNoteManager", "Recording could not be encrypted — plaintext temp destroyed")
    _recordingError.value = "The recording could not be saved securely. Please try again."
    discardOnRelease = true
    return@synchronized null
}
```

So the banner fires when `encrypted == false`, i.e. when
`blobFile == null || dek == null || !VoiceNoteCrypto.encryptRecordingFile(...)`.

## Failing-branch pinning (each condition)

### A. `blobFile == null` — defensive, effectively unreachable
- `currentBlobFile` is set at record start (`VoiceNoteManager.kt:109`) from
  `File(context.filesDir, "voice_notes")` + `voice_<pageId>_<stamp>.enc` — the
  SAME dir `VoiceNoteCrypto` (`VoiceNoteCrypto.kt:97` `blob.parentFile?.mkdirs()`)
  and `BackupExportPolicy`/`ImportExportService.kt:1552/3012`
  (`File(context.filesDir, "voice_notes")`) expect.
- It is only nulled in `finalizeRecording`/start-failure; with `_isRecording ==
  true` it is non-null by construction, so this arm is defensive.

### B. `dek == null` — the REAL driver of the user-visible generic message
- `val dek = VaultKeyHolder.dek` is a **stop-time snapshot of the mutable
  singleton** (`VaultKeyHolder.kt:13`). It is non-null only while the vault is
  unlocked, and `lock()` zeroizes it (`NoteflowViewModel.kt:4747`
  `repository.zeroizeKey()` inside the `hasMasterPassword` teardown).
- **Password vault, vault locked mid-recording** (the only in-editor null case
  that exists today): MainActivity locks on ON_STOP / screen-off / idle poll
  (phase-14/60) while the recorder is running → DEK zeroized → stop() reaches
  `finalizeRecording` with `dek == null` → the generic string. This is the
  GENUINELY-LOCKED case. It is *honest* but the message hides the vault state
  from the user.
- **Passwordless vault, in-memory holder null at stop**: the design says the
  device-wrapped copy IS the boot credential — `NoteflowSqlcipherFactory`
  re-reads it on every DB open (`NoteflowDatabase.kt:440-444`). But
  `finalizeRecording` has NO such re-arm: it reads `VaultKeyHolder.dek` once and
  fails if it is null, so an anomalous session where the holder was cleared
  (process recreate mid-recording is impossible, but any earlier
  `repository.zeroizeKey()`/`VaultKeyHolder.zeroize()` path, e.g. a re-key or
  change-password interruption, leaves it null) FAILS a recording that the
  passwordless vault could still legitimately encrypt. The DEK must be
  re-available at stop time — never mint, never plaintext.
- `dek` is set for a passwordless vault at `NoteflowViewModel` init
  (`NoteflowViewModel.kt:2047/2054`) and for a password vault at every unlock
  (`:3540`, `:3747`); a passwordless `lock()` is a session-preserving no-op
  since phase-181 (`NoteflowViewModel.kt:4743`).

### C. `!VoiceNoteCrypto.encryptRecordingFile(...)` — DEK present, encrypt failed
`VoiceNoteCrypto.kt:90-112` returns false when:
1. `!plaintext.isFile || plaintext.length() > MAX_BLOB_BYTES` (SOURCE) — the
   `< 44L` guard at `VoiceNoteManager.kt:239` already catches a missing/empty
   temp; `> 40 MB` is unreachable via the 32 MB / 30-min B2-DOS-03 ceilings.
2. `!isEncryptedBlobName(blob.name)` (BLOB_TARGET) — constructed as `.enc`
   (`VoiceNoteManager.kt:107`), so unreachable.
3. an exception inside the try (`EncryptionService.encryptAad` JCE, or
   `blob.writeBytes` I/O — e.g. ENOSPC/storage full, or a `plaintext.delete()`
   race) → the blob is deleted and **false**. This is the RECOVERABLE case the
   generic string hides (storage full / transient I/O / cipher init).

### Consequence of the generic branch (phase's "never leak" angle)
In the `!encrypted` branch the plaintext temp in `cacheDir` is **not deleted** —
only the references are nulled. It lingers until the next `startRecording`
sweep (`VoiceNoteManager.kt:102`) or `release()` (`:458`). For a recoverable
cipher/IO failure the raw AAC should be destroyed at once (fail closed, B1-DB-3
"if encryption cannot happen, delete the temp and fail closed").

## Calling UI (EditorScreen)
- Start: chip-tap → `voiceNoteManager.startRecording(page.id)` (`:1434`) —
  MediaRecorder streams to `cacheDir/voice_rec_<pageId>_<ts>.m4a.tmp`
  (`VoiceNoteManager.kt:105`), the blob target is `filesDir/voice_notes/...enc`.
  Same directory contract everywhere (voice dir is created at start `:106`).
- Stop: chip-tap → `voiceNoteManager.stopRecording()` (`:1423`) → the result is
  attached via `attachVoiceRecording` (`:1424-1426`). A null result is silently
  not attached; the banner is the only surface.

## Conclusion — fix targets
1. Stop-time DEK must be re-available for a passwordless vault (re-read the
   device-wrapped copy, mirroring the DB factory) — never null at stop.
2. The generic "could not be saved securely" string is reserved for the
   GENUINELY-LOCKED vault only; recoverable conditions (storage full / transient
   cipher-I/O) get a truthful, non-alarming message.
3. The plaintext temp is destroyed immediately in the failed-save branch.
4. Missing blob dir is created (`mkdirs`, already in the cryptor; pinned by test).
5. All decisions land in a pure-JVM policy for unit tests.
