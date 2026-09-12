# Phase 271 — Final verification gate (AI Studio audit)

## Goal
Prove phases 255-270 landed, no regressions, no new CRITICAL/HIGH. Release-gate before F-Droid.

## Scope
Re-verify every fix 1:1 against its PROMPT claim at HEAD:
- 255 drain `prevAcceptedTime` live stamp + single clock + dispose drains batcher
- 256 segment hit + pressure-radius decision + one undo per swipe
- 257 no-resurrect (`lastSeenIds`) + `remember(page.id)` + `isInitialLoadComplete` guard + mutex `loadEditorCanvasPage`
- 258 `remember(page.id, initialContent)` sync + dirty external not swallowed + dispose keyed by page.id + ONE editor (Hybrid deleted or rewired)
- 259 escaped titles, no utils shadow, caps, graph rekeys, tag hierarchy rekeys
- 260 no destructive fallback, FK/index, atomic deletes, confined delete, WAL BUSY handled, paged re-encrypt
- 261 InetAddress-local only, staging finally-deleted, sync warning present
- 262 prepareAsync/IO, elapsedRealtime, legacy .m4a deleted, streamed crypto
- 263 saveable states, ScrollableTabRow, 48dp, IME Search
- 264 menu BoxWithConstraints cap, single mapScale, drag keys, yield fires when dragged-to-top+PEN
- 265 pump pre-TIRAMISU starts, profile runbook + CI mapping proof
- 266 labeled controls, 48dp, 200% scale, contrast, MotionSystem coverage, focus order
- 267 commit() wipes, sanitize bounds, KeyStore synchronized
- 268 verification on, no jitpack confusion, R8 CI proof, SHA pins, VERSION_CODE required
- 269 single AgslGate, paged graph, trim at RUNNING_LOW
- 270 optional default, atomic delete, canonical payload

## DoD additions
- `gradle :app:testDebugUnitTest` + `assembleDebug` + `assembleRelease` (unsigned mapping OK) green, `lintDebug` 0 errors
- `workspace/phase-271/REPORT.md` verification table (16 rows: phase / claimed / reality / status / evidence)
- Any new CRITICAL/HIGH → follow-up phase prompt inside same REPORT (not silently unfixed)

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
