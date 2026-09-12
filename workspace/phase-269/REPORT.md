# Phase 269 — Compat: AGSL gate + OOM cull + trim + permissions — REPORT

## 1. Claim / reality / status / evidence

| # | PROMPT claim | Reality found | Status | Evidence (file:line) |
|---|---|---|---|---|
| 1 | Dual AGSL truth: manager = SDK≥33 AND tier≠LOW_END vs helper = SDK≥33 only; canvas `1153/5949` runs shader on LOW_END after re-enable | Confirmed exactly as described | FIXED | Single truth `utils/AgslGate.kt:16` (`isSupported(sdk,tier)` = SDK≥33 AND ≠LOW_END). `DeviceCompatibilityManager.kt:74` delegates; `ShaderCapabilityHelper.kt` exposes tier-aware `agslSupportedFor(sdk,tier)` + documents SDK-only as visibility-only. Canvas allocation `:1219-1232` (tier-keyed remember + try/catch), pump `:1304-1314`, use gate `:6009`, caller verdict `:6054`, pass param `:6291-6293`, all via `AgslGate.isSupported`. Zero `ShaderCapabilityHelper.isAgslSupported` code refs remain in `AnnotationCanvas.kt` (one comment). |
| 2 | `KnowledgeGraphScreen:214` decrypts whole vault before `cullToCap` (cap 120) → OOM on Go | Confirmed (`loadAllActivePages` then cull) | FIXED | New `Daos.kt:112` `getActivePagesNewestCapped` (newest-first SQL LIMIT — query only, no schema change) → `NoteRepository.kt:423` `getNewestActivePagesCapped` (+`:430` COUNT) → `NoteflowViewModel.kt:4593/4602` guarded `loadCappedActivePages/loadActivePageCount`. Screen `KnowledgeGraphScreen.kt:217-233`: tier → profile → COUNT → capped load → cull safety net. `lowEndFallback` now from pre-load COUNT (`:250`), honest "most recent N" notice preserved. Edge scan + tag aggregation run over the capped set. |
| 3 | `MainActivity:1372 onTrimMemory` clears pool only at BACKGROUND/CRITICAL, missing RUNNING_LOW(10)/MODERATE(5) | Confirmed (`>= BACKGROUND \|\| == RUNNING_CRITICAL`) | FIXED | `utils/MemoryTrimPolicy.kt:19` (`CLEAR_AT_LEVEL=10`); `MainActivity.kt:1383` routes through `shouldClearCaches(level)` and also drops `PaperGrainTileCache`; `onLowMemory` clears both too. |
| 4 | Heuristic defects: 3 GB→LOW_END, logical-core escape, 6c/8 GB→MID, catch→MID grants AGSL | Confirmed all four | FIXED | `utils/DeviceTierPolicy.kt:52` documented table: Go-signal/≤2 GB/≤2c → LOW; 6c+6 GB → FLAGSHIP (6c/8 GB now flagship); 3 GB + 8×little → MID; non-finite → LOW. Manager `:41` delegates; null-AM + catch → LOW_END (fail closed, `:34/47`). |
| 5a | Grain calls `detectDeviceTier` direct (override bypass) | Confirmed (`AnnotationCanvas:1151`) | FIXED | `AnnotationCanvas.kt:1156-1163`: shared override-aware `canvasDeviceTier` (`getDeviceTier`, keyed on `deviceTierOverride`); grep `detectDeviceTier(` in file = 0. |
| 5b | Stale `remember` tier (`GlassSurfaces:152`, `EditorScreen:770 Unit`) | Confirmed both | FIXED | `GlassSurfaces.kt:157-160` keyed on live `deviceTierOverride`; `EditorScreen.kt:619` `editorDeviceTier` + `LaunchedEffect(editorDeviceTier)` (`:819`). |
| 5c | `isHardwareBitmapsSupported` vacuous (always true, minSdk 26) | Confirmed, zero callers | FIXED | Deleted (`DeviceCompatibilityManager.kt`); repo-wide grep = definition only, build green. |
| 5d | No RuntimeShader try/catch (Mali-G31 crash) | Confirmed (bare constructor + uniform upload) | FIXED | Allocation try/caught → null (`:1219-1232`); uniform upload try/caught → plain fallback (`:6337-6375` region). Null effect routes every wet pass through the existing plain path. |
| 5e | Snackbar w/o Settings action | Confirmed (text-only pipeline) | FIXED | `SnackbarMessage` gains `actionLabel/actionId` (`NoteflowViewModel.kt:1626-1631`), `showSnackbar` 4-arg overload (`:1651`), `SNACKBAR_ACTION_OPEN_APP_SETTINGS` (`:3463`); root collector renders + routes (`MainActivity.kt:346-364`). Low-end GPU notice rewritten honest (no false override promise — gate enforced); mic permanent-denial carries Open Settings (`EditorScreen.kt:375-394`). |
| 5f | `USE_FINGERPRINT` deprecated | Confirmed in manifest | FIXED | Removed from `AndroidManifest.xml:4-8` (comment documents USE_BIOMETRIC as the declaration). |
| 5g | RECORD_AUDIO no rationale / permanent-denial → settings redirect | Confirmed (bare request + dead-end snackbar) | FIXED | Rationale AlertDialog (`EditorScreen.kt:395-413`), pre-request rationale check (`:1915`), permanent-denial → action snackbar (`:375-394`). |
| 5h | Revoke mid-record unhandled | Partially covered (generic stop error); now explicit | FIXED | `VoiceNoteManager.kt:375-379`: stop-time permission recheck names revocation explicitly. |
| 5i | `PaperGrainTileCache` ~0.5 MB never cleared on trim | Confirmed (KDoc said deliberately unwired) | FIXED | `MainActivity.kt:1383-1388` + `onLowMemory` call `PaperGrainTileCache.clear()`; KDoc rewritten (`PaperGrainTileCache.kt:70-81`). |

## 2. Honest behavior notes / deliberate scopings

- **AGSL re-enable is intentionally NOT honored on LOW_END** (unlike shadows/minimap, phase-213 pattern): the shader is an OOM/jank vector, so the gate is enforced and the settings toggle renders **disabled-with-explanation** (`EditorScreen.kt:5988-6015`, availability param `:5549`, threaded `:3139`). The low-end snackbar promises no override (`:826-833`); minimap/shadow notices keep theirs (those gates honor re-enable).
- **Tool-picker wet-tool notice** (`ToolPickerBottomSheet`, `EditorScreen.kt:4567`) is now tier-aware with a LOW_END-specific message; tier threaded as a param (`deviceTier`, call site `:3014`).
- **Brush-preset row** keeps the SDK-only visibility gate (presets also configure the vector path — honest as-is).
- **Graph caps now bind every tier** (120/220/400 via existing `GraphTierSelector`): flagship vaults >400 show the honest culled notice instead of unbounded decrypt. `cullToCap` retained as a deterministic safety net.
- **`drawCompositedLayersStrokes`** takes `deviceTier` (default MID_RANGE — only used by tests calling without it, if any) threaded from all 3 call sites (`:3369/:3426/:3572`).
- No Room schema change (one SELECT query added), no new deps, no `.github/workflows/` edits, `verification-metadata.xml` untouched, `allowBackup=false` intact.

## 3. Stale-pin maintenance (behavior the PROMPT intentionally changed)

| Suite | Update | Why |
|---|---|---|
| `Phase134LockVaultInflightTest` | graph pin → `loadCappedActivePages(`/`loadActivePageCount(` | New guarded accessors; same lock-race guard |
| `Phase205CanvasCommitIntegrityTest` | creation pin → `remember(canvasDeviceTier)` + `AgslGate` + catch | Single-truth allocation |
| `Phase200CanvasRenderParityTest` | grain pin → `remember(canvasDeviceTier)` + no `detectDeviceTier(` | Override-aware grain |
| `B2Dos01StrokeGeometryTest` | gate pins → `AgslGate.isSupported` / `agslShaderAllowed`; renamed test | Single truth replaces SDK-only |
| `Phase206EventDrivenTimersTest` | pump key pin → `remember(wetBrushEngine, gpuWetBrushesEnabled, canvasDeviceTier)` + gate pin | Tier-keyed rebuild |
| `Phase254CommentTrimTest` | PHASE 269 re-baseline: canvas 8820/7054, editor 7545/6557 | Measured growth documented in-test |

## 4. Tests

- New `Phase269CompatTest` (16): AgslGate truth table (8 asserts), SDK floor, DeviceTierPolicy (Go/tiny, 3 GB-mid, 8×little, 6c/8 GB flagship, unknown fail-closed, ordinary mid), MemoryTrimPolicy levels, + 7 source pins (single truth, canvas gate + driver catch + override grain, trim+grain, capped graph + tier-before-decrypt ordering, permission/action/revoke, manifest, override-keyed remembers).
- Full: `gradle :app:testDebugUnitTest` **3866 / 0 failures** (7 stale pins updated, 0 weakened — each pins the stricter phase-269 behavior).
- `gradle :app:assembleDebug` green; `gradle :app:lintDebug` **0 errors** (61 warnings / 18 info, pre-existing).

## 5. DoD

- [x] `gradle :app:assembleDebug` green
- [x] `gradle :app:testDebugUnitTest` green (3866, 0 failures)
- [x] `gradle :app:lintDebug` 0 errors
- [x] This REPORT with file:line evidence table
