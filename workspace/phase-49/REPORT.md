# Phase 49 — B2-UI-1 (HIGH): Post-lock autosave / dispose-flush saves write PLAINTEXT into field-encrypted columns — FIXED

- **Date:** 2026-08-15
- **Finding:** `B2-UI-1` — *Post-lock autosave / dispose-flush saves write PLAINTEXT into field-encrypted columns (encrypt-or-plaintext fallback with saves left running past `lock()`)* (HIGH)
- **Scope:** one finding per phase (tight diff). No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched. Fix is API-floor-neutral (API 26+): the added classes are pure-JVM and the repository changes only replace a conditional-encrypt path with a fail-closed helper, so no `Build.VERSION` branch or device fallback is required.

## Root cause (before)

1. `NoteflowViewModel.lock()` (`NoteflowViewModel.kt:2055-2067` before) only zeroizes the DEK + nulls StateFlows; it never cancels pending editor saves. `triggerAutoSave`'s job (1 s delay, `EditorScreen.kt:464-471` before) deterministically outlives the lock on the auto-lock path.
2. `EditorScreen.kt:392-402` (before) `onDispose` unconditionally launched `NonCancellable + Dispatchers.IO` direct `repository.saveStrokesForPage`/`saveCanvasItemsForPage`/`saveLayersForPage` calls — no authenticated/key check.
3. `NoteRepository.kt:549-563,697-703,778-785` (before) used `if (encryptionKey != null) encrypt else rawText/rawTitle/rawExtracted` — a SILENT plaintext fallback: a locked (zeroized) vault persisted stroke `textContent`, `pointsJson` (ink geometry), sticky-note/embed bodies and `note_versions` bodies as raw text inside the SQLCipher DB.

## What changed (after) — `file:line`

### 1. New fail-closed gate — `services/VaultWriteGate.kt`

- `requireKey(dek: ByteArray?)` throws `VaultLockedWriteException("B2-UI-1: vault locked - refusing to write a plaintext row")` when the DEK is null — the single fail-closed choke point for every field-encrypted write.
- `persistNow(keyIsPresent: Boolean)` = the persist-vs-defer decision shared by the ViewModel flush APIs and `createNoteVersion`.

### 2. New locked-flush stash — `services/EditorFlushPolicy.kt`

- Pure-JVM `DeferredSave(pageId, strokes, stickyNotes, embeds, layers)` snapshot + `defer()` (Latest-wins per page, `LinkedHashMap`, returns `true` on replace → bounded), `deferredCount`, `drain()` (snapshot + clear), `isUnlocked`.

### 3. Repository fails closed everywhere — `data/repository/NoteRepository.kt`

- New `requireEncryptionKey()` helper (`:44`) = `VaultWriteGate.requireKey(encryptionKey)`.
- `updatePageBody` (`:409`), `createPage` (`:547`), `renamePage` (`:574`), `updatePageTitleAndTags` (`:587`), `saveStrokesForPage` (`:762`, DEK grabbed once per stroke with a B2-UI-1 comment), `saveMediaEmbedsForPage` (`:905`), `createNoteVersion` (`:986`). The old `?: rawTitle /* rawExtracted /* rawText /* pointsJson` elvis fallbacks are gone from the file (grep-verified zero hits).

### 4. ViewModel routes every editor page write through the gate — `NoteflowViewModel.kt`

- New field `editorFlushPolicy` (`:125`); new APIs `flushEditorPageSave` (`:2302`), `autosaveStrokes` (`:2325`), `saveLayersGated` (`:2341`), private `persistOrDefer` (`:2362`) and `flushPendingEditorSaves` (`:2395`).
- `persistOrDefer` decides via `VaultWriteGate.persistNow(...)`; any `VaultLockedWriteException`/null-key mid-write is caught and the snapshot stashed (never dropped, never plaintext, never crash).
- `flushPendingEditorSaves()` is called in BOTH unlock paths after `_authenticated.value = true` (`:2120` `verifyMasterPassword`, `:2242` `verifyBiometricsAndUnlock`) — stashed snapshots re-write ENCRYPTED with the live DEK; a re-lock during flush re-defers.
- `createNoteVersion` (`:2275-2284`) is now gated on `persistNow(...)` and catches `VaultLockedWriteException` — a locked version snapshot is REJECTED (not deferred), per the finding's "queued or rejected" requirement.
- Locked-open guards added to `applyWorkspaceTemplate`, `addPage`, `createNoteFromSharedContent`, `renamePage`, `updatePageTitleAndTags`, `autoTagLanguageOnSave`, `openOrCreateDailyNote`, `openPageByTitle` (`:1451`,`:1498`,`:1526`,`:1566`,`:1577`,`:1600`,`:1828`,`:1878`).

### 5. EditorScreen has zero direct repository page-writes left — `ui/screens/EditorScreen.kt`

- DisposableEffect dispose-flush (`:430`) → `viewModel.flushEditorPageSave(...)` (removed `NonCancellable` + direct IO repository calls; `import kotlinx.coroutines.NonCancellable` deleted).
- Debounced `triggerAutoSave` (`:505`) → `viewModel.autosaveStrokes(...)` with full snapshot.
- `handleMediaEmbedsChange` (`:653`), `handleStickyNotesChange` (`:691`), `handleLayersChange` (`:522` → `saveLayersGated`), `insertPage` (`:746`), BackHandler (`:857`) and the top-bar back button (`:884`) all route through `flushEditorPageSave`/`saveLayersGated`.

## Checksum / secrets handling

- No keys, passwords, salted hashes, or decrypted note content are logged, printed, or persisted in the new code paths. The only DEK references in the diff are in-memory byte arrays handed to `EncryptionService`.
- No new `INTERNET` usage, no new permissions, `allowBackup="false"`, ClipboardGuard and FLAG_SECURE untouched.

## Verification

- **`gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.B2Ui1LockedFlushTest"`** — `BUILD SUCCESSFUL`, 10/10 green.
- **`gradle testDebugUnitTest`** — `BUILD SUCCESSFUL`, **1030 tests, 0 failures, 0 errors** (was 1020; +10 new `B2Ui1LockedFlushTest`).
- **`gradle assembleDebug`** — `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` produced (173 MB). (First invocation reported a transient FAILURE; a re-invocation completed the build and a further re-invocation was fully `UP-TO-DATE` green with the APK on disk — a daemon/task-environment hiccup, no task ever recorded a failure.)
- **New pure-JVM tests** — `app/src/test/java/com/authorss81/noteflow/B2Ui1LockedFlushTest.kt` (10 tests) — behavior + source-pin:
  1. zeroized-key write throws `VaultLockedWriteException`, 0 rows persisted;
  2. unlocked write encrypts + decrypt round-trips;
  3. lock→write→unlock: snapshots deferred while locked, drained encrypted after unlock;
  4. latest-wins per page; stash bounded; never dropped;
  5. all unlock-cycle rows decryptable (strokes textContent + pointsJson, sticky/embed textContent, note_versions);
  6. write-side source pins: every named `suspend fun` in NoteRepository requires the DEK and contains no `encryptionKey?.let` null-key fallback; no `?: rawTitle`/`?: rawExtracted`/`else { rawText }`/`else { pointsJson }`;
  7. EditorScreen has no `viewModel.repository.save*` call site left (reads only);
  8. ViewModel exposes the gate APIs; `flushPendingEditorSaves()` appears in BOTH unlock paths;
  9. `createNoteVersion` is gated + catches `VaultLockedWriteException`;
  10. `lock()` still zeroizes the DEK and no longer leaves a schedulable save.

## Out-of-scope (documented, NOT fixed here)

- **B2-UI-2 (phase-60?):** lock paths do not scrub the system clipboard — separate MEDIUM finding, untouched.
- **B2-UI-3 (data-integrity):** the shared `lastSavedStrokeHash` HashMap + concurrent `saveStrokesForPage` races — still a live MEDIUM finding, untouched in this phase.
- **B2-UI-4 (unlock state re-init) / B2-UI-5 (non-atomic markdown file writes):** separate LOW/MEDIUM findings; the phase-49 diff intentionally did not touch the markdown `File.writeText` path (that is a companion to B1-DB-4).
- First-invocation transient `assembleDebug` failure: not reproducible (subsequent runs green, all tasks `UP-TO-DATE`, APK present).