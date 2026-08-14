# Phase 24 — Dynamic plugin updates: latest versions with user approval

**Status:** DONE
**Date:** 2026-08-14
**Companion design doc:** `docs/plugin-architecture.md` (§ State machine / Update model)
**Phase 23:** `workspace/phase-23/REPORT.md` (download → pinned-cert + sha256 verify → load → consent)
**Phase 22:** `workspace/phase-22/REPORT.md` (skeleton — `PluginEntry`/`PluginVersion`/`PluginRuntime` seams)

## What Phase 24 delivers

The Phase-22 `PluginRuntime.update`/`rollback` seams are now REAL. Users get the
LATEST verified plugin versions through the Phase-21/23 store, and **nothing is
ever auto-installed**: every update requires explicit per-update approval, is
signature + sha256 **re-verified** before it replaces anything, keeps the
previous version intact until the new one is fully verified, and **rolls back
automatically** on ANY failure. There is no auto-update toggle anywhere.

```
approve (mandatory dialog) → download new artifact (HTTPS) → re-verify
pinned cert + sha256 → load smoke-test → keep previous version files →
atomic swap (new version becomes the active persisted entry + artifact)
```

## 1. Hosted version manifest (pure-JVM parser)

- `plugins/runtime/HostedPluginManifest.kt`
  - `HostedPluginVersion` — one offer: `id`, `version`, `downloadUrl`, `sha256`,
    `pinnedCertHash`, optional `installSizeBytes` / `updateNotes`, `updateChannel`
    (`:29-57`). Cross-field `validationErrors()` (`:41-56`): HTTPS-only url,
    non-blank sha256/pinnedCertHash/id, parseable non-negative version, non-blank
    channel, non-negative size.
  - `PluginManifestParser` (`:119-181`) — strict Gson parser. **A single malformed/
    tampered offer invalidates the WHOLE manifest** (`:161-163`); an empty
    `plugins` list is valid ("no updates"); duplicate ids refused (`:150-153`).
  - Wire format documents in the KDoc (`:88-105`).
  - `DEFAULT_PLUGIN_MANIFEST_URL = "https://plugin-updates.inkflow.app/v1/manifest.json"`
    (`:190-191`) — HTTPS only, keyless (the per-artifact pinned cert + sha256
    verification is the trust anchor, so the manifest host never needs to be an
    allow-list).
- `plugins/runtime/PluginManifestFetcher.kt`
  - `PluginManifestFetcher` — refuses any non-`https://` URL before the transport
    runs (`:56-60`); keyless + user-initiated only.
  - `HttpsManifestTransport` (`:83-145`) — production fetch: standard TLS chain
    validation, 2xx only, 256 KB body cap (`:150`), 20s/40s timeouts, never logs
    manifest contents. The manifest is NOT pinned to a single cert — nothing in it
    is trusted at face value; everything that executes is verified per artifact.

## 2. Update check (never a downgrade, never a no-op)

`plugins/runtime/PluginUpdateChecker.kt`
- `check(installed, manifest)` (`:67-86`) yields `PluginUpdateInfo` ONLY when:
  - the plugin is **downloadable** (`!isBundled`, `:70`) — built-ins "managed by
    app update" are excluded,
  - the offer's `updateChannel` equals the installed entry's channel (`:71`) —
    a "stable" install never sees "beta" offers,
  - `offer.version.isNewerThan(entry.version)` (`:72`) — equal and older offers
    are never reported (no no-op, no downgrade). Ordered deterministically by id.
- `toTargetEntry(installed)` (`:30-38`) preserves identity (id/name/description/
  capabilities/category) while carrying the new version + URL + digests + size +
  channel + `source = REMOTE`.

## 3. User approval (MANDATORY, defense in depth)

The approval gate is enforced in **both** the store controller and the runtime
engine — an update can never be applied silently even if one layer is bypassed.

- Store: `PluginStoreController.update` returns `UpdateOutcome.NeedsApproval`
  (`plugins/store/PluginStoreController.kt:283-286`) unless `userApproved == true`,
  and never touches the coordinator without it. It re-fetches a **fresh manifest**
  at install time and builds the target via `PluginUpdateChecker` so the offer is
  current (`:296-310`).
- Engine: `PluginUpdateEngine.update` refuses with "was not approved" unless
  `userApproved == true` (`plugins/runtime/PluginUpdateEngine.kt:81-85`).
- No-downgrade: `!target.version.isNewerThan(entry.version)` is refused
  (`:97-101`) — a manifest/offer can never roll a plugin back.
- UI: `PluginStoreDialog` renders the per-update **"Approve & install"** dialog —
  current → new version, `updateNotes`, download size, and a consent explanation
  with the rollback guarantee, dismissible (Cancel keeps the current version).
  `NoteflowViewModel.requestPluginUpdate` (`NoteflowViewModel.kt:442-445`) opens
  the dialog; `respondUpdateApproval(grant)` (`:448-452`) runs `storePluginUpdate`
  only on explicit approval. No auto-update prompt exists.

## 4. Verified update install (previous kept until the new one verifies)

`plugins/runtime/PluginUpdateEngine.kt` — PURE JVM orchestrator, fully unit-tested
with fake transports + real signed artifacts.

1. **Record the rollback root FIRST** — `updateStore.savePrevious(entry)` runs
   before any byte moves (`:104`), so a failure or mid-update process death always
   leaves a recoverable previous version.
2. **Download** the new artifact via `PluginDownloader` (HTTPS-only, size/free-
   space guards, `.part` hygiene) — already approved, `userConsented = true`
   (`:108-123`).
3. **Re-verify** the downloaded bytes with `ArtifactSignatureVerifier` (sha256 +
   pinned signing cert) — the Phase-23 gate is RE-RUN; an offer is never trusted
   (`:128-138`).
4. **Load smoke-test** — `RuntimePluginLoader` materializes the artifact so a
   signed-but-broken plugin is caught before it becomes active (`:143-161`).
5. **Atomic swap** — `entryStore.save(target)` persists the new active entry
   (`:166-177`); `cleanupStaleArtifacts` removes artifacts that are neither the
   new version nor the recorded previous (`:263-272`). The previous version's
   files stay on disk as the rollback source.
6. **ANY failure** (download, hash, signature, smoke-test, persist) deletes the
   new artifact and keeps the previous version active with a clear message:
   "the previous verified version vX is still active (nothing was replaced)"
   (`failedUpdateKeepsPrevious`, `:243-253`).

`SignatureVerifiedPluginRuntime.update` forwards through its injected engine
(`plugins/runtime/SignatureVerifiedPluginRuntime.kt:108-117`); the read-only
fallback (no engine wired) answers an honest failure (`:113-115`).

## 5. Rollback path (`PluginRuntime.rollback`)

`PluginUpdateEngine.rollback` (`:191-241`):
- Restores the recorded `previousFor(id)` (`PluginUpdateStore`);
- **Re-verifies** the previous artifact and **re-runs the load smoke-test**
  before restoring (`:207-225`) — a tampered/bit-rotted previous artifact is
  refused ("no longer passes verification");
- Restores it as the active persisted entry, deletes the superseded new artifact,
  clears the previous record (`:226-237`);
- A `previous.version >= active.version` (failed update left the previous active)
  is an honest no-op success (`:196-201`);
- With no record it fails "Nothing to roll back" (`:192-195`).

Rollback-to-older is the **sanctioned exception** to the no-downgrade rule:
it restores a previously-verified version, never a manifest/offer downgrade.

## 6. Store UI wiring

`ui/components/PluginStoreDialog.kt` (Phase 21 dialog, extended):
- **"Check for updates"** button in the header → `NoteflowViewModel.checkPluginUpdates`
  → `PluginStoreController.checkForUpdates` (`:249-263`) fetches the keyless HTTPS
  manifest and populates per-plugin offers; general results ("N update(s) available…",
  "All installed plugins are up to date.", fetch failures) land in the
  `storeGeneralMessage` row with a Dismiss action.
- Downloaded remote rows show **"Update available (vX → vY)"** + an **Update**
  button when the manifest offers a strictly-newer version; a real
  `LinearProgressIndicator` + "Downloading + verifying update…" while the verified
  install runs; failure messages surface the "rolled back / previous still active"
  wording.
- **Bundled (compile-time) plugins** show **"Managed by app update (updated with
  the app release)"** (`:248-254`) and their Update path is refused by the
  controller (`PluginStoreController.kt:289-294`) — built-ins update with the app,
  not this mechanism.
- `NoteflowViewModel` exposes `storeUpdates` / `updateBusy` / `updateProgress` /
  `pendingUpdatePluginId` / `storeGeneralMessage` (`NoteflowViewModel.kt:249-262`)
  and the actions `checkPluginUpdates` / `requestPluginUpdate` /
  `respondUpdateApproval` / `dismissStoreGeneralMessage` (`:420-452, 461-487`).

## 7. Wiring / persistence / garden paths

- `services/DownloadablePluginUpdater.kt` — production `PluginUpdateCoordinator`:
  `fetchManifest` via `PluginManifestFetcher(HttpsManifestTransport())`, `runUpdate`
  forwards an already-approved update to `runtime.update(…, onProgress)`, re-loads
  the swapped artifact and joins it in-session via `PluginRegistry.replaceRemotePlugin`
  (`:45-64`); any engine failure maps to `UpdateOutcome.RolledBack` (`:67-70`).
- `plugins/PluginRegistry.replaceRemotePlugin` (`:299-314`) swaps the active
  instance of the same id after a verified update without touching install state;
  lifecycle hooks of the new instance fire at the next `onProcessStart` (KDoc `:288-297`).
- `RemotePluginInstaller.activeEntryFor` (`plugins/store/RemotePluginInstaller.kt:26-37`,
  default null) + `DownloadablePluginInstaller.activeEntryFor = entryStore.find`
  (`services/DownloadablePluginInstaller.kt:60-62`) — the SECOND update compares
  against the ACTIVE persisted version, never the stale bundled catalog copy
  (`PluginStoreController.update` `:295-298`), so no-downgrade + previous-recording
  stay correct after an update.
- `services/SettingsPluginUpdateStore.kt` — rollback root persisted through
  `SettingsManager` under `plugin_update_previous_<id>` (`SettingsManager.kt:296-303`);
  store Delete wipes it via `wipePluginState` (`:255-268`) so a deleted plugin
  leaves no update residue.
- `NoteflowViewModel` init wires the production engine into the runtime and the
  coordinator into the controller (`NoteflowViewModel.kt:136-188`).

## Tests — pure JVM (no emulator, no network)

`gradle testDebugUnitTest` — **464 tests green (baseline 421 → +43).**

| Test class | Tests | Covers |
|---|---|---|
| `PluginManifestParserTest` | 13 | valid parse; optional-field defaults; empty list valid; malformed JSON refused; missing `plugins` refused; bad version / non-HTTPS / missing sha256 / missing pinnedCertHash / blank id / negative size / duplicate id **invalidate the whole manifest**; `offerFor` |
| `PluginUpdateCheckerTest` | 9 | strictly-newer offered; equal no-op never offered; older (downgrade) never offered; channel mismatch never offered; bundled excluded even when manifest lists it; unknown-offer ignored; deterministic ordering; empty manifest; `toTargetEntry` identity + new digests |
| `PluginUpdateEngineTest` | 10 | approved happy path (download→re-verify→smoke-test→swap→previous+artifact kept, progress 0→1); no-approval refused + nothing moves; downgrade/no-op refused; download failure keeps previous; SHA-256 mismatch never applied; signed-but-broken smoke-test failure; rollback restores previous verified version; no-record rollback fails honestly; rollback refuses tampered previous artifact; failed-update → rollback no-op |
| `PluginUpdateStoreFlowTest` | 11 | checkForUpdates UpdatesAvailable/UpToDate/fetch-failure; bundled never checked; NeedsApproval w/o approval (coordinator untouched); approved update builds fresh-manifest target + swaps; offers-turned-stale fails; bundled update refused "managed by the app update"; coordinator rollback propagates; second update vs ACTIVE persisted entry; no-coordinator failures |

The engine tests use **REAL signed artifacts** (JDK `keytool` + `jarsigner`,
same builder as Phase 23) with a fake `DownloadTransport`, so the trust chain —
right bytestream, right signing key, right class — is exercised, not mocked.

## End-to-end evidence

`PluginUpdateEngineTest` covers the full DoD arc in pure JVM:
1. **UPDATE_AVAILABLE** — `PluginUpdateCheckerTest` + `PluginUpdateStoreFlowTest`
   (offer only when manifest version is strictly newer, same channel, remote-only).
2. **Approval dialog** — store answers `NeedsApproval` without approval and the
   engine refuses a non-approved update; the approval dialog itself is the
   `ui/components/PluginStoreDialog.kt` "Approve & install" composable.
3. **Approved install verifies + swaps** — `PluginUpdateEngineTest`
   "approved update downloads re-verifies smoke-tests swaps and keeps the previous
   version": active entry becomes v2.0.0 with the new digests, artifact on disk,
   previous recorded.
4. **Tampered new artifact rolls back** — "a hash mismatch on the downloaded
   artifact is never applied" + "a signed-but-broken artifact fails its load
   smoke-test and is refused": the bad artifact is deleted, the v1.0.0 entry and
   files stay active; `rollback` then re-verifies the recorded previous. A user
   landing in a failed state sees "Update of '…' to v2.0.0 did not complete: … The
   previous verified version v1.0.0 is still active (nothing was replaced)".

## Verification commands

- `gradle testDebugUnitTest` — **464 tests, 0 failures.**
- `gradle assembleDebug` — **BUILD SUCCESSFUL.**

## Constraints honoured

- **No auto-update toggle**; every update is manual, per-plugin, and explicitly
  approved ("Approve & install").
- **HTTPS only** for manifest and artifact downloads; TLS enforced in both
  transports; a cleartext URL is refused before a connection opens.
- **Never downgrade**; never replace a verified version with a tampered one
  (re-verification on every download, rollback refuses anything unverified).
- Never logs keys, passwords, decrypted content or artifact contents (all logging
  is ids/names + exception class names, via `PluginLogger`).
- No DB schema change; no `.github/workflows/` edits; no new permissions; no
  `ClipboardGuard` bypass.
- Update logic lives in `plugins/runtime/` + `plugins/store/`; base-APK cost is a
  few pure-JVM classes (no heavy/native dependency added).