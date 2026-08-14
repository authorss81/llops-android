# Phase 78: B2-DOS-02 - Vault search re-decrypts the ENTIRE vault on every... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-02, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-02` (MEDIUM)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `NoteRepository.kt:58-76` (loadSearchCorpus: when corpus > `searchCorpusMaxPages` (1500) the cache is NOT stored, so every call re-runs full-vault AES-GCM on Dispatchers.IO), `NoteRepository.kt:260-268` (searchPages calls it per query), `HomeScreen.kt:171-176` (search on every non-blank keystroke, 300 ms debounce), `NoteflowViewModel.kt:1608-1613` (fresh coroutine per query, never cancelled), no LIMIT in `Daos.kt:74,83,101`
- **Exploit scenario:** A vault of 5k+ pages: typing 'hello' = ~4 fires x full-vault AES decrypt + O(n) substring scans, saturating 2-core CPUs for seconds per keystroke. The pre-1500 cache keeps the typical case fine; past 1500 it reverts to O(vault-size) per keypress.

## The fix (where & how)

`NoteRepository.kt:58-76,260-268` - always cap the searched window (e.g. first 1500 rows with an explicit 'refine' path) or build a real incremental encrypted search index; `NoteflowViewModel.kt:1608-1613` - cancel the previous in-flight search (share a Job) so concurrent full-decrypts cannot pile up; add `LIMIT` to `getAllActivePagesFlow`/`getPagesForSection` consumers (`Daos.kt:74,83,101`) or virtualize loads.


## Verification

- Unit test: searches beyond 1500 pages are bounded and concurrent keystrokes cancel prior in-flight searches. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-02 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-78/REPORT.md` committed: what changed (file:line), the
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
