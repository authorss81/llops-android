# Phase 100: B2-DOS-10 - lastSavedStrokeHash grows for every edited stroke... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-10, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-10` (LOW)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `NoteRepository.kt:511` (map field), `NoteRepository.kt:501/585` (adds a hash per loaded/saved stroke id), `NoteRepository.kt:541` (removals only when a stroke id disappears from a page's incoming set). Entries are keyed by global stroke UUID, so switching pages accumulates hashes for every stroke ever saved in the session - never GC'd
- **Exploit scenario:** A heavy editing session on a vault with tens of thousands of strokes grows the map for as long as the app runs, adding memory churn and lookup cost to every stroke save. Purely long-run accumulation.

## The fix (where & how)

`NoteRepository.kt:511,501,585,541` - clear `lastSavedStrokeHash` on page switch / unlock, or bound it with an LRU cap (e.g. 10k entries). Coordinate with phase-73 (B2-UI-3) which replaces the plain map with a synchronized/Concurrent one - do the size bounding here on top of that.


## Verification

- Unit test: saving across many pages keeps the map bounded (LRU eviction or clear-on-switch). `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-10 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-100/REPORT.md` committed: what changed (file:line), the
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
