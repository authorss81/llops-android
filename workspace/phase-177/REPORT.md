# Phase 177 — Plugin ecosystem full review — REPORT

Date: 2026-08-20 · Commits: `3b2f9fe` (step 1) · `c167d4c` (step 2) · `610c282` (steps 3-5)
· `c3f45a9` (step 6).

Scope: complete end-to-end review of the plugin wiring ecosystem — wiring,
opt-in defaults, accurate on/off state, enable/disable/delete correctness,
invocation-journal + diagnostics accuracy, plus regression proof.

## Per-plugin table

Default state everywhere is **OFF** (opt-in). "Store state source" is the
single source of truth each row is derived from. Verify column = covered by the
new pin suite (`Phase177PluginEcosystemReviewTest`) + the pre-existing plugin
suite.

| id | shipping bucket | default state | store state source | enable/disable/delete verified | confirm-dialog |
|---|---|---|---|---|---|
| `com.authorss81.noteflow.plugins.rot13` | bundled · built-in | installed, OFF (REGISTERED) | isInstalled + resolve | enable/disable/delete ✓ | ✓ (store Delete) |
| `...plugins.websearch` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.export.engine` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.clipshare` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.texttools` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.langdetect` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.webcapture` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.dictation` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.readaloud` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.screenshot` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.inktos` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.dictionary` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.weather` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.unitconverter` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.outline` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.citation` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `plugins.filetransfer` | bundled · built-in | installed, OFF | same | ✓ | ✓ |
| `...plugins.casechange` | bundled · OPTIONAL | **not downloaded**, OFF | isInstalled (installStore) + resolve | install→enable→disable→delete→reinstall(REGISTERED) ✓ | ✓ |
| `plugins.mlkit` | remote · downloadable | not downloaded (base APK serves no OCR/Translation) | persisted entry-store + isInstalled + resolve | consented download → REGISTERED → enable/disable/delete (asset+artifact wipe) ✓ | ✓ download-consent + ✓ delete |
| `plugins.llm` | remote · downloadable | not downloaded (base APK serves no Assistant) | same | ✓ (same path) | ✓ |

## Findings

### Correct as-is (no production fixes needed)
1. **Off-by-default is REALLY enforced** — `plugin_enabled_<id>` defaults false
   (`SettingsManager.kt:447-448`); only the two user-facing toggles write it; no
   plugin self-enables; `installPlugin` flips install only, never enable
   (`PluginRegistry.kt:428`); routing requires `enabled == true`
   (`PluginManager.kt:197`); `onProcessStart`/`resumeLifecycle`/`refreshAvailability`
   never auto-enable. Evidence: `workspace/phase-177/STEP2-VERIFY.md`.
2. **On/off state is accurate everywhere** — store rows + settings switch derive
   from the SAME enable store the router reads; `row.installed ⇔ row.state !=
   null ⇔ row.plugin != null`; off (REGISTERED/DISABLED) vs on
   (ENABLED/AVAILABLE/UNAVAILABLE) never both/neither. Evidence:
   `workspace/phase-177/STEPS-3-4-5-VERIFY.md`.
3. **Delete is confirmation-gated on the ONLY delete path** (Plugin Store →
   Delete → warning dialog with Delete/Cancel, `PluginStoreDialog.kt:535-558`).
4. **Rejected plugins** show Delete (no Enable) in the store
   (`PluginStoreDialog.kt:483`) and the registry refuses opt-in
   (`PluginRegistry.kt:805-809`). Unavailable plugins can never display an
   Enable affordance (UNAVAILABLE derives only when already enabled,
   `PluginRegistry.kt:730-740`).
5. **Journal + diagnostics** honest and bounded (`PluginInvocationJournal`,
   `MAX_JOURNAL_ENTRIES=20`, own key family, scrubbed, lock-serialized; delete
   wipes it, disable keeps it; `selfCheck` runs the plugin's real gate).

### Fixes made
| # | Fix | file:line (before → after) |
|---|---|---|
| F1 | **Stale R2-b2b2-DEP-03 pin after phase-175** — phase-175 moved ML Kit into `:plugins:mlkit`, where `mlkit:translate` resolves okhttp-3.0.0 (and okio-1.6.0) as ACTIVE runtime deps, so their jars are now pinned+verified in `gradle/verification-metadata.xml`; the phase-146 "their jars NEVER resolve / POM-only, no jar pinned" assertion contradicted the new graph and red-laned the suite. Updated the pin to require a **verified jar** (sha256) or a retained POM-only entry — the security control (tracked, verified, never dropped) is preserved and now matches reality. Also updated the `settings.gradle.kts` R2-b2b2-DEP-03 comment. | `Phase146BuildIntegrityTest.kt:207-238` → updated assertions; `settings.gradle.kts:205-214` → updated comment |
| F2 | New pin suite for three untested ecosystem invariants (see tests). | new `app/src/test/java/com/authorss81/noteflow/Phase177PluginEcosystemReviewTest.kt` |

## Tests added (phase-177)
`Phase177PluginEcosystemReviewTest` (3 tests, all green):
1. `store rows are the single source of truth at every lifecycle stage` — 6
   lifecycle stages, every catalog row matches installStore + enableStore, off/on
   mutual exclusion, `state ⇔ installed`, Enable-affordance equivalence.
2. `delete invokes deleteDownloadedAssets exactly once and never for a refused delete`.
3. `rejected plugins cannot be enabled and resolve rejected`.

## Regression proof (step 6)
- `gradle assembleDebug` → **BUILD SUCCESSFUL**.
- `gradle :app:testDebugUnitTest` → **2384 tests, 1 failed** (the pre-existing
  `Phase148UiFailureTextScrubTest` UNC-path failure — reproduced on a clean
  stash in earlier phases, untouched here).
- Specified plugin tests all green: `PluginFrameworkTest`,
  `PluginStoreLifecycleTest`, `PluginExecutionSimulationTest`,
  `PluginDownloaderTest`, `PluginInvocationJournalPolicyTest`,
  `PluginStoreRowPolicyTest`, `PluginCapabilityDirectoryTest`,
  `PluginDiagnosticsRowPolicyTest`, `PluginBytecodeIsolationTest`,
  `CompileTimePluginPinStoreTest`, `PluginOffByDefaultTest`,
  `PluginLifecycleStateMatrixTest`, `RemotePluginStoreDownloadTest`,
  `PluginSettingsNamespacingTest`, `PluginContextWhitelistTest`,
  `B2Deps04PluginSigningTest`, `B2Log04PluginLogScrubbingTest`,
  `B1Auth03PluginLifecycleGateTest`, `Phase177PluginEcosystemReviewTest`.
- Flaky note: `Phase151MarkdownMainThreadPerfTest` +
  `WikiLinkParserCacheUnitTest` failed once in one full-suite run under CI load
  (timing/concurrency), pass in isolation and pass in the second full run — not
  related to plugin code, no changes.

## Constraints honored
- No `.github/workflows/` changes. No new base-app dependencies. No DB schema
  change. PluginRuntime security model untouched (pinned-cert + SHA-256 verify,
  capability facade). No decrypted content/keys logged. Base-APK size rule
  unchanged.

## Artifacts
- `workspace/phase-177/INVENTORY.md` (step 1)
- `workspace/phase-177/STEP2-VERIFY.md` (step 2)
- `workspace/phase-177/STEPS-3-4-5-VERIFY.md` (steps 3-5)
- `app/src/test/java/com/authorss81/noteflow/Phase177PluginEcosystemReviewTest.kt`
- This REPORT.