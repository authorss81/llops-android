# Phase 39: B1-CRYPTO-01 - Downloadable-plugin integrity pins are... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-CRYPTO-01, CRITICAL) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-CRYPTO-01` (CRITICAL)
- **Area:** Batch 1 - Cryptography & key management
- **Evidence:** `PluginUpdateChecker.kt:74-82` / `30-38` (manifest values copied verbatim into the persisted active entry), `PluginManifestFetcher.kt:69-81,95-135` (`HttpsManifestTransport` is chain-validation-only, `instanceFollowRedirects=true`), `HttpsPluginDownloadTransport.kt:143-154` (pins TLS to `target.pinnedCertHash`), `PluginUpdateEngine.kt:108-138`, `SignatureVerifiedPluginRuntime.kt:92-99` (re-verifies against the same attacker-supplied entry fields), `PinnedCertHash.kt:20-21` (false 'never from the network' guarantee)
- **Exploit scenario:** MITM of `plugin-updates.inkflow.app` (DNS + any CA cert, hosting-account compromise, or a 302 to `http://`) serves a forged manifest offering `downloadUrl=https://attacker.example/evil.apk`, `sha256=<hash of evil>`, `pinnedCertHash=<attacker cert>`. Every later check (manifest validation, TLS pin, artifact signature) passes self-consistently and `DexClassLoader` executes attacker DEX in the app process on one click. Full arbitrary code execution.

## The fix (where & how)

`PluginManifestFetcher.kt` (`HttpsManifestTransport`), `PluginUpdateChecker.kt`, `HttpsPluginDownloadTransport.kt`, `SignatureVerifiedPluginRuntime.kt`, `PinnedCertHash.kt`. Replace chain-validation-only manifest fetch with a transport pinned to a COMPILE-TIME cert hash (reuse the existing `PinnedCertHash`/pinned-connection machinery from the artifact transport), or sign the manifest body with a compile-time-pinned key and verify the signature before trusting ANY field. Update offers must never be able to redefine `sha256`/`pinnedCertHash` from an unauthenticated source. Set `instanceFollowRedirects=false`.


## Verification

- New pure-JVM unit tests for the manifest transport (rejects unpinned/mismatched-pin manifests, rejects `http://` and cross-host redirects). `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-CRYPTO-01 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-39/REPORT.md` committed: what changed (file:line), the
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
