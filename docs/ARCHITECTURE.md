# InkFlow (Noteflow) — Architecture Map for AI Agents

> **Living document.** Read this first in any phase. It is injected as context at the
> start of every pipeline phase. After you implement something, append a 3-5 line
> "Implemented in <phase>:" note to the relevant section below AND update
> `docs/phase-status.md` — so the next phase reads fresh facts instead of re-exploring.
> Root: `app/src/main/kotlin/com/authorss81/noteflow/`. Read `AGENTS.md` for hard rules.

## Package layout

| Subpackage | Key files | Purpose |
|---|---|---|
| `data/model/` | `Entities.kt`, `StrokeModels.kt` | Room entities (8) + stroke/ink types |
| `data/db/` | `NoteflowDatabase.kt`, `Daos.kt` | Room DB (schema v9, 8 DAOs), corrupt-DB quarantine |
| `data/repository/` | `NoteRepository.kt`, `LruBoundedMap.kt` | Encrypted read/write, search corpus, WAL checkpoint, re-key |
| `services/` | `EncryptionService.kt`, `SecurityService.kt`, `DatabaseSecurityHelper.kt`, `VaultKeyHolder.kt`, `WetBrushEngine.kt`, `WebDavSyncService.kt`, `ImportExportService.kt`, `PaletteCatalog.kt`, `ShapeRecognitionHelper.kt` | Non-UI: crypto/vault, brush math, sync, import/export, palette |
| `services/localsend/` | `LocalSendProtocol.kt`, `LocalSendSender.kt`, `LocalSendPairing.kt`, `SettingsLocalSendPairedDeviceStore.kt` | Pure-JVM LocalSend v2.2 + real network sender + TOFU pairing gate (B1-NET-02) |
| `plugins/` | `NoteflowPlugin.kt`, `PluginRegistry.kt`, `PluginManager.kt`, `PluginDiagnostics.kt`, `PluginLifecycle.kt` | Compile-time plugin framework + typed serving interfaces |
| `plugins/runtime/` | `RuntimePluginLoader.kt`, `SignatureVerifiedPluginRuntime.kt`, `ArtifactSignatureVerifier.kt`, `PinnedCertHash.kt`, `PinnedTlsConnector.kt`, `PluginManifestFetcher.kt`, `HttpsPluginDownloadTransport.kt`, `PluginDownloader.kt`, `PluginUpdateEngine.kt`, `CompileTimePluginPinStore.kt`, `PluginFrameworkClassLoader.kt`, `ArtifactStaticScan.kt` | Downloadable-plugin runtime: pinned-cert verify (manifest + artifact transports, no redirects), DexClassLoader (scoped `plugins.*`-only parent), verify-time static content scan (B1-AUTH-01), updates |
| `plugins/store/` | `PluginStoreCatalog.kt`, `PluginStoreController.kt`, `RemotePluginInstaller.kt`, `PluginInstallStore.kt` | Plugin Store lifecycle (bundled catalog + remote install) |
| `plugins/<capability>/` | `ocr/MlKitOcrEngine.kt`, `websearch/DuckDuckGoWebSearchPlugin.kt`, `translation/MlKitTranslatorEngine.kt`, `inktos/InkToShapePlugin.kt`, `weather/`, `dictation/`, `readaloud/`, `citation/`, ... | One impl per capability, registered in `PluginRegistry` |
| `ui/components/` | `AnnotationCanvas.kt` (4535 lines), `AgslShaders.kt`, `ShaderCapabilityHelper.kt`, `PenNibVisualPreview.kt`, `LayerBitmapCache.kt`, `BrushStudioDialog.kt`, `PluginStoreDialog.kt` | Compose components: canvas, AGSL shaders, dialogs |
| `ui/screens/` | `EditorScreen.kt` (4805), `MarkdownPreviewScreen.kt`, `HomeScreen.kt`, `KnowledgeGraphScreen.kt`, `LockScreen.kt` | Top-level screens |
| `ui/viewmodel/` | `NoteflowViewModel.kt` (~1500) | God-ViewModel: DB, security, plugins, all state flows |
| `theme/` | `Theme.kt`, `GlassSurfaces.kt`, `GlassThemeMath.kt`, `Motion.kt`, `Type.kt`, `Color.kt` | Material3 + frosted-glass design system |
| `utils/` | `ConstantTime.kt`, `BitmapPool.kt`, `DeviceCompatibilityManager.kt`, `WikiLinkParser.kt` (dup, see notes) | Pure helpers |

## Core subsystem anchors (file:line)

- **Encryption/vault**: `services/EncryptionService.kt:18` (PBKDF2 600k, AES-256-GCM, NFKC-normalized password);
  `services/SecurityService.kt:14` (AndroidKeyStore-wrapped DEK `noteflow_dek_key`);
  `data/repository/NoteRepository.kt:18` (encrypted field r/w, `zeroizeKey()`, `checkpointWal()`);
  `services/DatabaseSecurityHelper.kt:21` (HMAC tamper checksum over `noteflow.sqlite`);
  `data/db/NoteflowDatabase.kt:43` (schema v9, quarantine);
  `services/VaultKeyHolder.kt:11` (in-memory DEK, zeroized on lock).
  - **Implemented in phase-43** (B1-DB-1, see `workspace/phase-43/REPORT.md`): the corrupt-open
    classifier `NoteflowDatabase.kt` `isDatabaseCorruptException` now matches ONLY genuine
    corruption (`android.database.sqlite.SQLiteDatabaseCorruptException`,
    `net.zetetic.database.sqlcipher.SQLiteNotADatabaseException`, messages "file is not a
    database"/"malformed"/"database disk image is malformed") — transient open failures
    (locked, disk I/O, ENOSPC, can't-open) are NEVER treated as corruption. Quarantine no
    longer auto-creates an empty replacement DB: `SafeSupportSQLiteOpenHelper` rethrows after
    quarantine + a `throwIfVaultQuarantined` guard fails any further open while the flag is
    set, so the empty vault is created only after the user's explicit "start fresh".
    `NoteflowViewModel` surfaces the `CorruptionRecoveryScreen` in-session, gates the six
    Room-backed note flows on `authenticated && !corruptionBlocked`, re-initializes after
    start-fresh, and clears the flag after a successful restore.
  - **Implemented in phase-44** (B1-DB-4 + B1-AUTH-06, see `workspace/phase-44/REPORT.md`): note
    bodies no longer live as PLAINTEXT files. The field-encrypted `pages.extractedText` column is
    now the ONLY body store. `services/NoteBodyVaultPolicy.kt` (pure JVM) classifies note-body
    sources (text/-typed pages and `.md`/`.txt` files only; PDF/image/attachment artifacts are
    never treated as bodies) and provides `resolveBodyForDisplay` + `deleteLegacyNoteTextBody`.
    Single write path: `NotePageDao.updatePageBody` + `NoteRepository.updatePageBody` (AES-GCM,
    per-record AAD). The markdown editor opens/saves via `viewModel.saveMarkdownNoteBody`
    (`MainActivity.kt` both layouts); `File.writeText` body writes are gone. `.md`/`.txt`/DOCX/
    HTML/Obsidian imports and journal/daily/wiki page creation store `sourceFilePath = null` +
    body in `extractedText`. One-time `NoteRepository.migrateLegacyPlaintextNoteBodies` (flagged
    by `SettingsManager.noteBodyPlaintextMigrated`, `fieldAadMigrated` pattern) sweeps pre-fix
    file bodies into the encrypted column then deletes the files; WAL is checkpointed + the DB
    HMAC re-stamped afterwards.
  - **Implemented in phase-45** (B1-CRYPTO-02, see `workspace/phase-45/REPORT.md`): the vault DEK
    is no longer obtainable without the password. `services/SecurityService.kt` now isolates the
    device-wrapped DEK copy behind an internal `DekDeviceStore` seam
    (`SharedPrefsDekDeviceStore` = `noteflow_keystore`/`noteflow_sec_dek`, `clear()` uses
    `commit()` so the removal is disk-acknowledged) and `readDek()` fails closed (absent OR
    `authRequired=true` blob ⇒ null; `getOrCreateDek` never mints over an auth-gated blob). New
    pure-JVM `services/DekAtRestPolicy.kt` is the decision table, wired as
    `NoteflowViewModel.enforceDekAtRestPolicy()` in `setMasterPassword`, `changeMasterPassword`,
    `verifyMasterPassword` (every password unlock), `verifyBiometricsAndUnlock` and
    `setBiometricEnabled`: biometrics OFF ⇒ `security.clearDek()` (only at-rest wrapper = the
    password-derived KEK in settings); biometrics ON ⇒ repersist ONLY `authRequired = true`
    (biometric-gated). The pre-fix `setBiometricEnabled(false,…)` path that re-wrapped non-auth is
    gone. `SecurityService(context)` call sites now use `SecurityService.forDevice(context)`.
    Tests: `DekAtRestPolicyTest` (4) + `B1Crypto02DekAtRestTest` (9, incl. a source-level wiring
    pin); 978 unit tests green + `assembleDebug` green.
  - **Phase-45 review fixes** (see `workspace/phase-45/REPORT.md` "Addendum 2"): a locked open
    never reaches the mint path — `NoteflowSqlcipherFactory.create` reads
    `SettingsManager.hasMasterPassword` and calls `getOrCreateDek(allowPasswordlessMint = !…)`
    (`data/db/NoteflowDatabase.kt:345-362`), so no fresh non-auth DEK is ever minted/dropped into
    prefs over a password-protected vault (which would also open with the wrong SQLCipher key and
    trip the phase-43 quarantiner). `storeDek`/`clearDek`/`DekDeviceStore.clear` now report
    success/failure; `enforceDekAtRestPolicy()` returns it and `setBiometricEnabled` commits the
    setting only when the at-rest blob was actually written/cleared (reverts on failure). Tests:
    B1Crypto02DekAtRestTest now 12 (+3); 981 unit tests green + `assembleDebug` green.
  - **Implemented in phase-47** (B1-AUTH-02, see `workspace/phase-47/REPORT.md`): the lock is
    enforced at the DATA LAYER. `NoteflowViewModel.lock()` (`NoteflowViewModel.kt:2543-2549`) now
    cancels the section/page observer jobs and calls `NoteflowDatabase.dispose()` so NO keyed
    SQLCipher connection survives a password-vault lock (previously only `VaultKeyHolder` was
    zeroized). `NoteflowSqlcipherFactory.create` (`data/db/NoteflowDatabase.kt:343-371`) routes a
    `dek == null` open through the pure-JVM `services/LockedOpenGuard.kt`: a password-protected
    vault with no in-memory DEK THROWS `"Vault is locked: database key not available"` BEFORE any
    `getOrCreateDek()`/persisted-copy access (a passwordless vault still re-reads its
    device-wrapped copy — the boot credential by design). Explicit unlocks
    (`verifyMasterPassword` `:2080`, `verifyBiometricsAndUnlock` `:2220`) reinstate the live
    connection via `reinstateDatabaseAfterLock()` (no-op unless `lock()` disposed it) BEFORE the
    dbGate flows flip on, and an open failure there is zeroized — never counted as a wrong
    password. `onCleared()` also disposes. `databaseDisposedByLock` + `dataInitialized=false` let
    the next unlock re-establish observers against the fresh connection.
  - **Implemented in phase-48** (B2-LOG-01, see `workspace/phase-48/REPORT.md`): crash logging is
    single-owner. `utils/AppStartupLogger.kt` is a startup-EVENT timer only — it no longer installs
    an `UncaughtExceptionHandler` and its raw `logCrash` (`printStackTrace` → `Log.e` = unredacted
    vault paths / note-title filenames to logcat) is deleted; its `Log.e` failure paths no longer pass
    the exception object. `services/PrivacyCrashReporter.kt` is the SOLE crash handler; every crash
    entry flows through the pure-JVM `PrivacyCrashReporter.crashLogEntry` (`:64`, sanitized message +
    scrubbed `class.method(file:line)` frames) and the uncaught path writes to the local file only —
    never logcat. Repo-wide pin: `setDefaultUncaughtExceptionHandler` appears only in
    `PrivacyCrashReporter.kt`. Path redaction (B1-PLAT-5) is also closed here:
    `sanitizeMessage` (`:91-93`) redacts ANY `/data/user/<uid>/...` or `/data/data/...`
    path — covers both the namespace and the real applicationId dir. Tests:
    `B2Log01CrashReportingTest` (7) — 1020 unit tests green.
  - **Implemented in phase-49** (B2-UI-1, see `workspace/phase-49/REPORT.md`): the WRITE side of the
    lock boundary fails closed. Every editor page-write now routes through the ViewModel lock-safe
    gate: `NoteflowViewModel.flushEditorPageSave`/`autosaveStrokes`/`saveLayersGated` + private
    `persistOrDefer` (`NoteflowViewModel.kt:2302/2325/2341/2362`) decide persist-vs-defer via the new
    pure-JVM `services/VaultWriteGate.kt` (`requireKey` throws `VaultLockedWriteException` on a
    zeroized DEK; `persistNow` = the persist-vs-defer decision). Locked flushes are stashed in the
    latest-wins `services/EditorFlushPolicy.kt` (`defer`/`drain`) and re-written ENCRYPTED by
    `flushPendingEditorSaves()` (`:2395`) in BOTH unlock paths (`:2120`, `:2242`) — never dropped,
    never plaintext, never crash. `NoteRepository.kt` uses `requireEncryptionKey()` (`:44`) in every
    encrypted-column write (`updatePageBody`, `createPage`, `renamePage`, `updatePageTitleAndTags`,
    `saveStrokesForPage`, `saveMediaEmbedsForPage`, `createNoteVersion`) and the
    `encrypt-or-plaintext` elvis/else fallbacks are grep-verified gone. `EditorScreen.kt` has no
    direct `viewModel.repository.save*` call sites (reads only); `createNoteVersion` is rejected
    while locked. Reads remain direct through the live repository — B1-AUTH-02 governs the read side,
    B2-UI-1 the write side.
  - **Phase-49 review fix (2026-08-15)**: (1) the non-flush page writes
    (`applyWorkspaceTemplate`, `addPage`, `createNoteFromSharedContent`, `renamePage`,
    `updatePageTitleAndTags`, `autoTagLanguageOnSave`, `openOrCreateDailyNote`, `openPageByTitle`)
    now route through `NoteflowViewModel.writeGuardedAgainstLock` / `isLockRacedWrite`
    — a lock racing the create/rename no longer crashes (bare TOCTOU guard), it surfaces a
    non-alarming snackbar. (2) `saveMarkdownNoteBody` is lock-safe now too: `EditorFlushPolicy`
    gained a `DeferredBody` stash (`deferBody`/`drainBodies`), so a body whose save races a lock is
    re-written ENCRYPTED after the next unlock instead of being dropped behind an error snackbar;
    the legacy plaintext file delete follows the encrypted-column write in the flush. (3)
    `createNoteVersion` lock-rejection now shows a notice instead of a silent drop.
    (4) KNOWN TRADE-OFF: the deferral stashes live in VM memory only — a process kill during a
    locked interval loses the last stashed page delta/body. A durable pending-queue is impossible
    without writing the data to disk while locked (i.e. plaintext), which is exactly what this
    finding forbids, so the in-memory stash is deliberate and bounded (latest-wins per page).
- **Canvas**: `ui/components/AnnotationCanvas.kt:83` (ink canvas, gestures, layers, `pointerInteropFilter`);
  `services/WetBrushEngine.kt:13` (AGSL wet-mixing gating); `ui/components/ShaderCapabilityHelper.kt:5`
  (`isAgslSupported` = SDK ≥ 33); `services/ShapeRecognitionHelper.kt:13` (`trySnapShape()` :27).
  Supporting math: `WetCanvasEngine.kt`, `WetMixingMath.kt`, `BrushStrokeMath.kt`, `StrokeStabilizer.kt`.
- **Plugins**: `plugin-sdk` → `plugins/FrameworkPlugin.kt:58` (`interface NoteflowPlugin`),
  `plugins/PluginCapability.kt:28` (sealed capability set); `plugins/PluginRegistry.kt:75`,
  `plugins/PluginManager.kt:83`; store: `plugins/store/PluginStoreCatalog.kt:57`, `PluginStoreController.kt:45`.
- **Downloadable runtime**: `plugins/runtime/RuntimePluginLoader.kt:68`; `services/AppClassLoaderFactory.kt:23`
  (`DexClassLoader`); `services/AppFacadeHost.kt:27` (deny-by-default facade, NO direct DB/keystore handles);
  `plugins/runtime/PinnedCertHash.kt:25`; `plugins/runtime/ArtifactSignatureVerifier.kt:52`.
  - **Implemented in phase-46** (B1-AUTH-01, see `workspace/phase-46/REPORT.md`): plugin bytecode no
    longer resolves app-private classes AND artifacts that merely mention them are rejected before any
    bytecode materializes. `plugins/runtime/PluginFrameworkClassLoader.kt:45` — a scoped parent between
    the plugin DEX and the app classloader: every `com.authorss81.noteflow.*` class OUTSIDE the
    `plugins.*` framework surface throws `ClassNotFoundException` (`isAppPrivateForbidden`,
    `PluginFrameworkClassLoader.kt:70-71`); the same check blocks `Class.forName(...)` reach-through;
    `java.*`/`javax.*`/`android.*`/`kotlin.*`/third-party classes still delegate. Wired in
    `services/AppClassLoaderFactory.kt:34`. `plugins/runtime/ArtifactStaticScan.kt` (pure JVM) runs
    inside `ArtifactSignatureVerifier.verify` (`ArtifactSignatureVerifier.kt:76-81`) — the single funnel
    for install / every load re-verify / update / rollback — and rejects app-private package prefixes
    (`services|data|ui|theme|utils`, slash+dot), bare secret-bearing class names (`VaultKeyHolder`,
    `EncryptionService`, `NoteflowDatabase`, `SettingsManager`, `NoteRepository`, `SecurityService`) and
    raw `java.net`/`javax.net.ssl` egress primitives, parsing `.class` constant pools + DEX string/type
    tables structurally. Phase-46 review additions to the scan: net-egress + `ProcessBuilder`/`Runtime`
    classes matched in slash AND dot form (a `Class.forName("java.net.HttpURLConnection")` reflection
    literal is refused too), sensitive class names matched as whole tokens (no false-positive on a
    benign plugin's own compound identifiers), and a source-level pin test holds the invariant that
    `plugins.*` host code (the artifact-resolvable surface) never references a vault-handle type.
    Native (`System.loadLibrary`) / `sun.misc.Unsafe` gating and a separate `:remote` process remain
    out-of-scope (future isolation phases), noted in the phase-46 REPORT. Tests: `PluginBytecodeIsolationTest` (20).
  - **Implemented in phase-39**: update-manifest + artifact transports share
    `plugins/runtime/PinnedTlsConnector.kt` (`open` pins the leaf via constant-time
    `PinnedCertHash.matches`, `instanceFollowRedirects = false`; 3xx refused in both
    `PluginManifestFetcher.kt` `HttpsManifestTransport:109` and
    `HttpsPluginDownloadTransport.kt:55`). The manifest host is allow-listed
    (`HostedPluginManifest.kt:197 DEFAULT_MANIFEST_HOST`) and pinned to the
    compile-time `PLUGIN_MANIFEST_CERT_PIN` (`:220`, placeholder pending operator
    substitution; fails closed without it), so update offers can never redefine
    `downloadUrl`/`sha256`/`pinnedCertHash` from an unauthenticated source
    (closes B1-CRYPTO-01, commit `4d72a6a`).
  - **Implemented in phase-42** (B1-NET-03, see `workspace/phase-42/REPORT.md`): the
    per-plugin **update trust anchor now lives in the APK**, not the manifest.
    `plugins/runtime/CompileTimePluginPinStore.kt` (`CompileTimePluginPinStore`,
    `PinnedPluginRelease`, `PinVerdict`, `CompileTimePluginPins`, `isHostAllowListed`)
    carries `id → version → {sha256, pinnedCertHash}` release pins + a download-host
    allow-list (`DEFAULT_DOWNLOAD_HOSTS = {DEFAULT_MANIFEST_HOST}`); the production
    `CompileTimePluginPins.RELEASES` is empty ⇒ **fail closed** (publishing a
    downloadable plugin REQUIRES adding its pin rows here + an app bump). Enforced at
    three independent gates: `PluginUpdateChecker.check` offers only compile-time-pinned
    values (`PluginUpdateChecker.kt`, new `pins` arg), `PluginUpdateEngine.update`
    re-verifies the persisted target before any byte moves / rollback-root write
    (`PluginUpdateEngine.kt:110-116`), and `PluginDownloader` refuses artifact hosts off
    the allow-list before connecting (`PluginDownloader.kt`, `allowedDownloadHosts`).
    `PluginStoreController` threads the pins into both check calls.
- **Markdown**: `ui/screens/MarkdownPreviewScreen.kt:137` (renders via **commonmark 0.29.0 +
  gfm-tables**). Phase 37 hybrid-editor slice: pure-JVM block tokenizer
  `services/MarkdownBlockTokenizer.kt` (exact source round-trip), code-span-aware
  inline-math scanner `services/MarkdownInlineMath.kt`, waveform decimation
  `services/WaveformPeakMath.kt`, and the shared renderer + editor
  `ui/components/markdown/MarkdownRenderer.kt` + `HybridMarkdownEditor.kt`
  (replaces the raw text field in EDIT/SPLIT panes; typed callouts + interactive
  checkboxes; `AnimatedCheckmark.kt` respects reduce-motion).
- **Knowledge graph**: `ui/screens/KnowledgeGraphScreen.kt`
  (Phase 38 rewrite — deterministic force-directed layout, cluster colouring, tag
  filter chips, link pulses, collision bounding, low-RAM cull + notice) built on
  `services/graph/GraphLayoutMath.kt` (forces `GraphPhysicsConfig`, layout +
  collision `GraphLayoutMath`, clusters `assignClusters`, tiers
  `GraphTierSelector`) and `services/WikiLinkParser.kt:66` `buildWikiLinkEdges`
  + `:373` `buildTagHierarchy`. Serverless tier detection: `utils/DeviceCompatibilityManager.kt`.
- **Command Palette (Phase 38 HUD)**: `ui/components/CommandPaletteOverlay.kt`
  (global quick-switcher; two-finger swipe down in `MainActivity.kt`
  `detectTwoFingerSwipeDown`, keyboard icon in `HomeScreen.kt`), ranking/tag
  combination/action routing in `services/graph/CommandPaletteMath.kt`, search +
  plugin-action execution in `NoteflowViewModel.commandPaletteSearch` /
  `runPaletteAction` over the cached decrypted corpus
  (`NoteRepository.cachedCorpus`, generation `currentSearchCorpusGeneration`).
- **WebDAV sync**: `services/WebDavSyncService.kt:28` (encrypted vault archives, HTTPS enforced).
  - **Implemented in phase-40**: server-supplied PROPFIND hrefs are re-resolved against the
    configured server origin by the new pure-JVM `services/WebDavHrefResolver.kt`
    (`resolveDownloadHref`), and EVERY connection is origin-gated in `createConnection`
    (`WebDavSyncService.kt:147`) before the `Authorization: Basic` header is attached
    (`:164`), with `instanceFollowRedirects=false` (`:158`). Off-origin/private-IP hrefs
    and 3xx redirects are refused with a clear `SyncResult(false, "Sync refused: ...")` —
    closes B1-NET-01 + the WebDAV slice of B1-NET-05 (see `workspace/phase-40/REPORT.md`).
- **LocalSend**: `services/localsend/LocalSendProtocol.kt:29`, `LocalSendSender.kt:48`.
  - **Implemented in phase-41**: confirmed-pairing gate for sends. Pure-JVM
    `services/localsend/LocalSendPairing.kt` (`gate` = HTTPS-only +
    fingerprint-present + TOFU-paired, `startPairing` derives a 6-digit out-of-band
    code + formatted fingerprint, `confirmPairing`/`pair` persist a constant-time
    verified TOFU anchor), stores `InMemoryLocalSendPairedDeviceStore` (tests) +
    `SettingsLocalSendPairedDeviceStore` (SharedPreferences `localsend_paired_<fp>`).
    `LocalSendSender.sendFile` refuses unpaired/http receivers before any I/O
    (`:313-326`) and pins every payload connection to the STORED paired
    fingerprint (`trustedFingerprint` `:325`), never the wire-announced one;
    `openConnection` refuses non-https payload URLs; the announce never says
    `protocol:"http"`. `LocalSendSendDialog` shows a pairing sub-view that
    requires either a verification code typed from the receiving device
    (constant-time checked, mismatch refuses) or an explicit "fingerprints match"
    acknowledgement, plus a per-send confirmation; `200` to `/prepare-upload` is
    zero evidence of consent.
- **Palette**: `services/PaletteCatalog.kt:131` (swatches + `familyFor`), `PaletteMath` :24.
- **Brush preview**: `ui/components/PenNibVisualPreview.kt:50` (driven by `services/NibPreviewMath.kt`).
- **Glass theme**: `theme/GlassSurfaces.kt:44` (`GlassBlurGate`), :80 (`GlassSurfaceMath`), :140
  (`FrostedGlassSurface`), :192 (`innerLuminescence`); `theme/Motion.kt:50` (`MotionSystem`, `LocalReduceMotion` :16);
  `theme/Type.kt:70`; `theme/Theme.kt:239` (`NoteflowTheme`, dynamic + paper/sepia/dark/AMOLED).
- **ViewModel/nav**: `ui/viewmodel/NoteflowViewModel.kt:105` (builds SecurityService/NoteRepository/PluginRegistry
  :121/PluginManager :131/PluginRuntime :170/PluginStoreController :196; ~60 capability suspend fns);
  `MainActivity.kt:73` (single activity, **`mutableStateOf` nav** — NOT Navigation Compose).
- **Import/export**: `services/ImportExportService.kt:30` (encrypted backup/restore, `validateBackupPassword`,
  PDF/HTML/image export).

## Build / CI essentials

- `app/build.gradle.kts`: `namespace = "com.authorss81.noteflow"` (:11),
  `applicationId = "com.aistudio.inkflow.app.bkxjrz"` (:15), compileSdk 36, minSdk 26, JVM 17,
  Room schema → `app/schemas`, R8 minify on for release, `jniLibs.useLegacyPackaging = true`.
- **No Gradle wrapper** — use system `gradle`. Tests: `gradle testDebugUnitTest`; build: `gradle assembleDebug`
  / `assembleRelease`. Runs in GitHub Actions (gradle 8.13, JDK 17).
- Tests: `app/src/test/java/com/authorss81/noteflow/` (~110 unit tests, pure JVM, no androidTest).
- **Do NOT run Gradle on the Windows dev machine** (no SDK; CI-only builds).
- Version: `VERSION_CODE`/`VERSION_NAME` env (default 2 / "1.0.0"); release falls back to debug keystore.
  - **Implemented in phase-32** (APK attack, see `workspace/phase-32/REPORT.md`): the release APK was built and audited with apktool/jadx/androguard/APKiD/strings/apksigner/readelf. Confirmed at binary level: release release signing is the well-known Android **debug** cert (`CN=Android Debug`, SHA-256 `81a2980a…`, v2-only scheme — B1-PLAT-1 + new Phase-32-NEW-03); base APK bundles an **80.2 MB packed `language-models/` n-gram pack (~199 MB raw = 56% of the 142 MB release APK) that is the compile-time `lingua` language-detection library's corpus** (Phase-32-NEW-01 — identical byte-for-byte to the lingua JAR; note the review corrected the initial "ML Kit translation models" attribution: ML Kit translate models are runtime-downloaded, only its `libtranslate_jni.so`/`libmlkit_google_ocr_pipeline.so` natives are baked in) despite the downloadable-plugin hard rule; no ABI splits (Phase-32-NEW-02); plugin-manifest cert pin is still the placeholder `sha256/AAECAwQFBgcI…` so hosted plugin updates fail closed until the operator substitutes the real pin (Phase-32-NEW-04, B1-CRYPTO-01 fix wiring verified). Positives re-verified: release not debuggable, FLAG_SECURE wired, allowBackup=false, R8 ON, no tasks-genai/GGUF in base, no hardcoded secrets in 1M+ strings.
- **Implemented in phase-32 review fix (2026-08-15)**: `scripts/phase_runner.sh` only writes a phase's `.done` if the `opencode run` left working-tree changes outside `logs/` + the phase's own markers (`tree_work`/`has_new_work` in `phase_runner.sh`). A zero-work run (opencode exit 0 with no delta — the phase-32 false completion at commit `6b17422`) counts as a failed attempt and leaves a `.no_work` marker; phase-32's bogus `.done` was removed so the pipeline re-selects it. **Second fix (same day)**: normal-run mode now also short-circuits when `.done` already exists (`phase_runner.sh` "Already-done guard") and clears stale failure markers (`.deferred`/`.no_work`/`.session`/`.deferred_attempts`/`.attempts`), so a completed phase is never re-run — phase-32 had been re-selected after completion, leaving contradictory `.no_work`+`.deferred` alongside `.done` (commits `44a7210`+`27b93fd`); those stale markers are now removed.

## Libraries

AGP 8.7.3 · Kotlin 2.0.21 · KSP · Compose BOM 2024.12.01 · Room 2.6.1 over SQLCipher 4.9.0 ·
androidx.ink 1.0.0 · ML Kit text-recognition 16.0.1 + translate 17.0.3 (base) ·
**MediaPipe tasks-genai 0.10.25 = downloadable plugin only, NEVER base APK** ·
commonmark 0.29.0 + gfm-tables · Lingua 1.2.2 · jsoup 1.17.2 · Coil 2.7.0 · Gson 2.11.0 ·
androidx.biometric 1.1.0 · coroutines 1.9.0.

## Known broken / gotchas (agent must know)

1. `applicationId = "com.aistudio.inkflow.app.bkxjrz"` vs `namespace = "com.authorss81.noteflow"`
   (intentional mismatch; AGENTS.md's applicationId value is stale — trust the build file).
2. No Gradle wrapper; CI pins gradle 8.13. Local machine can't build.
3. ROADMAP.md `[x]` claims are not all true — trust `AGENTS.md` + `docs/phase-status.md` truth tables.
4. **Base-APK size is a hard constraint**: heavy native libs (tasks-genai LLM, heavy OCR) MUST stay
   downloadable plugins. Never add them to the base app.
5. `extractNativeLibs="true"` (`useLegacyPackaging=true`) required for SQLCipher `.so` on SDK 36 (16KB pages).
6. `allowBackup="false"` + data-extraction rules — never re-enable. FLAG_SECURE in non-debug.
7. Baseline profiles disabled (AGP bug); unit tests use `isReturnDefaultValues = true` (no Robolectric).
8. `INTERNET` used only by WebDAV sync + LocalSend. WebDAV HTTPS-only unless local-network opt-in.
9. Duplicate `WikiLinkParser` (`utils/` vs `services/`) — `services/` is the one screens use.
10. Two plugin-state persistence layers exist (`SettingsPlugin*Store.kt` vs `plugins/runtime/Plugin*Store.kt`) —
    keep them in sync when changing install/update state.

## Phase status truth table
See `docs/phase-status.md` — per-phase `DONE`/`PARTIAL`/`NOT STARTED` with verified commit evidence.
`docs/phase-status-gaps.md` lists deferred sub-items.