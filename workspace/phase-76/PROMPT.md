# Phase 76: B2-DEPS-04 - Downloadable-plugin signing key: hardcoded default... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DEPS-04, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DEPS-04` (MEDIUM)
- **Area:** Batch 2 - Dependencies / CVE / supply chain
- **Evidence:** `plugins/llm/build.gradle.kts:147-148` (`KEYSTORE_ALIAS = "plugin-signing"`, `DEFAULT_KEY_PASSWORD = "inkflow.2026.plugins"`), `:150-175` (when `PLUGIN_SIGNING_KEYSTORE_B64` unset it runs `keytool -genkeypair` to mint a fresh self-signed JKS every build), `:177-184` (loadSigningCert falls back to the hardcoded password), `:186-212` (jarsigner with the fallback password), `:227-262` (pluginMetadata emits sha256+pinnedCertHash of whatever key was used), `:229-233` (comment claims `:app:generateLlmPluginSeed` consumes the pin - that task does not exist anywhere; `PluginStoreCatalog.kt:57-81` seeds all bundled entries with sha256=null/pinnedCertHash=null)
- **Exploit scenario:** The signer's identity is self-referential (the trust anchor would be the hash of a key a local build silently generated seconds earlier) and today no compiled-in pin exists at all. The committed default password means any genuine plugin keystore created with it is protected by a public secret - anyone with source can sign official-looking plugins.

## The fix (where & how)

`plugins/llm/build.gradle.kts:147-212,227-262` - delete the ephemeral `keytool -genkeypair` fallback and the hardcoded `DEFAULT_KEY_PASSWORD`; make the plugin build FAIL when `PLUGIN_SIGNING_KEYSTORE_B64`/`PLUGIN_SIGNING_STORE_PASS` are unset. Actually implement `:app:generateLlmPluginSeed` (or remove the claim) so the app's compiled-in pin matches the one real CI key identity - never a build-bred one. Keep the key in a secret store.


## Verification

- `gradle assembleDebug` (debug path, no plugin signing) still works; verify the llm plugin build fails-loud when signing env is missing (document the failure output - do NOT change workflows to add a real keystore). `gradle testDebugUnitTest` passes.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DEPS-04 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-76/REPORT.md` committed: what changed (file:line), the
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
