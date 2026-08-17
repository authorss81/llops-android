# Phase 134: Lock-vs-inflight race — every write/search/read path guarded against the disposed SQLCipher pool [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-B1A-01, R2-B1A-02, R2-b2b1-UI-01) and `docs/phase-status.md` +
`docs/ARCHITECTURE.md`. This phase fixes the three lock-vs-inflight-coroutine
crash findings as ONE coherent change: the write guard, the search-job cancel,
and the read-side guard all protect against `lock()` disposing the SQLCipher
pool under in-flight DAO work.

## Source findings (all OPEN, MEDIUM)

1. **R2-B1A-01** — ~18 page/notebook/section/tag/pin/trash/template mutations
   bypass `writeGuardedAgainstLock` (used only at `NoteflowViewModel.kt:3177,
   3187`): `updateNotebookTags :1728`, `deleteNotebook :1736`, `addSection
   :1750`, `renameSection :1758`, `deleteSection :1767`, `updatePageTags
   :1971`, `renameTag :1980`, `deleteTag :2007`, `togglePinPage :2032`,
   `trashPage :2038`, `updatePageTemplate :2047`, `restorePage :2260`,
   `deletePagePermanently :2266`, `movePage :2278`, `emptyTrash :2284`, palette
   ops `:2290-2306`. `lock()` disposes the pool at `:3668-3673`. A racing
   in-flight DAO call throws `IllegalStateException("connection pool has been
   closed")` → process crash.
2. **R2-B1A-02** — `searchVaultJob` (`:2098`) is cancelled only on a new
   keystroke (`:2101,:2116`), never in `lock()` (`:3638-3694`); `deepSearchPages`
   (`NoteRepository.kt:550-564`) + `searchPages` hit the disposed pool, and
   `onResult` (`:2105,:2120`) only guards with `ensureActive()` so a completed
   search can publish decrypted results after auth dropped.
3. **R2-b2b1-UI-01** — READ-side composition-scoped loads crash on lock:
   `EditorScreen.kt:424-444` (`getStrokesForPage :425`, `getLayersForPage :428`,
   `getCanvasItemsForPage :432`), `KnowledgeGraphScreen.kt:155-157`
   (`getAllActivePages` decrypts the whole vault), `BacklinksInspector.kt:48-64`,
   `TagExplorerView.kt:43-55`, `TagManagerDialog.kt:40-59`,
   `VersionHistoryBottomSheet.kt:38-42`, `UnifiedSidebar.kt:54,61`. No catch,
   no re-check of `authenticated` before assigning state.

## The fix (where & how)

- **Writes:** Route every site listed in R2-B1A-01 through the existing
  `writeGuardedAgainstLock` / `isLockRacedWrite` helper (`NoteflowViewModel.kt:
  3170-3190`). Do not silently swallow failures — the guard already surfaces a
  "Vault is locked" notice; re-use that exact pattern.
- **Search:** In `lock()` (`:3638-3694`) add `searchVaultJob?.cancel()` and clear
  `_searchResults`; in the search launch (`:2098-2120`) re-check `_authenticated`
  before invoking `onResult`, and wrap the DAO calls so a closed-pool throw is
  classified (via `isLockRacedWrite`) and suppressed instead of escaping.
- **Reads:** Introduce one shared checked accessor in `NoteflowViewModel` (e.g.
  `withLockedPoolGuard { }`) that catches the closed-pool `IllegalStateException`
  and returns `emptyList()`/null, and route every read-site in R2-b2b1-UI-01
  through it; each `LaunchedEffect` must also re-check `authenticated` before
  writing the loaded list into state.

## Verification

- New pure-JVM unit tests (repo layout `app/src/test`): a model test proving
  lock cancels search + clears results; a guard test proving every R2-B1A-01
  site is source-pinned to `writeGuardedAgainstLock`; a read-side test proving
  closed-pool reads fail closed without crashing.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report outcomes in
  `workspace/phase-134/REPORT.md`.

## Definition of done

- All three findings closed with `file:line` before/after evidence in REPORT.md.
- No write path regressed (the guard's existing notice UX is preserved).
- New unit tests prove the fix; no existing test regressed.
- OS/API floor 26+ respected; reduce-motion/low-end rules unchanged.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE, and the fail-closed lock model intact.
- Do not fix OTHER findings in this phase — document anything new you find in
  REPORT.md, do not fix it here.
