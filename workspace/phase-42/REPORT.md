# Phase 42 — B1-NET-03 Fix: compile-time plugin update trust anchor

**Status:** DONE
**Finding:** B1-NET-03 (HIGH) — "Plugin update chain: the unpinned, unsigned,
redirect-following manifest defines the ENTIRE trust anchor (downloadUrl +
sha256 + pinnedCertHash) → manifest compromise = arbitrary code execution"
(`docs/security-report.md`).

## What changed

### 1. New — `plugins/runtime/CompileTimePluginPinStore.kt`

The per-plugin update trust anchor moves **into the APK** (a compile-time table),
so the manifest can never set it out of nothing:

- `PinnedReleaseVersion(sha256, pinnedCertHash)` — one released version's pinned
  identity.
- `PinnedPluginRelease(id, version, sha256, pinnedCertHash)` — declarative row
  used to seed the production table and by tests.
- `PinVerdict.Verified(pin) | Rejected(reason)` — decision type.
- `CompileTimePluginPinStore` — builds/owns `id → version → pin`, plus a
  **download-host allow-list**:
  - `pinnedFor(id, version)` — compile-time pinned identity or null (⇒ not
    updatable by the Phase-24 mechanism).
  - `verifyOffer(HostedPluginVersion)` — checks `id`, `version`, `sha256`,
    `pinnedCertHash` AND that the artifact URL host is allow-listed.
  - `verifyEntry(PluginEntry)` — same check on the persisted-target form
    (the engine's re-verify).
  - `isAllowedDownloadHost(url)` — host gate for the downloader.
  - all digest/pin compares via `ConstantTime.hexEqual` (same fixed-length
    ASCII values as everywhere else in the runtime).
- `DEFAULT_DOWNLOAD_HOSTS = setOf(DEFAULT_MANIFEST_HOST)` — artifacts may only
  come from the same host as the update manifest.
- `isHostAllowListed(url, allowedHosts)` (internal, shared) — lowercase +
  trailing-dot folded on both sides, port ignored, false for anything that does
  not parse to a URL with a host.
- `CompileTimePluginPins.RELEASES = emptyMap()` — **the production table is
  deliberately EMPTY ⇒ fail closed**: no downloadable (remote) plugin has been
  released yet, so no hosted update is accepted until the operator adds the
  genuine `PinnedPluginRelease` row(s) AND bumps the app (same stance as the
  placeholder `PLUGIN_MANIFEST_CERT_PIN`). A manifest can never introduce an
  unpinned release.
- `CompileTimePluginPins.defaultStore` — the default every production consumer
  uses; a test/operator may inject a richer store.

### 2. `plugins/runtime/PluginUpdateChecker.kt`

- `check(installed, manifest, pins = CompileTimePluginPins.defaultStore)` — new
  `pins` parameter (default = secure production table, so existing call sites
  like `NoteflowViewModel` keep working unchanged).
- Every newer offer is verified via `pins.verifyOffer(offer)`; a `Rejected`
  verdict means the offer is **not offered at all** (`PluginUpdateChecker.kt:91-93`).
- `PluginUpdateInfo` is now built from the **compile-time pin values**
  (`sha256 = pinned.sha256`, `pinnedCertHash = pinned.pinnedCertHash`,
  `PluginUpdateChecker.kt:99-100`), not the manifest's text/casing. The
  old `toTargetEntry` verbatim-copy path is gone.

### 3. `plugins/runtime/PluginUpdateEngine.kt`

Defense-in-depth: even a caller that bypassed `PluginUpdateChecker` cannot
install an unpinned or re-pinned offer.

- New `pins` constructor param (default `CompileTimePluginPins.defaultStore`,
  `PluginUpdateEngine.kt:70`).
- `update()` re-verifies the **persisted target** via `pins.verifyEntry(target)`
  **before ANY byte moves and before the rollback-root write**
  (`PluginUpdateEngine.kt:110-116`) — a `Rejected` verdict returns a clear
  "refused … previous verified version is still active" failure. A `Verified`
  verdict is required for `updateStore.savePrevious` to run, so a rejected
  update leaves no rollback root and no downloaded bytes.

### 4. `plugins/runtime/PluginDownloader.kt`

- New `allowedDownloadHosts: Set<String> = DEFAULT_DOWNLOAD_HOSTS` param
  (`PluginDownloader.kt:90`) — the ONLY hosts artifact bytes may be fetched from.
- After the `https://` scheme check and **before any connection**, the URL host
  must pass `isHostAllowListed(url, allowedDownloadHosts)`; otherwise
  `DownloadOutcome.Failed("…the artifact host is not on the allow-listed plugin
  download hosts.")` (`PluginDownloader.kt:147-149`). Even a valid offer whose
  URL was swapped to a non-allow-listed host is refused.

### 5. `plugins/store/PluginStoreController.kt`

- New `pins` constructor param (default `CompileTimePluginPins.defaultStore`) and
  threaded into **both** `PluginUpdateChecker.check` calls (`:261`, `:306`).

### 6. KDocs updated

- `PinnedCertHash.kt` — documents the two compile-time trust anchors (phase 39
  manifest transport pin = B1-CRYPTO-01; phase 42 per-plugin release pins =
  B1-NET-03) and that the manifest values are now compared, never trusted.
- `HostedPluginManifest.kt` — the manifest's `downloadUrl`/`sha256`/
  `pinnedCertHash` are validated/compared but never trusted off the wire; all
  trust flows through the compile-time table.

## Out of scope (observed, not fixed — this phase is B1-NET-03 only)

- `PluginUpdateChecker.check` still surfaces the manifest's `downloadUrl`
  verbatim inside `PluginUpdateInfo`. It is **neutralized** (the engine's
  `verifyEntry` host gate + the downloader's allow-list refuse any non-allow-listed
  host before a connection, and the persisted target carries the pinned digests),
  but a future phase could normalize the offered URL to the allow-listed host.
- `NoteflowViewModel` uses the default pin store — correct today (fail-closed);
  an operator wiring real releases goes through `CompileTimePluginPins.RELEASES`.
- The two plugin-state layers (`plugins/runtime/PluginEntryStore` vs
  `plugins/store/PluginEntryStore`) remain as-is; unchanged this phase.
- No DB schema change, no migration, no new dependency — pure JVM + JDK.

## Files changed

- NEW `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/CompileTimePluginPinStore.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/PluginUpdateChecker.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/PluginUpdateEngine.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/PluginDownloader.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/plugins/store/PluginStoreController.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/PinnedCertHash.kt` (KDoc)
- `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/HostedPluginManifest.kt` (KDoc)
- NEW `app/src/test/java/com/authorss81/noteflow/CompileTimePluginPinStoreTest.kt`
- `app/src/test/java/com/authorss81/noteflow/PluginUpdateCheckerTest.kt`
- `app/src/test/java/com/authorss81/noteflow/PluginUpdateEngineTest.kt`
- `app/src/test/java/com/authorss81/noteflow/PluginDownloaderTest.kt`
- `app/src/test/java/com/authorss81/noteflow/PluginUpdateStoreFlowTest.kt`

## Tests

New `CompileTimePluginPinStoreTest` (10): matching offer Verified; mismatched
sha256 / mismatched cert pin / non-allow-listed host / unknown id / unshipped
version all Rejected; `pinnedFor` null cases; host allow-list case-insensitivity,
trailing dot, port ignored, non-URL input; entry-target verification and
re-pin rejection; production default store is fail-closed and includes the
manifest host.

New B1-NET-03 cases:
- `PluginUpdateCheckerTest` (+5): mismatched sha / mismatched cert pin refuse
  the offer; unshipped version not offered (fail closed); off-allow-list
  `downloadUrl` not offered; surfaced `PluginUpdateInfo` carries the compile-time
  pin values even when the manifest uses different casing.
- `PluginUpdateEngineTest` (+1): an unpinned or re-pinned target is refused
  BEFORE any byte moves (no download, no swap, no rollback root, no artifact on
  disk).
- `PluginDownloaderTest` (+2): a non-allow-listed host is refused before the
  transport is touched; the default allow-list (the manifest host) blocks
  everything else.
- `PluginUpdateStoreFlowTest` (+1): `checkForUpdates` refuses a forged offer even
  when it is strictly newer.

Also: **the documented flaky `PluginUpdateEngineTest#a hash mismatch on the
downloaded artifact is never applied` was made deterministic.** It previously
depended on v1/v2 artifacts differing (they are built from the same keystore +
class, and can be byte-identical when both jars land in one clock tick, so the
"SHA-256 mismatch" intermittently resolved to a success). The test now serves a
tampered copy (`appendBytes(0x42)`) so the served digest can NEVER match the v2
pin — the mismatch is guaranteed. Verified stable over 4 consecutive isolated
runs (was failing ~2/3 runs in isolation).

## Verification

Run on CI Linux runner (system gradle 8.13, JDK 17):

- `gradle :app:testDebugUnitTest` — **943 tests completed, 0 failed** twice
  (full-suite `--rerun-tasks` runs). The `WikiLinkParserCacheUnitTest#a
  cancelled scan…` timing flake (documented phase-40/41, untouched file) surfaced
  in one intermediate run and passed green in isolation + on re-run.
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL** (57 tasks, 24 executed).

## Security posture / checksum & secrets handling

- No keys, passwords, or decrypted note content are logged or added anywhere;
  no new logging strings beyond the existing failure messages. All digest/pin
  compares go through `ConstantTime.hexEqual` (constant-time, the runtime's
  established pattern).
- `.github/workflows/` untouched. `allowBackup="false"`, `data_extraction_rules.xml`,
  FLAG_SECURE intact. No DB schema change, no migration.
- B1-NET-03 documented as FIXED in `docs/security-report.md`; phase-status +
  ARCHITECTURE.md plugin-runtime sections updated.