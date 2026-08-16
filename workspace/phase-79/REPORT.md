# Phase 79 — B2-DOS-03 (MEDIUM) — Voice notes: unbounded recording duration/size + O(n²) main-thread waveform sampler

2026-08-16 · finding source: `docs/security-report.md` B2-DOS-03, batch 2 (resource exhaustion / DoS)

## The vulnerability (before/after)

**Before** (`VoiceNoteManager.kt`, pre-fix):

- The sampler loop ran **on the main dispatcher** (`VoiceNoteManager.kt:20` `CoroutineScope(Dispatchers.Main + ...)`, sampler `scope.launch { ... }`), and every 100 ms tick rebuilt the
  amplitude list by copy-on-write: `_waveformAmplitudes.value = _waveformAmplitudes.value + normalizedAmp` (old `:108`) — a full `List` copy + boxed-Float allocation proportional to the
  session length, every tick. After 1 h that is ~648M element copies + thousands of boxed-Float allocations on the main thread → jank/ANR on 2-core devices.
- **No max duration or max file size** anywhere in `startRecording`/the sampler (old `:62-127`). The 128 kbps AAC file grew ~57 MB/hour with no stop → internal-storage disk-fill DoS if
  the recorder is left running.
- The whole unbounded list was **persisted** into the `media_embeds.waveformJson` column (`NoteRepository.kt`, old `:705` `joinToString`) and **re-parsed whole** on load (old
  `:589-598` `parseWaveformJson`), so an hour-long recording also grew the DB row, and a crafted/legacy backup could carry an arbitrarily long `waveformJson` that was materialized into a
  `List` on every page open.

**After:**

- **Sampler off the main dispatcher**: the sampler is launched with `scope.launch(Dispatchers.Default)` (`VoiceNoteManager.kt:150`), so the per-tick amplitude work never runs on main.
- **O(1)-amortized appends + bounded emission**: amplitudes append into the new pure-JVM `services/LiveWaveformBuckets.kt` — a preallocated `FloatArray` accumulator where a sample lands in a
  pending register and only occasionally seals a bucket (O(1) amortized), folding (pairwise average + span doubling) when the budget fills so the emitted `snapshot()` NEVER exceeds
  `WaveformPeakMath.recordingLiveBuckets` (160) entries and keeps representing the WHOLE session (mean-preserving, unit-tested). The emitted `_waveformAmplitudes.value = waveformBuckets.snapshot()`
  (`VoiceNoteManager.kt:158`) is therefore a fixed-budget view (≤ 600 per the finding), so the StateFlow emission, the DB `waveformJson` column and the render path are all bounded for a
  recording of any length.
- **Duration + size ceilings with a surfaced, non-alarming abort**: the new pure-JVM `services/VoiceRecordingPolicy.kt` is the single decision table — `MAX_RECORDING_DURATION_MS = 30` min,
  `MAX_RECORDING_BYTES = 32` MB (defense-in-depth backstop for encoder bitrate variance; at the configured 128 kbps, 30 min ≈ 28.8 MB), `SAMPLER_TICK_MS = 100`, `MAX_STORED_WAVEFORM_ENTRIES = 600`.
  Every tick the sampler checks `isOverDuration(elapsed)` and the raw temp-file size (`VoiceNoteManager.kt:160-175`); past either ceiling it calls `finalizeRecording(<limit message>)`, which
  STOPS the recorder, ENCRYPTS the audio into the vault-DEK `.enc` blob (B1-DB-3 path preserved), surfaces the non-alarming banner via the existing `recordingError` StateFlow, and publishes the
  completed result via a new `completedRecordingResult` StateFlow (`VoiceNoteManager.kt:62-63`) so the editor attaches the audio embed — the capped audio is saved, never silently discarded,
  never orphaned.
- **Race-free finalize**: `startRecording`, `stopRecording` and `finalizeRecording` are serialized under a new `recorderLock` (`VoiceNoteManager.kt:75`; `startRecording` = `synchronized`,
  `finalizeRecording` = `synchronized(recorderLock)`), so a manual chip-tap stop racing a sampler ceiling-abort can never double-stop/finalize the MediaRecorder across the two threads.
- **Editor auto-attaches a ceiling-completed recording** (`EditorScreen.kt`): the chip-tap stop path and the new `LaunchedEffect(completedVoiceRecording)` observer both route through a single
  shared `attachVoiceRecording(result)` helper (old inline embed construction in the chip handler removed), so a capped recording lands on the canvas exactly like a manual one.
- **Bounded re-parse**: `NoteRepository.parseWaveformJson` (`NoteRepository.kt:997-1008`) now materializes at most `VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES` (600) entries from the
  stored `waveformJson` (both the `JSONArray` and the fallback-split paths), so a legacy or crafted-backup column can never be re-parsed into an unbounded list.

## File:line evidence (commit before/after)

| Site | Before | After |
|---|---|---|
| `services/VoiceNoteManager.kt` sampler | `:20` `CoroutineScope(Dispatchers.Main + ...)`; `:108` `_waveformAmplitudes.value = _waveformAmplitudes.value + normalizedAmp` every 100 ms on main; `:62-127` no duration/size cap | `:150` `scope.launch(Dispatchers.Default)`; `:157-158` `waveformBuckets.append(normalizedAmp)` + `= waveformBuckets.snapshot()` (≤160 view); `:160-175` `VoiceRecordingPolicy.isOverDuration`/`isOverSize` → `finalizeRecording(limitMessage)` + `return@launch` |
| `services/VoiceNoteManager.kt` stop/finalize | `:144-196` `stopRecording` (single path, main thread, no error-surface on save) | `:197-200` `stopRecording` delegates to `finalizeRecording(null)`; `:213-273` shared `finalizeRecording` — `synchronized(recorderLock)` re-check, stops+releases recorder, encrypts (B1-DB-3), on the ceiling path sets `_recordingError.value = limitMessage` + `_completedRecordingResult.value = result` |
| `services/VoiceRecordingPolicy.kt` | — | NEW pure-JVM decision table: 30 min duration, 32 MB size, 100 ms tick, 600 stored-entry ceiling, `isOverDuration`/`isOverSize`, non-alarming limit messages |
| `services/LiveWaveformBuckets.kt` | — | NEW pure-JVM fixed-budget accumulator: preallocated `FloatArray`, O(1)-amortized `append`, fold-on-full, bounded `snapshot()` (≤ maxBuckets) |
| `data/repository/NoteRepository.kt` `parseWaveformJson` | `:997-1006` materialized the WHOLE `waveformJson` into a `List` | `:997-1009` bounded by `minOf(arr.length(), VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES)` + `.take(...)` on the fallback |
| `ui/screens/EditorScreen.kt` attach | `:928-965` inline audio-embed construction inside the chip-tap stop handler | `:678-701` shared `attachVoiceRecording(result)`; chip handler `:973` calls it; `:285-293` `LaunchedEffect(completedVoiceRecording)` observer auto-attaches ceiling-completed recordings |

## Checksums / secrets handling

- No keys, passwords, or decrypted note content are logged; no new `INTERNET` usage; `allowBackup=false` + `data_extraction_rules.xml`, `ClipboardGuard`, FLAG_SECURE untouched.
  The B1-DB-3 audio-encryption contract is preserved: the raw AAC temp is still destroyed and only the vault-DEK `.enc` blob is persisted; the only change to that path is that a ceiling
  abort also runs it.
- **No DB schema change, no migration.** The bounded `waveformJson` is a behavioral change to what new recordings write (≤ 160 floats) — the column stays TEXT NOT NULL; old rows (≤ 600
  parsed) still load, truncated to 600 for any over-cap legacy/crafted row. Nothing is deleted.
- No new dependencies. `.github/workflows/` untouched. No Gradle verification-metadata changes (no new artifacts resolved).

## Verification

- `gradle :app:testDebugUnitTest` — **1429 tests completed, 2 failed.** The only 2 failures are the PRE-EXISTING `B1Plat01ReleaseSigningTest` asserts (`docs/RELEASE.md` must not advertise a
  debug-keystore fallback; debug buildType must not assign a custom signingConfig). Both assert on `app/build.gradle.kts` / `docs/RELEASE.md`, which this phase did not touch (git status:
  only the 5 source + 3 test files changed) — identical failures documented as pre-existing since phase-55 in `docs/phase-status.md`.
- The 3 new test classes all pass:
  - `LiveWaveformBucketsTest` (9) — the O(1)/bounded behavioral half: snapshot ≤ maxBuckets after 200 000 feeds, `size` ≤ maxBuckets, constant input collapses to a near-constant bounded
    view, mean preservation across folding, the 18 000-sample 30-minute ceiling feeding completes in ms, degenerate `maxBuckets=1`.
  - `VoiceRecordingPolicyTest` (6) — decision table: 30-min ceiling with exact 29:59.999/30:00 boundary, 32 MB size cap boundary, 600-entry stored ceiling, 100 ms cadence, non-alarming
    limit messages that say "saved" (never error/fail/lost).
  - `B2Dos03VoiceRecordingTest` (12) — source-level wiring pins: sampler on `Dispatchers.Default`, `= _waveformAmplitudes.value + ` copy-on-write gone, `waveformBuckets.append`/`snapshot`
    with the recording budget, duration/size checks routed through `finalizeRecording(limitMessage)`, ceiling path surfaces the error + publishes `completedRecordingResult`, manual stop
    delegates to `finalizeRecording(null)` without publishing, `synchronized(recorderLock)` double-finalize guard, editor observer + shared `attachVoiceRecording`, completed-result reset on
    start, bounded re-parse in `NoteRepository`.
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL** (57/57 tasks). Debug APK 174,098,389 bytes (166.0 MiB), SHA-256 `5cc83e7920f10b51d8f3bd40fedd07fef56a244e5245427a2a07f4df5e14a216`.
- The MediaRecorder-backed sampler loop cannot run on a JVM-only unit test (no Android `MediaRecorder`), so its behavior is covered by the pure-JVM accumulator/policy tests plus the
  source-level pins above — the same split used by B2-Dos02 (phase-78).

## Out of scope / inputs judged (documented, NOT fixed here)

- **`waveformJson` column byte-length on restored/crafted backups**: the parse result is bounded to 600 entries, but the DB read of an attacker-swelled TEXT column still loads the raw string
  once. This is a read-side transient of the restore path (B1-DB-7 territory) and far beyond the recorder finding; the materialized list — the actual OOM/jank surface on page open — is now
  capped.
- **Other recording-side limits** (sample-rate/bit-rate forcing, mono-vs-stereo, per-vault audio quota) were not changed — the finding asked for length + size caps, which are delivered;
  audio quality/size trade-offs are a product decision.
- **Playback paths** (`startPlayback`, `seekTo`) were untouched (no finding).
- The pre-existing "recorder still streams to a cacheDir temp until stop" design is retained per B1-DB-3 (encryption at stop); the duration/size caps bound how large that transient temp can
  ever grow (≤ ~30 min ≈ 28.8 MB nominal, ≤ 32 MB enforced).
