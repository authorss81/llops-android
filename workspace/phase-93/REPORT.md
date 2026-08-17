# Phase 93 — B2-LOG-04 (LOW): plugin download/install failure messages echo the attacker-controllable download URL into logcat

**Finding** (`docs/security-report.md`, row `B2-LOG-04 | LOW | phase-93`):
plugin failure text built from the attacker-influenceable `entry.downloadUrl`
(`PluginDownloader` guard refusals), `DownloadablePluginInstaller.failCleanup`
(`logger.error(..., "remote install failed: ${message.substringBefore('.')}")`),
and `PluginStoreController`'s `result.reason` forwards, and then written
**verbatim** to logcat by `AndroidPluginLogger.error` — breaking the
`PluginLogger` KDoc contract ("ids/names + exception class names only"). The
exploit shape is a hostile manifest URL `https://attacker.example/steal?user=bob&vault=…&note=…`
being echoed into logcat — an exfil/log-injection primitive, because a CR/LF
inside the URL or plugin id can also forge arbitrary logcat lines.

## What changed

### 1. New pure-JVM decision table — `plugin-sdk/.../plugins/PluginLogPolicy.kt`

The phase-71 B2-LOG-03 pattern (`services/FailureLogPolicy.kt`) applied to the
plugin pipeline. Four functions, all pinned by `B2Log04PluginLogScrubbingTest`:

- `hasLineBreak(value)` — detects a CR or LF anywhere (the logcat line-forgery
  vehicle).
- `lineBreakError(fieldName)` — the **fixed** user-facing refusal text; the
  offending value is never echoed back (B2-LOG-04: fixed text only).
- `stripLineBreaks(value)` — removes every `\r`/`\n`.
- `safeLine(text)` — the ONE sink sanitizer: strips CR/LF **and** redacts
  URL-shaped tokens (`https?://\S+` → `<url>`), so a hostile URL or its query
  string can never be echoed, and a `\n` can never follow a truncated
  `substringBefore('.')`.

Why plugin-sdk: `PluginEntry` (plugin-sdk) and `HostedPluginVersion` (app) both
need the CR/LF rule, and the SDK must not depend on an app package.

### 2. Parse-time rejection of CR/LF — `plugin-sdk/.../runtime/PluginEntry.kt` and `app/.../runtime/HostedPluginManifest.kt`

- `PluginEntry.validationErrors()` now refuses CR/LF in `id`, `name` and
  `downloadUrl` via `PluginLogPolicy.lineBreakError(...)`, so a line-forgery
  vehicle can never enter the pipeline. Because `PluginEntryCodec.decode`
  calls `isValid()`, a hand-edited catalog blob carrying a newline id is now
  refused on decode; because `PluginDownloader.download` calls
  `validationErrors()` before any byte moves, and `PluginUpdateEngine.update`
  refuses a target with `validationErrors().isNotEmpty()`, a hostile entry is
  stopped at the model.
- `HostedPluginVersion.validationErrors()` now refuses CR/LF in `id` and
  `downloadUrl`, which `PluginManifestParser.parse` folds into its aggregate —
  so a manifest leg with a newline in `id`/`downloadUrl` invalidates the WHOLE
  document (`ManifestParseResult.Invalid`).

### 3. Sink-side hygiene — `app/.../plugins/PluginLogger.kt`

`AndroidPluginLogger.lifecycle` and `.error` now route their composed line
through `PluginLogPolicy.safeLine` before `Log.d`/`Log.e`. Defense in depth:
even if a future caller slips a hostile string through, CR/LF and URL tokens
can never reach logcat.

### 4. Call sites log FIXED tokens only (the actual B2-LOG-04 leak fix)

Every `logger.error(...)` detail that previously embedded `message` / `reason`
/ `result.reason` fragments now carries a fixed reason code or stage token:

- `services/DownloadablePluginInstaller.kt` — `failCleanup(...)` gains a
  `reasonCode` parameter and logs `remote install failed; code=<code>`
  (`MISSING_SHA256`, `MISSING_CERT_PIN`, `VERIFY_FAILED`,
  `VERIFY_NOT_IMPLEMENTED`, `REGISTRY_REFUSED`, `LOAD_FAILED`,
  `LOAD_NOT_IMPLEMENTED`); the download-guard early return logs
  `code=DOWNLOAD_GUARD`. The pre-fix
  `"remote install failed: ${message.substringBefore('.')}"` line is gone.
  `message` remains user-facing (returned in `DownloadOutcome.Failed` for the
  store dialog) — it is just never a logcat line.
- `plugins/store/PluginStoreController.kt` — download (`:211`) and delete
  (`:241`) refusal logs now use the fixed `code=REGISTRY_REFUSED`; the
  pre-fix `"store download/delete refused: ${result.reason}"` forwards are gone.
- `services/DownloadablePluginUpdater.kt` — post-update reload failure logs the
  fixed `post-update reload failed` (the
  `(${loaded.message.substringBefore('.')})` fragment is gone).
- `plugins/runtime/PluginUpdateEngine.kt` — `failedUpdateKeepsPrevious` takes a
  `stageCode` and logs `update failed; stage=<stageCode>; previous version kept`;
  call sites pass `download` / `verification` / `load-smoke-test` /
  `swap-persist`. The prior
  `"update failed (${reason.substringBefore('.')})…"` — which under the exact
  B2-LOG-04 exploit leaked the URL **prefix** (e.g. a transport failure message
  `network failure https://attacker.example/exfil?…` truncated to
  `https://attacker`) — is gone.

The downloader's own guard refusals (`PluginDownloader.download`) were already
log-free (they return `DownloadOutcome.Failed(message)` for the user-facing
store dialog only); that is now pinned by a test.

## Verification

- `gradle :app:testDebugUnitTest` (full suite): **1602 tests, 0 failures, 0
  errors, 0 skipped** (`BUILD SUCCESSFUL`). Includes the new
  `B2Log04PluginLogScrubbingTest` (10 tests) plus the affected suites
  (`PluginManifestParserTest`, `PluginUpdateEngineTest`,
  `RemotePluginStoreDownloadTest`, `PluginStoreLifecycleTest`,
  `PluginEntryStoreTest`, `PluginDownloaderTest`, `PluginUpdateCheckerTest`)
  all green.
- `gradle testDebugUnitTest` (all modules incl. `:plugins:llm`): green.
- `gradle assembleDebug`: first invocation hit the documented transient
  dex-merge failure; retry `BUILD SUCCESSFUL`. Debug APK 173,794,594 bytes,
  SHA-256 `4219405bffbb9b2f0cd110a044f7a575e854cb3efb65502b30c93efac36629f1`.

## New test file

`app/src/test/java/com/authorss81/noteflow/B2Log04PluginLogScrubbingTest.kt`
(10 tests), pure JVM, three layers:

Decision table (`PluginLogPolicy`):
- CR/LF detection in every position; strip removes every `\r`/`\n`;
- `safeLine` on the exact B2-LOG-04 exploit shape (hostile URL + CR/LF + forged
  line) yields a line with no CR/LF, no host, no query, no forged text, and the
  URL replaced by `<url>`;
- `safeLine` redacts only the URL run (up to the next space) and preserves
  surrounding fixed text.

Model rejection:
- `PluginEntry` refuses `\n` id, `\r` name and `\n`/`\r` downloadUrl; the error
  text is fixed (`line break`) and never echoes the hostile value;
- `PluginEntryCodec` refuses a persisted blob whose id carries CR/LF
  (`decode → null`), clean round-trip still works;
- `PluginManifestParser` invalidates the whole document for a newline `id` or
  `downloadUrl`, and a clean manifest still parses `Valid`.

Call sites:
- `PluginDownloader` guard refusal (attack-host URL, empty allow-list) never
  reaches the logger at all — `RecordingPluginLogger.lines` stays empty;
- a download-stage `PluginUpdateEngine.update` failure with a hostile transport
  message (`network failure https://attacker.example/exfil?note=secret\nFORGED-END`)
  logs exactly one error line, a fixed `stage=download`, containing no URL:
  the pre-fix code would have logged the URL prefix through
  `reason.substringBefore('.')`; the `RuntimeOutcome.Failed.message` (user-facing)
  still carries the real reason.

## Checksums / secrets

- No secrets, keys, passwords, or decrypted note content were logged, printed,
  or committed. `AndroidPluginLogger` writes only id/name + fixed tokens and now
  scrubs CR/LF + URL tokens even there.
- `allowBackup="false"`, `data_extraction_rules.xml`, FLAG_SECURE untouched.
- No new permissions; `INTERNET` usage unchanged.

## API floor (AGENTS.md hardware reality)

- `PluginLogPolicy` is pure JVM (string ops + one regex), API 26+ floor; the
  model checks run on every device tier. No fallback needed; no new
  dependencies; `app/build.gradle.kts` and `plugin-sdk/build.gradle.kts`
  untouched.

## Out of scope (deliberately not fixed here)

- **B2-LOG-05 (WebDAV failure echo, phase-94)** — user-facing UI echo, separate
  finding, separate phase. Not touched.
- The `PluginDownloader` guard refusal **messages** still embed the entry id /
  downloadUrl — they are user-facing only (store dialog) and are never logged;
  pinning their absence from the sink is exactly what the downloader test does.
- Registry lifecycle logs (`PluginRegistry`, `PluginManager`) pass exception
  **class names** (needed diagnostics, contract-safe) and registry ids/names
  that are model-validated CR/LF-free; left as-is.
- No DB schema change, no migration, no `.github/workflows/` edit, no new
  dependencies.