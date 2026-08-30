# Phase 253 — Final Strict Audit: all phases 247-252 + outstanding criticals

## Goal
End-to-end honest audit of the entire codebase AFTER phases 247-252 land. Verify the 6 phase fixes are correctly applied, no regressions, no leftover dead code, no new vulnerabilities. Find any remaining CRITICAL/HIGH issues. This is the **release-gate audit** before publishing the F-Droid build.

## Audit scope

Read every file changed by phases 247-252 and confirm the fix is present and correct:
- `app/src/main/kotlin/com/authorss81/noteflow/services/PaperTextureStrengthPolicy.kt` (phase 247)
- `app/src/main/kotlin/com/authorss81/noteflow/services/FloatingWidgetDragPolicy.kt` (phase 248)
- `app/src/main/kotlin/com/authorss81/noteflow/services/DockPosturePolicy.kt` (phase 248)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt` (phases 248, 249)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt` (phases 248, 250)
- `app/src/main/kotlin/com/authorss81/noteflow/data/NoteflowViewModel.kt` (phases 249, 250)
- `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt` (phase 251)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/WindowSizeClassProvider.kt` (phase 251)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` (phase 252)
- `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt` (phase 252)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/dialogs/BackupPasswordRequirementDialog.kt` (phase 252, new file)
- `app/src/main/res/values/strings.xml` (phase 252)

Also audit the previously-deferred items to confirm they remain acceptable:
- The `applyEraser` quadratic (phase 249 partial fix; verify it improved but document the residual)
- The `WetMixingMath` float-precision EOTF round-trip (audit 2/5 8.2)
- The `SymmetryCommitPolicy` partial-erase mask mirror (audit 2/5 8.3)
- The `LocalConfiguration` reads in `OverflowMenuSupport.kt` (audit 3/5 L5)
- The `FloatingWindowPolicy` is now wired in (phase 251 implies it; verify)
- The `dataExtractionRules` narrow allowlist (audit 4/5 L-3)
- The `cacheDir` orphan cleanup (audit 4/5 L-2)

## Strict verification protocol

For each phase 247-252, in the corresponding REPORT.md, the agent must list:
- **Claimed fix** (from the PROMPT.md)
- **HEAD reality** (file:line of the actual code)
- **Test coverage** (file:line of the pinning test)
- **Status**: FIXED / PARTIAL / NOT FIXED / NEW REGRESSION
- **Evidence**: grep output / test name / line citation

For each pre-existing item, confirm it is still working as documented.

## File:line expectations

### Phase 247 — Paper
- `PaperTextureStrengthPolicy.kt:grainDrawAlpha:52` should early-return 0f at strength 0
- `PaperTextureStrengthPolicy.kt:grainScale:60` should early-return 0f at strength 0
- `PaperTextureStrengthPolicy.kt:shaderGain:71` should early-return 0f at strength 0
- `PaperTextureStrengthPolicyTest` (existing) + new `PaperTextureStrengthZeroTest` should pass

### Phase 248 — Minimap + ink bar
- `AnnotationCanvas.kt:3358` minimap block should NOT use `LocalConfiguration.current.screenWidthDp/screenHeightDp`
- `AnnotationCanvas.kt:3408` `pointerInput` keys should NOT include `screenW, screenH`
- `FloatingWidgetDragPolicy.kt:77` `constrainWithinSafeArea` should accept `topReservedPx`
- `EditorScreen.kt:3530-3533` should pass `topReservedPx = topBarHeight.toPx()` (or equivalent)
- `DockPosturePolicy.kt:58-71` should reserve topBar

### Phase 249 — Canvas criticals
- `AnnotationCanvas.kt:2038-2044` wet throttle should use the real stored timestamp (NOT `System.currentTimeMillis() - 16L`)
- `AnnotationCanvas.kt:1753-1755` card-hit `onDragStart` should call `dropPredictedTail()` before the early-return
- `NoteflowViewModel.kt:4034-4043` `flushPendingSaves` should be wrapped in `withContext(NonCancellable) { ... }`
- `applyEraser` should pre-bucket by bounding box and cap `eraseSamples` to the last 8

### Phase 250 — Data loss
- `NoteflowViewModel.kt` should have `editorSaveGeneration` and bump it on every save/flush
- `NoteflowViewModel.kt:saveStrokesForPage` should check the generation at the start of the write
- `EditorScreen.kt:866-884` `LaunchedEffect(page.id)` should re-check `authenticated.value` at the assignment moment
- `BackHandler` + top-bar back should check `!loadFailedDueToLock` before `flushPendingSaves`

### Phase 251 — WindowSizeClass
- `MainActivity.kt:644-655` should be preceded by `LaunchedEffect(LocalConfiguration.current) { sizeClassRefreshKey++ }`
- `WindowSizeClassProvider.kt:23-25` should default to `Compact/Compact` (or read from `LocalConfiguration`)

### Phase 252 — Passwordless backup
- `HomeScreen.kt:884-915` should show `BackupPasswordRequirementDialog` when `!hasMasterPassword`
- `ImportExportService.kt:exportBackup` should throw `IllegalArgumentException` when `password == null` AND `vaultDek` is device-wrapped
- `BackupPasswordRequirementDialog.kt` should exist
- `strings.xml` should have the new warning string

## Tests required (pure JVM)

- `PaperTextureStrengthZeroTest` (phase 247) — already exists or created
- `Phase248MinimapPaneSizeTest` (phase 248)
- `Phase249CanvasCriticalsTest` (phase 249)
- `Phase250DataLossCriticalsTest` (phase 250)
- `Phase251WindowSizeClassRefreshTest` (phase 251)
- `Phase252PasswordlessBackupTest` (phase 252)
- A new `Phase253FinalAuditRegressionTest` that pins every claim above

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- Every finding must cite `file:line` or `commit:file:line`
- Honest strict reviewer — if any of the 6 phases is NOT actually fixed at HEAD, say so

## DoD
- `gradle :app:testDebugUnitTest` 3556+ green (all new + existing)
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-253/REPORT.md` with the verification table (6 rows: phase / claimed / reality / status / evidence) and any pre-existing items re-confirmed
- Any new CRITICAL/HIGH found by the audit gets its own follow-up phase prompt in the same REPORT (not silently left unfixed)
