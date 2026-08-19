# Phase 173 — Plugin ecosystem: serve FileTransfer over LocalSend + invocation journal + honest store metadata

Status: **DONE** (2026-08-19). `gradle testDebugUnitTest` 2363 total / 1 pre-existing
`Phase148UiFailureTextScrubTest` UNC-path failure (untouched); `gradle assembleDebug` green.

## Feature 1 — FileTransfer capability served over LocalSend (REAL)

`PluginCapability.FileTransfer` was the last exclusive capability with no serving
plugin (requests failed loudly with `NO_PLUGIN_INSTALLED`). It is now served by a
compile-time plugin that REUSES the existing LocalSend Protocol v2.2 sender.

### Implementation

- Serving interface + types in `plugins/NoteflowPlugin.kt`:
  - `FileTransferKind` (`NOTE_HTML`, `VAULT_BACKUP`, `OBSIDIAN_ZIP`, `HTML_ZIP`)
  - `FileTransferRequest(kind, file, device)`
  - `FileTransferOutcome` sealed (`Sent` / `Rejected` / `Error`) — never null, never silent.
  - `FileTransferPlugin` (`sendFile(context, request)`, `discover(context, timeoutMillis)`).
- `services/localsend/FileTransferSender.kt` — the minimal seam the plugin talks
  through (`discoverDevices`, `sendFile`). Pure-JVM, recording-fake-testable.
- `services/localsend/LocalSendSender.kt` — now implements `FileTransferSender`
  (reuse, never fork, documented). Default values for `includeLegacyHttpScan`
  live on the seam interface (Kotlin forbids defaults on overrides) and reference
  `LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT` — B1-NET-06
  OFF-by-default invariant preserved (pin updated in `B1Net06LocalSendDiscoveryGateTest`).
- `services/localsend/LocalSendSenderFactory.kt` — HOST-side default-sender
  factory (`LocalSendSender(SettingsLocalSendPairedDeviceStore(SettingsManager(...)))`).
  This keeps `plugins.*` code free of vault-handle type references, satisfying the
  `PluginBytecodeIsolationTest` "no vault-handle types in plugin-host code" pin.
- `plugins/filetransfer/LocalSendFileTransferPlugin.kt` — id `plugins.filetransfer`,
  v1.0.0, minApi 26, capability `FileTransfer`, permission `Internet`, availability
  `Ok`, `onEnable` no-op (sender consent gates live inside `LocalSendSender`).
  Fail-closed mapping: null/no sender in context → typed `Error`; empty/missing
  file → `Error`; every sender failure → `Rejected`; all user-facing descriptions
  pass `UiFailureTextPolicy.scrubForUi`. Discovery calls
  `discoverDevices(timeout, includeLegacyHttpScan = false)` (B1-NET-06 sweep never
  auto-enabled).
- `PluginRegistry.defaultPlugins()` — last entry now `LocalSendFileTransferPlugin()`.
- `NoteflowViewModel.sendFileWithPlugin(FileTransferRequest)` →
  `withPluginAsync(PluginCapability.FileTransfer, ...)`.

### Consent model intact

The TOFU pairing gate and the receiver's `/prepare-upload` human-accept step run
inside `LocalSendSender` (unchanged) before any byte leaves the device; per-send
confirmation is the HomeScreen dialog flow the plugin's callers drive. The plugin
never constructs a raw LocalSend message.

### metadata.json / directory

`metadata.json` capability buckets: `file_transfer` moved from `unserved` to
`servedByCompileTimePlugins` (after `web_search`); `unserved` is now `[]`.
`Phase131MetadataAlignmentTest` updated to assert the move.
`PluginCapabilityDirectory` (store "What can plugins do?" view) derives from the
store catalog which includes compiled plugins, so FileTransfer now renders as
INSTALLED/served rather than "No plugin yet".

## Feature 2 — Invocation journal (bounded, persisted, scrubbed)

`services/PluginInvocationJournal.kt` (pure JVM):

- `MAX_JOURNAL_ENTRIES = 20` per plugin; `record` trims the OLDEST first (a fixpoint).
- `MAX_DETAIL_CHARS = 120`; `sanitizeDetail` runs every detail through
  `UiFailureTextPolicy.scrubForUi` AND strips the journal's own `\n`/`\u0001`
  separators (no line/field forgery, no path leak).
- Wire format: `epochMillis\u0001capabilityKey\u0001ok|fail\u0001detail`, lines
  joined by `\n`; `parse` skips malformed lines without dropping the valid tail.
- `Store` interface + `NoOpStore` default so every existing `PluginManager`
  caller/test keeps working unchanged.
- `renderLine` / `journalLines` / `newestFirst` for the dialog view (newest first).

Persistence:

- `SettingsManager.getPluginInvocationJournal`/`setPluginInvocationJournal` under
  `plugin_invocation_journal_<id>` — deliberately OUTSIDE the `plugins.<id>.*`
  namespace (a plugin cannot forge/read its own history), and wiped by store
  Delete via `wipePluginState`.

Wiring:

- `PluginManager(registry, logger = NoOp, journal = NoOpStore)` —
  `invokeGuarded`/`invokeGuardedSuspend`/`selfCheck` record every invocation with
  the capability key (or `self-check`), summary `"Success"` or `"Threw <ExceptionClass>"`.
- `services/SettingsPluginInvocationJournalStore.kt` — production Store.
- `NoteflowViewModel` exposes `pluginJournals: StateFlow<Map<String, List<Entry>>>`
  refreshed in `refreshPluginStates()`.
- `ui/components/PluginSettingsDialog.kt` renders a "Recent activity (keeps last
  20 per plugin)" section, bounded and scrubbed.

## Feature 3 — Store row metadata (what it can do / how it ships)

`services/PluginStoreRowPolicy.kt` (pure JVM):

- `capabilitiesLabel(caps)` — bounded fold to `MAX_CAPABILITIES_IN_LINE = 3` with
  "+N more"; deterministic sort = **exclusive-first, then alphabetical** (store
  arbitrates single-winner exclusives, so they are the headline of the line);
  `"none declared"` defensive empty set.
- `bucketLabel(source)` — `Bundled (in app)` / `Downloadable (verified)`.
- `downloadNote(entry)` — `~N MB download` when the remote plugin declares a size,
  else the honest `"needs the hosted channel"` note; null for bundled (nothing is
  ever downloaded).
- `metadataLine(entry)` — one bounded footer line, replacing the old ad-hoc
  source-label + capability-join + size string.

`ui/components/PluginStoreDialog.kt` renders `PluginStoreRowPolicy.metadataLine(entry.entry)`.

## Tests

| Class | Count | Notes |
|-------|-------|-------|
| `FileTransferPluginPolicyTest` | 9 | recording `FileTransferSender` fake; manifest/availability; send-through passes device+file unchanged; failure → scrubbed Rejected; empty/missing file fails closed before sender; discover UDP-only; discover null on no sender; capability routing fails loudly pre-opt-in and resolves after; no-sender Error without network |
| `PluginInvocationJournalPolicyTest` | 8 | record/parse round-trip; bounded to 20 (newest survive); sanitize bounds + scrub + separator strip; parse skips malformed + keeps valid tail; renderLine bounded/honest; journalLines newest-first + bounded; manager records invocation+self-check; throwing invocation → scrubbed failure entry |
| `PluginStoreRowPolicyTest` | 7 | bundled line; downloadable known size; unknown size → hosted channel; long-list fold; deterministic sorted label; empty defensive; downloadNote null/vs bounded |
| `PluginFrameworkTest` | updated | unserved probe switched `FileTransfer` → `Assistant` (FileTransfer now has a serving plugin) |
| `Phase131MetadataAlignmentTest` | updated | `file_transfer` in `servedByCompileTimePlugins`, not unserved |
| `B1Net06LocalSendDiscoveryGateTest` | updated | the OFF-by-default sweep pin now targets the `FileTransferSender` seam signature |

## Verification

- `gradle compileDebugUnitTestKotlin` — clean after fixes.
- `gradle testDebugUnitTest` — 2363 total, only failure = the KNOWN pre-existing
  `Phase148UiFailureTextScrubTest` UNC-path scrub case (untouched; reproduced as
  documented pre-existing). Two timing-sensitive tests flaked under full-suite load
  (`WikiLinkParserCacheUnitTest` cancellation, `Phase151MarkdownMainThreadPerfTest`
  linearity) and both pass in isolation on re-run — unrelated to phase-173 code.
- `gradle assembleDebug` — green.

## Constraints honored

- No `.github/workflows/` edits. No schema change / migration. No new dependencies.
- Base-APK-size rule: plugin is lightweight/pure-JVM, compile-time (no native/ML).
- Plugin cert-pin placeholder untouched (Phase-171 owns it).
- Never log keys/passwords/decrypted content; all user-facing text scrubbed.
- Never silent degradation (fail-closed Rejected/Error; loud typed outcomes).

## Docs updated

- `docs/PLUGINS.md` — FileTransfer now served section ("Phase 173" block: LocalSend
  reuse, consent, seams, journal, store metadata; still-unserved = Assistant only).
- `docs/ARCHITECTURE.md` — `services/localsend/` + `plugins/<capability>/` package
  rows; "Implemented in phase-173" note; phase-157 "still-unserved" copy updated.
- `docs/phase-status.md` — phase-173 row `DONE`.