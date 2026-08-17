# Phase 22: Hybrid plugin architecture skeleton — design + wiring [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a hardened compile-time plugin framework (Phases 10–11, 15, 16), a plugin
store UI (Phase 21), and an existing plugin set (Phases 12/15/16).

**THE CORE GOAL OF THIS PHASE (read first):** plugins must NOT increase the base
APK size and must NOT force features onto users they will never use. Heavy/native
features (camera OCR/QR, large ML engines, the local LLM) must ship as
**downloadable, signature-verified plugins** fetched over HTTPS only on explicit
user consent. Lightweight features (pure-JVM or small-keyless-HTTP) stay
compile-time because they cost only a few KB. This is the **hybrid model**.

**This phase is the ARCHITECTURAL FOUNDATION.** It does NOT yet implement the full
downloadable runtime — later phases build the runtime (23), updates (24), the
ink→shape canvas plugin (25) and the lightweight ecosystem (26) on top of the
skeleton you design and wire here. The skeleton must be so clear that every later
phase plugs into it without redesign.

## Deliverable 1 — Architecture design doc (`docs/plugin-architecture.md`)
A precise, decision-focused design doc that settles the hybrid model ONCE:
- **Module layout**: base app (compile-time plugins, registry, store, capability
  routing) vs. runtime package (`plugins/runtime/`) vs. future downloadable plugin
  modules (separate Gradle modules, e.g. `plugins/llm/`, `plugins/ocr/`). Show the
  dependency direction (base must NOT depend on downloadable modules; the runtime
  loads them via `DexClassLoader`).
- **Unified catalog-entry model**: a single `PluginEntry` that covers BOTH
  compile-time and downloadable plugins: `id`, `name`, `version` (semver),
  `capabilities`, `category`, `permissions`, `downloadUrl` (nullable for built-ins),
  `installSizeBytes`, `updateChannel`, `sha256`, `pinnedCertHash`. This entry type
  is the seam that Phase-21 store, Phase-23 runtime, and Phase-24 updates all use.
- **Capability facade contract**: define the narrow `PluginContext` surface
  (e.g. `insertText`, `showResult`, `httpGet(httpsOnly)`, `readSelection`,
  `requestModelDownload`) that plugin code may call — NEVER direct DB/keystore/
  `EncryptionService`/decrypted-content handles. Define the capability whitelist
  matrix (which capability gets which facade calls).
- **State machine**: `NOT_DOWNLOADED → VERIFYING → VERIFIED → REGISTERED(off) →
  ENABLED → DISABLED(kept) → DELETE(wiped)` PLUS update states `UPDATE_AVAILABLE →
  APPROVING → DOWNLOADING → VERIFYING → READY → ROLLBACK_NEEDED`. Integrate with
  the Phase-21 lifecycle.
- **Versioning + update model**: semver, per-plugin update channel, how a new
  version is discovered (Phase-24 hosted manifest), signature + sha256 re-verify on
  update, rollback path (keep previous version until new one verified).
- **Security model**: pinned-cert-hash verification, tamper rejection before ANY
  load, integrity re-check on every load, no direct data handles. Tie into
  `docs/PLUGINS.md`.

## Deliverable 2 — Compilable skeleton (wiring, not full implementation)
Implement the skeleton so later phases slot in:
- `PluginEntry` data class + `PluginEntryStore` (persistence seam over
  `SettingsManager`, pure-JVM testable) covering built-ins AND downloadable entries.
- `PluginVersion` (semver compare + bump) with pure-JVM tests.
- The `PluginContext` capability-facade **interface + deny-by-default skeleton**
  (a real `DefaultPluginContext` that returns `CapabilityDenied` for everything
  until a later phase wires permitted calls). Pure-JVM test: deny-by-default.
- `PluginRuntime` interface: `verify(artifact)`, `load(entry)`, `update(entry)`,
  `rollback(entry)` — STUBBED with honest `NotYetImplemented` results in this phase
  (they are built in phases 23/24). Register the seams so phases 23/24 only fill in
  implementations.
- Extend the Phase-21 store catalog to carry the unified `PluginEntry` fields
  (version shown in the store UI; downloadable entries marked "remote", built-ins
  marked "bundled").
- `docs/plugin-architecture.md` + update `docs/PLUGINS.md` "Design rules" with the
  hybrid model (keep the compile-time rule for built-ins; add the downloadable +
  verified rule for heavy features).

## Definition of done
- `docs/plugin-architecture.md` written and consistent with the actual code seams.
- `PluginEntry`, `PluginVersion`, `PluginContext` (deny-by-default), `PluginRuntime`
  (stubbed seams) compile; `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes: semver tests, entry persistence round-trip,
  deny-by-default facade tests.
- Store UI shows version + bundled/remote marker for entries.
- No fake implementations: stubs return honest `NotYetImplemented`, never pretend
  to work. REPORT.md records the design decisions + what phases 23–26 will fill in.

## Constraints
- Do NOT implement full download/verify/load here (that is phase 23) and do NOT
  implement updates (phase 24). Design + seams only.
- Do NOT change the DB schema. Do NOT edit `.github/workflows/`.
- Never log keys, passwords, decrypted content. Keep the plugin boundary clean.
- No new permissions.