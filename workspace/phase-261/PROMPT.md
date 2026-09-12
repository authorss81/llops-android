# Phase 261 — WebDAV DNS-masquerade + backup staging hygiene

## Goal
Fix cleartext-credential bypass + crash-window plaintext staging.

## Evidence
- HIGH `WebDavSyncService.kt:115-126 isLocalNetworkHost()`: `String.startsWith` on raw host → DNS `10.evil.com`/`192.168.attacker.example` misclassified local → `allowInsecureHttp` sends Basic + encrypted backup over HTTP to attacker. `172.` octet parse runs on DNS labels. Fix: `InetAddress.getByName` + `isLoopback/isSiteLocal/isLinkLocal` only; never startsWith on DNS. MEDIUM: missing `fc00::/7`, `fe80::/10`, `::ffff:10.x`, `127/8`.
- MEDIUM `BackupPortabilityPolicy.kt:22` allows `requireBackupPassword=false` for WebDAV/LocalSend → silent device-keyed backup (KeyStore loss = permanent loss). Keep gate but require explicit UI warning (phase-252 copy) at sync call sites.
- MEDIUM `BackupExportPolicy.kt:55` transient plaintext zip on cacheDir/filesDir with no in-policy `try/finally delete`; crash between staging and caller delete leaves plaintext. Fix: `createTempFile` + `finally{ staging.delete() }` inside policy.
- PASS (do not regress): HTTPS dual-gate (`validateServerUrl` + `requireSecureUrl` + `requireConfiguredServerOrigin`, redirects refused), Basic only after same-origin, userinfo stripped/scrubbed, LocalSend sender-only + HTTPS+fingerprint pin, path traversal (`encodedRemoteFolderSegment`, `sanitizeImportFileName`, `WebDavHrefResolver`) + zip-bomb/size caps (200/400MB, 100x ratio, streaming).

## Files to change
- `services/WebDavSyncService.kt`, `services/BackupExportPolicy.kt`, `services/WebDavSyncDialog` warning copy if missing

## Tests
- `Phase261WebDavBackupTest`: `10.evil.com` NOT local; literal `10.0.0.5` IS local; staging deleted on crash (JVM policy test).

## Constraints
- No Room schema change / migration
- No new dependencies (pure Kotlin/Compose only)
- No `.github/workflows/` edits
- Follow AGENTS.md hard rules (allowBackup=false stays, no plaintext rows, fail closed)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-NNN/REPORT.md` with file:line evidence table (claim / reality / status / evidence)
