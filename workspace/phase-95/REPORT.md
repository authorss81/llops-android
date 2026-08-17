# Phase 95 — B2-UI-4: lock() clears the session StateFlows but unlock() never re-establishes them (VERIFY-ONLY)

> **Status: `DONE` — no production code change required.** B2-UI-4 (LOW) is
> already closed in the current tree. The fix landed as part of **phase-47 (B1-AUTH-02,
> commit `23c8742`)** and the phase-49 (B2-UI-1) re-verify: `lock()` resets
> `dataInitialized = false`, and both unlock paths call `initializeData()`
> (restoring active notebook/section from prefs and re-arming
> `observeSections`/`observePages`) before any dbGate flow re-subscribes. This
> phase ran the existing behavior + added a dedicated pin test
> (`B2Ui4UnlockReinitializesStateTest`, 10 tests) so the closed path is captured
> for the finding itself, then confirmed the whole suite + debug build stay green
> with the phase-89 verify-only precedent applied: no code edit was made.

## 1. Source finding (recap)

- **B2-UI-4** (LOW, `docs/security-report.md:588-594`, status row `:901`):
  `lock()` nulls `_selectedNotebook`, `_selectedSection`, `_sections`, `_pages`
  but `initializeData()` is gated on a sticky `dataInitialized` flag that the
  init block set on first start and `lock()` never reset — so on the next
  `verifyMasterPassword` success the `initializeData()` call **silently no-oped**:
  `observeSections`/`observePages` never re-ran, Room observer jobs from the old
  session were gone, the home list stayed empty, and the user had to manually
  re-navigate to see notes. The persistent `activeNotebookId`/`activeSectionId`/
  `activePageId` prefs kept pointing at the previous session.

## 2. Fix already in place (before/after evidence)

**Before (the phase-30/32 audit, `NoteflowViewModel.kt:1123,1125-1127,2055-2067`):**
- `dataInitialized` defaulted false, was set `true` once by the init block
  (`:1156-1172`) for a passwordless vault or by the first unlock.
- `lock()` (`:2055-2067`) nulled the selection/session StateFlows but **never
  reset `dataInitialized`** and never cancelled the observer jobs.
- `verifyMasterPassword` success → `initializeData()` (`:1889-1896`) returned
  immediately (`if (dataInitialized) return`), observers stayed un-armed.

**After (current tree, all line numbers verified in this phase):**

1. **`lock()`** — `NoteflowViewModel.kt:3638-3694` (inside
   `if (settings.hasMasterPassword)`, `:3668-3681`):
   - `sectionsJob?.cancel()` / `pagesJob?.cancel()` (`:3669-3670`) — no stale
     collection from the closed vault.
   - `NoteflowDatabase.dispose()` (`:3671`) — the keyed SQLCipher connection is
     dropped (the data-layer lock boundary from B1-AUTH-02).
   - **`dataInitialized = false` (`:3673`)** — **this is the exact line the B2-UI-4
     finding asked for** ("reset `dataInitialized = false` in `lock()`").
   - `_pages.value = emptyList()` / `_selectedPage.value = null` /
     `_sections.value = emptyList()` / `_selectedSection.value = null` /
     `_selectedNotebook.value = null` (`:3686-3690`) and `_authenticated.value = false`.
   - Passwordless vaults intentionally skip tear-down (device-wrapped DEK is the
     boot credential; no lock boundary exists there) — the empty-list exploit
     cannot apply to them.
2. **Both unlock paths** reinstate the disposed connection, flip
   `_authenticated`, then call `initializeData()` **after** the flag reset:
   - `verifyMasterPassword` — `NoteflowViewModel.kt:2769-2809`:
     `reinstateDatabaseAfterLock()` (`:2780`) → `_authenticated.value = true`
     (`:2785`) → `resetMasterPasswordVerificationCounters()` → **`initializeData()`
     (`:2789`)** → `startPluginLifecycle()`.
   - `verifyBiometricsAndUnlock` — `NoteflowViewModel.kt:2978-3006`:
     `reinstateDatabaseAfterLock()` (`:2985`) → `_authenticated.value = true`
     (`:2990`) → **`initializeData()` (`:2995`)** → `startPluginLifecycle()`.
3. **`initializeData()`** — `NoteflowViewModel.kt:1400-1432`: the
   `if (dataInitialized) return` guard now correctly acts as **re-entry
   protection**, not a sticky latch — a lock() in between resets the flag so the
   next unlock boots `initializeDataCore()`.
4. **`initializeDataCore()`** — `NoteflowViewModel.kt:1434-1563`: restores the
   persisted selection before arming observers at `:1526-1549`:
   - reads `settings.activeNotebookId` (`:1526`) + `settings.activeSectionId`
     (`:1527`), resolves both against the repo (`:1528-1536`);
   - restored pair valid → `_selectedNotebook`/`_selectedSection` set + **both
     `observeSections(restoredNb.id)` and `observePages(restoredSec.id)` armed**
     (`:1538-1542`) — the finding's `:1246-1253` concern ("observePages only
     (re)created from selectSection") is closed because `initializeDataCore`
     itself calls `observePages`;
   - stale/missing pair → `ensureDefaultNotebookAndSection()` fallback
     (`:1543-1549`), so a prefs rabbit-hole can never leave an empty selection.
5. **Observer re-arm** — `observeSections` (`:1665-1678`) and `observePages`
   (`:1686-1693`) re-assign `sectionsJob`/`pagesJob`, repopulate
   `_sections`/`_pages`; `selectSection`/`selectNotebook` persist the prefs
   (`:1661`, `:1682`) that the next unlock restores.
6. **dbGate home-list flows** — `notebooks`/`allSections`/`allActivePages`/
   `paletteItems`/`recentPages`/`trashedPages` (`:1272-1376`) are all
   `dbGate.flatMapLatest` on `_authenticated && !_corruptionBlocked &&
   !_keystoreKeyLost` with a `flowOf(emptyList())` branch — on lock the gate
   emits empty immediately, on unlock it re-subscribes and re-emits, so the
   lock boundary *also* re-validates these lists (the finding's
   "re-shown without any re-validation step" half of the exploit).
7. **`activePageId` restore** — `MainActivity.kt:221-239`: `activePage` is
   derived from `pages.find { it.id == activePageId }` and a
   `LaunchedEffect(authenticated, pages)` re-selects `settings.activePageId`
   after unlock once the pages list repopulates, completing the whole
   lock→unlock state round-trip.

## 3. Verification

### 3.1 Pin test added (B2-UI-4 behavior)

`app/src/test/java/com/authorss81/noteflow/B2Ui4UnlockReinitializesStateTest.kt` (10 tests, 0 failures):

- Behavioral model mirroring the production `dataInitialized`/prefs/observer
  state machine:
  - `password vault unlocks into a repopulated home list without manual navigation` —
    restores `activeNotebookId`/`activeSectionId` from prefs, re-arms both
    section and page observers, repopulates `pages` with no user tap.
  - `a lock then unlock then lock then unlock cycle repopulates every time` —
    repeated cycles never degrade to an empty home list.
  - `passwordless vault keeps the session state intact under lock - by design` —
    no lock boundary means no tear-down, the exploit cannot apply.
  - `initializeData is re-entrant guarded - the sticky-flag bug cannot recur` —
    the guard no-ops a second boot while initialized but a `lock()` between calls
    lets the next unlock boot exactly once.
  - `sections auto-select their first page when the restored selection is stale` —
    prefs rabbit-holes fall back to defaults.
- Source-level wiring pins (same technique as `B1Auth02LockedOpenTest`,
  `B1Crypto02DekAtRestTest`):
  - `lock()` contains `dataInitialized = false`, `sectionsJob?.cancel()`,
    `pagesJob?.cancel()`, `_selectedPage.value = null`, `_sections.value = emptyList()`.
  - both unlock blocks contain `reinstateDatabaseAfterLock()` + `initializeData()`.
  - `initializeDataCore()` reads `settings.activeNotebookId`/`activeSectionId`
    and arms `observeSections(`/`observePages(`.
  - `selectNotebook`/`selectSection` persist `settings.activeNotebookId`/
    `activeSectionId`.
  - every dbGate home-list flow is declared as a gated `StateFlow<List<…>>` and
    the gate predicate `isAuth && !blocked && !keyLost` is present.

### 3.2 Out-of-phase observations (reported, not fixed)

- `verifyMasterPassword`/`verifyBiometricsAndUnlock` call `initializeData()` BEFORE
  `_authenticated` is observed by the Compose layer — the dbGate flows and
  `initializeDataCore` both depend on `_authenticated == true` (which was already
  set at `:2785`/`:2990` before `initializeData()` at `:2789`/`:2995`), so ordering
  is correct; no change.
- The finding's `:1125-1127` `initializeData()` early-return is intentional and
  now provably correct (re-entry guard) — kept, pinned by the re-entrant test.

### 3.3 Command outcomes

1. `gradle :app:testDebugUnitTest --tests com.authorss81.noteflow.B2Ui4UnlockReinitializesStateTest`
   (isolation): **BUILD SUCCESSFUL** — 10/10 green.
2. `gradle testDebugUnitTest` (full): **BUILD SUCCESSFUL** — app module **1629
   tests, 0 failures, 0 skipped**; plugin modules +17 (AssistantPromptTest 11,
   LocalLlmHardwareCheckTest 6) → **1646 total, 0 failures** (baseline was 1619;
   +10 from the new pin class).
3. `gradle assembleDebug`: **BUILD SUCCESSFUL** on the documented transient
   first-invocation dex-merge failure (pre-existing across phases 47-50/81/90);
   retry green. Debug APK `app/build/outputs/apk/debug/app-debug.apk` ~173.8 MB,
   SHA-256 `d8d539068a9129468e3124cadfe31ace3fbd26ea07e1a8a26b2a80b328003519`.

## 4. Definition of done

- **Vulnerability path closed with file:line evidence**: yes — see §2. The fix
  predates this phase (phase-47 commit `23c8742` for `dataInitialized = false` in
  `lock()` at `NoteflowViewModel.kt:3673` and the unlock-side `initializeData()`
  at `:2789`/`:2995`); this phase added the finding-specific pin test + docs.
- **OS/API floor (API 26+)**: no new API anywhere; the fix is flag + StateFlow +
  Room-observer wiring on the existing API-26 floor. No fallback required.
- **New unit tests prove the fix and no existing test regressed**: yes — 10 new
  tests; full suite 1646 green (0 failures).
- **`gradle testDebugUnitTest` + `gradle assembleDebug` both pass**: yes (the
  transient dex-merge first invocation is the documented pre-existing flake).
- **`workspace/phase-95/REPORT.md` committed**: this file. Checksum/secrets: none
  added; no keys/passwords/decrypted content logged. No schema change, no
  migration, no new dependencies, `.github/workflows/` untouched (per AGENTS.md).

## 5. Files changed in this phase

| File | Change |
|---|---|
| `app/src/test/java/com/authorss81/noteflow/B2Ui4UnlockReinitializesStateTest.kt` | **New** — 10-test pin class (behavioral state-machine model + source wiring pins). |
| `docs/security-report.md` | B2-UI-4 status row `:901` → `FIXED 2026-08-17`. |
| `docs/phase-status.md` | New phase-95 row → `DONE`. |
| `workspace/phase-95/REPORT.md` | This report. |