# Phase 101: B2-DOS-11 - Backlink/tag-hierarchy and knowledge-graph builders... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-11, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-11` (LOW)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `WikiLinkParser.kt:90-110` (findBacklinks: for each of allPages, `getFullTextForPage` re-reads the note file (:59-76) and runs extractWikiLinks + a per-page regex), `WikiLinkParser.kt:125-163` (buildTagHierarchy: same full-vault reads + regex + a recursive tree build whose depth = number of `/`-segments in attacker-controlled tags), `KnowledgeGraphScreen.kt:83-84` (materializes the full page list each time). No memoization, no cap
- **Exploit scenario:** On a vault of thousands of notes, opening Backlinks/Knowledge Graph triggers O(notes x avg-note-KB) file I/O + regex scanning on the main-ish path every time the panel opens - multi-second freezes on 2-core devices, recomputed from scratch on every visit.

## The fix (where & how)

`WikiLinkParser.kt:90-163`, `KnowledgeGraphScreen.kt:83-84` - cache computed backlinks/tag-hierarchy per unlock epoch; cap the scanned set (LIMIT); cache `getFullTextForPage` results in-memory (bounded); bound the tag-tree recursion depth; run the builds on `Dispatchers.Default` with cancellation when the panel closes.


## Verification

- Unit test: repeated panel opens reuse the cache (no re-scan); the scanned set and tree depth are bounded. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-11 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-101/REPORT.md` committed: what changed (file:line), the
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
