# InkFlow hybrid plugin architecture (Phase 22)

Status: **approved 2026-08-14** (commit `9292978`) · Skeleton shipped in Phase 22 ·
downloadable runtime (23), updates (24), ink→shape (25) and lightweight ecosystem (26)
build on this document and the code seams it specifies.

## 1. The problem this architecture settles once

Two hard facts about InkFlow:

1. **Base-APK size is a first-class constraint.** Heavy/native features — camera
   OCR/QR, large ML engines, the local LLM — must NOT be baked into the base APK.
2. **Nobody wants features they will never use.** Forcing a 100 MB OCR engine or a
   400 MB LLM on every user, in every install, is wrong even when the size were
   affordable.

The **hybrid model** is the answer:

- **Lightweight features** (pure-JVM cores, small keyless-HTTP clients — a few KB)
  ship **compile-time**, registered in `PluginRegistry.defaultPlugins()`, exactly as
  Phases 10–16 already do. They add no download step, no verification surface, no
  runtime risk.
- **Heavy/native features** (camera OCR/QR, MediaPipe `tasks-genai` LLM, large ML
  engines) ship as **downloadable, signature-verified plugins**: fetched over HTTPS
  on explicit user consent, verified (pinned cert + SHA-256) BEFORE any load, and
  loaded via `DexClassLoader` inside a capability facade that can never reach the
  database, keystore, `EncryptionService` or decrypted content directly.

The decision of *which* bucket a feature lands in is made once here:

| Bucket | Rule | Examples today / planned |
|--------|------|--------------------------|
| Compile-time (bundled) | Pure JVM core or ≤ a few KB of keyless HTTP glue; no large native libs | ROT13, CaseChange, TextTools, LangDetect, WebCapture, WebSearch, Export, ClipShare, Dictation, ReadAloud, Translation, ScreenshotNote |
| Downloadable (remote, verified) | Large ML/native dependency, or a runtime the base app should not carry | Phase 23+ camera OCR/QR engine, Phase 29+ the LLM moved OUT of the base APK |

The rest of this document specifies the skeleton that makes both buckets *one
catalog, one store, one lifecycle* — so a later phase never has to redesign, only
fill in implementations.

## 2. Module layout & dependency direction

```
┌─────────────────────────── base app (com.authorss81.noteflow) ───────────────┐
│                                                                               │
│  plugins/            ── compile-time plugin framework (Phases 10–16, 21)      │
│    PluginCapability      sealed extension points                               │
│    PluginManifest        manifest + SemanticVersion + validator                │
│    NoteflowPlugin        plugin interface + per-capability serving interfaces │
│    PluginRegistry        compile-time registry, lifecycle, arbitration         │
│    PluginManager         guarded capability routing                            │
│    store/                PluginStoreCatalog/Controller/InstallStore (Phase 21) │
│    <feature>/            bundled plugins (ocr/, websearch/, assistant/, …)     │
│                                                                               │
│  plugins/runtime/     ── Phase 22 SEAMS (this phase ships these)              │
│    PluginEntry           unified catalog-entry model (bundled + remote)        │
│    PluginVersion         semver compare + bump                                  │
│    PluginEntryStore      persistence seam (codec + in-memory impl)             │
│    PluginContext         capability facade (deny-by-default)                   │
│    PluginRuntime         verify / load / update / rollback seams (stubbed)     │
│    PluginRuntimeRegistry seam registration (Phase 23/24 swap the stub)         │
│                                                                               │
│  services/            SettingsManager + thin production adapters               │
│    SettingsPluginEntryStore   PluginEntryStore over SharedPreferences          │
│                                                                               │
│  ui/                  PluginStoreDialog (bundled/remote marker) + ViewModel    │
│                                                                               │
│  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V  V    │
│  (the base app NEVER depends on downloadable modules — see arrows)             │
└────────────────────────────────────────────────────────────────────────────────┘

    future downloadable plugin modules (separate Gradle modules, Phase 23+)
    ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
    │ plugins/ocr │   │ plugins/llm │   │  plugins/qr │   … each is packaged as an
    └─────────────┘   └─────────────┘   └─────────────┘   APK/DEX artifact, signed,
      │    │    │         │    │    │         │    │       sha256 + cert pinned
      │    │    │         │    │    │         │    │       in its PluginEntry,
      │    │    │         │    │    │         │    │       downloaded + verified.
      ▼    ▼    ▼         ▼    ▼    ▼         ▼    ▼
      MUST only use: plugins/runtime types (PluginEntry, PluginContext) that are
      compiled INTO the downloadable module; MUST NOT import base-app packages.
```

**Dependency direction (the non-negotiable):**

- The **base app** depends on the `plugins/runtime` seams (its own package).
- The base app **NEVER depends on a downloadable module** — it knows remote plugins
  only as `PluginEntry` metadata (id, version, digests, URL) until the runtime
  turns a verified artifact into a `NoteflowPlugin` instance.
- A **downloadable module** depends on the `plugins/runtime` types (its contract)
  and the plugin SDK (`docs/PLUGIN_SDK.md`). It must compile against a published
  jar of `NoteflowPlugin`/`PluginContext`/serving interfaces and MUST NOT import
  `data/`, `services/`, `ui/` or anything that would give it a direct handle to
  secrets or the vault.
- Loading happens **only** through `PluginRuntime.load(entry)`, which Phase 23
  implements with `DexClassLoader` after verification. There is no other path by
  which downloadable code enters the process.

## 3. Unified catalog-entry model — `PluginEntry`

One type covers BOTH buckets. `PluginEntry` (`plugins/runtime/PluginEntry.kt`) is
the seam that Phase-21 store, Phase-23 runtime and Phase-24 updates all read.

```kotlin
data class PluginEntry(
    val id: String,                      // globally-unique; matches manifest id for built-ins
    val name: String,                    // user-facing
    val description: String,             // store listing
    val version: PluginVersion,          // semver (compare + bump)
    val capabilities: Set<PluginCapability>,
    val category: String,                // "Vision", "AI", "Text", …
    val permissions: Set<PluginPermission>,  // declared for VISIBILITY only
    val downloadUrl: String? = null,     // null ⇔ bundled; must be https:// for remote
    val installSizeBytes: Long? = null,  // on-device cost after install (models/native)
    val updateChannel: String = "stable",// per-plugin update channel (Phase 24)
    val sha256: String? = null,          // hex SHA-256 of the artifact; remote only
    val pinnedCertHash: String? = null,  // pinned TLS cert hash of the download host; remote only
    val source: PluginEntrySource        // BUNDLED or REMOTE
)
```

Invariants (enforced by `PluginEntry.validationErrors()` + tested):

- `source == REMOTE` ⇔ `downloadUrl` (https), `sha256` and `pinnedCertHash` are all
  non-blank.
- `source == BUNDLED` ⇒ all three are null (nothing to fetch or verify).
- `version`, `category`, `updateChannel`, `capabilities` are always required;
  identity fields never blank.

`PluginStoreEntry` (the store's row) now **wraps** a `PluginEntry` (delegating
`pluginId/name/description/version/capabilities/category/permissions/installSizeBytes/…`)
plus the store-only `optional` flag. Existing store UI/tests kept compiling unchanged;
the UI now renders `sourceLabel` — **"bundled"** vs **"remote"** — and the version
(`v1.0.0`) on every row.

## 4. Persistence — `PluginEntryStore`

- Bundled entries are **derivable facts** of the APK: `PluginStoreCatalog` builds
  them fresh from `PluginRegistry.compiledPlugins` every time. They are never
  persisted.
- Remote entries are fetched from the Phase-24 hosted manifest and **persisted** so
  a downloaded-and-verified plugin survives restarts with its downloadUrl /
  sha256 / pinnedCertHash / updateChannel intact.

`PluginEntryStore` (`plugins/runtime/PluginEntryStore.kt`) is a tiny interface —
`save / find / all / remove` — with:
- `InMemoryPluginEntryStore` (pure JVM, unit-tested),
- `PluginEntryCodec` (pure JVM, Gson) that stores capability/permission **keys**
  (not objects) so the wire format is stable across builds; unknown keys are
  dropped on decode (forward compatible),
- `SettingsPluginEntryStore` (production) persisting one JSON blob per remote plugin
  under `plugin_entry_<id>` in SharedPreferences; the store's **Delete** wipes it via
  `SettingsManager.wipePluginState`.

## 5. Capability facade contract — `PluginContext`

Downloadable plugin code NEVER receives `Context`, DB, `NoteRepository`,
AndroidKeyStore, `EncryptionService` or decrypted-content handles. It receives one
`PluginContext` scoped to its own `PluginEntry` (`plugins/runtime/PluginContext.kt`)
with five narrow calls:

| Facade call | Purpose |
|-------------|---------|
| `insertText(text)` | Insert text into the open note at the cursor |
| `showResult(title, body)` | Show a typed result in the app's own UI |
| `httpGet(url, httpsOnly)` | HTTP(S) GET; `httpsOnly=true` refuses non-TLS (never downgrades) |
| `readSelection()` | Read the current selection in the open note |
| `requestModelDownload(sizeBytes)` | Ask the host to download a model/asset into app-private files (host shows the consent dialog) |

Every call returns a typed `FacadeResult<T>` (`Granted` / `Denied(reason)` /
`Failed(message)`) — the facade never throws at plugin code.

**Deny-by-default (Phase 22):** `DefaultPluginContext` returns `FacadeResult.Denied`
for every call with an honest reason — no downloadable plugin is permitted anything
until Phase 23 wires the whitelist. `PluginContextFactory.DEFAULT` is the current
factory; Phase 23 swaps in a capability-aware one without touching the rest of the
runtime.

**Capability whitelist matrix** (Phase 23 fills in `Granted`; the skeleton is Denied):

| Facade call | `TextTransform` | `OCR` | `WebSearch` | `WebCapture` | `Assistant` | `Export` |
|---|---|---|---|---|---|---|
| `insertText` | ✅ result-insert | ✅ | ✅ link-insert | ✅ note-body | ✅ answer-insert | ❌ |
| `showResult` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `httpGet(httpsOnly=true)` | ❌ | ✅ model-download | ✅ | ✅ | ✅ model-download | ❌ |
| `readSelection` | ✅ | ✅ image-context | ✅ | ✅ | ✅ | ✅ |
| `requestModelDownload` | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ |

Rule: a plugin is granted exactly the calls its **capability** needs — never
"because it is trusted". No call reaches the vault.

## 6. State machine

Per-entry lifecycle — Phase 22 integrates the remote flow **above** the existing
Phase-21 install lifecycle (compile-time registry derived states unchanged):

```
                  (Phase 23 runtime fills the download/verify/load steps)
  NOT_DOWNLOADED ──user taps Download──▶ VERIFYING ──pinned-cert + sha256 ok──▶ VERIFIED
        ▲                                      │ (tamper/mismatch → FAILED,      │
        │                                      │  nothing loaded, user told)     │
        │                                      ▼                                ▼
      DELETE(wiped) ◀────────────────────────────────────────── REGISTERED (off)
        │ (assets+entry+opt-in+settings wiped)        │ user enables
        │                                              ▼
        │                                          ENABLED ──facade granted──▶ AVAILABLE
        │                                              ▲        │
        │                                              │        ▼
        │                                          DISABLED (kept, re-enableable)
        └──────────────────────────────────────────────────────────┘
```

Mapping to the existing registry: `REGISTERED` / `ENABLED` / `AVAILABLE` /
`UNAVAILABLE` / `DISABLED` / `REJECTED` remain exactly as Phase 21 derives them.
The **remote-only** states are owned by the runtime/store, not the registry:

| State | Meaning | Owner |
|-------|---------|-------|
| `NOT_DOWNLOADED` | entry exists in catalog, nothing on disk | store |
| `VERIFYING` | artifact downloaded, pinned-cert + sha256 check running | runtime (P23) |
| `VERIFIED` | integrity proven, not yet loaded | runtime (P23) |
| `REGISTERED(off)` | available, user never enabled (reuses Phase-21 derived state) | registry |
| `ENABLED` → `AVAILABLE` | opted in + serving (reuses Phase-21 derived states) | registry |
| `DISABLED(kept)` | off, data kept (reuses Phase-21) | registry |
| `DELETE(wiped)` | gone: assets, entry blob, opt-in, settings (Phase-21 Delete) | store |

**Update states** (Phase 24 wires the transitions):

```
UPDATE_AVAILABLE ──user approves──▶ APPROVING ──▶ DOWNLOADING ──▶ VERIFYING ──▶ READY
     │                                                                   │
     └──(new version tampered / failed verify)──▶ ROLLBACK_NEEDED ◀──────┘
            (previous verified version is still in place — nothing was replaced)
```

The cardinal rule for updates: **the previous version is kept until the new one is
verified** (`PluginRuntime.update` must not discard it; `rollback` restores it).
Phase 22 ships the seam (`update`, `rollback`) as honest stubs.

## 7. Versioning + update model

- **Semver.** `PluginVersion` (`plugins/runtime/PluginVersion.kt`) is the catalog
  version type: strict `Major.Minor.Patch` parse, ordering, `isNewerThan` and
  `bump(Major/Minor/Patch)`. It interops losslessly with the framework's
  `SemanticVersion` (used by manifests). `bump` only *derives* expected versions for
  sanity checks — shipped versions always come from a manifest, never a guess.
- **Per-plugin update channel.** `PluginEntry.updateChannel` ("stable" default;
  "beta"/"alpha" as needed). Phase 24's hosted manifest carries per-channel streams;
  the app only offers updates from the channel the user selected for that plugin.
- **Discovery.** A Phase-24 hosted manifest lists the current `PluginEntry` (with
  digests + pinned certs) per plugin per channel; the app compares
  `manifestVersion.isNewerThan(installedVersion)` to mark `UPDATE_AVAILABLE`.
- **Signature + sha256 re-verify on update.** Every update is a fresh download that
  must pass the same pinned-cert + sha256 gate as the original install. An update
  that fails verification never replaces the running plugin.
- **Rollback path.** Previous verified artifact retained on disk until the new one
  is verified; `PluginRuntime.rollback(entry)` restores it. Phase 24 wires it.

## 8. Security model

- **Verify before ANY load.** `PluginRuntime.verify(artifact)` checks sha256 and
  the pinned certificate hash; any mismatch is a hard `Failed` — a tampered artifact
  is never loaded, never partially executed.
- **Integrity re-check on EVERY load.** `PluginRuntime.load(entry)` re-verifies even
  a previously-verified artifact (bit-rot / on-disk tampering is caught at load,
  not assumed away).
- **No direct data handles.** The only surface a downloaded plugin sees is
  `PluginContext`; it cannot name a table, a key, `EncryptionService`, or read
  decrypted content. `permissions` declared in an entry are *displayed*, never
  auto-granted — the facade is the grant.
- **HTTPS only, pinned.** Remote `downloadUrl` must be `https://` (validated by
  `PluginEntry.validationErrors`); the pinned cert hash is checked against the TLS
  session before any bytes are trusted.
- **Never log secrets.** Runtime/seam code logs ids, names and exception class names
  only — never keys, passwords, or decrypted content (same rule as the framework).
- **Delete = gone.** Store Delete wipes the artifact assets, the persisted entry
  blob, opt-in, ever-enabled history and every `plugins.<id>.*` setting
  (`SettingsManager.wipePluginState`).
- Ties into `docs/PLUGINS.md` (design rules) and `docs/PLUGIN_SDK.md` (authoring
  contract for downloadable modules).

## 9. What this phase ships vs. what later phases fill in

| Seam | Phase 22 (this phase) | Later phase |
|------|-----------------------|-------------|
| `PluginEntry` + invariants | ✅ shipped + tested | — |
| `PluginVersion` | ✅ shipped + tested | — |
| `PluginEntryStore` (+codec) | ✅ shipped + tested | P24 seeds remote entries from hosted manifest |
| `PluginContext` facade | ✅ shipped, **deny-by-default** + tested | P23 wires the whitelist matrix; P25/26 plugins use it |
| `PluginRuntime.verify` | 🔵 stub → `NotYetImplemented(23)` | P23 real pinned-cert + sha256 verification |
| `PluginRuntime.load` | 🔵 stub → `NotYetImplemented(23)` | P23 `DexClassLoader` materialization + facade wiring |
| `PluginRuntime.update/rollback` | 🔵 stubs → `NotYetImplemented(24)` | P24 user-approved updates + rollback |
| Downloadable modules (`plugins/ocr`, `plugins/llm`) | 📐 designed (this doc) | P23+ built as separate Gradle modules |
| Camera OCR/QR, LLM out of base APK | 📐 rule fixed | P23, P29 |
| Ink→shape canvas plugin | 📐 will use `PluginContext.insertText` + this catalog | P25 |

The seams are wired into the app already: `NoteflowViewModel` exposes
`pluginRuntime` (via `PluginRuntimeRegistry.current()`) and `PluginStoreCatalog`
merges persisted remote entries. Phase 23/24 only register a real `PluginRuntime`
and seed `PluginEntryStore` — nothing else changes.

## 10. File index (Phase 22)

| File | Purpose |
|------|---------|
| `plugins/runtime/PluginEntry.kt` | `PluginEntry` + `PluginEntrySource` + invariant checks |
| `plugins/runtime/PluginVersion.kt` | `PluginVersion` + `BumpKind` |
| `plugins/runtime/PluginEntryStore.kt` | `PluginEntryStore` + `InMemoryPluginEntryStore` + `PluginEntryCodec` |
| `plugins/runtime/PluginContext.kt` | `FacadeResult` + `PluginContext` + `DefaultPluginContext` + `PluginContextFactory` |
| `plugins/runtime/PluginRuntime.kt` | `PluginRuntime` + `PluginArtifact/Verification/Update/Rollback` + `RuntimeOutcome` + `NotYetImplementedPluginRuntime` + `PluginRuntimeRegistry` |
| `services/SettingsPluginEntryStore.kt` | production `PluginEntryStore` over `SettingsManager` |
| `plugins/store/PluginStoreCatalog.kt` | `PluginStoreEntry` wraps `PluginEntry`; catalog merges persisted remote entries |
| `ui/components/PluginStoreDialog.kt` | version + bundled/remote marker |
| Tests | `PluginVersionTest`, `PluginEntryStoreTest`, `PluginContextFacadeTest`, `PluginRuntimeSeamTest` (26 tests, pure JVM) |
