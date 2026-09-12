# Phase 262 — Voice: ANR + clock-skew + orphan plaintext + OOM reads — REPORT

## Summary
Fixed the CRITICAL playback ANR, the HIGH clock-skew ceiling bypass, the
HIGH legacy-plaintext delete leak, the MEDIUM whole-file OOM reads, and the
MEDIUM recorder/playback hardening gaps (native caps, in-manager permission
gate, KeyStore-out-of-lock, audio focus + becoming-noisy, unpredictable cache
names, bounded waveform persist, voice-path confinement, empty-dir orphan
sweep, drag-scrub + speed guard). One pre-existing stale pin updated
(B1Db03 temp-name literal → invariant, for the intentional nonce suffix).

## Evidence table (claim / reality / status / evidence)

| # | Claim (PROMPT) | Reality | Status | Evidence |
|---|---|---|---|---|
| 1 | CRITICAL `VoiceNoteManager.kt:392-399` blocking `prepare()` on Main ANRs | Confirmed (blocking `prepare()` on the Main scope) | FIXED — `prepareAsync()` with `OnPreparedListener` (start/duration/tracker in the callback, `OnErrorListener` fail-closed) | `services/VoiceNoteManager.kt`: `player.prepareAsync()` + `setOnPreparedListener`; no `.prepare()` string remains. Pinned by `Phase262VoiceTest.playback uses prepareAsync never blocking prepare` |
| 2 | HIGH wall-clock `currentTimeMillis(:179,181)` bypasses 30-min ceiling + bogus `durationMs(:274)` | Confirmed (sampler delta + elapsed + persisted duration all wall-clock) | FIXED — `voiceMonotonicNowMs()` (`elapsedRealtime()`, `nanoTime` JVM fallback); wall clock kept ONLY for filename stamps | `services/VoiceNoteManager.kt`: `voiceMonotonicNowMs() - startTime`; pin `recording elapsed uses elapsedRealtime not wall clock` |
| 3 | HIGH `deletePagePermanently` deletes only `.enc`, legacy `voice_*.m4a` leaks (B1-DB-3) | Already fixed at HEAD (phase-260 `PageDeleteFilePolicy` matches BOTH names; KDoc `:1356-1362` claims it) — verified, not re-broken | VERIFIED + PINNED (no code change needed) | `services/PageDeleteFilePolicy.kt:54-56` (`isEncryptedBlobName \|\| isPlaintextRecordingName`); pins `voice delete policy covers legacy plaintext m4a…` + `…refuses escapes…` (JVM behavior on the real policy) |
| 4 | MEDIUM `VoiceNoteCrypto.kt:134,172,196` whole-file `readBytes()` → ~56 MB heap OOM | Confirmed (encrypt/decrypt/re-key all `readBytes()`) | FIXED — 64 KB `Cipher.update` streaming, same `[VERSION][IV][ct+tag]` wire format, atomic tmp+rename re-key | `services/VoiceNoteCrypto.kt`: `STREAM_BUFFER_BYTES`, `cipher.update` loops; zero `plaintext/blob.readBytes()` code; round-trip + re-key behavior test (257 KB) green |
| 5 | MEDIUM no native `setMaxDuration/setMaxFileSize`; no in-manager RECORD_AUDIO gate; KeyStore I/O under `synchronized` starves sampler; no focus/noisy; predictable `voice_pb_<ms>.m4a` | All confirmed | FIXED — native caps + `OnInfoListener` → same ceiling path; `checkSelfPermission` gate; `FinalizeSnapshot` (fast capture under lock, KeyStore+encrypt after); focus request/abandon + `RECEIVER_NOT_EXPORTED` noisy receiver; `UUID` nonce in rec + blob + playback temps | `services/VoiceNoteManager.kt`: `setMaxDuration/setMaxFileSize`, `Manifest.permission.RECORD_AUDIO`, `FinalizeSnapshot`, `AudioFocusRequest`/`ACTION_AUDIO_BECOMING_NOISY`, `UUID.randomUUID()`; 5 source pins |
| 6 | MEDIUM unbounded waveform persist; voice path `../` escape; ON_STOP race; `migrateLegacy` misses empty-dir orphans | Confirmed (bare `joinToString`; playback opened any absolute `.enc`; migrate swept only embed parents) | FIXED — persist capped to `MAX_STORED_WAVEFORM_ENTRIES` + `finiteOrZero`; playback confined to `filesDir/voice_notes` (`isConfinedVoiceBlob`, delete path already confined); lock race documented fail-closed (temp deleted); migrate adds canonical `voiceNotesDir()` | `data/repository/NoteRepository.kt`: capped/filtered `waveformJson`, `+ voiceNotesDir()` in sweep set; `services/VoiceNoteManager.kt`: `isConfinedVoiceBlob`; pins for both |
| 7 | `AudioPlaybackCard` tap-only scrub, speed `indexOf` guard | Confirmed (`detectTapGestures` only; `(indexOf+1)%size` jumps unknown → 0.5x) | FIXED — chained `detectHorizontalDragGestures` (tap kept) via `scrubTargetMs`; `speedIndex<0 → 1.0x` | `ui/components/AudioPlaybackCard.kt`: `detectHorizontalDragGestures`, `speedIndex`; 2 pins |

## Stale-pin maintenance (existing tests, behavior-verified)
- `B1Db03VoiceNoteEncryptionTest.records to a temp…` pinned the literal
  `voice_rec_${pageId}_${stamp}.m4a.tmp`; the phase-262 nonce suffix
  (`_<nonce>`) intentionally changes the literal while keeping the invariant
  (cacheDir scratch, `voice_rec_` prefix, `.m4a.tmp`). Pin now asserts the
  invariant, not the literal.
- `Phase206EventDrivenTimersTest.resume restarts the tracker` pinned the
  literal `trackPlaybackPosition(player)`; the call now lives in the
  `OnPreparedListener` (param renamed `player`, same call shape). Pin passes
  unchanged after the rename.
- `Phase148UiFailureTextScrubTest` pins EXACTLY 8 `classNameToken` logs; the
  first draft added a 9th in the prepared-callback — removed (error banner
  without a new log line), count stays 8.

## Review fixes (FINDINGS 1–5)
1. `*.enc.tmp` / `*.rekey.tmp` streaming temps leaked no sweeper — new
   `VoiceNoteCrypto.isStreamingTempName` + `sweepStreamingTemps`, wired into
   the migrate orphan sweep (`NoteRepository.kt`) and the recurring
   `startRecording`/`release` sweeps (`VoiceNoteManager.kt`); real blobs and
   recording temps untouched (pinned).
2. Denied audio focus was ignored — `startPlayback` now fails closed with
   "Could not get audio focus — playback didn't start." (temp destroyed, no
   player, no noisy receiver).
3. `onDragStart` sought on every touch-down, double-seeking taps — removed;
   taps seek on release, drags on move (pinned: no `onDragStart` in the card).
4. Streaming decrypt/re-key retried under legacy `FIELD_AAD` (parity with
   `EncryptionService.decryptAad`); re-keyed blobs always re-bound to the
   blob-name AAD. Pinned by a behavior test over a real FIELD_AAD blob.
5. Confinement refusal split from the missing-file message ("outside the
   vault voice folder").

## Verification
- `gradle :app:assembleDebug` green.
- `gradle :app:testDebugUnitTest` **3768 tests, 0 failures / 0 errors**
  (incl. 20 `Phase262VoiceTest`: 15 phase + 5 review-fix pins).
- `gradle :app:lintDebug` 0 errors (BUILD SUCCESSFUL, only pre-existing warnings).
- No Room schema change, no new dependencies, no `.github/workflows/` edits,
  `verification-metadata.xml` untouched, `allowBackup=false` untouched, no
  plaintext rows introduced (fail-closed paths delete plaintext temps).
