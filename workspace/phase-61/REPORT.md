# Phase 61 — B1-PLAT-7: locally-present APKs are no longer a trusted update source

**Status:** DONE — 2026-08-15
**Finding:** `B1-PLAT-7` (MEDIUM, Batch 1 · Android platform surface, `docs/security-report.md`)
**Verification:** `gradle testDebugUnitTest` (1217 tests, 2 documented pre-existing failures) +
`gradle assembleDebug` (BUILD SUCCESSFUL)

## What the finding said

`UpdateService.kt:104-137` (`checkForDownloadedUpdates`) scanned **publicly writable**
directories — `getExternalFilesDir`, `cacheDir`, `filesDir`, `/sdcard/Download`,
`/storage/emulated/0/Download` — for `.apk` files. `UpdateService.kt:195-246` treated
signature equality with the installed app as sufficient trust, so a same-signature file was
announced as `"New update detected in local storage"` and `installApk` (`:146-175`) drove the
platform installer with no warning. Because the release build falls back to the public Android
debug key (B1-PLAT-1), an attacker with that key drops a same-signature malicious
higher-versionCode APK into `/sdcard/Download` via any storage-writable app or a poisoned web
download → the app itself offers it as an official update → one-step watering hole for full
vault compromise — and the mechanism conditions users into trusting "updates found in Downloads".

## The fix (before → after, `file:line`)

### New pure-JVM trust policy — `services/UpdateTrustPolicy.kt` (new)
Owns the entire decision table; no Android deps, unit-testable:
- `hasOfficialChannel()` (:46) → `false` (the app ships no official, remote-key-verified channel).
- `classifySource(hasOfficialChannel)` (:52) → every locally-present APK is
  `UNTRUSTED_LOCAL`; only a real key-verified channel would yield `OFFICIAL`.
- `isPubliclyWritableDirectory(dir)` (:62) — structural path refusal of `/sdcard`,
  `/sdcard/Download`, `/storage/emulated/0`, `/storage/emulated/0/Download`, any
  `/sdcard/…`/`/storage/emulated/…` mount, and any `…/Android/data/…` (external-files-dir)
  path, case-insensitive with slash normalization. App-private `filesDir`/`cacheDir`
  (`/data/user/0/…`, `/data/data/…`) are NOT public.
- `isScanSafeDirectory(dir)` (:87) — a scan candidate is only claimable when not public.
- `mayInstall(trust, userConfirmedUntrusted)` (:94) — `UNTRUSTED_LOCAL` installs ONLY on
  explicit confirmation; **fail closed** (`false` default).
- Trust-neutral copy: `confirmationTitle`/`confirmationMessage` (:101-106) and
  `announcementForLocal` (:113) — deliberately never says "New update"/"detected".

### `services/UpdateService.kt`
- `UpdateInfo` gained `trust: UpdateSourceTrust` (:22).
- `inspectApkFile` (:60): classifies via `classifySource(hasOfficialChannel())` (:79);
  release notes come from `announcementForLocal`/`staleFileMessage` (:106-109); signature
  mismatch is still an outright refusal (integrity hint only — signature equality is NOT
  provenance under B1-PLAT-1).
- `checkForDownloadedUpdates` (:128): candidate dirs are now ONLY app-private
  `filesDir`/`cacheDir` — `getExternalFilesDir`, `/sdcard/Download`,
  `/storage/emulated/0/Download` are **gone** from the scan — and each candidate passes
  through `UpdateTrustPolicy.isScanSafeDirectory` (:137) so a future re-added public dir
  still can't sneak back in.
- `installApk` (:175): signature gained `trust` + `userConfirmedUntrusted` params; the FIRST
  check is `UpdateTrustPolicy.mayInstall(trust, userConfirmedUntrusted)` (:181) — an
  untrusted file without explicit confirmation returns `false` before any staging/copy/
  FileProvider/launch.

### `ui/components/Dialogs.kt` — `AppUpdateDialog`
- "Scan Downloads for APK" → "Scan App Storage for APK"; status copy is now
  "public Downloads are never scanned" (:208).
- Picker result copy dropped "Found newer APK version!" in favour of the trust-neutral
  "Selected local APK is newer than the installed app (…)" (:113).
- "Install Update" no longer launches directly — it sets `showUntrustedConfirm` (:236), which
  renders the **strong untrusted-confirmation dialog** (`UpdateTrustPolicy.confirmationTitle/
  Message`, error color, `Install anyway` vs `Cancel`, :259-295). `installApk` is invoked only
  from that dialog with `userConfirmedUntrusted = true` (:286-292).

## API / device floor

No new API surfaces (minSdk 26 unchanged). The existing API-28 `GET_SIGNING_CERTIFICATES` vs
pre-28 `GET_SIGNATURES` branch in `verifyApkSignature` is retained, now as a pure integrity
hint; trust no longer depends on it. Everything else is pure JVM + existing Compose dialog
primitives. No fallback needed beyond what exists.

## Verification output

- `gradle :app:testDebugUnitTest --tests "…B1Plat07UpdateTrustTest"` — **8 tests, PASS**.
- `gradle testDebugUnitTest` — **1217 tests completed, 2 failed**:
  - `B1Plat01ReleaseSigningTest > debug buildType keeps AGP auto generated debug keystore`
  - `B1Plat01ReleaseSigningTest > release guide forbids distributing debug-signed builds`
  - Both are the **documented pre-existing failures** asserted against
    `app/build.gradle.kts` / `docs/RELEASE.md` (which intentionally keep the debug-keystore
    fallback per AGENTS.md `docs/RELEASE.md` wiring). They already failed in phases 55/59/60
    and fail identically on a clean tree; this diff touches neither file. Unrelated.
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (2m 39s).

## Tests added — `B1Plat07UpdateTrustTest.kt` (8)

1. `no local APK is ever official - only a remote key-verified channel would be`
2. `public Downloads mounts are never scan-safe`
3. `app-private storage dirs stay scan-safe`
4. `untrusted install is fail-closed and yields only to explicit confirmation`
5. `a same-signature APK is still UNTRUSTED - signature equality is not provenance`
6. `announcements never condition the user into trusting a local file`
7. `UpdateService never references a publicly writable directory` (source pin) +
   `UpdateService routes every scan candidate through the policy gate` (source pin) +
   `UpdateService announcement copy is trust-neutral` (source pin) +
   `installApk refuses an unconfirmed untrusted file before any byte moves` (source pin)
8. `install is gated behind the strong untrusted confirmation in the dialog` (source pin)

## Checksums / secrets

No keys, passwords, or decrypted content are involved or logged. The only new `Log` calls are
the module-tag failure lines in `installApk` (no PII, no file contents). No `INTERNET` usage
added; no new permissions; `allowBackup=false`, `ClipboardGuard`, and FLAG_SECURE untouched.

## Scope notes / out-of-scope

- **No schema change, no migration, no new dependencies, `.github/workflows/` untouched.**
- The existing "Select Local APK File…" manual picker is **kept** (explicit human action),
  but every install now requires the two-tap strong confirmation — the arbitrary-file launch
  path a normal app installer already exposes to the user's own finger.
- **B1-PLAT-1** (release signed with the debug key) is a separate phase-57 finding — signature
  equality was demoted from "trust" to "integrity hint" here precisely because of it, but the
  key itself is not rotated in this phase.
- No official (network) update channel is implemented in this app today; when one ships it
  must arrive with a remote-verified pin (B1-CRYPTO-01 pattern), not another local APK feed.
- Known residual: `installApk`'s catch still falls back to the original `apkFile` path when
  staging fails — the gate already ran, so the fallback never bypasses trust.