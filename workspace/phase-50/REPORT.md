# Phase 50 — B2-DOS-01 (HIGH): Unbounded stroke geometry allows OOM/ANR via crafted backup or organic heavy page — FIXED

- **Date:** 2026-08-15
- **Finding:** `B2-DOS-01` — *Unbounded stroke geometry (`pointsJson`) — editor/restore/canvas can OOM or ANR with a crafted backup or heavy page* (HIGH)
- **Scope:** one finding per phase (tight diff). No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched, all new classes pure-JVM (`deserializeStrokes` guard, DAO SQL + repository/VM wiring, canvas culling are the only touched existing files). API-floor-neutral (API 26+): the fix is loop/LIMIT/SQL rhetoric, no `Build.VERSION` branch or hardware fallback required — the snackbar notice covers "never silent degradation".

## Root cause (before)

1. `NoteRepository.getStrokesForPage` (`NoteRepository.kt:443-503` before) did a single full-page `getStrokesForPage(pageId)` → `deserializeStrokes` over EVERY row with `Gson.fromJson` in RAM (`EncryptionService.kt:127-135` before, un-capped). A backed-up row holding a ~2,000,000-point stroke (~100 MB JSON) → page-open decodes the whole blob in one allocation — B1-DB-7-style crafted backups and organic heavy pages both OOM/ANR the app.
2. `Daos.kt:167` (before) fetched with no `LIMIT`/`ORDER BY`, so the whole page's rows materialized regardless of size.
3. `NoteRepository.saveStrokesForPage` (`:533-587` before) never bounded input — a runaway autosave loop could write arbitrary geometry.
4. `ImportExportService.kt:1414-1429` (before) transplanted restored stroke rows verbatim — a crafted backup's monster rows landed in the live vault untouched.
5. `AnnotationCanvas` O(points) work per frame — the renderer walks every ink point every frame (`AnnotationCanvas.kt:2610,2217` before), so total points scale renderer work linearly even for off-screen pages.

## What changed (after) — `file:line`

### 1. New pure-JVM budget & gate policy — `services/StrokeGeometryPolicy.kt`

- Budgets (`:46-57`): `MAX_POINTS_PER_STROKE = 20_000` (~5.5 min of continuous 60 Hz pen input), `MAX_POINTS_PER_PAGE = 200_000`, `MAX_STROKES_PER_PAGE = 2_000`, `MAX_STROKES_LOAD_BATCH = 128`, `MAX_STROKE_JSON_PLAINTEXT_CHARS = 2_500_000`, `MAX_STORED_POINTS_JSON_CHARS = 3_400_000`.
- `storedPointsJsonOverBudget` (`:60`) — ciphertext `length()` is an **exact** proxy for plaintext size: AES-GCM does not compress, base64 fixed-ratio overhead only.
- `plaintextPointsJsonOverBudget` (`:64`), `totalPoints` (`:67`), `gateStroke` (`:78`, truncates a per-stroke-oversized stroke to its head), `applySaveGate` (`:92`) → `StrokeGeometryGateResult` (truncates oversize strokes to head, DROPS strokes that overflow the page budget, meters `beforePoints/afterPoints/beforeStrokes/afterStrokes/truncated/dropped` + human `noticeText`), `capLoadedPoints` (`:137`, belt-and-braces equivalent of `gateStroke` on the load side).

### 2. Bounded, length-gated DAO read — `data/db/Daos.kt:190`

- `getStrokesForPageBounded(pageId, maxStoredChars, limit, offset)` — `WHERE pageId = :pageId AND length(pointsJson) <= :maxStoredChars ORDER BY ROWID ASC LIMIT :limit OFFSET :offset`. Query-only: **no schema change, no migration**. The `length()` gate runs on the stored ciphertext before any decrypt; pages are fetched in `MAX_STROKES_LOAD_BATCH = 128`-row pages.

### 3. Save-side gate — every write routed through the policy — `data/repository/NoteRepository.kt:793`

- `saveStrokesForPage` now returns `StrokeGeometryGateResult`; every `StrokeEntity` construction (`NoteRepository.kt:851` — grep-verified the sole stroke writer) receives `StrokeGeometryPolicy.applySaveGate(strokes)` output BEFORE the DEK-ciphertext write. `saveCanvasItemsForPage`/`saveLayersForPage` route their stroke sets through the same gate.

### 4. Load-side belt-and-braces — `NoteRepository.kt:638`, `EncryptionService.kt:470`

- `getStrokesForPage` (`:638`) now: reads via `getStrokesForPageBounded` batches, drops an over-`MAX_STORED_POINTS_JSON_CHARS` row, refuses plaintext over `MAX_STROKE_JSON_PLAINTEXT_CHARS` (skips decode), `capLoadedPoints` per stroke, and **stops** at `MAX_STROKES_PER_PAGE`/`MAX_POINTS_PER_PAGE` — never materializes more than the renderer could draw. `lastSavedStrokeHash` semantics preserved.
- `EncryptionService.deserializeStrokes` (`:470`) refuses `json.length > MAX_STROKE_JSON_PLAINTEXT_CHARS` BEFORE any Gson allocation and returns `emptyList` — a second independent chokepoint so no call site can decode a monster blob.

### 5. Restore-time strip — `services/ImportExportService.kt:1680` `sanitizeRestoredStrokeGeometry`

- Runs inside the candidate loop of `validateAndPrepareRestoredDb` (after `PRAGMA integrity_check`/`user_version`, BEFORE re-key/migrate/transplant): `DELETE FROM strokes WHERE length(pointsJson) > 3_400_000` in the temporary backup DB. Crafted monster strokes never reach the live vault; pages and compliant strokes are untouched. "no such table" errors (aged backups without strokes) are swallowed.

### 6. One-time non-alarming notice — `ui/viewmodel/NoteflowViewModel.kt:132-136, 2861`

- `maybeNotifyGeometryCapped(pageId, gate)` shows a snackbar ONCE per page per SESSION (`geometryCappedNotifiedPages` latch, cleared in `lock()` at `:2861`) so a cap is never silent but autosave never spams. Wired into all three save paths (`flushEditorPageSave` `:2378`, `autosaveStrokes` `:2401`, `flushPendingEditorSaves` `:2510`).

### 7. Renderer viewport culling — `ui/components/AnnotationCanvas.kt:1415-1434`

- Paginated-mode page-slab culling: a page whose `pageTopY..pageBottomY` doesn't intersect the visible world rect `((0 - pan)/zoom .. (size - pan)/zoom)` is `continue`'d BEFORE paper/template/page-bitmap/stroke-filter/layer-raster — off-screen pages no longer pay O(strokes)/frame, and spot-zoom no longer scales work by total canvas points. Horizontal off-world band guards added.

## Checksum / secrets handling

- No keys, passwords, salted hashes, or decrypted note content are logged, printed, or persisted in the new paths. The gate operates on in-memory `List<Stroke>` geometry only; the restore strip runs on the SQLCipher-encrypted temp DB, not plaintext.
- No new `INTERNET` usage, no new permissions, `allowBackup="false"`, ClipboardGuard and FLAG_SECURE untouched.

## Verification

- **`gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.B2Dos01StrokeGeometryTest"`** — `BUILD SUCCESSFUL`, 18/18 green.
- **`gradle testDebugUnitTest --rerun`** — `BUILD SUCCESSFUL`, **1053 tests, 0 failures, 0 errors** (baseline at commit `5d76259` = 1035; +18 new `B2Dos01StrokeGeometryTest`).
- **`gradle assembleDebug`** — `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` produced (173,664,442 bytes, Aug 15 14:13). (First invocation: transient Gradle daemon failure; re-run completed green, subsequent run fully `UP-TO-DATE`.)

## New tests — `app/src/test/java/com/authorss81/noteflow/B2Dos01StrokeGeometryTest.kt` (18)

Behavior + source-level wiring pins (pure JVM, no Robolectric):
1. sub-budget strokes pass `applySaveGate` unchanged;
2. oversize single stroke truncated to exactly `MAX_POINTS_PER_STROKE` head, tail dropped, `truncated++`;
3. page-point budget: cumulative points stop at `MAX_POINTS_PER_PAGE`, later strokes dropped;
4. stroke-count budget: stops at `MAX_STROKES_PER_PAGE`;
5. `drop` counter + zero-point/no-op inputs behave;
6. `noticeText` mentions drops/truncations, empty for no-op;
7. `gateStroke`/`capLoadedPoints` agree (head-preserving truncation);
8. ciphertext-vs-plaintext budget: `storedPointsJsonOverBudget` accepts values the plaintext check accepts and rejects farther-out ones (AES-GCM no-compress proxy);
9. serialization: `deserializeStrokes` refuses over-budget plaintext, returns `emptyList` (guard BEFORE Gson);
10.-11. `saveStrokesForPage` returns a gated `StrokeGeometryGateResult` and only writes sub-budget rows;
12. `getStrokesForPage` never returns more than the page budget from an oversized fixture;
13. repo save fun signature returns `StrokeGeometryGateResult` (source pin);
14. `NoteRepository` applies `StrokeGeometryPolicy.applySaveGate` inside the save path (source pin);
15. `getStrokesForPage` calls `getStrokesForPageBounded` and no longer calls the un-bounded DAO (source pin);
16. `Daos.kt` `getStrokesForPageBounded` contains `LIMIT :limit OFFSET :offset` + `length(pointsJson)` (source pin);
17. `ImportExportService` contains `sanitizeRestoredStrokeGeometry` with a `DELETE FROM strokes` length predicate; `EncryptionService.deserializeStrokes` has the plaintext guard (source pin);
18. `AnnotationCanvas` contains the `visibleTop`/`visibleBottom` culling block (source pin).

## Out of scope (documented only, NOT fixed)

- **B1-MEM-01-flavored canvas portion**: each frame still builds one in-memory bitmap of the visible region; with the 200k-point/page and viewport culling caps the surviving worst case is one bounded visible slab → bounded bitmap, so the original DoS vector is closed. A sub-region dirty-rect raster could halve steady draw cost — noted for the performance roadmap, not this phase.
- **`getCanvasItemsForPage`/`getLayersForPage`** read paths were checked and are small-row enumerations (sticky embeds, layer list) — bounded by existing note-size expectations, not stroke geometry; a `length()`-gate variant would be future hardening, not required by this finding's evidence.
- **Restore WAL/`-wal` re-packing**: `sanitizeRestoredStrokeGeometry` deletes rows in the open temp DB (WAL journaled); rows deleted pre-transplant never reach the live vault. A future hardening could also sweep `-wal` copies — not reachable by this finding's exploit chain (restore opens the backup DB, not its WAL).

## Docs

- `docs/phase-status.md` — phase-50 row `DONE` (added).
- `docs/ARCHITECTURE.md` — "Implemented in phase-50" note appended to the encryption/vault anchor.
- `docs/security-report.md` — B2-DOS-01 row flipped `NOT STARTED` → `FIXED` (both the finding at `:642` and the status table at `:836+`).