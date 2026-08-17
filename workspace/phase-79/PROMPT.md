# Phase 79: B2-DOS-03 - Voice notes: unbounded recording duration/size, and... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-03, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-03` (MEDIUM)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `VoiceNoteManager.kt:20` (`CoroutineScope(Dispatchers.Main + ...)`), `VoiceNoteManager.kt:96-111` (every 100 ms `_waveformAmplitudes.value = _waveformAmplitudes.value + amp` at :108 - a full list copy + StateFlow emission on main), `VoiceNoteManager.kt:62-127` (startRecording has no max-duration/max-size constraint), persisted whole via `NoteRepository.kt:705` and re-parsed at `NoteRepository.kt:589-598`
- **Exploit scenario:** After 1 h the per-tick copy-on-write is ~648M element copies + thousands of boxed-Float allocations on the main thread -> jank/ANR on 2-core devices; the 128 kbps AAC file grows ~57 MB/hour with no stop - disk-fill DoS if left running.

## The fix (where & how)

`VoiceNoteManager.kt:62-127` - enforce a maximum recording length (e.g. 30 min) and a max file size in startRecording/the sampler (abort + surface the error); write amplitudes into a preallocated `MutableList`/`FloatArray` and emit a down-sampled fixed-budget view (<=600 entries) so appends are O(1); run the sampler OFF the main dispatcher.


## Verification

- Unit test: the sampler stays O(1) per tick and emits a bounded view; a recording past the cap aborts with a surfaced error. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-03 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-79/REPORT.md` committed: what changed (file:line), the
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
