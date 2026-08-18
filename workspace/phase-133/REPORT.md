# Phase 133 — Add Page FAB & Daily Journal: new pages must open immediately

**Status: DONE (2026-08-18)**

## The bug (owner-confirmed)

After creating a new note via the Add Page FAB, or opening/creating a Daily
Journal entry, the new page **did not open** — the click appeared to do nothing
because the screen transition was lost on the immediate frame.

### Root cause

`MainActivity` resolved the active page ONLY against `viewModel.pages`
(`MainActivity.kt`, pre-fix):

```kotlin
val activePage = pages.find { it.id == activePageId }
```

`viewModel.pages` is the **section-filtered** Room-backed flow. Room emits
asynchronously, so on the exact frame of creation the brand-new page is not yet
in `pages`:

- `Add Page FAB` → `createPage` → `selectPage` → `activePageId` set
- `pages.find { it.id == activePageId }` on the same frame → **null**
  (the create is still in flight in the Room flow)
- → the editor/transition code sees `activePage == null` and the navigation is
  dropped. Nothing opens.

The Daily Journal path had the same symptom plus a second latent defect: the
create branch ran inside `writeGuardedAgainstLock` with `selectPage(newPage)`
but **without re-synchronizing the section observation** — `observePages(sec.id)`
was only called on the existing-branch or the pre-133 code path, so the
section-filtered `pages` flow never re-observed the section the new page was
created in.

## Fix

### 1. Pure-JVM fallback resolution + synchronous tracker

New `app/src/main/kotlin/com/authorss81/noteflow/services/ActivePageResolution.kt`:

- `ActivePageResolution.resolve(activePageId, synchronous, allActivePages,
  sectionPages)` — returns the first match in **order of precedence**:
  1. the **synchronous in-memory copy** (`synchronous` — the page captured at
     open/create time, available on the exact frame of creation);
  2. the global `allActivePages` list (catches a page created in a section that
     is not the currently observed one);
  3. the section-filtered `pages` list (the original source).
- `ActivePageTrackerState(id, synchronous, confirmed)` — the state holder.
- `ActivePageTracker` — the pure-JVM state machine:
  - `open(current, page, allActivePages, sectionPages)` — captures the page
    synchronously; `confirmed` only when an authoritative list already knows it
    (an existing page opened from a list). A brand-new page stays unconfirmed —
    exactly the window the old code lost the transition in.
  - `onAuthoritative(current, allActivePages, sectionPages)` — refresh on every
    Room emit: page present → refresh copy + confirm; page **absent but was
    confirmed** → genuinely deleted/trashed → idle (never a stale editor);
    page absent + unconfirmed → creation-frame race → keep the copy.
  - `restore(savedId, allActivePages, sectionPages)` — re-arm the persisted
    page id (launch / unlock / config change); idle for blank or gone ids.

### 2. `MainActivity.kt:226-294`

- Collects `allActivePages` (`MainActivity.kt:230`) as the second fallback.
- Holds the synchronous copy in `var activeTracker by remember { … ActivePageTrackerState() }`
  (`MainActivity.kt:239`).
- Resolves `activePage` through `ActivePageResolution.resolve(...)` with
  `synchronous = activeTracker.synchronous` (`MainActivity.kt:246-251`).
- `setActivePage` routes through `ActivePageTracker.open(...)`
  (`MainActivity.kt:252-258`).
- Restore effect `LaunchedEffect(authenticated, pages, allActivePages)`
  re-arms the saved page id via `ActivePageTracker.restore`
  (`MainActivity.kt:263-275`).
- Sync effect `LaunchedEffect(allActivePages, pages)` runs
  `ActivePageTracker.onAuthoritative`; when a confirmed page disappears
  (deleted/trashed) it drops `activePageId` + `settings.activePageId`
  (`MainActivity.kt:282-294`).

### 3. `NoteflowViewModel.kt` — synchronize observation + main-thread dispatch

- `openOrCreateDailyNote` (`NoteflowViewModel.kt:2324-2390`): the created page is
  **returned from the guard** (was: callback fired inside the block on the launch
  context); the create branch now calls `observePages(sec.id)` (`:2381`) so the
  page is present in the section-filtered `pages` flow; `onOpen(page)` is
  dispatched via `withContext(Dispatchers.Main)` (`:2388`).
- `openPageByTitle` (`NoteflowViewModel.kt:2392-2439`): identical restructure —
  returned page, `observePages(sec.id)` (`:2430`), `withContext(Dispatchers.Main)`
  (`:2437`).
- `createNoteFromSharedContent` (`NoteflowViewModel.kt:1871-1922`): same latent
  defect — created page never re-observed; added `observePages(sec.id)` (`:1916`).

## Tests

`app/src/test/java/com/authorss81/noteflow/Phase133ActivePageResolutionTest.kt`
(19 tests, pure JVM, no Android deps):

- `null activePageId resolves to null`
- `null immediate frame — synchronous copy wins even when async lists lack the page` (the bug)
- `synchronous copy takes precedence over both async lists`
- `without synchronous copy — allActivePages beats the section-filtered pages`
- `without synchronous or allActivePages match — section pages wins`
- `id present in no list resolves to null` / `synchronous copy with a different id is ignored`
- tracker: `open` null/brand-new/existing/replace, `onAuthoritative`
  refresh/confirmed-deleted-drop/creation-frame-survive/idle-no-op,
  `restore` blank/section/allActivePages/not-found.

## Verification

- `gradle testDebugUnitTest` → **BUILD SUCCESSFUL**: app **1825 tests +
  plugins:llm 50 tests = 1875 total, 0 failures / 0 errors / 0 skipped**
  (plugin-sdk NO-SOURCE).
- `gradle :app:assembleDebug` → green (57/57). A first plain invocation hit the
  documented transient packaging flake; the forced `--rerun-tasks` run executed
  57/57 and passed. Debug APK
  `app/build/outputs/apk/debug/app-debug.apk` SHA-256 `919a2d32…`.

## Definition of done

- [x] New notes (Add Page FAB) and Daily Journal entries open immediately on click
      (synchronous in-memory copy resolves on the creation frame)
- [x] No regression to section switching, back-navigation, or page restoration
      (fallback lists + restore effect re-verify id on launch/unlock/config change;
      sync effect drops deleted/trashed pages instead of leaving a stale editor)
- [x] REPORT.md committed

## Constraints honored

- No DB schema change; no migration; no new dependencies
  (`gradle/verification-metadata.xml` untouched).
- `.github/workflows/` untouched.
- No logging of keys/decrypted content; `allowBackup=false`, `ClipboardGuard`,
  FLAG_SECURE untouched.
- Low-end safe: pure-JVM object + two `remember`-ed locals; `withContext(Dispatchers.Main)`
  is a no-op under the existing `Main.immediate` launch context; no new I/O or threads.
