# Phase 157 — Plugin ecosystem & store UX (capability browser, update UX, diagnostics)

**Status:** DONE (implemented + verified; `gradle testDebugUnitTest` + `gradle assembleDebug` green)

## What shipped (three features, deeply bundled)

### Feature 1 — Capability browser in the Plugin Store

- New pure-JVM `app/src/main/kotlin/com/authorss81/noteflow/services/PluginCapabilityDirectory.kt` (`object PluginCapabilityDirectory`, `:31`):
  - `CapabilityRow` (`:43`) = one `PluginCapability` + its `PluginRef` lists (`installed` vs `available`),
    following `PluginCapability.ALL` order.
  - `Coverage` (`:40`) = `INSTALLED` (serviceable today) / `AVAILABLE_ON_STORE` (a download away) /
    `UNSERVED` (honest "no plugin yet").
  - `rows(entries, isInstalled)` (`:63`), `capabilitiesInStore` (`:88`), `coverageLabel` (`:94`),
    `servingSummary` (`:107`, names bounded to `MAX_NAMES_PER_ROW` = 3 with `+N more`).
- `ui/components/PluginStoreDialog.kt` wiring:
  - view-mode toggle "Plugins | What can plugins do?" (`showCapabilities`, `:88`; segmented tabs `:146-152`);
  - `StoreCapabilityRow` composable (`:669`), per-capability filter-chip row + "All" chip
    (`selectedCapability`, `:89`/`:255-262`) that composes with the phase-156 `storeFilter` text filter (`:98-106`);
  - combined match-less empty state whose ONE "Clear filter" CTA clears BOTH filters (`:276-286`).
- The still-unserved capabilities (FileTransfer today; Assistant until the downloadable LLM is
  installed) are now surfaced BEFORE the fail-loud request — "No plugin yet" copy, no fake promises.
  Design seam documented (a future LocalSend-backed `FileTransferPlugin` behind the facade; not implemented).

### Feature 2 — Update UX with scrubbed release notes + "Update all"

- New pure-JVM `app/src/main/kotlin/com/authorss81/noteflow/services/PluginUpdatePromptPolicy.kt` (`:28`):
  - `notesForDisplay` (`:44`): collapse control chars → bound `MAX_NOTES_CHARS` = 240 → **then**
    `UiFailureTextPolicy.scrubForUi` (R2-b2b3-LOG-03 rule — hosted release notes never render raw);
  - `versionDeltaText` (`:54`); `UpdateAllItem` + `updateAllPlan` (`:58`/`:71`, deterministic,
    deduped per download id, sorted ids); `batchSummary` (`:93`, names bounded `MAX_BATCH_NAMES` = 3).
- `NoteflowViewModel.kt`:
  - `_updateAllInProgress`/`updateAllInProgress` (`:435-436`);
  - `updateAll()` (`:637`): check → `UpdateCheckOutcome.UpdatesAvailable` ⇒ set `storeUpdates` +
    `batchSummary` general message → `openNextPendingUpdate()`; `UpToDate`/`Failed` clear the flag (`:654-658`);
  - `openNextPendingUpdate()` (`:667`): walks the offered updates ONE at a time through the EXISTING
    per-download approval dialog (`pendingUpdatePluginId` + `respondUpdateApproval`) — declining any
    approval ends the batch (`:672`); a completed update advances the walk only while
    `_updateAllInProgress` (`:682`/`:725`).
  - Posture unchanged: compile-time pins (phase-42/77), TLS pinning, per-download user approval all intact.
- `PluginStoreDialog.kt`: approval dialog renders "What changed: …" via `notesForDisplay` (`:616`);
  "Update all" button row (`:169-180`, disabled/`Updating…` while busy).

### Feature 3 — Per-plugin diagnostics rows

- New pure-JVM `app/src/main/kotlin/com/authorss81/noteflow/services/PluginDiagnosticsRowPolicy.kt` (`:27`):
  - `servedCapabilitiesLabel` (`:42`, bounded `MAX_CAPABILITIES_SHOWN` = 4 + `+N more`),
    `optInLabel` (`:54`), `lifecycleLabel` (`:57`, fixed shared table for enabled/disabled/error),
    `scrub` (`:65`), `reasonLine` (`:72`), `lastInvocationLine` (`:81`), `footer` (`:93`).
- `ui/components/PluginSettingsDialog.kt`: every per-plugin row now renders the diagnostics footer
  (`:132-136`) and the `state.reason` + last-invocation summaries go through the policy's scrub
  (`:151`) — replaces the old raw `last.summary` interpolation (phase-148 rule; never raw paths/text).

## Verification

- New tests (all green):
  - `PluginCapabilityDirectoryTest` (9) — mapping table, coverage verdicts incl. unserved, bounded summaries.
  - `PluginUpdatePromptPolicyTest` (10) — notes scrubbing/bounding/control-char collapse, delta text,
    plan determinism/dedup, batch summary singular/plural + name bound.
  - `PluginDiagnosticsRowPolicyTest` (9) — labels, lifecycle table, scrub, reason/last-invocation lines, footer.
- `gradle testDebugUnitTest`: **2216 tests, 2215 green** — only the pre-existing documented
  `Phase148UiFailureTextScrubTest` UNC-path failure (untouched; reproduced on clean stash per AGENTS.md).
- `gradle assembleDebug`: **BUILD SUCCESSFUL** — one transient `DexArchiveMergerException` on first
  run (dex merge), succeeded on re-run; `app-debug.apk` produced.

## Constraints honored

- NO DB schema change · `.github/workflows/` untouched · no new base-APK dependencies.
- No download/install path weakened: compile-time pins + TLS pinning + per-download user approval intact.
- No raw `${e.message}`, decrypted content, plugin paths, or attacker-influenceable text surfaced —
  everything goes through `UiFailureTextPolicy.scrubForUi` + bounds (phase-148 R2-b2b3-LOG-03 precedent).
- Base-APK cap respected: all three policies are pure-JVM Kotlin in `services/`, no new assets or natives.