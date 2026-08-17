# Phase 61: B1-PLAT-7 - UpdateService auto-discovers update APKs in publicly... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-PLAT-7, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-PLAT-7` (MEDIUM)
- **Area:** Batch 1 - Android platform surface
- **Evidence:** `UpdateService.kt:104-137` (checkForDownloadedUpdates scans `getExternalFilesDir`, `cacheDir`, `filesDir`, `/sdcard/Download`, `/storage/emulated/0/Download`), `UpdateService.kt:146-175` (stages into `filesDir/apk` and drives the platform installer), `UpdateService.kt:195-246` (signature check = compare against current app signatures - weak under B1-PLAT-1's debug key)
- **Exploit scenario:** An attacker who obtains the (trivial) release signing key drops a same-signature malicious higher-versionCode APK in public Downloads; the app itself announces 'New update detected in local storage' and the platform installs it with no signature warning - a one-step watering hole for full vault compromise, and the mechanism conditions users to trust 'updates found in Downloads'.

## The fix (where & how)

`UpdateService.kt:104-137,146-175,195-246` - never treat public Downloads (or ANY locally-present APK) as a trusted update source. Only trust updates from an official channel with signature verification against a known/remote-verified signing key, and gate any self-install behind a strong 'update is not from a trusted source' confirmation; remove the public-Downloads scan path.


## Verification

- Unit test: a same-signature malicious APK placed in Downloads is NOT offered as an update; only the official channel path (with key verification) offers updates. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-PLAT-7 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-61/REPORT.md` committed: what changed (file:line), the
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
