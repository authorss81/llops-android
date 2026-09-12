# Phase 261 — WebDAV DNS-masquerade + backup staging hygiene

## Goal
Fix cleartext-credential bypass (`isLocalNetworkHost` DNS masquerade) + crash-window
plaintext staging, and make the documented device-keyed sync producers warn loudly.

## Evidence table (claim / reality / status / evidence)

| # | Claim | Reality | Status | Evidence |
|---|-------|---------|--------|----------|
| 1 | `10.evil.com` is NOT local | DNS names never reach address classification; only `localhost`, `*.local`, and numeric IP literals proceed | FIXED | `services/WebDavSyncService.kt:111-131` (`isNumericIpCandidate` gate `:174-189`); `Phase261WebDavBackupTest.dns masquerade 10-evil-com is NOT local` green |
| 2 | `192.168.attacker.example` is NOT local | Same gate: dotted non-numeric segments fail the candidate check, return false with no DNS resolution | FIXED | `WebDavSyncService.kt:174-189`; `... 192-168-attacker is NOT local` green |
| 3 | Literal `10.0.0.5` IS local | IP literals classify via `InetAddress.getByName` + structural fallback | FIXED | `WebDavSyncService.kt:119-130`; `literal 10-0-0-5 IS local` + `validateServerUrl` literal-accept green |
| 4 | No `startsWith` on DNS | All four private-prefix `startsWith` branches deleted; classification is `isLoopback/isSiteLocal/isLinkLocal` + explicit ULA/mapped/127/8 | FIXED | `WebDavSyncService.kt` has zero `startsWith("10.")` / `("192.168.")` / `("172.")` / `("169.254.")` (source-pinned); `isUniqueLocalV6` `:191-195`, `isStructuralLocalV6` `:209-226`, `isPrivateIpv4Value` `:250-258` |
| 5 | Missing `fc00::/7`, `fe80::/10`, `::ffff:10.x`, `127/8` covered | `fc00::/7` via byte-prefix + first-hextet checks, `fe80::/10` via `(v & 0xFFC0)==0xFE80`, mapped via trailing-IPv4 private check, full `127/8` via loopback + `0x7F000000..0x7FFFFFFF` | FIXED | `WebDavSyncService.kt:191-258`; `rfc1918 and loopback literals ARE local` (fc00/fd00/fe80/mapped/127.0.0.2) green |
| 6 | Staging deleted on crash | `useStagingZip` creates the stage via `createTempFile` and deletes it in the in-policy `finally`; the export caller holds no predictable staging path | FIXED | `services/BackupExportPolicy.kt:63-77`; `exportBackupInternal` routes the whole zip+encrypt through it (`services/ImportExportService.kt:1812`); `staging file is deleted on crash` + `... after success` green |
| 7 | Device-keyed sync warns explicitly | WebDAV dialog shows the device-bound notice always; LocalSend shows it for the vault-backup payload; shared copy in `BackupPortabilityPolicy.DEVICE_KEYED_SYNC_WARNING`; translatable strings in `strings.xml` | FIXED | `ui/components/WebDavSyncDialog.kt:186-195`, `ui/components/LocalSendSendDialog.kt:431-441`, `services/BackupPortabilityPolicy.kt:41-49`, `res/values/strings.xml:36-37`; warning source-pins green |
| 8 | PASS preserved: HTTPS dual-gate, same-origin Basic, no redirects, scrubbed userinfo, sender-only pinned LocalSend, traversal + zip-bomb caps | Untouched — no edits to `validateServerUrl` scheme logic, `requireSecureUrl`, `requireConfiguredServerOrigin`, `instanceFollowRedirects=false`, `stripUrlUserInfo`, LocalSend pairing/TLS, `encodedRemoteFolderSegment`/`sanitizeImportFileName`/`WebDavHrefResolver`, 200/400MB + 100x caps | PASS | Full suite green; `WebDavSyncServiceTest`, `WebDavHrefResolverTest`, `B1Db05ImportZipBombTest` untouched and green |
| 9 | Portability gate kept | `requireBackupPassword` default `true` + `requirePortableBackup` unchanged; WebDAV/LocalSend keep the documented `false` opt-out | PASS | `services/BackupPortabilityPolicy.kt:73-79` unchanged; `Phase252PasswordlessBackupTest` green |

## Stale pin updated (not a product change)
- `B2Dos07BackupExportStreamingTest.exportBackup streams the archive to a staging
  file instead of a heap array` pinned the OLD hygiene model (predictable
  `cacheDir/stagingFileName` path + caller-side `stagingZip.delete()`). Per this
  phase's PROMPT the stage is now policy-owned (`useStagingZip` /
  `createTempFile` / in-policy `finally`), so the pin asserts the new shape:
  caller uses `useStagingZip`, holds no `stagingFileName(backupName)` path, and
  performs no export-region `stagingZip.delete()` (the restore-path decrypted
  staging keeps its own finally delete — explicitly out of scope, slice-scoped
  assertion). `stagingFileName()` itself is retained for the unchanged
  never-public-name pin.

## Verification
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` **3745 / 0 failures / 0 errors / 0 skipped**
  (3733 at HEAD + 12 new `Phase261WebDavBackupTest`; the B2Dos07 pin rework
  changes assertions within the same test methods, not the count)
- `gradle :app:lintDebug` 0 errors (BUILD SUCCESSFUL)

## Constraints
No Room schema change / migration, no new dependencies (pure Kotlin/Compose +
`java.net.InetAddress` + `File.createTempFile` only), no `.github/workflows/`
edits, `allowBackup="false"` untouched, fail-closed throughout
(masquerade → HTTPS-required refusal; crash → stage deleted; oversized →
refused), `verification-metadata.xml` untouched.
