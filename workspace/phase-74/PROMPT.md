# Phase 74: B2-UI-5 - Markdown note body saves are non-atomic File.writeText... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-UI-5, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-UI-5` (MEDIUM)
- **Area:** Batch 2 - Compose/UI, concurrency, TOCTOU
- **Evidence:** `MainActivity.kt:312-318,410-416` (`produceState` reads `File(path).readText()` on IO), `MainActivity.kt:337-341,434-438` (`onSaveContent` -> `NonCancellable + Dispatchers.IO { File(path).writeText(newText) }` - truncate+write, no temp+rename), `MarkdownPreviewScreen.kt:154-159` (flushSave fires on BackHandler + DisposableEffect dispose), `MarkdownPreviewScreen.kt:147,522-523` (contentText state)
- **Exploit scenario:** The dispose flush runs concurrently with the next screen's produceState read (same file when flipping back-and-forth): `readText()` observes a partially-written file (tail missing), the preview shows truncated text, and if the user edits + flushes the truncated version is written back permanently. Two overlapping flush writes can leave a torn file.

## The fix (where & how)

`MainActivity.kt:312-341,410-438` and `MarkdownPreviewScreen.kt:154-159` - serialize + atomicize markdown saves: write to a temp file in the same directory and `renameTo` (atomic on the same filesystem), or hold a per-path `Mutex` around read+write; cancel/await in-flight writes in `onDispose` before allowing the target screen's read.


## Verification

- Unit test (or documented manual verification): concurrent read+write on one path never yields a truncated file; a torn-write simulator is covered. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-UI-5 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-74/REPORT.md` committed: what changed (file:line), the
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
