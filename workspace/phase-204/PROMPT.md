# Phase 204: Silent Data-Loss Batch — Orphaned Voice Recordings, Migration Overwrite, Start-Fresh Guard, Picker No-Ops [BUG/HIGH]

**Goal:** Four verified failure paths where the app silently destroys or loses user data without any notice.

1. **Rotation mid-recording permanently orphans the saved audio.** `EditorScreen.kt:266` `remember { VoiceNoteManager(context) }` is composition-scoped; on dispose `release()` (`:277`) DISCARDS the success result of `VoiceNoteManager.finalizeRecording` (`VoiceNoteManager.kt:304-317`) — the `.enc` blob IS written but `_completedRecordingResult` publishes only when a limit message exists (`:315`). No `configChanges` in the manifest ⇒ any rotation finalizes: blob orphaned, no embed attached, no discard-notice (that fires only for `discardOnRelease == true`). The only sweeps target plaintext temps + imports, never voice_notes.
   **Fix:** hoist a ViewModel-scoped pending-result slot keyed by pageId; `release()`/`finalizeRecording` publishes there; the NEXT editor instance consumes it and attaches the embed (or surfaces an honest "recording recovered/saved" state). Unit-test the slot lifecycle; source-pin that release() can no longer drop a successful save silently.

2. **Legacy body migration overwrites a GOOD encrypted column with "" then deletes the plaintext source when a read throws.** `AttachmentIngestPolicy.readTextHead` swallows IO errors into `return ""` (`AttachmentIngestPolicy.kt:72-74`) so the caller's throw-guard is dead (`NoteRepository.kt:691-695`); flow proceeds to `updatePageBody(page.id, encrypted(fileBody))` (`:696-701`) + `file.delete()` (`:703`). A transient read error during unlock-time migration = permanent silent loss.
   **Fix:** `readTextHead` returns `String?` (null = read error) or rethrows IOException; migration treats null as skip BOTH overwrite AND delete, increments `filesRemaining`, retries next unlock. Pure-JVM tests: error → file preserved + column untouched; partial-read contract documented.

3. **Keystore-loss "start fresh" boots a broken vault if renames fail.** `NoteflowViewModel.kt:2970` wraps each vault-file rename in `runCatching { renameTo(...) }` with results swallowed, then proceeds to `clearDek()` + fresh init (`:2945-2956`). If `noteflow.sqlite` fails to move aside, the new vault opens the OLD ciphertext file → decrypt/integrity fail → recovery loop; the escape hatch bricks exactly when needed.
   **Fix:** collect rename outcomes; ABORT start-fresh with a surfaced error unless the main DB (+wal/shm) actually moved; pure-JVM decision helper + tests for the outcome matrix.

4. **Media pickers silently no-op** when `openInputStream(uri)` returns null (cloud provider offline): photo embed (`EditorScreen.kt:1185`), custom background (`:335`), paper texture (`:365`) all lack an else branch.
   **Fix:** surface one non-alarming snackbar per repo policy (`UiFailureTextPolicy` pattern) at all three sites; source-pin test asserting no bare `?.use { }` picker remains.

## DoD
`gradle assembleDebug` green; `testDebugUnitTest` green incl. new readTextHead-null-contract tests, start-fresh outcome-matrix tests, pending-result slot tests; `workspace/phase-204/REPORT.md` with before/after behavior walkthroughs for all four paths. No schema change, no workflow edits.
