# Phase 265 — Perf: frame pump guard + profile pipeline + budgets

## Goal
Fix HIGH frame-pump disable + wire profile pipeline proof; keep budgets.

## Evidence
- HIGH `WetBrushFramePump.kt:83 start(){ if(SDK<TIRAMISU) return }` → API 26-32 (most of minSdk fleet) never arms pump → `recordFrameTime`/`updateTierAndFallback` dead, ema stuck 16.6ms, thermal never degrades. `Choreographer` exists since API 16. Fix: drop guard to `N` or remove.
- MEDIUM toolchain wired but empty: consumer+producer+profileinstaller 1.4.1 OK, but zero committed `baselineProfiles/*.txt` → `compile*ArtProfile` always disabled (`build.gradle.kts:251-264`); `android.yml` skips llops-bot so fullMode+shrinkResources+signed never proven on CI; first real profile may re-trigger `String index out of range: 62`. Fix: document generation runbook + add CI unsigned `assembleRelease`+mapping check (no keystore needed for mapping), keep guard.
- MEDIUM single 16-layer page =166MB resident vs 64MB budget by design (protected-page no-evict) + pool 64 + corpus 32 → ~230MB peak; tight on 256MB heap. Keep two-tier guarantee, add `onTrimMemory` pool clear proof (already on lock) + doc.
- LOW `gradle.properties:22 configuration-cache=false` vs `build.gradle.kts:218` comment claiming true (doc drift); DecryptedPageCache 32MB chars heavy on 256MB heap (LRU graceful, keep).

## Files
- `ui/components/WetBrushFramePump.kt`, `app/build.gradle.kts`, `docs/ARCHITECTURE.md` (runbook note), `gradle.properties` comment

## Tests
- `Phase265PerfTest`: pump starts pre-TIRAMISU (source pin); release mapping exists when profile absent (CI log pin).

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
