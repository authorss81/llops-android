# Phase 67: B1-AUTH-03 - Downloadable-plugin lifecycle hooks (onProcessStart ->... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-AUTH-03, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-AUTH-03` (MEDIUM)
- **Area:** Batch 1 - App logic & auth
- **Evidence:** `NoteflowViewModel.kt:211-227` (init block loads ALL entries + `pluginRegistry.onProcessStart(appContext)` unconditionally before authentication), `PluginRegistry.kt:172-191` (onProcessStart fires `guardedOnEnable(plugin, context)` with the real application Context)
- **Exploit scenario:** An installed + enabled plugin's `onEnable(context)` runs at every process launch while the app sits on the LockScreen - a live Context and (per B1-AUTH-01) full class access, able to open the DB and recover the DEK before the user ever unlocks.

## The fix (where & how)

`NoteflowViewModel.kt:211-227` - gate ALL plugin runtime loading and lifecycle hooks (including store re-materialization) behind `authenticated == true`; do not run any plugin code before a successful unlock; stop/disable hooks on lock (`PluginRegistry.kt:172-191`).


## Verification

- Unit test: with the vault locked, an enabled plugin's onEnable is never invoked; after unlock it is invoked once. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-AUTH-03 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-67/REPORT.md` committed: what changed (file:line), the
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
