# Phase 22 — Hybrid plugin architecture: design + skeleton (2026-08-14) [DONE]

Goal: settle the hybrid model (compile-time lightweight plugins + downloadable,
signature-verified heavy plugins) ONCE, and ship compilable seams that Phases
23–26 fill in without redesign. This phase adds NO download/verify/load logic and
NO updates — only the architecture doc + the honest skeleton.

---

## What was shipped

### Design
- `docs/plugin-architecture.md` — the decision-focused architecture doc:
  module layout + dependency direction (base app never depends on downloadable
  modules; the runtime loads them via `DexClassLoader`), the unified
  `PluginEntry` model, the `PluginContext` capability-facade contract with the
  per-capability whitelist matrix, the integrated lifecycle + update state
  machines, the semver/channel/rollback update model, and the security model
  (verify before ANY load, re-verify on EVERY load, no direct data handles).
- `docs/PLUGINS.md` — design rules updated for the hybrid model (unified
  `PluginEntry` catalog, bundled vs remote buckets, verify-before-load,
  Delete-wipes-entry-blob), plus a Phase-22 note in the Plugin Store section and
  the new `runtime/` package row in the layout table.

### Code seams (all compiled, all tested)
| File | What it is |
|---|---|
| `plugins/runtime/PluginEntry.kt` | Unified catalog entry (bundled + remote) + `PluginEntrySource` + invariant `validationErrors()` |
| `plugins/runtime/PluginVersion.kt` | Semver compare + `bump(Major/Minor/Patch)` + lossless `SemanticVersion` interop |
| `plugins/runtime/PluginEntryStore.kt` | `PluginEntryStore` interface + `InMemoryPluginEntryStore` + Gson `PluginEntryCodec` (key-stable wire format) |
| `plugins/runtime/PluginContext.kt` | `FacadeResult` (Granted/Denied/Failed) + `PluginContext` (5 narrow calls) + `DefaultPluginContext` (deny-by-default) + `PluginContextFactory` |
| `plugins/runtime/PluginRuntime.kt` | `PluginRuntime` (verify/load/update/rollback) + `PluginArtifact/PluginVerification/PluginUpdateResult/PluginRollbackResult` + `RuntimeOutcome` + `NotYetImplementedPluginRuntime` + `PluginRuntimeRegistry` seam |
| `services/SettingsPluginEntryStore.kt` | Production `PluginEntryStore` over `SettingsManager` (`plugin_entry_<id>` JSON blobs; Delete wipes them) |
| `plugins/store/PluginStoreCatalog.kt` | `PluginStoreEntry` now WRAPS a `PluginEntry` (accessors delegate, existing callers untouched); catalog merges persisted remote entries |
| `ui/components/PluginStoreDialog.kt` | Store rows show version + **bundled/remote** marker |
| `ui/viewmodel/NoteflowViewModel.kt` | Wires `PluginEntryStore` into the catalog + exposes `pluginRuntime` via `PluginRuntimeRegistry.current()` |
| `services/SettingsManager.kt` | `plugin_entry_<id>` JSON accessors + `allPluginEntryIds()` + Delete wipes the entry blob |
| `PluginCapability.kt` / `PluginManifest.kt` | Added lazy `byKey()`/`ALL` lookups (key-stable codec resolution) |

### Tests (26 new, pure JVM — full suite: 379 tests green)
- `PluginVersionTest` (7) — parse/order/isNewerThan/bump/interop.
- `PluginEntryStoreTest` (6) — codec round-trips (remote + bundled), malformed
  reject, store CRUD, entry-invariant validation, catalog merges a persisted
  remote entry alongside bundled definitions.
- `PluginContextFacadeTest` (7) — every facade call is `Denied` with an honest
  reason; context scoped to its plugin id; factory default is deny-by-default.
- `PluginRuntimeSeamTest` (6) — verify/load report `NotYetImplemented(23)`,
  update/rollback `NotYetImplemented(24)`, the stub NEVER fabricates success,
  and `PluginRuntimeRegistry` can be swapped by a later phase.

### Verification gates
- `gradle assembleDebug` — **SUCCESS** (clean rebuild, APK produced).
- `gradle testDebugUnitTest` — **SUCCESS** (379 tests, incl. the 26 new).

---

## Design decisions (locked in this phase)

1. **One entry type for both buckets.** `PluginEntry` is the single catalog
   vocabulary; `source` = BUNDLED or REMOTE with the invariant
   `REMOTE ⇔ https downloadUrl + sha256 + pinnedCertHash`. Store, runtime and
   updates all read it.
2. **Bundled entries are derivable; remote entries are persisted.** The store
   catalog rebuilds bundled entries from `PluginRegistry.compiledPlugins` each
   time; `PluginEntryStore` persists only remote entries (so a downloaded plugin
   survives restarts) and Delete wipes the blob.
3. **Deny-by-default facade.** Downloadable code gets only `PluginContext`
   (`insertText / showResult / httpGet(httpsOnly) / readSelection /
   requestModelDownload`), returning `FacadeResult.Denied` for everything until
   Phase 23 grants per-capability calls. No direct Context/DB/keystore/
   `EncryptionService`/decrypted-content handles, ever.
4. **Honest stubs, not fakes.** `PluginRuntime` operations return
   `RuntimeOutcome.NotYetImplemented(phase, message)` — verify/load → phase 23,
   update/rollback → phase 24. Nothing pretends to work.
5. **`PluginRuntimeRegistry` is the swap point.** Phase 23/24 `register()` their
   real implementation; store/registry/UI need no further changes.
6. **Store UI labels buckets.** Every row shows `v<version>` + bundled/remote.
7. **Version semantics reuse the framework's strict rule.** `PluginVersion`
   matches `SemanticVersion`'s exactly-three-components rule and interops
   losslessly; `bump` only derives expected versions (shipped versions always
   come from a manifest).

## What Phases 23–26 fill in (seams already exist)

- **23 — downloadable runtime:** real `verify()` (pinned-cert-hash + sha256,
  tamper rejection before any load), real `load()` (`DexClassLoader`
  materialization + capability-aware `PluginContextFactory` granting the whitelist
  matrix), the `plugins/ocr`/`plugins/llm` Gradle modules, HTTPS download with
  pinned session. Registered via `PluginRuntimeRegistry`.
- **24 — user-approved updates:** hosted per-channel manifest seeded into
  `PluginEntryStore`; `UPDATE_AVAILABLE → APPROVING → DOWNLOADING → VERIFYING →
  READY`, keep-previous-until-verified, `rollback()` restores the last-good
  version; re-verify on every update.
- **25 — ink→shape canvas plugin:** a plugin served through the same catalog,
  using `PluginContext` calls (e.g. `insertText`) for its results.
- **26 — lightweight ecosystem:** more bundled plugins; they flow through the same
  `PluginEntry` catalog (bundled, no download).

## Constraints honored
- No download/verify/load implementation (that is Phase 23), no updates (Phase 24).
- No DB schema change. No `.github/workflows/` edits. No new permissions.
- Nothing logged that could carry keys, passwords or decrypted content.
- Plugin boundary kept clean: downloadable code can only ever see `PluginContext`.
