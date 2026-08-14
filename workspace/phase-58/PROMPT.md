# Phase 58: B1-PLAT-2 - Exported singleTask MainActivity accepts ACTION_SEND... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-PLAT-2, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-PLAT-2` (MEDIUM)
- **Area:** Batch 1 - Android platform surface
- **Evidence:** `AndroidManifest.xml:33-68` (MainActivity `exported="true"`, `launchMode="singleTask"`, SEND/SEND_MULTIPLE filters for `text/plain`, `image/*`, `*/*`), `MainActivity.kt:95,502-578` (readShareIntent), `MainActivity.kt:582-600` (copySharedUris copies arbitrary granted streams into `filesDir/shared`), `MainActivity.kt:173-181` (pending share auto-applied to a new note after unlock)
- **Exploit scenario:** A malicious app fires an ACTION_SEND directly at this exported component (no chooser): the app is yanked to the foreground and (a) copies attacker-supplied `EXTRA_STREAM` bytes into app-private storage, and (b) on the user's next unlock silently creates an attacker-controlled note (phishing/'vault compromised', storage-exhaustion DoS via huge payloads).

## The fix (where & how)

`MainActivity.kt:95,502-600,582-600,173-181` - require an explicit in-app confirmation for ALL incoming shares (e.g. only auto-accept shares delivered via the chooser flow, or always show a 'Clip into InkFlow?' confirm dialog before staging/copying); cap `copySharedUris` total bytes (reuse a bounded stream-copy helper); do not pre-copy share streams while the vault is locked.


## Verification

- Unit test (or documented manual verification): a share intent without chooser-origin is held behind a confirmation; the staging copy enforces a byte cap and is paused while locked. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-PLAT-2 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-58/REPORT.md` committed: what changed (file:line), the
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
