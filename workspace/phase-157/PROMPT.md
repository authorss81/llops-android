# Phase 157: Plugin ecosystem & store UX — capability browser, update UX, diagnostics [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` +
`docs/ARCHITECTURE.md` + `docs/PLUGINS.md` + `docs/plugin-architecture.md` +
`plugin-sdk/.../plugins/PluginCapability.kt` first.** This is a PRODUCT feature
phase. One coherent slice of the plugin ecosystem built on the phase-21/22-26
store + runtime (`PluginStoreCatalog`, `PluginStoreController`,
`PluginSettingsDialog`, `PluginUpdateEngine`) and informed by round-2's
plugin-runtime findings (R2-B1N-03/05, R2-b2b3-LOG-03).

## Features (2-3 related, bundle deeply)

1. **Capability browser in the Plugin Store:** in `PluginStoreDialog`, add a
   "What can plugins do?" view: for each `PluginCapability` (TextTransform,
   OCR, WebSearch, FileTransfer, Assistant, Export — from
   `PluginCapability.kt`), list which plugins serve it (installed vs available).
   This makes the still-unserved capabilities (FileTransfer, Export) visible and
   honest (fail loud → "no plugin installed" is already the behavior — this
   surfaces it BEFORE the request).
2. **Update UX with release notes:** when `PluginUpdateEngine` finds a newer
   pinned version, surface the version delta + change notes in the update
   confirmation dialog (compile-time pin store values, `PluginUpdateChecker`).
   Only compile-time-pinned targets may be offered (fail-closed posture from
   phase-42/77 retained). Add an "Update all" (checks + offers approved updates
   in one flow) — still user-approved per download.
3. **Plugin diagnostics:** extend `PluginSettingsDialog` with a per-plugin row:
   served capabilities, opt-in state, lifecycle state (enabled/disabled/error via
   `PluginRegistry`), last failure reason (`PluginResult.Failure` text — never
   raw paths, per phase-148). This closes the "which plugin failed why" opacity.

## UI/UX + plugin ideas

- Filters in the store by capability (matches phase-127's compact-description
  work — keep rows compact).
- A future `FileTransfer` capability could be served by LocalSend — note the
  design seam (a `FileTransferPlugin` behind the facade) without implementing it.
- Keep all runtime installs user-approved + signature-verified as today.

## Verification

- Unit tests for new pure-JVM logic (capability→plugin mapping table, update
  dialog decision with notes, diagnostics row builder). Follow repo test layout
  `app/src/test`.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-157/REPORT.md`.

## Definition of done

- All three features shipped with `file:line` evidence, wired through the
  existing `PluginStoreDialog`/`PluginSettingsDialog`/`PluginUpdateEngine`.
- No download/install path weakened: compile-time pins + user approval + TLS
  pinning intact.
- New tests green + no existing test regressed.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new base-APK deps —
  any new plugin ships through the existing downloadable/compile-time split.
- Never log decrypted note content, plugin paths, or keys. Keep the plugin
  fail-closed pin posture (empty release table = nothing downloads).