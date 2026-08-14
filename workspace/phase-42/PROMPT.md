# Phase 42: B1-NET-03 - Plugin update chain: unpinned, unsigned,... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-NET-03, HIGH) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-NET-03` (HIGH)
- **Area:** Batch 1 - Data-in-transit & network
- **Evidence:** `HostedPluginManifest.kt:29-57` (offer carries `downloadUrl`, `sha256`, `pinnedCertHash`), `PluginUpdateChecker.kt:30-38` (`toTargetEntry` copies them verbatim), `PluginUpdateEngine.kt:128` (verifier trusts manifest-supplied values), `PluginManifestFetcher.kt:83-151` (system-chain HTTPS only, `instanceFollowRedirects=true` at :98), `HttpsPluginDownloadTransport.kt:56-62` (artifact transport follows redirects)
- **Exploit scenario:** The manifest is fetched with nothing stronger than ordinary CA-validated TLS; an attacker who compromises or MITMs the manifest host serves a self-consistent forged offer. Every subsequent check (manifest validation, TLS pin, artifact signature) passes by construction and attacker DEX installs after the single approval dialog. The compile-time-pin claim in `PinnedCertHash.kt` is false for this path.

## The fix (where & how)

`PluginUpdateChecker.kt`, `HostedPluginManifest.kt`, `PluginManifestFetcher.kt`, `HttpsPluginDownloadTransport.kt`, `PinnedCertHash.kt`. Ship compile-time per-plugin pinned identities in the APK (e.g. a map `id -> {signingCertHash, sha256}` for every released version) and verify ALL update offers against those compile-time pins - never against values read from the manifest. Restrict `downloadUrl` hosts to an allow-list that includes the manifest host. Set `instanceFollowRedirects=false` on both manifest and artifact transports.


## Verification

- Pure-JVM unit tests: an update whose manifest-supplied sha256/certHash differs from the compile-time pin is rejected even if the manifest itself validates. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-NET-03 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-42/REPORT.md` committed: what changed (file:line), the
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
