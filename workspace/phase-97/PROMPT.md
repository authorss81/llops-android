# Phase 97: B2-DEPS-01 - jsoup 1.17.2 is vulnerable to CVE-2026-71497 (XSS via... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DEPS-01, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DEPS-01` (LOW)
- **Area:** Batch 2 - Dependencies / CVE / supply chain
- **Evidence:** `gradle/libs.versions.toml:28` (`jsoup = "1.17.2"`, lib at `:74`), used only by `WebToMarkdownExtractor.kt:3,27` (`Jsoup.parse`). CVE-2026-71497 affects `org.jsoup:jsoup >= 1.14.3, < 1.23.1`; fixed in 1.23.1
- **Exploit scenario:** The app does not currently use Jsoup.clean/Cleaner/Safelist (grep-verified), so the vulnerable path is not exercised today - but the pinned version carries a public unfixed XSS advisory and is 4+ minor releases behind, so any future HTML-sanitizing feature inherits the flaw.

## The fix (where & how)

`gradle/libs.versions.toml:28` (and `:74`) - upgrade `jsoup` to `>= 1.23.1`; verify `WebToMarkdownExtractor` still parses correctly. This is an existing-dependency upgrade justified by a confirmed CVE (not a new dep).


## Verification

- `gradle testDebugUnitTest` (WebToMarkdownExtractor tests) + `gradle assembleDebug` pass after the version bump. `gradle dependencyUpdates`-style check not required.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DEPS-01 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-97/REPORT.md` committed: what changed (file:line), the
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
