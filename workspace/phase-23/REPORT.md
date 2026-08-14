# Phase 23 — Downloadable-plugin runtime: download, verify, load

**Status:** DONE
**Date:** 2026-08-14
**Companion design doc:** `docs/plugin-architecture.md` (§ Security model)
**Phase 22:** `workspace/phase-22/REPORT.md` (skeleton — `PluginEntry`/`PluginContext`/`PluginRuntime` seams)

## What Phase 23 delivers

The Phase-22 `PluginRuntime` seams are now REAL for the download side. Heavy/native
features can be shipped as downloadable, signature-verified artifacts without growing
the base APK: HTTPS download → pinned-certificate + SHA-256 verification → `DexClassLoader`
load → capability-facade execution. The three remaining Phase-22 seams that stay
honest stubs are `update`/`rollback` (Phase 24 — user-approved updates) and the LLM/QR
plugins themselves (Phase 29 — moved out of the base APK).

## 1. Download (pure-JVM core + pinned HTTPS transport)

- `PluginDownloader` (`plugins/runtime/PluginDownloader.kt`) — pure-JVM downloader
  with the full guard set BEFORE and DURING any transfer:
  - **User-initiated only:** refuses without explicit consent (`PluginDownloader.kt:122-126`).
  - **TLS only:** the entry's `downloadUrl` must be `https://`, re-checked in addition
    to `PluginEntry.validationErrors` (`PluginDownloader.kt:133-138`).
  - **Never to shared storage:** the target must resolve inside the caller-supplied
    app-private directory (`PluginDownloader.kt:154-159`).
  - **Size + free-space guards:** per-entry `installSizeBytes` vs `MAX_ARTIFACT_BYTES`
    (500 MB) and vs the free-space probe (`PluginDownloader.kt:139-153`, cap at `:214`).
  - **Resume:** interrupted downloads leave `<name>.part`; the next attempt resumes via
    a `Range` request (`PluginDownloader.kt:163-164`).
  - **Cancel:** cancelled coroutine or a `false` `isActive` aborts and deletes the
    partial file (`PluginDownloader.kt:179-181, 198-200`).
  - **Deterministic artifact name:** `<sanitized-id>-<version>.apk`, so a re-download
    overwrites stale artifacts and production resolution works across restarts
    (`PluginDownloader.kt:218-224`).
- `HttpsPluginDownloadTransport` (`plugins/runtime/HttpsPluginDownloadTransport.kt`,
  178 lines) — the production transport: HTTPS, `Range`-resume support, and it verifies
  the TLS session's leaf certificate against the entry's `pinnedCertHash` before trusting
  any byte. Progress forwarded into the store's existing progress flow.
- `PinnedCertHash` (`plugins/runtime/PinnedCertHash.kt`) — base64(SHA-256) helper for
  the pinned cert hash; used by both the verifier and the transport.

## 2. Signature verification (the security-critical gate)

`ArtifactSignatureVerifier` (`plugins/runtime/ArtifactSignatureVerifier.kt`) runs
BEFORE any code is loaded and is RE-RUN on EVERY load. Two independent, both-mandatory
checks:

1. **SHA-256 of the artifact bytes == `PluginEntry.sha256`** — a single flipped byte
   fails (`ArtifactSignatureVerifier.kt:55-62`).
2. **The signing certificate hashes to the compile-time `PluginEntry.pinnedCertHash`**
   — a genuine artifact signed by a different key is rejected even with untouched bytes
   (`ArtifactSignatureVerifier.kt:63-75`).

Signature validation uses the JDK's own `java.util.jar.JarFile` verifier (`verify = true`,
`ArtifactSignatureVerifier.kt:89-105`): every non-`META-INF` entry is fully streamed so a
tampered signature block throws `SecurityException` mid-read, which maps to
`Result.Invalid` (`ArtifactSignatureVerifier.kt:106-108`). No bespoke crypto. Never throws.

`SignatureVerifiedPluginRuntime` (`plugins/runtime/SignatureVerifiedPluginRuntime.kt`):
- `verify` — runs the full gate (`:50-71`).
- `load` — refuses bundled entries, resolves the on-disk artifact, RE-verifies integrity
  on every load, and only then delegates to the loader (`:73-98`).
- `update`/`rollback` — honest Phase-24 stubs; the previous version is never discarded
  here (`:100-112`).

## 3. Loading

`RuntimePluginLoader` (`plugins/runtime/RuntimePluginLoader.kt`, 140 lines) materializes
the VERIFIED artifact through a plugin `ClassLoader` (production:
`services/AppClassLoaderFactory.kt` — `DexClassLoader` over the app-private artifact,
parented by the app classloader), instantiates the plugin through the existing
`NoteflowPlugin` interface via reflection, and injects a capability-aware `PluginContext`.
Compile-time plugins keep the Phase-10/11 registry API untouched.

## 4. Capability isolation (deny-by-default contract)

`PluginContext.kt` (`plugins/runtime/PluginContext.kt`):
- `FacadeWhitelist` — the capability → facade-call grant matrix (`:119-155`). A plugin
  is granted EXACTLY the calls its capability needs, as a pure function of its
  `PluginCapability` set.
- `CapabilityAwarePluginContext` (`:169-204`) — grants per the whitelist and delegates
  the actual operation to `FacadeHost` (`services/AppFacadeHost.kt`). The plugin NEVER
  receives direct `Context`, DB, `NoteRepository`, keystore, `EncryptionService` or
  decrypted-content handles.
- **TLS rule:** `httpGet` is only ever granted as the HTTPS variant; `httpsOnly == false`
  is refused, never downgraded (`:183-189`).
- `DefaultPluginContext` (`:70-89`) still denies everything (used by the standalone stub
  runtime).

## 5. Consent + enablement + persistence

- First download requires explicit user consent with clear wording. `NoteflowViewModel`
  exposes `pendingConsentPluginId` (a `StateFlow`); `PluginStoreDialog` renders a consent
  `AlertDialog` ("this plugin adds features from a third party; it is signature-verified")
  and calls `respondStoreConsent(grant)`.
- Downloaded plugins are **OFF by default**; enable/disable via the Phase-21 store
  (`PluginRegistry` + `SettingsManager plugin_enabled_<id>`).
- Persistence: `PluginArtifactStorage` (app-private files) + `SettingsPluginEntryStore`
  (installed entry: id/version/sha256/pinnedCertHash) via `DownloadablePluginInstaller`.
  `SignatureVerifiedPluginRuntime.load` re-verifies integrity against the persisted
  sha256/pin on every load.
- Delete wipes the downloaded artifact + persisted entry + all `plugins.<id>.*` settings;
  disable keeps data. (`PluginStoreController.delete`, `RemotePluginInstaller`, `NoteflowPlugin.deleteDownloadedAssets`.)

## 6. Store wiring

`PluginStoreDialog` now distinguishes **Bundled** vs **Remote (downloadable)** entries and
drives download/verify/install/delete/disable states from the unified `PluginEntry`
through `PluginStoreController` (consent-gated remote path) — built-ins stay "bundled".

## Tests (pure JVM)

`gradle testDebugUnitTest` — **421 tests green** (up from 380 at Phase 22).

| Test class | Tests | Covers |
|---|---|---|
| `ArtifactSignatureVerifierTest` | 8 | valid sign ✓; tampered bytes ✗ (SHA-256); sha mismatch ✗; wrong key ✗ (pinned cert); unsigned ✗; missing file ✗; corrupt ✗; content-derived re-verify |
| `PluginDownloaderTest` | 11 | consent required; HTTPS-only; bundled refused; size cap; free-space; transport failure cleans `.part`; cancel cleans partial; resume from `.part`; deterministic file name; outside-dir refused |
| `PluginContextWhitelistTest` | 8 | grant matrix per capability; HTTP-but-not-model; TLS refusal on `httpsOnly=false`; deny-by-default; denied never reaches host |
| `DownloadablePluginRuntimeTest` | 7 | download→verify→load→execute happy path; tampered rejected before load; wrong key rejected; re-verify on EVERY load; bundled refused; missing artifact; `update`/`rollback` honest stubs |
| `RemotePluginStoreDownloadTest` | 7 | consent-gated `NeedsConsent`; post-consent Installed + progress; registry OFF by default; delete wipes artifact+entry+registry; no-installer fails honestly; consent persisted |

The test artifact is a REAL signed JAR built in-test via the JDK `keytool` + `jarsigner`
CLIs (`TestDownloadablePlugin.kt`), so the verifier's accept/reject paths exercise actual
JAR signature machinery, not mocks.

## Base-APK size delta

Measured by building the baseline at the Phase-22 HEAD (`5f70805`) in a separate git
worktree and the Phase-23 tree in-place (same gradle cache, `assembleDebug`):

| Build | APK bytes |
|---|---|
| Baseline (Phase-22 HEAD) | 294,291,530 |
| Phase 23 | 294,324,298 |
| **Delta** | **+32,768 bytes (~32 KB)** |

The delta is the pure-JVM runtime + adapter classes only. No ML Kit barcode, native OCR,
or LLM was added to the base app. `gradle assembleDebug` succeeds.

## Pinned-cert hash handling

- `PluginEntry.pinnedCertHash` is populated from the compile-time catalog
  (`PluginStoreCatalog`) and `SettingsPluginEntryStore` on install — never user-editable.
- `ArtifactSignatureVerifier` compares the artifact's actual signing-cert hash against it
  on every verify (`ArtifactSignatureVerifier.kt:68-75`); `HttpsPluginDownloadTransport`
  also checks the TLS leaf cert against it during the transfer.
- Release builds get the same gate (no debug bypass — the verifier is pure JVM and
  unconditional).

## Capability surface (what downloadable plugins may do)

- `insertText`, `showResult`, `readSelection` — granted per capability.
- `httpGet(url, httpsOnly=true)` — HTTPS only, never a cleartext downgrade.
- `requestModelDownload(sizeBytes)` — host-owned, user-consent dialog (models never ride
  inside the artifact).
- Everything else resolves to `FacadeResult.Denied` with an honest reason.

## Deferred (later phases, not in this phase's scope)

- **User-approved updates / rollback** — `PluginRuntime.update`/`rollback` stay honest
  stubs; they land in **Phase 24**.
- **LLM out of the base APK** — moves to a downloadable plugin in **Phase 29**.
- **QR/barcode + camera OCR plugins** — heavy native features remain deferred, same
  downloadable path once catalog entries exist.

## Honest notes

- The Phase-22 skeleton's "real" `PluginRuntime` for the standalone stub runtime is
  unchanged; `SignatureVerifiedPluginRuntime` is what production registers through
  `PluginRuntimeRegistry` and what the store's download path uses.
- No DB schema change, no workflow edits, no new permissions, no `ClipboardGuard`
  bypass (Phase-23 changes touch no clipboard path).
