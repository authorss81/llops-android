# Phase 253 — Final Strict Audit: all phases 247-252 + outstanding criticals

**Date:** 2026-08-30
**HEAD verified:** `2c9f9b16099ef0ee3694456dad12f109550e3be2` (phase-252 review fixes)
**Status:** DONE — every phase 247-252 fix re-verified FIXED at HEAD; no NEW CRITICAL/HIGH uncovered; pre-existing deferred items all still present/accepted.

This is the release-gate audit before publishing the F-Droid build. Every claim the six phases
made was re-verified against the actual HEAD source (`file:line`), pinned by a new pure-JVM
`Phase253FinalAuditRegressionTest` (20 methods), and the full suite + both builds + lint re-run green.

---

## 1. Verification table (6 phases)

`file:line` below is HEAD; the PROMPT's line numbers were approximate (pre-fix snapshots) and are
superseded by the verified positions. Note the PROMPT's `data/NoteflowViewModel.kt` path is actually
`ui/viewmodel/NoteflowViewModel.kt` (the reports used the correct path; the PROMPT's was stale).

### Phase 247 — Paper texture TRUE ZERO at strength 0

- **Claimed fix:** `grainDrawAlpha`/`grainScale`/`shaderGain` early-return `0f` at strength 0;
  DEFAULT=50 anchors byte-identical.
- **HEAD reality:** `services/PaperTextureStrengthPolicy.kt` — `grainDrawAlpha` `:77-79`
  (`if (clamp(strength) == 0) 0f else …`), `grainScale` `:89-91`, `shaderGain` `:103-105`;
  `shaderStrength` `:94` already linear-zero. Anchor `grainDrawAlpha(50)==0.045f`, `grainScale(50)==1.0f`.
- **Test coverage:** `PaperTextureStrengthZeroTest` (10) + `Phase253FinalAuditRegressionTest`
  `247 - PaperTextureStrengthPolicy early-returns true zero at strength 0` + `247 - paper strength zero anchors…`.
- **Status:** FIXED
- **Evidence:** grep of `if (clamp(strength) == 0) 0f` appears in all three strength-mapped functions;
  unit assertion `assertEquals(0f, PaperTextureStrengthPolicy.grainDrawAlpha(0))` passes.

### Phase 248 — Minimap pane binding + ink bar topBar reservation

- **Claimed fix:** minimap binds to the canvas PANE not the device screen; ink bar reserves the
  Scaffold topBar band.
- **HEAD reality:**
  - `ui/components/AnnotationCanvas.kt`: minimap block at `:3516-3521` binds `paneW=canvasBoxW` /
    `paneH=canvasBoxH`; drag `pointerInput(minimapDraggable, minimapWidthPx, minimapHeightPx, paneW, paneH)`
    at `:3566`; clamp `constrainWithinSafeArea(…, paneW, paneH, …)` at `:3574-3579`. `grep` proves ZERO
    `LocalConfiguration.current.screenWidthDp/screenHeightDp` (only comments remain).
  - `services/FloatingWidgetDragPolicy.kt`: `topReservedPx: Float = 0f` last param `:94`, effective top
    clamp `top + topReservedPx` `:100`.
  - `services/DockPosturePolicy.kt`: `horizontalDefaultAnchor` `:41-56` / `verticalDefaultAnchor` `:65-80`
    both take `topReservedPx`, y-clamp `coerceAtLeast(reserved)` `:55`/`:79`.
  - `ui/screens/EditorScreen.kt`: `topBarHeightPx` state `:186`, measured via `.onSizeChanged`
    `:1785`, passed `:2794`; `topReservedPx = (topBarHeightPx - topInsetPx).coerceAtLeast(0f)` `:3525`,
    feeds resting anchors `horizontalDefaultAnchor`/`verticalDefaultAnchor` `:3529`/`:3533` AND drag clamp
    `constrainWithinSafeArea(…, topReservedPx = topReservedPx)` `:3620-3623`.
- **Test coverage:** `Phase248MinimapPaneSizeTest` (11) + regression pins `248 - AnnotationCanvas minimap…`,
  `248 - FloatingWidgetDragPolicy…`, `248 - DockPosturePolicy…`, `248 - EditorScreen derives…` (semantic:
  `constrainWithinSafeArea(y=10, top=48, reserve=56) == 104f`).
- **Status:** FIXED
- **Evidence:** grep (zero device dims), unit assertions, source pins.

### Phase 249 — Canvas criticals (wet throttle, flush NonCancellable, card-hit tail, eraser quadratic)

- **Claimed fix:** wet throttle uses real stored timestamps + raw delta; `flushPendingSaves` wrapped in
  `withContext(NonCancellable)`; card-hit `onDragStart` drops predicted tail first; `applyEraser` pre-buckets
  + caps samples to 8.
- **HEAD reality:**
  - `AnnotationCanvas.kt` wet gate `:2143-2187`: `curTime = sampleTimestampMs` `:2170`,
    `lastTime = lastRawWetTimeMs` `:2171`, fed `rawCanvasX/rawCanvasY`/`lastRawWetX/Y` `:2173-2180`;
    stroke-local `lastRawWetX/Y/TimeMs` `:1695-1697`, reset per stroke `:1922-1924`. `grep` proves zero
    `System.currentTimeMillis() - 16L/100L` and zero `wetBrushEngine.shouldProcessPoint(`.
  - `ui/viewmodel/NoteflowViewModel.kt` `flushPendingSaves` `:4087-4107`: `withContext(NonCancellable)` `:4097`,
    `cancel()` `:4098`, `join()` `:4103`; import `:146`.
  - Card-hit branch `:1837-1846`: `dropPredictedTail()` `:1843` precedes `isDraggingCard = true` `:1844`
    and `return@detectDragGestures` `:1845`.
  - `applyEraser` `:1726-1806`: `lastProcessedEraseSampleIndex` windowing `:1728`/`:1739`,
    `takeLast(EraseHitBucketPolicy.MAX_ERASE_SAMPLES_PER_APPLY)` `:1731`, lazy `EraseHitBucketPolicy.build`
    `:1747-1750`, `candidatesWithinCircle` `:1751`, `replaceStrokes` `:1806`; reset at drag start `:1974-1975`.
  - `services/WetThrottlePolicy.kt`: `MIN_PX_FOR_WET_SAMPLE=1.5f`, `MAX_MS_PER_WET_SAMPLE=16L`, fail-open.
- **Test coverage:** `Phase249CanvasCriticalsTest` (9) + `EraseHitBucketPolicyTest` (7) + regression pins
  (semantic: `shouldProcess(10,20,1000,15,20,1004)=true`, `(10,20,1000,10,20,1004)=false`,
  `(10,20,1000,10,20,1020)=true`, null refs = true).
- **Status:** FIXED
- **Evidence:** source pins + pure-JVM gate behavior + `MAX_ERASE_SAMPLES_PER_APPLY == 8`.

### Phase 250 — Data-loss criticals (stale autosave + lock-during-load)

- **Claimed fix:** `editorSaveGeneration` bumped on every save/flush and checked at write start;
  `LaunchEffect(page.id)` re-checks `authenticated` at assignment; both back paths gate on
  `!loadFailedDueToLock`.
- **HEAD reality:**
  - `NoteflowViewModel.kt`: `@Volatile var editorSaveGeneration: Int = 0` `:216`; `bumpSaveGeneration` `:220`,
    `isCurrentSaveGeneration` `:225`; `flushPendingSaves` bumps `:4095` before cancel/join/flush;
    `flushEditorPageSave` bumps `:4020`; `saveLayersGated` (layer-only) passes `generation = null` (ungated);
    `persistEditorSaveSuspend(generation: Int?, …)` `:4264` gates `if (generation != null && !isCurrentSaveGeneration(...))`
    at entry `:4269` + mid-gate `:4283` immediately before `unlockedPersist`.
  - `EditorScreen.kt`: `LaunchedEffect(page.id, isAuthenticated)` `:885`; `if (viewModel.authenticated.value)` `:889`;
    `isInitialLoadComplete = true` INSIDE the block `:906`; else `isInitialLoadComplete = false` `:911` +
    `loadFailedDueToLock = true` `:912`. BackHandler `:1760-1773` + top-bar `IconButton` `:1795-1807` both gate
    `if (isInitialLoadComplete && !loadFailedDueToLock)` before `flushPendingSaves`.
- **Test coverage:** `Phase250DataLossCriticalsTest` (11) + regression pins `250 - generation token…`,
  `250 - editor load assigns…`, `250 - back paths refuse…`.
- **Status:** FIXED
- **Evidence:** source pins (both back paths counted ≥2), generation-gate semantics.

### Phase 251 — WindowSizeClass refresh + strict default

- **Claimed fix:** `LaunchedEffect(LocalConfiguration.current)` precedes the keyed re-derivation;
  provider defaults to Compact/Compact.
- **HEAD reality:** `MainActivity.kt` `LaunchedEffect(LocalConfiguration.current) { sizeClassRefreshKey++ }`
  `:313-315` precedes `key(sizeClassRefreshKey) { calculateWindowSizeClass(activity = this@MainActivity) }`
  `:668-669`. `ui/WindowSizeClassProvider.kt` default `WindowSizeClass.calculateFromSize(DpSize(0.dp, 0.dp))`
  `:29` (= Compact/Compact); no `840.dp`.
- **Test coverage:** `Phase251WindowSizeClassRefreshTest` (5) + regression pins `251 - MainActivity re-derives…`
  and `251 - provider default…` (semantic: `calculateFromSize(DpSize(0,0))` → Compact/Compact both axes).
- **Status:** FIXED
- **Evidence:** source order pin (effect index < keyBlock index), semantic classification.

### Phase 252 — Passwordless backup portability gate

- **Claimed fix:** HomeScreen shows `BackupPasswordRequirementDialog` when `!hasMasterPassword`;
  `exportBackup` throws `IllegalArgumentException` for the device-keyed shape; dialog + strings exist.
- **HEAD reality:**
  - `ui/screens/HomeScreen.kt`: `onBackup` `:890-907` sets `showBackupPasswordRequirementDialog = true` in the
    `else` (`!hasMasterPassword`); the ONLY `exportBackup(` is the master-password flow `:2060`;
    `BackupPasswordRequirementDialog(` composited `:2123-2131` routing `onSetMasterPassword → showSecurityDialog`.
  - `services/ImportExportService.kt` `exportBackup(…, requireBackupPassword: Boolean = true, …)` `:1710-1735`
    calls `BackupPortabilityPolicy.requirePortableBackup(requireBackupPassword, backupPassword, keyAvailable = key != null)`
    `:1726-1730` before `exportBackupInternal`.
  - `services/BackupPortabilityPolicy.kt`: `isDeviceKeyed(backupPassword, keyAvailable) = keyAvailable && backupPassword == null`
    `:54-57`; `requirePortableBackup` throws `IllegalArgumentException(PASSWORDLESS_DEVICE_KEYED_ERROR)` `:64-72`.
  - `ui/dialogs/BackupPasswordRequirementDialog.kt` `:32-52`; strings `:31-34`.
  - WebDAV (`NoteflowViewModel.kt:4618`) + LocalSend (`LocalSendSendDialog.kt:177`) opt out with
    `requireBackupPassword = false` (documented B1-CRYPTO-05 device-keyed producers, preserved by design).
- **Test coverage:** `Phase252PasswordlessBackupTest` (7) + regression pins `252 - HomeScreen blocks…`, `252 -
  BackupPortabilityPolicy rejects…`, `252 - exportBackup carries…` (semantic: device-keyed + gated throws with
  the exact message; legitimate shapes pass).
- **Status:** FIXED
- **Evidence:** source pins + policy behavior + resource scan.

---

## 2. Pre-existing deferred items — re-confirmed at HEAD

| Item | Status @ HEAD | Evidence |
|---|---|---|
| `applyEraser` quadratic (phase-249 partial fix) | **Improved, residual documented (acceptable)** | Now windowered (`lastProcessedEraseSampleIndex`, `AnnotationCanvas.kt:1728`) + spatially bucketed (`EraseHitBucketPolicy`, `:1747`) + capped (`MAX_ERASE_SAMPLES_PER_APPLY=8`). Residual: every pass still does O(candidates × points × ≤8 samples), but candidates are only nearby strokes (not the whole list) and the full-list O(strokes) pass runs only when something changed (z-order). Pre-249 was O(strokes×points×samples) every move — the quadratic is bounded. |
| WetMixingMath float-precision EOTF round-trip (audit 2/5 8.2) | **Accepted, documented** | `WetMixingMath.kt:98-102` — the Kotlin reference is purely algebraic in `Double` (`Math.pow(...,2.4)`/`1/2.4`, `:52/:58`), so the factor-0 round trip is exact to <1e-7; the AGSL shader routes zero-pigment pixels through a bit-exact passthrough (`base.rgb`/`vibBrushColor`) instead of paying an fp16 EOTF round trip. No user-visible defect. |
| SymmetryCommitPolicy partial-erase mask mirror (audit 2/5 8.3) | **Accepted — mask stays index-aligned** | `bakedTwin` (`SymmetryCommitPolicy.kt:54-65`) mirrors each point via `points.map(::mirror)` preserving ORDER; `Stroke.eraseMask` (per-point) is carried by `stroke.copy` unchanged, so it stays aligned with the MIRRORED point array. The eraser carves each twin as an independent row (`AnnotationCanvas.kt:1701` doc + phase-203 design); erasing either copy leaves the other — consistent by design. |
| `LocalConfiguration` reads in `OverflowMenuSupport.kt` (audit 3/5 L5) | **Accepted (LOW), bounded caps** | `OverflowMenuSupport.kt:47` (`screenHeightDp`) / `:62` (`screenWidthDp`) bound drop-down menu height/width. These feed `OverflowMenuPolicy.maxMenuHeightDp([120,560]dp)` and `AdaptiveLayoutPolicy.maxMenuWidthDp(0.9×window ∈ [160,520]dp)` — MAXIMUM caps on a transient menu, not a layout posture; a stale config during live drag-resize at worst lets a menu run to the PRE-resize bound, recovering on the next config firing (the same event phase 251 leverages). No data, security, or layout-persistence impact. |
| `FloatingWindowPolicy` wired (phase 251 implies) | **Wired & re-confirmed** | `MainActivity.kt` `FloatingWindowNoticeLauncher` invoked in the main composition `:360-365`; measures the real window via `BoxWithConstraints` + `FloatingWindowPolicy.isLikelyFloatingWindow(w,h,inMultiWindow)` `:1383`; one-time `noticeDue`/`floatingWindowNoticeShown` `:1390-1394`, SettingsManager `:657-658`. |
| `dataExtractionRules` narrow allowlist (audit 4/5 L-3) | **Strictest — full root exclusion** | `res/xml/data_extraction_rules.xml` excludes `domain="root" path="."` for BOTH `cloud-backup` and `device-transfer`. Matches `allowBackup="false"` (never re-enabled). |
| `cacheDir` orphan cleanup (audit 4/5 L-2) | **Still handled** | Per-outcome SAF cleanup via `ExportStagingPolicy.cleanupAfterSaF` (delivered→delete, failed→keep, no-data→delete, cancel→delete) + `SaFExporter`. Backup staging `.zip-staging` + staged DB deleted in `finally` (`ImportExportService.kt:1926-1927`). Voice-note cacheDir temp deleted on stop/complete/release. |

---

## 3. New test: `Phase253FinalAuditRegressionTest` (20, pure JVM)

Covers every claim above: source pins against the actual HEAD source files (read at test time, same
`mainSource`-family helper precedent as `Phase251WindowSizeClassRefreshTest`) + semantic behavior tests
over the real pure-JVM policy classes (`PaperTextureStrengthPolicy`, `FloatingWidgetDragPolicy`,
`DockPosturePolicy`, `WetThrottlePolicy`, `EraseHitBucketPolicy`, `BackupPortabilityPolicy`,
`WindowSizeClass.calculateFromSize`) + `data_extraction_rules.xml`/`strings.xml` resource scans.

---

## 4. DoD verification

| DoD item | Result |
|---|---|
| `gradle :app:testDebugUnitTest` 3556+ green | **3653 / 0 failures / 0 errors / 0 skipped** (3633 baseline + 20 new) |
| `gradle :app:assembleDebug` green | green |
| `gradle :app:assembleRelease` green (R8+signed) | green (`-Dorg.gradle.jvmargs=-Xmx5120m` override; repo `gradle.properties` untouched) |
| `gradle :app:lintDebug` 0 errors | **0 errors / 0 Fatal** (106 pre-existing warnings; no new) |
| `workspace/phase-253/REPORT.md` verification table + pre-existing re-confirmed | this file |
| Any new CRITICAL/HIGH → follow-up phase prompt | **none found** — all 6 phases FIXED, no regression, no dead code, no new vuln |

## 5. Constraints honored

- **No CRITICAL/HIGH found** — none of the 6 phases regressed; all pre-existing acceptances remain valid.
- No schema change, no new dependencies, `verification-metadata.xml` untouched,
  `.github/workflows/` untouched, base-APK-size rule intact.
- New/changed files end with a final newline (`.editorconfig`).
- No production code was modified by this audit — only the new regression test + this REPORT +
  `docs/phase-status.md` + `docs/ARCHITECTURE.md` notes. The phase-247 moves the prior audits' claims are
  all verified true at HEAD (honest-strict); no finding required a new fix phase.
