# Phase 269 — Compat: AGSL gate + OOM cull + trim + permissions

## Goal
Single truth for capability; no OOM before fallback; honest degraded states.

## Evidence
- CRITICAL dual AGSL truth: `DeviceCompatibilityManager.kt:68 isAgslSupported` = SDK>=33 AND tier!=LOW_END vs `ShaderCapabilityHelper.kt:27` = SDK>=33 only → `AnnotationCanvas.kt:1153/5949` allocates/uses shader on LOW_END API33+ if user re-enables `gpuWetBrushes`. Fix: single `AgslGate(tier, sdk)` both call.
- CRITICAL `KnowledgeGraphScreen.kt:214` decrypts ENTIRE vault before `cullToCap` (LOW_END cap 120) → OOM on Go before `lowEndNotice:231`. Fix: tier cap FIRST, then paged `getAllActivePages(limit=cap)`/chunked decrypt.
- HIGH `MainActivity.kt:1372 onTrimMemory` clears BitmapPool only at BACKGROUND(40)/CRITICAL(15), ignoring RUNNING_LOW(10)/MODERATE(5) → 64MB pool OOMs first. Fix: clear at >=RUNNING_LOW.
- HIGH heuristic: 3GB→LOW_END (actually Go-mid), logical-core count (8x little escapes), 6c/8GB→MID, catch→MID grants AGSL on broken context. Recalibrate + document.
- MEDIUM override bypass (`AnnotationCanvas:1097` grain calls `detectDeviceTier` direct, not `getDeviceTier` override); stale `remember` tier (`GlassSurfaces:152`, `EditorScreen:770 Unit`); `isHardwareBitmapsSupported` vacuous (always true); no RuntimeShader try/catch (Mali-G31 crash); snackbar w/o Settings action; `USE_FINGERPRINT` deprecated; RECORD_AUDIO no rationale/permanent-denial → settings redirect; revoke mid-record unhandled; `PaperGrainTileCache` 0.5MB never cleared on trim.

## Files
- `utils/DeviceCompatibilityManager.kt`, `ui/components/ShaderCapabilityHelper.kt`, `ui/components/AnnotationCanvas.kt`, `ui/screens/KnowledgeGraphScreen.kt`, `MainActivity.kt`

## Tests
- `Phase269CompatTest`: single AgslGate truth table (JVM); trim threshold source pin; paged graph load (source pin).

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
