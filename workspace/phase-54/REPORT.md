# Phase 54 — B1-DB-3 FIXED: Voice notes encrypted at rest

- **Finding:** [B1-DB-3] Voice notes are recorded as UNENCRYPTED `.m4a` files and
  excluded from field encryption, backups, and permanent-delete (MEDIUM).
- **Status:** FIXED — `gradle testDebugUnitTest` (1129 tests) + `gradle assembleDebug` green.
- **Date:** 2026-08-15 (sprint F-6).

---

## What was wrong (evidence from the finding)

| Path | Pre-fix behavior |
|---|---|
| `VoiceNoteManager.kt:65-66` | `startRecording` wrote `MediaRecorder` output STRAIGHT to `File(context.filesDir, "voice_notes")` as plaintext MPEG-4/AAC `.m4a`. |
| `media_embeds.contentUrlOrPath` | Held the plaintext path; NOT in the field-encryption map and NOT in `reencryptPlaintextFields`. |
| `NoteRepository.kt:422-434` (`deletePagePermanently`) | Deleted only paths containing `imports/` or `exports/` — `voice_notes/` audio survived page deletion (orphans). |
| `ImportExportService.exportBackup` | Packed only the DB + `imports/` — audio never backed up (simultaneously unprotected AND unrecoverable). |

Exploit: `run-as`/adb/root/forensic image reads every private voice memo in
cleartext without touching the SQLCipher vault or any key.

---

## Fix summary

Only encrypted blobs ever sit at rest now; raw AAC exists only as a transient
cacheDir temp during an active recording or playback and is destroyed on stop.

### 1. New pure-JVM cryptor — `services/VoiceNoteCrypto.kt`
- At-rest blob format: `voice_<page>_<ts>.enc` = AES-256-GCM
  `[PAYLOAD_VERSION][12-byte IV][ciphertext+GCM tag]` via
  `EncryptionService.encryptAad/decryptAad` (`VoiceNoteCrypto.kt:44,92,122`).
- AAD `Noteflow-Voice-Note-v1|<blob file name>` binds every blob to its file
  name — a blob can never be renamed/relocated and every decrypt is bound to
  the exact DEK (`VoiceNoteCrypto.kt:41-44`).
- `MAX_BLOB_BYTES = 40 MB` (~5 h AAC @ 128 kbps) caps `readBytes` on
  attacker-influenced/crafted oversized blobs (B2-DOS-01 symmetry)
  (`VoiceNoteCrypto.kt:39`).
- `encryptRecordingFile` / `decryptRecordingFile` (`:88,117`): fail-closed pair —
  both refuse a non-`.enc` file name (a blob with any other name could never
  decrypt), symmetric guards (this symmetry was the one test-driven discovery
  of the phase — described in the test section below).
- `reencryptAudioBlobInPlace` (`:141`): cross-device restore re-key.
- `migrateLegacyRecordingFile` (`:167`): legacy `.m4a` → `.enc`,
  plaintext PRESERVED on any failure (never destroyed when its encryption did
  not complete — same invariant as phase-44 note-body migration).
- `deleteOrphanPlaintext` (`:180`): deletes plaintext `.m4a`/temps not
  referenced by a still-pending DB row; `sweepPlaintextTemps` (`:202`) removes
  stale `voice_*` cacheDir scratch.

### 2. `services/VoiceNoteManager.kt` — record/play lifecycle
- `startRecording` writes to a cacheDir temp `voice_rec_<page>_<ts>.m4a.tmp`
  (`:79`), sweeps stale temps first (`:74`), returns the `.enc` blob path.
- `stopRecording` encrypts temp → blob with the vault DEK; a LOCKED vault
  (null DEK) fails closed and destroys the plaintext temp; the result path is
  the blob (`:182`).
- `startPlayback` refuses non-`.enc` blobs, decrypts to a transient cacheDir
  temp, deletes on stop/complete/release (`:210-222,359`).

### 3. `data/repository/NoteRepository.kt`
- `deletePagePermanently` now deletes AUDIO_NOTE `.enc` blobs before dropping
  `media_embeds` rows (`:635`).
- New `migrateLegacyPlaintextVoiceNotes(): VoiceNoteMigrationResult`
  (`:667-706`): locked vault defers (plaintext file retained), rows retargeted
  via `MediaEmbedDao.updateContentUrlOrPath`, orphan sweep after, `isComplete`
  == no plaintext `.m4a` remains. Result type at `:1206`.

### 4. `data/db/Daos.kt`
- `MediaEmbedDao.updateContentUrlOrPath(id, path)` (`:240`) — retargets
  `.m4a` → `.enc` rows. No schema change (same Phase-09 C1 trick).

### 5. `services/SettingsManager.kt`
- `voiceNotesEncryptedMigrated` flag (`:52-56`), key `voice_notes_encrypted_migrated`.

### 6. `ui/viewmodel/NoteflowViewModel.kt`
- Migration hook in `initializeDataCore`, gated on the flag (`:1248-1269`):
  sweep cacheDir temps → repository migration → `checkpointWal()` +
  `stampDatabaseChecksum()` (B1-DB-6: WAL frames must be folded before the
  HMAC baseline is re-armed) → set flag only when `isComplete`.

### 7. `services/ImportExportService.kt`
- `exportBackup` packs `voice_notes/*.enc` ONLY (never plaintext `.m4a`, `:1270`).
- `restoreFromZip` / `extractBackupEntriesTo` extract `voice_notes/` entries
  with `safeImportRelativePath` + the existing zip-bomb caps (50 MB/file,
  200 MB total, `:1591-1601`); `commitRestoredFiles` swaps blobs into
  `filesDir/voice_notes`.
- `validateAndPrepareRestoredDb` + `rekeyVoiceNoteBlobs` (`:1696-1712`) re-key
  blobs in place when `openedWith != currentDekHex` (cross-device restore),
  zeroizing DEKs on exit.

---

## Verification

- `gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.B1Db03VoiceNoteEncryptionTest"` — **18 passed, 0 failed** (new suite, pure JVM).
- `gradle testDebugUnitTest` — **1129 tests, 0 failures/errors, 0 skipped** (pre-phase-54 baseline was 1111).
- `gradle assembleDebug` — **BUILD SUCCESSFUL**, `app-debug.apk` produced.

### New test coverage
`B1Db03VoiceNoteEncryptionTest.kt` (18 tests):
1. `.enc` / plaintext-name classifiers 2. `encryptedBlobNameFor` retarget
3. cache-temp name scoping 4. record-then-stop leaves no plaintext + byte-exact
round-trip 5. wrong DEK fails closed, no plaintext scratch left 6. encrypt
failure keeps the plaintext untouched 7. oversized blob refused 8. re-key:
old DEK fails, new DEK plays 9. legacy migration success 10. migration failure
preserves plaintext + no partial blob 11. orphan sweep keeps pending rows
12. cache sweep only touches `voice_*` 13-17. source pins (VoiceNoteManager,
deletePagePermanently, migration, SettingsManager+viewmodel hook, backup
export/extract/commit/re-key) 18. log lines never contain `voice_` paths.

**Test-driven discovery:** the in-phase test `encrypt failure keeps the plaintext
untouched` proved `encryptRecordingFile` would happily write a blob that
`decryptRecordingFile` refuses (target named `.notenc`). The fix adds the SAME
`isEncryptedBlobName` guard to the encrypt path (`VoiceNoteCrypto.kt:90`),
making the encryption/decryption guards symmetric fail-closed.

---

## Out of scope (not part of this phase)
- B1-DB-4 (markdown bodies) — already fixed phase-44.
- `emptyTrash` blob deletion: permanent-delete is the covered path; note that
  `emptyTrash` (soft-delete purges) also routes through
  `deletePagePermanently`, so covered transitively.
- Playback scrubbing in-memory after the media player has read the file (the
  cacheDir temp is deleted at stop/complete/release; cacheDir is OS-scrubbed
  and never included in backups).