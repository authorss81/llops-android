# Phase 153: Post-lock UI channels — snackbar host gated on auth + voice-discard notice re-surfaced after lock [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-b2b1-UI-04, R2-b2b1-UI-05) and `docs/phase-status.md` +
`docs/ARCHITECTURE.md`. This phase stops vault-content-bearing messages from
rendering over the locked UI and ensures a lock-mid-recording discard is not
silent.

## Source findings (both OPEN, LOW)

1. **R2-b2b1-UI-04** — The root SnackbarHost is composed OUTSIDE the `LockScreen`
   conditional, so messages (restore/import `e.message`, note titles) enqueue
   past `lock()` and render over the locked UI: host at `MainActivity.kt:211-219`
   collector, `:577-580` (`.align(Alignment.BottomCenter)` inside the nav `Box`
   `:317-320`, NOT inside the `if (hasMasterPassword && !authenticated)
   LockScreen(...)` branch `:352-353`). Producers: `HomeScreen.kt:161,202,256,
   269,290,600`, `EditorScreen.kt:1154,1188`, `NoteflowViewModel.kt:1305-1309`
   (MutableSharedFlow + tryEmit), `:1452` (persistent decrypt-failure notice).
2. **R2-b2b1-UI-05** — Locking mid-voice-recording silently destroys the
   finished recording: `VoiceNoteManager` is per-editor composition
   (`EditorScreen.kt:183,186-187`), error observed via `EditorScreen.kt:297`.
   On lock, `lock()` zeroizes the DEK first, recomposition dips to `LockScreen`,
   disposes the editor → `release()` (`VoiceNoteManager.kt:432-437`:
   `stopRecording` + `sweepPlaintextTemps`) → `finalizeRecording`
   (`:248-257`): `dek == null` short-circuits `encryptRecordingFile`
   (`:249-250`), plaintext temp swept (`:436`), `_recordingError.value =
   "The recording could not be saved securely…"` (`:255`) set after the
   editor's collector is gone.

## The fix (where & how)

- **R2-b2b1-UI-04:** Gate the snackbar consumption on `authenticated` (route the
  host's `snackbarHostState.showSnackbar` through the same `dbGate`/`authenticated`
  state, or clear the `SharedFlow` in `lock()`), and vet dynamic `e.message`
  slots so import/restore errors never echo vault content (ties into phase-148's
  sanitizer — reuse it here).
- **R2-b2b1-UI-05:** On the lock path, publish the discard notice through the
  persistent `snackbarMessages` pipeline (or keep the session error in
  `_recordingError` for re-display after unlock until explicitly cleared) instead
  of relying on the editor's short-lived collector. The fail-closed at-rest
  behavior (plaintext swept, no decrypt-without-key) must stay EXACTLY as-is.

## Verification

- New/updated pure-JVM + source-pin unit tests: a locked vault has no snackbar
  consumption (or the flow is cleared on lock); the voice-discard notice is
  emitted into the persistent channel on the lock path; the DEK-null sweep path
  unchanged.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-153/REPORT.md`.

## Definition of done

- Both findings closed with `file:line` before/after evidence.
- No vault-content snackbar renders over LockScreen; a lock-mid-recording
  surfaces an honest discard notice.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep the fail-closed
  at-rest voice handling (B1-DB-3) byte-intact.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.