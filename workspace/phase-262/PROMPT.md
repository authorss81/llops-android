# Phase 262 — Voice: ANR + clock-skew + orphan plaintext + OOM reads

## Goal
Stop ANR/crash + cleartext orphans in voice notes.

## Evidence
- CRITICAL `VoiceNoteManager.kt:392-399`: `MediaPlayer.setDataSource; prepare(); start()` on `Dispatchers.Main+SupervisorJob(:31)` → 32MB AAC blocks Main >5s (2-core) → ANR/watchdog. Fix: `prepareAsync()` or `withContext(IO)`.
- HIGH wall-clock `System.currentTimeMillis(:179,181)` drives `isOverDuration/isOverSize` + persisted `durationMs(:274)` → NTP/user step bypasses 30-min ceiling. Fix: `SystemClock.elapsedRealtime()`.
- HIGH `NoteRepository.kt:1170-1196 deletePagePermanently` deletes only `isEncryptedBlobName` → legacy `voice_*.m4a` plaintext never deleted → raw AAC persists forever (B1-DB-3 violation). Fix: also match `isPlaintextRecordingName`.
- MEDIUM `VoiceNoteCrypto.kt:134,172,196` whole-file `readBytes()` (40MB MAX_BLOB) on Main-adjacent scope → ~56MB heap → OOM. Stream via `CipherInputStream`/chunked `Cipher.update` (cf. `AttachmentIngestPolicy.boundedReadBytes`).
- MEDIUM no native `setMaxDuration/setMaxFileSize` (100ms poll overshoots); no in-manager RECORD_AUDIO gate (relies on caller); KeyStore I/O inside `synchronized(recorderLock)` (:246-293) starves sampler; no AudioFocus/noisy handling; predictable `voice_pb_<ms>.m4a` cache name.
- MEDIUM `saveMediaEmbeds` waveform persist unbounded (`joinToString` w/o cap/NaN filter); voice path not confined via `SourceFilePathPolicy` (`../` escape); `ON_STOP lock()` races finalize (recorder keeps writing while locked); `migrateLegacy` misses orphans in empty dirs.

## Files to change
- `services/VoiceNoteManager.kt`, `services/VoiceNoteCrypto.kt`, `data/repository/NoteRepository.kt`, `ui/components/AudioPlaybackCard.kt` (tap-only scrub→drag, speed indexOf guard)

## Tests
- `Phase262VoiceTest`: elapsed uses elapsedRealtime (source pin); delete covers legacy .m4a (JVM test); no `prepare()` on Main (source pin).

## Constraints
- No Room schema change / migration
- No new dependencies (pure Kotlin/Compose only)
- No `.github/workflows/` edits
- Follow AGENTS.md hard rules (allowBackup=false stays, no plaintext rows, fail closed)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-NNN/REPORT.md` with file:line evidence table (claim / reality / status / evidence)
