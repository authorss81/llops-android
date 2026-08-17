# Phase 99: B2-DOS-09 - RamerDouglasPeucker.simplify recurses with depth... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-09, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-09` (LOW)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `RamerDouglasPeucker.kt:14-37` (recursive split at :31-32; worst-case depth ~ points), invoked on every committed freehand stroke at `AnnotationCanvas.kt:892-895` inside the commit coroutine (`:864`, no `catch(Throwable)`); point input only distance-thinned (>1.5 px, `AnnotationCanvas.kt:802-830`)
- **Exploit scenario:** A user (or automated gesture stream) draws one long continuous stroke with high point density and an RDP-maximal point distribution -> recursion depth = point count -> StackOverflowError raised in the canvas commit coroutine outside any guarded catch -> process crash (interactive DoS on low-end devices).

## The fix (where & how)

`RamerDouglasPeucker.kt:14-37` - convert `simplify` to an iterative stack implementation, or cap the segment size / terminate recursion at a fixed budget (e.g. depth 2000 -> fall back to no-op smoothing); alternatively cap accumulated `activePoints` (e.g. 20k) in the touch handler at `AnnotationCanvas.kt:802-830`.


## Verification

- Unit test: a degenerate long-stroke point sequence completes without StackOverflowError and matches the iteration-based result on normal inputs. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-09 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-99/REPORT.md` committed: what changed (file:line), the
  checksum/secrets handling, verification output, and any input you judged
  out-of-scope.

## Constraints

- NO DB schema change unless this fix requires one - then a migration-safe note
  in REPORT.md is MANDATORY, and the migration must never delete user data.
- Do NOT edit `.github/workflows/`. Do not add new dependencies unless required
  by the fix (then justify in the commit).
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`,
  `ClipboardGuard`, and FLAG_SECURE intact.
- Do not fix OTHER security findings in this phase - that is a different phase.
  If you find a new related bug, document it in REPORT.md, do not fix it here.
