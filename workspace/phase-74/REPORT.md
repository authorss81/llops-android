# Phase 74 — B2-UI-5 (MEDIUM): non-atomic markdown note-body save

**Status:** `FIXED` 2026-08-16 — REPORT for `workspace/phase-74`.

## Finding (docs/security-report.md:589-595, row :871)

> B2-UI-5 (MEDIUM): the markdown note-body save is non-atomic and un-serialized.
> Old path before this phase:
>
> - Save side (`MainActivity.kt`, old `saveMarkdownNoteBody` via the editor's
>   `flushSave` dispose-flush and the BackHandler): `NonCancellable` +
>   `Dispatchers.IO` → `File(path).writeText(body)` — a plaintext truncate-then-write
>   to the legacy note-body file.
> - Read side (`MainActivity.kt`, both layout `produceState` blocks, old):
>   `File(path).readText()` directly on the same path.

Consequences that were live:
1. **Torn write.** Two overlapping flush saves (dispose-flush + BackHandler, or the
   produceState re-read) could interleave truncate/partial/complete and leave a torn
   file whose truncated tail was then edited + re-saved — permanent content loss.
2. **Stale-snapshot overwrite.** The `produceState` used the Room `page` flow
   snapshot as the editor body; a page whose last save had not yet flushed to the
   DB/source could be opened as showing the STALE committed body, edited, and the
   save then PERMANENTLY overwrote the newer committed content with the stale edit.
3. **Ordering.** Nothing guaranteed an older issued save could not land after a
   newer one (two `writeText` on the same file had no happens-before).

## Root cause

Since phase-44 the markdown body lives ONLY in the field-encrypted
`pages.extractedText` column (`NoteRepository.updatePageBody`, `NoteRepository.kt:414`,
AES-256-GCM). The old code still wrote the body through the legacy plaintext
`File.writeText` path as well (`NoteflowViewModel.saveMarkdownNoteBody`, old `:2208`),
so the write path was a plaintext truncate+write to `filesDir` — with no serialization
against the navigate-away flush (`MarkdownPreviewScreen.kt` `flushSave` dispose +
BackHandler) and no serialization/settle against the next page-open's
`produceState` read (`MainActivity.kt` old `File.readText`). A truncated file read
into the next editor session could then be edited and saved over the real body.

## Fix

### 1. New pure-JVM coordinator — `app/src/main/kotlin/com/authorss81/noteflow/services/MarkdownBodySaveCoordinator.kt`

- `issue(pageId, body, legacySourceFilePath, legacySourceFileType): SaveRequest` —
  stamps a monotonically increasing `seq` per page at CALLING-THREAD time. The
  issue order IS the latest-wins order (UI order).
- `commitLatest(request) { write }` — serializes every write through a per-page
  `kotlinx.coroutines.Mutex`; compares against `latestSeqByPage`; a superseded
  request returns `false` and the `write` is NEVER invoked; otherwise runs `write`
  exactly once under the mutex, records the seq, settles the request's deferred
  (idempotent, also on the throw path via `finally`). The latest-wins comparator is
  ISSUE-ORDER, never time-based — touching a page before a slow older write can
  never let a stale snapshot win.
- `awaitSettled(pageId)` — bounded (default `SETTLE_AWAIT_TIMEOUT_MS = 3000L`,
  `settleTimeoutMs` is a constructor test seam), returns as soon as there is no
  pending/committed request newer than the caller's baseline, and CHASES any newer
  request issued while the reader was waiting (loop over `latestSeqByPage`), so a
  read initiated at time T always waits for requests issued before T.
- Pure `kotlinx.coroutines` (Mutex, CompletableDeferred, withTimeoutOrNull) — no
  Android classes, unit-testable on the JVM.

### 2. ViewModel — `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`

- New field `private val markdownBodySaveCoordinator = MarkdownBodySaveCoordinator()`
  (~`:139`, next to `editorFlushPolicy`).
- `saveMarkdownNoteBody` rewired (`:2214-2278`):
  - VaultWriteGate /
    `EditorFlushPolicy.deferBody`(deferred body) lock semantics UNCHANGED
    (locked ⇒ deferred, never dropped, never plaintext — B2-UI-1 preserved).
  - Unlocked: `issue(...)` on the calling thread, then
    `viewModelScope.launch(Dispatchers.IO) { commitLatest { repository.updatePageBody(pageId, body); NoteBodyVaultPolicy.deleteLegacyNoteTextBody(legacySourceFilePath, legacySourceFileType, ImportExportService.getImportsDir(appContext)) } }`.
    A superseded request returns `false` → `return@launch` (no write at all). The
    single encrypted column write is the ONLY body write — transactional already.
  - `CancellationException` rethrown; `VaultLockedWriteException` → `deferBody`
    (re-write after unlock); other `Exception` with `encryptionKey == null` →
    `deferBody`, else the existing non-alarming `showSnackbar(...)`.
- NEW `readMarkdownNoteBody(pageId, fallbackExtractedText, fallbackSourceFilePath, fallbackSourceFileType)` (`:2291-2336`):
  - `markdownBodySaveCoordinator.awaitSettled(pageId)` — waits out any in-flight save.
  - Fresh `repository.getPageById(pageId)` — NEVER the possibly-stale flow snapshot.
  - Deflates to the composition's in-memory snapshot only when the fresh read
    cannot decrypt (a lock wiping the DEK racing the read) — so a transient
    key-lost race can never surface as an empty editor that would then be saved
    over the real body.
  - B1-AUTH-05 (phase-69): legacy file read still confined to
    `ImportExportService.getImportsDir(appContext)` (null root refuses).
- `flushPendingEditorSaves` body drain re-issued (`:2929-2988`): each deferred body
  is `issue(...)`d on the calling thread FIRST (so the flush is strictly OLDER than
  any user save issued after unlock — the flush write loses via `commitLatest` if the
  user saved first), then `commitLatest { repository.updatePageBody(request.pageId, request.body); deleteLegacyNoteTextBody(request.legacySourceFilePath, request.legacySourceFileType, ImportExportService.getImportsDir(appContext)) }`.

### 3. MainActivity — `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt`

- Both markdown `produceState` blocks (`:438` and `:536`) now read
  `viewModel.readMarkdownNoteBody(page.id, page.extractedText, page.sourceFilePath, page.sourceFileType)`
  inside `withContext(Dispatchers.IO)`.
- The old inline `File(path).readText()` editor-body read is gone — MainActivity no
  longer resolves note bodies or touches body files at all (the now-unused
  `ImportExportService` / `NoteBodyVaultPolicy` imports are removed).

## Verification

- `gradle :app:testDebugUnitTest` — 1386 total, 1383 green. The only 3 failures are
  pre-existing and PROVEN unrelated (they also fail identically on a stash of all
  phase-74 changes against base `ef33a49`):
  - `B1Plat01ReleaseSigningTest` × 2 — asserts on `docs/RELEASE.md` /
    `app/build.gradle.kts` (the app intentionally falls back to the auto-generated
    debug keystore when `KEYSTORE_FILE` is unset — see AGENTS.md; untouched here).
  - `WikiLinkParserCacheUnitTest` — documented cancellation-timing flake (untouched,
    pure-JVM cache class).
- `gradle assembleDebug` — green; debug APK 173,751,590 bytes.
- New/updated tests:
  - NEW `B2Ui5MarkdownSaveSerializationTest` (13). Behavioral:
    - a save superseded by a newer one never touches the store;
    - a slow OLDER write can never land after a NEWER one (the phase's headline);
    - even an in-flight write is serialized — the latest issued body is final;
    - 24-way torn-write simulator — concurrent flushes leave only a COMPLETE body,
      never `''`/`'partial'`, and the last-issued body is final;
    - a reader awaiting settle sees the fully committed value, never a partial one;
    - `awaitSettled` is immediate when the page has no pending save;
    - `awaitSettled` is bounded (100 ms seam) when a request never settles;
    - two pages serialize independently — no cross-page blocking;
    - a reader chases a request issued while it was already awaiting.
    Wiring pins (source-level):
    - MainActivity body reads route through `readMarkdownNoteBody`, no inline
      `NoteBodyVaultPolicy.resolveBodyForDisplay` and no `File` `readText` survive;
    - the ViewModel routes save → `issue`/`commitLatest` (fresh `getPageById`,
      `awaitSettled` read, flush issues before commit, `updatePageBody` +
      imports-root-confined legacy delete);
    - no plaintext body `writeText`/`File(` remains on the markdown save path.
  - UPDATED `B2Ui1LockedFlushTest` — body-flush source pins now match the
    issue→commitLatest shape (`repository.updatePageBody(request.pageId, request.body)`
    + `NoteBodyVaultPolicy.deleteLegacyNoteTextBody(request.legacySourceFilePath, …)`).
  - UPDATED `B1Auth05SourceFilePathTest` — the imports-root body-resolver pin moved
    from MainActivity (which no longer resolves bodies) to `NoteflowViewModel`
    (`readMarkdownNoteBody` passes `ImportExportService.getImportsDir(appContext)`).

## Checksum / secrets handling

- No secrets involved. No new keys, no logging of keys/passwords/decrypted content.
  `allowBackup="false"` + `data_extraction_rules.xml`, `ClipboardGuard` and FLAG_SECURE
  intact (untouched). The body is only ever held in memory; nothing persists
  plaintext anywhere.

## Out of scope (documented, not fixed here)

- The room `pages` flow still decrypts on the reading side (fine — reads carry the
  DEK correctly; `readMarkdownNoteBody` uses a fresh fetch).
- The in-memory `EditorFlushPolicy` deferred-body stash remains process-volatile
  (documented phase-49 trade-off; a durable queue is impossible without writing
  plaintext while locked).
- The two pre-existing `B1Plat01ReleaseSigningTest` asserts and the
  `WikiLinkParserCacheUnitTest` flake — unrelated, pre-existing, documented above
  and in AGENTS.md.

No schema change, no migration, no new dependencies, `.github/workflows/` untouched.