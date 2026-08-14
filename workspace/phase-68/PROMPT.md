# Phase 68: B1-AUTH-04 - Markdown image references resolve arbitrary absolute... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-AUTH-04, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-AUTH-04` (MEDIUM)
- **Area:** Batch 1 - App logic & auth
- **Evidence:** `ImageViewer.kt:123-132` (MarkdownInlineImage resolves `destination` as `File(dest)` and accepts it if `file.isAbsolute && file.exists()`, else `File(baseDir, dest)` with no canonicalization - `../..` escapes baseDir), `MarkdownPreviewScreen.kt:1249-1264` (feeds every `![alt](dest)` into it), baseDir = note file's parent (`MarkdownPreviewScreen.kt:534`)
- **Exploit scenario:** A crafted note (via vault-import zip, WebDAV, share sheet, or LocalSend) contains `![x](/data/user/0/<appId>/files/voice_notes/...)` or `![x](../../../<file>)`. Opening the note makes the app decode-and-display any file the process can read (imports, shared staging, exports, voice notes), and the 'File not found: <path>' fallback (ImageViewer.kt:163-170) doubles as an existence oracle.

## The fix (where & how)

`ImageViewer.kt:123-132` - resolve image destinations ONLY inside an allowlisted app-private subtree via `file.canonicalPath.startsWith(rootDir.canonicalPath)`; reject absolute paths and any path segment `..` before reading.


## Verification

- Unit tests: absolute-path and `../`-traversal destinations are rejected; in-subtree relative paths resolve. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-AUTH-04 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-68/REPORT.md` committed: what changed (file:line), the
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
