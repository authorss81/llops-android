# Phase 73: B2-UI-3 - Unsynchronized shared lastSavedStrokeHash HashMap +... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-UI-3, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-UI-3` (MEDIUM)
- **Area:** Batch 2 - Compose/UI, concurrency, TOCTOU
- **Evidence:** `NoteRepository.kt:511` (plain `mutableMapOf<Int>`-typed single shared map for ALL pages), `NoteRepository.kt:544-547,584-585` (read-modify-write, no synchronization), `EditorScreen.kt:466-470` (debounced autosave on Dispatchers.IO), `EditorScreen.kt:392-402` (NonCancellable dispose flush can overlap the debounce window), `EditorScreen.kt:486-487` (saveLayersForPage fires immediately)
- **Exploit scenario:** Two concurrent saves for the same page interleave the HashMap read-modify-write: an older snapshot's hash commit can land last (newer stroke silently not inserted -> data loss), or a ConcurrentModificationException poisons the map, or the two withTransaction delete+Upsert rounds interleave dropping rows. Pure concurrency defect.

## The fix (where & how)

`NoteRepository.kt:511,544-547,584-585` - serialize per-page saves (per-page `Mutex`, or route all page writes through a single actor/coroutine); make `lastSavedStrokeHash` a `ConcurrentHashMap` keyed by pageId+strokeId (or move it inside the DAO transaction under Room's writer lock); `EditorScreen.kt:392-402` - have the dispose flush cancel the pending debounce job and await it.


## Verification

- Unit test (concurrency stress): concurrent same-page saves never drop the newest stroke and never corrupt the map. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-UI-3 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-73/REPORT.md` committed: what changed (file:line), the
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
