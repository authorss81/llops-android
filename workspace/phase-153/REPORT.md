# Phase 153 — Post-lock UI channels: snackbar host gated on auth + voice-discard notice re-surfaced (R2-b2b1-UI-04 + R2-b2b1-UI-05)

Both findings are `CLOSED`. Source: `docs/security-report-round2.md`.

## R2-b2b1-UI-04 — Root SnackbarHost composed OUTSIDE the LockScreen conditional (LOW)

### Before
- `MainActivity.kt` (pre-fix): the collector `LaunchedEffect(Unit) {
  viewModel.snackbarMessages.collect { ... } }` at activity root consumed
  unconditionally, and the `SnackbarHost` was a direct child of the nav `Box`
  (`.align(Alignment.BottomCenter)`), NOT inside the
  `if (hasMasterPassword && !authenticated) LockScreen(...)` branch — so any
  message enqueued past `lock()` (restore/import outcomes via the
  `viewModelScope` pipeline that phase-96 moved there so they survive screen
  teardown, plus note titles from screenshot-created notes) rendered over the
  locked UI for the full `Long` duration.
- The channel was a `MutableSharedFlow` (`NoteflowViewModel.kt`, `tryEmit`),
  which has **no clear primitive** — `lock()` could not purge pending messages.
- Producers (`HomeScreen.kt:161,202,256,269,290,600`,
  `EditorScreen.kt:1154,1188`, `NoteflowViewModel.kt:1452`) all ran through
  `viewModel.showSnackbar`, the single choke point — but the choke point had no
  auth gate.

### After
`app/src/main/kotlin/com/authorss81/noteflow/services/SnackbarLockPolicy.kt` (new, pure JVM)
- `VOICE_RECORD_DISCARDED_NOTICE` — the one fixed, honest message allowed across
  the lock boundary (R2-b2b1-UI-05).
- `mayBufferWhileLocked(isAuthenticated, text)` (`:41`) — the single decision
  table: **unlocked** → every message buffers (normal flow); **locked** → only
  `messageSurvivesLock(text)` notices buffer, every other message is DROPPED at
  the boundary (restore/import outcomes, note titles, plugin results).
- `MAX_PENDING = 16` — the bounded queue tail replacing the old
  `extraBufferCapacity = 16`.

`app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
- The channel is now a **bounded `StateFlow<List<SnackbarMessage>>` FIFO**
  (`:1344-1345`) so `lock()` can CLEAR it and the collector can be gated. The
  `showSnackbar` emission API is unchanged for all ~140 call sites.
- `showSnackbar` (`:1347`) routes through `SnackbarLockPolicy.mayBufferWhileLocked`
  and caps the queue via `takeLast(MAX_PENDING)`.
- `consumeSnackbar` (`:1362`, identity `filterNot { it === message }`) +
  `nextSnackbarMessage` (`:1369`) are the collector's ack/peek seam.
- `notifyVoiceRecordDiscarded()` (`:1378`) publishes the discard notice through
  the same persistent pipeline.
- `lock()` (`:4187`) CLEARS the queue inside the `hasMasterPassword` teardown
  (`:4252`) — anything pending at lock time (restore/import outcomes, note
  titles) can neither render over the LockScreen nor surface stale after unlock.
  Survive-lock notices are emitted AFTER this clear by the editor-teardown hook
  and pass the emission gate.

`app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt`
- The root collector is now `LaunchedEffect(authenticated)` (`:257`), keyed on
  the auth gate: while locked it does not run (nothing can render over the
  LockScreen), it dismisses a snackbar still showing at the boundary
  (`:259`), and on unlock it drains exactly the survive-lock notices. The host
  stays where it is (`:690-693`, bottom-center, TalkBack-reachable) — the gate
  is the collector, not a host move.

Dynamic `e.message` slots: phase-148 already routed every restore/import/backup
snackbar through `UiFailureTextPolicy` fixed-text classification (verified
repo-wide — the only remaining `${o.message}`/`${r.message}` are fixed plugin-
outcome strings, and the collector gate now prevents any of them from rendering
over the LockScreen).

## R2-b2b1-UI-05 — Locking mid-voice-recording silently destroyed the finished recording (LOW)

### Before
- `VoiceNoteManager` is per-editor (`EditorScreen.kt:183`), error observed via
  the editor subtree (`recordingError`). On lock: `lock()` zeroizes the DEK →
  recomposition dips to `LockScreen` → `DisposableEffect.onDispose` →
  `release()` → `stopRecording()` → `finalizeRecording`: `dek == null`
  short-circuits encryption, the plaintext temp is swept (fail-closed, correct),
  and `_recordingError.value = "The recording could not be saved securely…"`
  was set **after the editor's collector was gone** — nobody ever saw it, so the
  user believed the recording was saved.

### After
- `VoiceNoteManager.kt`: `finalizeRecording`'s sole fail-closed branch
  (`!encrypted`, `:268`) sets `discardOnRelease = true`; `release()` (`:454`)
  is now `release(): Boolean` — stops recorder/playback, cancels the scope,
  keeps the `VoiceNoteCrypto.sweepPlaintextTemps` sweep **byte-intact**
  (B1-DB-3), and returns a one-shot `discarded` flag.
- `EditorScreen.kt` `DisposableEffect.onDispose` (`:255`): when `release()`
  reports a discard, `viewModel.notifyVoiceRecordDiscarded()` publishes the
  fixed notice into the persistent snackbar pipeline — the ONLY message the
  emission gate allows while locked — so it surfaces once the vault unlocks
  instead of dying with the editor's short-lived collector.
- The fail-closed at-rest behavior is unchanged: `val dek = VaultKeyHolder.dek`
  still gates encryption, the null-DEK short-circuit still destroys the
  plaintext temp, and nothing is persisted without the DEK.

## Verification

- `gradle testDebugUnitTest` — **2117 tests (2105 + 12 new), 1 failure**: the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path assertion
  (`\\fileserver\share\secret-wills.docx` redaction) — reproduced on clean
  stashes in phases 149-152, untouched by this phase (see AGENTS.md).
- `gradle assembleDebug` — green.

New tests: `app/src/test/java/com/authorss81/noteflow/Phase153LockedSnackbarPolicyTest.kt` (12):
- Decision table: unlocked buffers everything; locked drops every vault-content
  message (restore/import/backup/note-title/"vault is locked" strings) and
  buffers only the discard notice; `messageSurvivesLock` accepts exactly the
  fixed notice.
- Notice honesty: states the discard + cause, never claims the audio was saved,
  no interpolation.
- Source pins: MainActivity collector gated on `authenticated` + boundary
  dismiss + FIFO drain; VM `lock()` clears inside the hasMasterPassword
  teardown; `showSnackbar` routes through the gate on a `StateFlow<List>` FIFO
  with bounded tail; `notifyVoiceRecordDiscarded` publishes the constant;
  `VoiceNoteManager` DEK-null finalize path unchanged + `discardOnRelease`;
  `release()` keeps the plaintext sweep and returns the discard; EditorScreen
  teardown republishes through the ViewModel pipeline.

No DB schema change, no `.github/workflows/` edits, no new dependencies. No keys,
passwords or decrypted note content are logged or touched.
