# Phase 204 REPORT — Silent Data-Loss Batch: Orphaned Voice Recordings, Migration Overwrite, Start-Fresh Guard, Picker No-Ops

**Status:** DONE (2026-08-24). Four verified silent-data-loss / silent-no-op paths fixed. `gradle assembleDebug` green; `gradle testDebugUnitTest` 2778 total / 3 failures ALL reproduced on a clean stash immediately before this phase's changes (`Phase148UiFailureTextScrubTest` UNC-path — documented pre-existing; `PaparazziSmokeTest` ×2 layoutlib env — verified identical on stashed HEAD; `WikiLinkParserCacheUnitTest` concurrency flake passed this run and passes in isolation per AGENTS.md). No schema change, no new deps, `.github/workflows/` untouched.

---

## Fix 1 — Rotation mid-recording no longer orphans a SAVED recording [BUG/HIGH]

### Before (failure walkthrough)
1. User records voice on page X (`EditorScreen.kt:266` — `remember { VoiceNoteManager(context) }`, composition-scoped).
2. User rotates the device. The manifest has NO `configChanges`, so the whole activity/composition is destroyed.
3. `DisposableEffect.onDispose` → `release()` → `stopRecording()` → `finalizeRecording(null)` **succeeds**: MediaRecorder stops, the plaintext temp is AES-GCM-encrypted into `filesDir/voice_notes/*.enc`, temp deleted (`VoiceNoteManager.kt:304-317`). The success result is returned…
4. …into `release()`, which ignored it. `_completedRecordingResult` publishes ONLY on the ceiling-abort path (`limitMessage != null`); `discardOnRelease` is false (the save did NOT fail).
5. Result: the `.enc` blob sits on disk forever with NO DB row, NO audio embed, NO notice. Silent data loss.

### After
- `VoiceNoteManager` now tracks `lastFinishedResult` + `lastFinishedResultAttached`; every successful finalize records itself as UNATTACHED; both live attach sites (manual chip-tap stop, ceiling-abort observer) acknowledge via `markRecordingAttached()`.
- `release()` captures any UNATTACHED success into `unpublishedResultForRelay` BEFORE clearing it; `takeUnattachedRecordingForRelay()` is a one-shot accessor.
- Editor teardown (`EditorScreen.kt`, `DisposableEffect(voiceNoteManager)`) relays the unattached result to the NEW ViewModel-scoped slot: `viewModel.publishPendingVoiceRecording(page.id, recovered)` (`services/VoicePendingRecordingSlot.kt`, pure JVM, ConcurrentHashMap keyed by pageId).
- The NEXT editor instance for that page adopts it AFTER its initial canvas load (`LaunchedEffect(page.id, isInitialLoadComplete)` — attaching earlier would be clobbered by the async embed load at `EditorScreen.kt:686`): attaches the AUDIO_NOTE embed through the shared `attachVoiceRecording` path and surfaces the honest fixed notice `"Saved voice recording restored to this note."`.
- Consume-once semantics ⇒ never double-attaches; a new `startRecording` invalidates the prior latch; the phase-153 discard notice for FAILED saves is byte-intact (`release(): Boolean` signature unchanged).

### Tests
`Phase204VoicePendingSlotTest` (12): 7 slot-lifecycle unit tests (round-trip, one-shot consume, republish-keeps-latest, page independence, fixed notice) + 5 source pins (finalize records success as unattached; release captures unattached SUCCESS; teardown relays to the VM slot; ≥2 attach-site acks; post-load adoption + recovered notice; VM relay API present).

---

## Fix 2 — Legacy body migration can no longer overwrite a good column with "" [BUG/HIGH]

### Before (failure walkthrough)
1. Unlock-time sweep `migrateLegacyPlaintextNoteBodies` reads a legacy plaintext body via `AttachmentIngestPolicy.readTextHead(file)` (`AttachmentIngestPolicy.kt:56` pre-fix).
2. A transient I/O error mid-read (NFS hiccup, fd pressure, media scanner lock) was swallowed: `catch (e: Exception) { return "" }` (`:72-74`) — so the caller's throw-guard in `NoteRepository.kt:691-695` was DEAD CODE (readTextHead never threw).
3. Flow continued: `fileBody ("") != dbBody (real content)` ⇒ `updatePageBody(page.id, encrypted(""))` overwrote the GOOD encrypted column, then `file.delete()` destroyed the only plaintext copy. Permanent silent loss from one transient read error.

### After — the null contract
- `readTextHead(file, maxBytes = MAX_ATTACHMENT_BYTES, open = ::FileInputStream): String?`:
  - `""` = benign no-content (missing/unreadable/empty file or zero budget) — unchanged, documented;
  - `null` = the read STARTED but FAILED (any exception during open/read). A partial head is NEVER returned (partial bytes followed by a throw yield null, not the truncated prefix);
  - the stream factory is injectable (`open`) for pure-JVM tests; production callers use the default.
- Migration (`NoteRepository.migrateLegacyPlaintextNoteBodies`): null ⇒ `filesRemaining++` + skip the page ENTIRELY — no overwrite AND no delete; the migration flag stays unset so the sweep retries next unlock. The dead try/catch is gone.
- Display-path callers updated without behavior change: `NoteBodyVaultPolicy.resolveBodyForDisplay` falls through to the encrypted column on null; `WikiLinkParser.readFullText` appends nothing on null.

### Tests
`Phase204LegacyBodyMigrationGuardTest` (7): behavioral — injected-opener IOException ⇒ null; partial-read-then-fail ⇒ null (never the truncated body); benign cases still `""`; healthy default path intact. Source pins — migration skips BOTH writes before any `updatePageBody`/`delete` with retry accounting; display/wikilink callers handle null. `B2Dos05AttachmentIngestTest` updated for the nullable return (success head still non-null, pinned).

---

## Fix 3 — Keystore-loss "start fresh" aborts when vault files cannot move aside [BUG/HIGH]

### Before (failure walkthrough)
1. KeystoreKeyLostScreen → "Start fresh": `startFreshAfterKeystoreKeyLoss()` ran `quarantineVaultFiles("keystore-lost")` which wrapped each rename in `runCatching { renameTo(...) }` and swallowed every outcome (`NoteflowViewModel.kt:2961-2973` pre-fix).
2. If `noteflow.sqlite` (ENOSPC/EBUSY/fd held) failed to move aside, execution proceeded anyway to `clearDek()` + fresh-DEK boot.
3. The brand-new vault opened the OLD ciphertext file at the same path ⇒ decrypt/integrity fail ⇒ back to recovery. The escape hatch bricked exactly when needed. An unmoved `-wal` sidecar was equally fatal (SQLite WAL recovery against wrong-key bytes poisons the fresh DB).

### After
- New pure-JVM decision table `services/StartFreshVaultResetPolicy.kt`: `VaultFileRename(fileName, role, sourceExisted, moved)` outcomes + `decide()` matrix — Proceed iff EVERY existing vault file moved; ANY existed-but-unmoved file (main DB or wal/shm/journal) ⇒ `Abort(blockedBy)` naming the offenders; nothing-existed (brand-new install) ⇒ Proceed.
- `quarantineVaultFiles` returns the outcome list (renameTo result collected; exceptions ⇒ moved=false); `startFreshAfterKeystoreKeyLoss` evaluates it BEFORE touching anything: abort ⇒ fixed honest message surfaced via new `startFreshError: StateFlow<String?>` rendered on `KeystoreKeyLostScreen`, `return@launch` — no `clearDek()`, no fresh DEK, no `_keystoreKeyLost=false`, old vault untouched and still recoverable offline.
- Phase-163 wiring pins preserved (`clearKeystoreLostDismissal` still inside the start-fresh body; function order unchanged for the substringBefore anchors).

### Tests
`Phase204StartFreshRenamePolicyTest` (11): full outcome matrix (empty/no-files/all-moved/main-only-moved proceed; unmoved main DB aborts; unmoved sidecar aborts; mixed lists ONLY unmoved names; abort message fixed/path-free/data-unchanged wording) + wiring pins (decision gates BEFORE clearDek; abort returns before keystore-lost reset; quarantine collects outcomes; MainActivity renders `startFreshError`).

---

## Fix 4 — Media pickers surface a notice instead of silently no-oping [BUG/MEDIUM]

### Before (failure walkthrough)
1. User picks an image from a cloud provider that is offline (or any provider that fails `openInputStream`).
2. `context.contentResolver.openInputStream(uri)` returns null; the bare `?.use { boundedReadBytes(it) }` produced `bytes == null`, and all three sites' `if (bytes != null) { … }` simply skipped everything. No snackbar, no error — the tap looked dead.

### After
- All FOUR bare picker sites now handle `bytes == null` with ONE fixed non-alarming sentence per kind from `UiFailureTextPolicy.pickerSourceUnavailable(kind)` (PHOTO_EMBED / CUSTOM_BACKGROUND / PAPER_TEXTURE / REFERENCE_UNDERLAY — fixed-text discipline, never exception text, always invites a retry):
  - photo embed picker (`EditorScreen.kt` ~:1246), custom background (~:330), paper texture (~:360) — the three sites named by the phase prompt;
  - reference underlay picker (~:1281) had the IDENTICAL bare pattern; fixed for the same failure class so the source-pin holds everywhere.
- The brush-preset import site already handled null explicitly (`"Could not read that brush file"`) — untouched, pattern precedent.

### Tests
`Phase204PickerNoOpTest` (5): behavioral (distinct fixed sentence per kind, deterministic, no paths, retry invitation) + source pins (exactly 5 code call sites; ZERO sites without a `bytes == null` guard within their callback; exactly 4 policy-routed snackbars; each guard precedes its success branch and shows a snackbar).

---

## Verification

| Command | Result |
|---|---|
| `gradle assembleDebug` | GREEN |
| `gradle testDebugUnitTest` | 2778 tests / 3 failures — all reproduced on clean stash HEAD (see header) |
| New tests | `Phase204VoicePendingSlotTest` (12) + `Phase204LegacyBodyMigrationGuardTest` (7) + `Phase204StartFreshRenamePolicyTest` (11) + `Phase204PickerNoOpTest` (5) = 35, all green; `B2Dos05AttachmentIngestTest` updated & green |

## Files touched

- `app/src/main/kotlin/com/authorss81/noteflow/services/VoicePendingRecordingSlot.kt` (new)
- `app/src/main/kotlin/com/authorss81/noteflow/services/StartFreshVaultResetPolicy.kt` (new)
- `app/src/main/kotlin/com/authorss81/noteflow/services/VoiceNoteManager.kt` (attach accounting + release capture + one-shot relay)
- `app/src/main/kotlin/com/authorss81/noteflow/services/AttachmentIngestPolicy.kt` (readTextHead null contract + injectable opener)
- `app/src/main/kotlin/com/authorss81/noteflow/services/NoteBodyVaultPolicy.kt`, `services/WikiLinkParser.kt` (null-safe call sites)
- `app/src/main/kotlin/com/authorss81/noteflow/services/UiFailureTextPolicy.kt` (PickerSourceKind + fixed picker text)
- `app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt` (migration skip-overwrite-and-delete on null)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt` (pending-slot relay API; start-fresh gate + startFreshError flow; outcome-collecting quarantineVaultFiles)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt` (teardown relay; markRecordingAttached at both attach sites; post-load recovery consumer; four picker null-guards)
- `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt` (KeystoreKeyLostScreen renders the start-fresh abort)
- Tests: 4 new classes + `B2Dos05AttachmentIngestTest` nullable-return update
