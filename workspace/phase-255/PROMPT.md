# Phase 255 — Canvas ingest: stale-timestamp dots + batcher tail loss

## Goal
Fix pen dots (points.size==1 → drawCircle) from the ingest gate, on top of f7510ac/b5842ee.

## Evidence (AI Studio strict canvas audit, verified on main HEAD)
- HIGH `AnnotationCanvas.kt:2273-2294`: `val prevAcceptedTime = lastIngestedInputTimestampMs` frozen before `for(sample in batchDrainScratch)`; `isStale(sample.ts, prevAcceptedTime)` never advances to the just-accepted sample, so duplicate-`eventTime` bursts inject zero-distance samples that pollute the wet-throttle distance gate (`WetThrottlePolicy.kt:62`, MIN_PX 1.5f). Sibling `else if (drainedCount>0)` branch correctly uses live `lastIngestedInputTimestampMs`. Fix: `if(isStale(sample.ts, lastIngestedInputTimestampMs))`.
- MEDIUM `AnnotationCanvas.kt:1358-1408` vs `:2309-2310`: producer stamps `motionEvent.eventTime`/`getHistoricalEventTime(h)` but fallback uses `change.uptimeMillis` (different dispatch layer; 120Hz panels show off-by-1). First live sample after a batch can be wrongly stale (or duplicate). Fix: single clock — prefer `eventTime` everywhere, or accept `abs(diff)<=1ms` as fresh.
- MEDIUM `AnnotationCanvas.kt:1281-1335` vs `:2633/:2670`: mid-gesture navigation `DisposableEffect(Unit)` commits `activePoints.toList()` WITHOUT draining `strokeInputBatcher` first; last 10-20ms tail (2-3 queued ACTION_MOVE samples) lost. Fix: `drainInto`+`ingestPointerSample` before building the dispose Stroke.

## Files to change
- `ui/components/AnnotationCanvas.kt` (drain loop, fallback clock, dispose flush)
- `services/StrokeInputBatcher.kt` (KDoc: UI-thread contract pin)

## Tests (pure JVM + source pins)
- `Phase255CanvasIngestTest`: isStale only vs live stamp (no frozen prev); fallback clock single-source; dispose path drains batcher (source pin).

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
