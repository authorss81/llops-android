# Phase 263 — Home/gallery/tab: restoration + import + overflow

## Goal
Stop rotation wiping search/tabs/selections/imports; keep Phase-166 overflow fixes.

## Evidence
- HIGH `HomeScreen.kt:147,152-153,169-170,392-394,162,161,164,117-120`: `searchQuery/selectedTab/pageViewMode/activeTagFilterPath/pendingImportUris/selectedImportOrientation/multiSelectedIds/dialog flags` are plain `remember` → rotation/process-death clears query (debounce orphaned), tab jumps Pages (wrong-dest destructive verbs), 10-file import dialog lost. Fix: `rememberSaveable` (Uri list via String). Keep `backupPasswordInput` saveable pattern.
- HIGH `HomeScreen.kt:1365 PrimaryTabRow` fixed-width 4 tabs → clips at 320-360dp (72dp/tab vs 75-92dp label); children no maxLines/ellipsis. Fix: `ScrollableTabRow`/`SecondaryScrollableTabRow` + `maxLines=1, Ellipsis` (cf. `OnDeviceSmartAssistant.kt:197` correct).
- HIGH touch targets: `GalleryView.kt:330 28dp`, `:1432 32dp`, `UnifiedSidebar.kt:335/352/438/453/526 26-30dp`, `HomeScreen.kt:1314 20dp`, `LockScreen.kt:210 32dp`, `EditorScreen.kt:2813 28dp/:2998 26dp/:4123 36dp`. Fix: 48dp via `minimumInteractiveComponentSize` (toolbar pills `:4012-4168` already correct — extend everywhere).
- HIGH `HomeScreen.kt:1230` search `TextField` no `imeAction=Search`/`onSearch`; leadingIcon null no label semantics; `370` debounce flashes empty state (needs `isSearching`).
- MEDIUM `GalleryView.kt:95 Adaptive(168dp)` → 1 col until >372dp (use Fixed(2)<600dp or 150dp min); type badge/pin `contentDescription=null`; `weight(1f,fill=false)` foot-gun under unbounded height.

## Files
- `ui/screens/HomeScreen.kt`, `ui/components/GalleryView.kt`, `ui/components/UnifiedSidebar.kt`, `ui/screens/LockScreen.kt`, `ui/screens/EditorScreen.kt`

## Tests
- `Phase263HomeGalleryTest`: saveable keys present (source pins); ScrollableTabRow present; 48dp minimums (source pins).

## Constraints
- No Room schema change / migration
- No new dependencies (pure Kotlin/Compose only)
- No `.github/workflows/` edits
- Follow AGENTS.md hard rules (allowBackup=false stays, no plaintext rows, fail closed)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-NNN/REPORT.md` with file:line evidence table (claim / reality / status / evidence)
