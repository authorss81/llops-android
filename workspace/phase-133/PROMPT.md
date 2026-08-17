# Phase 133: Add Page FAB & Daily Journal — new pages must open immediately [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE BUG (owner-confirmed):** after creating a new note via the Add Page FAB,
or opening/creating a Daily Journal entry, the new page **does not open / the
click appears to do nothing** — the screen transition is lost on the immediate
frame.

## What to do
- **`app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt`**: the active
  page state is currently resolved only against `viewModel.pages` (which is
  filtered to the currently active section). When a new note or daily journal
  entry is created, **asynchronous Room emissions** mean
  `pages.find { it.id == activePageId }` returns **null on the immediate
  frame**, preventing the transition.
  - Add **synchronous in-memory tracking** (`activePageObject`) so newly
    created pages open on the exact frame of creation.
  - Resolve `activePage` with **fallback matching** against
    `activePageObject`, `allActivePages`, and `pages` (in that order).
- **`app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`**:
  `openOrCreateDailyNote` and `openPageByTitle` must (a) synchronize the active
  **section observation** (`observePages(sec.id)`) and (b) guarantee the
  `onOpen` callback is dispatched safely via `withContext(Dispatchers.Main)`.
- Where feasible, extract the fallback-resolution logic into a pure-JVM
  testable helper and add unit tests (null-immediate-frame → fallback wins,
  order of precedence).

## Verification
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).
- REPORT.md with file:line evidence (before/after).

## Definition of done
- New notes (Add Page FAB) and Daily Journal entries open immediately on click;
  no regression to section switching, back-navigation, or page restoration;
  REPORT.md committed.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact. Low-end safe.