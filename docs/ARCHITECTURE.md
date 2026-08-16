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
| `services/` | `EncryptionService.kt`, `SecurityService.kt`, `DatabaseSecurityHelper.kt`, `VaultKeyHolder.kt`, `WetBrushEngine.kt`, `WebDavSyncService.kt`, `ImportExportService.kt`, `ImportArchivePolicy.kt`, `PaletteCatalog.kt`, `ShapeRecognitionHelper.kt`, `SsrfHostPolicy.kt`, `VoiceNoteCrypto.kt`, `DecryptFailurePolicy.kt` | Non-UI: crypto/vault, brush math, sync, import/export, zip-import zip-bomb policy (B1-DB-5), palette, SSRF blocklist (B1-NET-04), voice-note audio cryptor (B1-DB-3), decrypt-failure render decision (B1-DB-8) |
| `services/localsend/` | `LocalSendProtocol.kt`, `LocalSendSender.kt`, `LocalSendPairing.kt`, `SettingsLocalSendPairedDeviceStore.kt`, `LocalSendDiscoveryPolicy.kt` | Pure-JVM LocalSend v2.2 + real network sender + TOFU pairing gate (B1-NET-02) + discovery/sweep gate (B1-NET-06) |
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
  - **Verified in phase-89** (B1-PLAT-5, verify-only — see `workspace/phase-89/REPORT.md`):
    the path-redaction fix above was re-confirmed against the finding's real
    applicationId (`com.aistudio.inkflow.app.bkxjrz`, `app/build.gradle.kts:15`). The
    generic `/data/user/\d+/...` + `/data/data/...` rules in `sanitizeMessage`
    (`PrivacyCrashReporter.kt:94-95`) cover BOTH the namespace and the runtime app-package
    dir — pinned by `B2Log01CrashReportingTest:80-90` (`crash entry redacts the real
    runtime applicationId data dir too`). No code change was required or made.
  - **Implemented in phase-70** (B2-LOG-02, see `workspace/phase-70/REPORT.md`):
    `app_startup.log` is capped, rotated and pruned — the pre-fix append-only
    `FileWriter(logFile, true)` (no length check / rotation / delete, unbounded growth on
    the vault's partition) is gone. New pure-JVM `services/StartupLogPolicy.kt` is the
    single decision table: `LOG_FILE_NAME`/`BACKUP_SUFFIX=".1"`, `MAX_LOG_BYTES = 500_000L`
    (same ~500KB budget `PrivacyCrashReporter` uses), `MAX_LOG_FILES = 2`, plus
    `wouldExceedCap` (the BEFORE-write rotate decision), `rotateForAppend` (keep-last-N:
    drop the oldest `.1`, promote the active file) and `pruneOnInit` (clears any leftover
    over-cap file). `AppStartupLogger.appendToFile` now gates the write through the policy
    (`AppStartupLogger.kt:71-80`), `init` prunes on the background executor, and the dead
    `getLogs`/`clearLogs` accessors are removed. Active log never exceeds the cap; total
    retention bounded at 2 × 500KB. Tests: `B2Log02StartupLogRotationTest` (14) — 1350
    green (only the 2 pre-existing B1Plat01ReleaseSigningTest asserts + 1 documented
    WikiLinkParserCacheUnitTest flake that passes in isolation).
  - **Implemented in phase-71** (B2-LOG-03, see `workspace/phase-71/REPORT.md`):
    import/export failures never reach logcat as full throwables. The eleven
    `Log.e("ImportExportService", "...", e)` call sites in `ImportExportService.kt`
    (which passed the exception OBJECT, so logcat got path-carrying messages
    embedding note-title filenames under `filesDir/noteflow/imports/` — a
    bypass of PrivacyCrashReporter) are now 2-argument `Log.e` calls whose
    message is built by the new pure-JVM `services/FailureLogPolicy.kt`
    (`safeLogMessage(e, operation)` = FIXED operation label + `classNameToken(e)`
    = the exception's simple class name only; `e.message`/stack are never read).
    Tests: `FailureLogPolicyTest` (8, incl. a mechanical source pin: every
    `Log.(e|w)` call in the file has exactly TWO arguments and routes through the
    policy) — 1359 green in the final run (only the 2 pre-existing
    B1Plat01ReleaseSigningTest asserts + 1 documented WikiLinkParserCacheUnitTest
    flake that passes in isolation); `gradle :app:assembleDebug` green.
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
  - **Implemented in phase-74** (B2-UI-5, see `workspace/phase-74/REPORT.md`): the markdown-body
    save+read ARE serialized and latest-wins now, closing the last non-atomic body path (the old
    dispose-flush `File.writeText` / `produceState` `File.readText` truncate-race is gone; bodies
    live only in the field-encrypted `pages.extractedText` column). New pure-JVM
    `services/MarkdownBodySaveCoordinator.kt` serializes every body write through a per-page
    `kotlinx.coroutines.Mutex`, stamps a monotonic `seq` at `issue(...)` time, and `commitLatest`
    refuses (never runs the write; settles so awaiters release) any request that is no longer the
    latest issued one — the latest-wins comparator is issue-ORDER, never time-based, so touching a
    page before a slow older write can never let a stale snapshot win. `saveMarkdownNoteBody`
    (`NoteflowViewModel.kt:2214-2278`) issues on the calling (UI) thread, then
    `commitLatest { repository.updatePageBody(...); NoteBodyVaultPolicy.deleteLegacyNoteTextBody(...Defaults.importsDir) }`
    on `Dispatchers.IO`; the new `readMarkdownNoteBody` (`:2291-2336`) awaits settle + RE-FETCHES
    `repository.getPageById` (never a stale flow snapshot; deflates to the in-memory snapshot only
    on a transient decrypt failure so a key-lost race can never surface as an empty editor that gets
    saved over the real body); `flushPendingEditorSaves` (`:2929-2988`) re-issues each deferred body
    on the calling thread BEFORE any write. Both markdown `produceState` blocks (`MainActivity.kt:438/536`)
    now read `viewModel.readMarkdownNoteBody(page.id, …snapshot fallbacks)` — MainActivity no longer
    resolves note bodies or touches body files at all. B2-UI-1 lock semantics (defer, never drop)
    and B1-AUTH-05 imports-root confinement are retained.
  - **Implemented in phase-50** (B2-DOS-01, see `workspace/phase-50/REPORT.md`): stroke geometry is
    bounded at EVERY hop between DB bytes and the composable, so a crafted backup (B1-DB-7) or an
    organic heavy page can no longer OOM/ANR page-open or scale renderer work linearly. New pure-JVM
    `services/StrokeGeometryPolicy.kt` owns the budgets (20k pts/stroke, 200k pts/page, 2k strokes/
    page; per-stroke plaintext cap 2.5M chars, stored base64-ciphertext cap 3.4M chars — AES-GCM
    doesn't compress, so ciphertext length is an exact proxy) + `applySaveGate`/`gateStroke`/
    `capLoadedPoints`/`storedPointsJsonOverBudget`/`plaintextPointsJsonOverBudget`.
    `NoteRepository.saveStrokesForPage` (`:793`) routes every write through the gate (returns a
    `StrokeGeometryGateResult`); `getStrokesForPage` (`:636`) pages through the new
    `StrokeDao.getStrokesForPageBounded` (`Daos.kt:181`, `WHERE length(pointsJson) <= :maxStoredChars
    … LIMIT :limit OFFSET :offset`), refuses over-budget plaintext pre-Gson, caps legacy over-specified
    strokes and stops at the page budget. `EncryptionService.deserializeStrokes` (`:470`) guards the
    parse length; `ImportExportService.sanitizeRestoredStrokeGeometry` (`:1674`) DELETEs over-budget
    stroke rows from a restored backup DB before re-key/migrate/transplant; `NoteflowViewModel` shows
    ONE non-alarming snackbar per page per session via `maybeNotifyGeometryCapped` (latch cleared on
    lock); `AnnotationCanvas.kt:1415-1434` culls pages whose slab misses the visible world rect
    `(screen − pan)/zoom` in paginated mode. Tests: `B2Dos01StrokeGeometryTest` (18) — 1053 green.
  - **Implemented in phase-73** (B2-UI-3, see `workspace/phase-73/REPORT.md`): the shared
    `NoteRepository.lastSavedStrokeHash` diff cache and per-page saves are serialized, closing the
    concurrent-save interleave (older hash commit landing last drops the newest stroke / interleaved
    delete+upsert rounds drop rows / `ConcurrentModificationException` on the access-order
    `LinkedHashMap`). `lastSavedStrokeHash` (`:911-912`) is now
    `Collections.synchronizedMap(LruBoundedMap<…>)` (per-op atomic, B2-DOS-10 LRU bound preserved);
    new `pageSaveLocks = ConcurrentHashMap<String, Mutex>()` (`:944`) gives each page ONE fair/FIFO
    kotlinx `Mutex` that `saveStrokesForPage` (`:986-988`), `saveMediaEmbedsForPage` (`:1165-1167`)
    and `saveLayersForPage` (`:1244-1246`) all acquire via `lock.withLock { … }` before their Room
    `withTransaction` — so the full strokes→embeds→layers snapshot is atomic per page and different
    pages stay concurrent. `NoteflowViewModel.disposeEditorPageFlush` (`:2875-2892`)
    CANcels→joins the editor's debounce job then flushes the newest snapshot; `autosaveStrokes`
    (`:2907-2920`) is `suspend fun` so the write runs inline in the debounce job (cancellable +
    awaitable). `EditorScreen`'s dispose (`:443-451`) captures/clears the pending `saveJob` and
    routes through the VM helper. Tests: new `B2Ui3StrokeSaveConcurrencyTest` (11, behavioral model
    driven by the same `synchronizedMap`+per-page-`Mutex` primitives + source pins) — 1447 green, 0
    failures, `gradle assembleDebug` green. No schema change, no new deps.
  - **Implemented in phase-53** (B1-DB-2, see `workspace/phase-53/REPORT.md`): the plaintext→SQLCipher
    migration can no longer destroy the original plaintext database on failure.
    `NoteflowDatabase.migratePlaintextIfNeeded` (`:201-258`) swaps atomically — the encrypted scratch
    file is verified (exists, non-empty, no plaintext header) then `tempFile.renameTo(dbFile)` replaces
    the original via `rename()` (atomic on bionic/Linux), killing the old delete-then-rename window in
    which the user had NO database file; stale `-wal`/`-shm` are removed only after the verified
    encrypted file is in place. The catch block routes through the new pure-JVM `quarantineMigrateFailed`
    (`:487-510`): drops ONLY the scratch copy, preserves the original + `-wal`/`-shm`/`-journal` as
    `noteflow.sqlite.migrate-failed-<ts>`, and returns a timestamp so the caller raises the persistent
    corruption flag (`DatabaseSecurityHelper.setCorruptionDetected`) — the phase-43 recovery screen
    surfaces instead of silent data loss — then `throw e`.
  - **Implemented in phase-54** (B1-DB-3, see `workspace/phase-54/REPORT.md`): voice-note audio is
    encrypted at rest. `services/VoiceNoteCrypto.kt` (pure JVM): `*.enc` AES-256-GCM blobs under
    `filesDir/voice_notes/`, DEK + blob-name AAD `Noteflow-Voice-Note-v1|<name>` (blob-bound, never
    renamable), fail-closed encrypt/decrypt (`isEncryptedBlobName` on both paths), in-place re-key,
    legacy `.m4a` migration, orphan/temp sweeps, 40 MB blob cap. `VoiceNoteManager` records to a
    cacheDir temp → encrypt at stop; locked vault fails closed; playback decrypts to a transient
    cacheDir scratch deleted on stop/complete/release. `deletePagePermanently` (`NoteRepository.kt:635`)
    deletes AUDIO_NOTE blobs; `migrateLegacyPlaintextVoiceNotes` (`:667`) retargets rows via
    `MediaEmbedDao.updateContentUrlOrPath` (no schema change), gated on
    `SettingsManager.voiceNotesEncryptedMigrated`, WAL-checkpoints + re-stamps the DB HMAC first.
    `exportBackup` packs only `.enc`; restore re-keys blobs to the restoring device's DEK
    (`ImportExportService.kt:1708`). Tests: `B1Db03VoiceNoteEncryptionTest` (18) — 1129 green.
  - **Implemented in phase-79** (B2-DOS-03, see `workspace/phase-79/REPORT.md`): the voice RECORDER is
    bounded and the amplitude sampler is off-main. New pure-JVM `services/LiveWaveformBuckets.kt`
    (preallocated `FloatArray` accumulator, O(1)-amortized `append`, fold-on-full, `snapshot()` ≤
    `WaveformPeakMath.recordingLiveBuckets`=160) + `services/VoiceRecordingPolicy.kt` (decision table:
    30 min `MAX_RECORDING_DURATION_MS`, 32 MB `MAX_RECORDING_BYTES`, 100 ms tick,
    `MAX_STORED_WAVEFORM_ENTRIES`=600, non-alarming limit messages). `VoiceNoteManager` sampler runs on
    `Dispatchers.Default` (`VoiceNoteManager.kt:150`), appends `waveformBuckets.append(...)` + emits
    `waveformBuckets.snapshot()` (pre-fix `= _waveformAmplitudes.value + amp` full-list copy gone), and
    aborts past the duration/file-size ceilings via `finalizeRecording(limitMessage)` — stops+encrypts
    (B1-DB-3 path), surfaces `recordingError`, publishes `completedRecordingResult` so `EditorScreen`'s
    `LaunchedEffect` auto-attaches the audio embed through the shared `attachVoiceRecording` helper.
    `startRecording`/`stopRecording`/`finalizeRecording` are serialized under `recorderLock`.
    `NoteRepository.parseWaveformJson` (`:997-1009`) is bounded to 600 entries. Tests:
    `LiveWaveformBucketsTest` (9) + `VoiceRecordingPolicyTest` (6) + `B2Dos03VoiceRecordingTest` (12) —
    1429 app tests, only the 2 pre-existing `B1Plat01ReleaseSigningTest` failures.
  - **Implemented in phase-62** (B1-CRYPTO-03, see `workspace/phase-62/REPORT.md`): the master-password
    salt + wrapped-DEK pair is persisted atomically as ONE versioned blob
    (`services/MasterPasswordCredential.kt`, format `MPB1|<saltB64>|<wrappedDek>`, pure JVM). The old two
    independent SharedPreferences `.apply()` writes (`NoteflowViewModel.kt:1794-1795/1829-1830`) whose
    inter-write kill bricked the vault are gone: `SettingsManager.commitMasterPasswordCredential`
    (`SettingsManager.kt:101`) writes the blob + removes the two legacy keys in a single synchronous
    `commit()` (atomic temp-file+rename — a torn write leaves the previous complete blob) and returns the
    disk-acked result; the read accessor `masterPasswordCredentialOrLegacy` (`:84`) prefers the blob and
    falls back to the legacy pair so pre-fix vaults unlock until the next set/change migrates them.
    `setMasterPassword` (`:2094`) / `changeMasterPassword` (`:2145`) round-trip-validate the wrapped DEK
    before committing and abort (`return false`) before any in-memory state flips on commit failure.
    Tests: `B1Crypto03MasterPasswordAtomicTest` (7).
  - **Implemented in phase-63** (B1-CRYPTO-04, see `workspace/phase-63/REPORT.md`): NEW master passwords
    must clear the pure-JVM `services/PasswordStrengthPolicy.kt` (single decision table +
    `PasswordStrengthVerdict` with human-readable messages): ≥ 8 NFKC-normalized graphemes
    (`MIN_STRENGTH_GRAPHEMES` — stronger than the old 6 floor, still ≤ the 128 cap), no
    sequential/keyboard-row/single-run-repeat patterns, ≥ 3 distinct graphemes, and 3-of-4 class
    diversity for passwords < 12 graphemes (passphrases ≥ 12 pass on length alone). The policy judges
    the NFKC-normalized password (the exact bytes `EncryptionService.deriveKey` hashes, B2-CRYPTO-07).
    Authoritative gate in `NoteflowViewModel.setMasterPassword` (`:2071`) + `changeMasterPassword`
    (`:2134`, NEW password only) and surfaced with `verdict.message` by both Dialogs.kt master-password
    dialogs; unlock paths (`verifyMasterPassword`/`unwrapMasterDek`/`isMasterPasswordValid`) never
    strength-gate, so a pre-existing weaker vault keeps unlocking and rotating. The finding's
    "lockout is UI-only / vault only as strong as the password" caveat is documented in the policy KDoc;
    TEE-bound attempt gating / Argon2id remain tracked follow-ups (not introduced, no new deps).
    Tests: `B1Crypto04PasswordStrengthTest` (10).
  - **Implemented in phase-90** (B1-PLAT-8, see `workspace/phase-90/REPORT.md`): the strength floor is
    raised ≥ 10 NFKC-normalized graphemes (`PasswordStrengthPolicy.MIN_STRENGTH_GRAPHEMES` = 10, was 8)
    and common/prefix-suffix words are rejected (`isCommonPasswordVariant` — a widely-leaked base
    (`password`/`sunshine`/`letmein`/…) is refused reach-able whole or with only digit/symbol padding
    around it; structural, so genuine passphrases that merely contain a word keep passing). The policy
    KDoc + `docs/RELEASE.md` document explicitly that offline brute force on a copied vault is only
    mitigated by password ENTROPY, never by the on-device lockout (the 5-attempt UI lockout only
    throttles typing on-device). Enforced only at set/rotate; unlock never strength-gates, so
    pre-existing weaker vaults keep unlocking and rotating. Tests: `B1Crypto04PasswordStrengthTest`
    (now 17: 3 new B1-PLAT-8 cases incl. common-word rejection + documentation pin) +
    `B2Crypto04BackupPasswordTest` updated to the 10 floor (backups reuse the same bar).
  - **Implemented in phase-64** (B1-CRYPTO-05, see `workspace/phase-64/REPORT.md`): a stored DEK
    device wrapper that becomes undecryptable (AndroidKeyStore key lost/unreadable) is NEVER
    silently re-keyed. Pure-JVM `services/DekReadResult.kt` defines sealed `DekReadResult`
    (`NoBlob` / `Unlocked(dek)` / `AuthRequired` / `KeyLost(wrapperAlias)`) + typed
    `KeystoreKeyLostException(message, wrapperAlias)`. `SecurityService.readDekResult()`
    (`SecurityService.kt:162-186`) distinguishes "no blob stored" from "blob present but its
    wrapping key is gone" (the old `readDek()` collapsed both to `null`); `getOrCreateDek`
    (`:203-236`) THROWS `KeystoreKeyLostException` on `KeyLost` at every mint site and mints only
    from `NoBlob` + `allowPasswordlessMint` — the stored wrapper can never be overwritten by a
    fresh DEK, so the phase-43 quarantiner is never tripped for this cause. `storeDek` stamps a
    non-secret `wrapperAlias` + `wrapperVersion = 1` marker persisted/cleared by
    `SharedPrefsDekDeviceStore` (`dek_wrapper_alias`/`dek_wrapper_version`). Recovery UX:
    `NoteflowViewModel` gains a `_keystoreKeyLost` StateFlow gated into `dbGate` (third input
    alongside `_authenticated` + `_corruptionBlocked`), the passwordless init routes through
    `readDekResult` (Unlocked→use, NoBlob→mint, AuthRequired/KeyLost→recovery state — the old
    `var dek = readDek(); if (dek == null) mint()` collapse is gone), `initializeData`'s catch
    surfaces key-lost (not corruption) when the corruption flag is clear, `setMasterPassword`
    throws `KeystoreKeyLostException` on `KeyLost`, and two exits exist:
    `attemptKeystoreKeyLostRecoveryFromBackup` (validates the backup password BEFORE closing the
    DB, mints a fresh DEK in memory, imports the backup re-keyed into it, and persists the device
    wrapper ONLY AFTER the restore succeeds — a failed restore never overwrites the old wrapper)
    and `startFreshAfterKeystoreKeyLoss` (moves the old vault aside as
    `noteflow.sqlite.keystore-lost-<ts>` via `quarantineVaultFiles`, bytes preserved — never
    quarantined as corrupt). `MainActivity` renders the dedicated `KeystoreKeyLostScreen` between
    the corruption and restore screens. Tests: `B1Crypto05SilentRekeyTest` (16).
  - **Implemented in phase-65** (B1-CRYPTO-07, see `workspace/phase-65/REPORT.md`): the vault-DEK
    biometric AndroidKeyStore key is now ONLY ever created STRONG-bound (API 30+); on API 26-29 the
    biometric-lock feature is refused/downgraded. Pure-JVM `services/BiometricKeyBindingPolicy.kt`
    is the single decision table: `MIN_API_FOR_STRONG_BIOMETRIC_BINDING = 30`,
    `strongBiometricKeyBindingSupported(apiLevel)`, `refuseEnableMessage(apiLevel)` (non-alarming),
    and `PRE_30_BIOMETRIC_ONLY_VALIDITY_SECONDS = -1` — the ONLY pre-30 validity that excludes a
    device credential (AOSP: non-(-1) validity, incl. the default 0, maps to
    `HW_AUTH_PASSWORD | HW_AUTH_BIOMETRIC`, so a screen PIN satisfies a bare
    `setUserAuthenticationRequired(true)` key). Enforcement layers: `NoteflowViewModel.setBiometricEnabled`
    (`:2531`) REFUSES enabling below API 30 before the setting flips (one-shot `biometricRefusalMessage`
    StateFlow `:1106`); `enforceDekAtRestPolicy` (`:2194`) DOWNGRADES a legacy enabled state below API 30
    to password-only (setting off + `clearDek()`, never re-writes the weak-bound copy); `SecurityService.getOrCreateKey`
    (`:76-98`) binds any pre-30 auth key defensively via `setUserAuthenticationValidityDurationSeconds(-1)`;
    `getDecryptionCipher` (`:105-127`) + `getBiometricCipher` return null below API 30 (LockScreen falls
    back to the master password + `disableBiometricFallback()`). The finding's explicit API-level marker:
    `storeDek` stamps `DekDeviceBlob.wrapperApiLevel = Build.VERSION.SDK_INT` persisted as
    `dek_wrapper_api_level` — informational/auditable only, deliberately NOT read-gated (a pre-fix
    API-30+ blob carries marker 0 and MUST still unlock). `BiometricAuthHelper` now distinguishes
    "strong biometric available at prompt time" (`isBiometricAvailable`) from "key can be STRONG-bound"
    (`canCreateStrongBiometricBoundKey`); the settings dialog gates the switch on the latter and shows
    the refusal message. `DekAtRestPolicy.modeFor` gained `strongBiometricBindingSupported: Boolean = true`
    (3rd arg, default keeps 2-arg call sites compatible). Out of scope (documented, untouched):
    `WebDavCredentialStore`'s positive-duration pre-30 binding is the B1-NET-08 design, and the minSdk
    bump to 30 is a product decision. Tests: `B1Crypto07BiometricKeyBindingTest` (20).
  - **Implemented in phase-87** (B1-DB-6, see `workspace/phase-87/REPORT.md`): the tamper HMAC now
    authenticates `main + -wal` AND the banner dismissal is per-session. The pre-fix main-file-only
    inline loop in `DatabaseSecurityHelper.computeDatabaseHmac` (`DatabaseSecurityHelper.kt:50-65`)
    is replaced by the new pure-JVM `services/DatabaseHmacPolicy.kt`
    (`streamDbAndWal` `:42` streams `noteflow.sqlite` then its `-wal` companion through the same
    initialised `Mac`, returning total bytes consumed) — a WAL-only mutation committed between two
    checkpoints (the vault runs `JournalMode.WRITE_AHEAD_LOGGING`) is now detected at the next
    verification, and every baseline-arming site already checkpoints first or reads a closed raw
    file (the export/migration sites `NoteflowViewModel.kt:1348-1350`/`:1375-1377`/`:2466-2471`/`:3126-3128`,
    `HomeScreen.kt:529-531`/`:1320-1322`, the restore `rearmBaselineFromFile` at `ImportExportService.kt:1805`,
    and the migration stamp at `NoteflowDatabase.kt:250`), so a freshly armed baseline covers
    `(main + empty/absent wal)` with a cleanly-emptied WAL
    contributing byte-identical state to an absent one. `NoteflowViewModel.dismissDatabaseIntegrityWarning`
    (`NoteflowViewModel.kt:1106-1109`) no longer flips `databaseIntegrityCheckEnabled` and neither
    dismissal path touches the persisted `databaseIntegrityWarningDismissed` latch; the banner routes
    through the new pure-JVM per-session `services/IntegrityWarningDismissalGate.kt`
    (`integrityWarningDismissal.mayShow()` `:1091`, `.onDismiss` `:1107`, `.onReenable()` `:1115`),
    re-armed on every launch, and the checkbox is relabelled "Don't show again this session"
    (`MainActivity.kt:362`). Documented trade-offs: a process kill after a baseline arm leaves
    `-wal` frames that flag at the next launch (the intended detection flip-side), and a
    pre-phase-87 stored main-only checksum fails the first post-upgrade verify only when a leftover
    non-empty `-wal` is present at verify time — both surfaced as a per-session-dismissible banner.
    B1-CRYPTO-06's fail-open re-baseline at `verifyDatabaseIntegrity`
    `:147-152` untouched (own phase-91 finding; must account for the re-arm now hashing `main + wal`).
    Tests: `B1Db06WalCoverageAndDismissalTest` (16).
  - **Implemented in phase-88** (B1-DB-8, see `workspace/phase-88/REPORT.md`): decrypt-failure
    fallbacks never render RAW CIPHERTEXT as note content. Single pure-JVM decision table
    `services/DecryptFailurePolicy.kt` owns `render(storedValue, decrypted, isCiphertext)` — the
    ONLY render outcome (legacy plaintext verbatim, authenticated ciphertext's plaintext, or
    `UNREADABLE_MARKER` = "Unreadable (decryption failed)"), the structural classifier
    `isStructuralCiphertext` (keeps legacy plaintext rows out of the decrypt branch), the
    persistent-failure threshold (`PERSISTENT_FAILURE_THRESHOLD` = 10 DISTINCT records) and the
    non-alarming notices. Every `NoteRepository` display-field decrypt site routes through it:
    `getStrokesForPage` (`:946-960`, text via `decryptFieldForDisplay`, geometry via
    `decryptStoredGeometryOrBlank` — an unreadable row yields an EMPTY payload, never phantom ink or
    raw ciphertext into `deserializeStrokes`), `getMediaEmbedsForPage`, `getNoteVersions` and
    `decryptPageIfNeeded` (the pre-fix catch-all `catch { page }` that returned the page — encrypted
    title/body — unchanged is gone). Each failed auth while a DEK is present (a locked vault never
    records) is counted once per session in a deduped ledger (`NoteRepository.kt:79-99`); when the
    threshold is crossed `decryptFailureListener` fires once and `NoteflowViewModel.initializeDataCore`
    (`:1330-1343`) escalates to the existing corruption/restore event — `setCorruptionDetected` +
    `_corruptionBlocked` (recovery screen: restore-from-backup / re-key / start-fresh) plus a
    non-alarming `PERSISTENT_DECRYPT_FAILURE_NOTICE` snackbar, never silent degradation. The ledger
    + in-memory escalation are reset at every legitimate session boundary (`lock()`, re-key
    `changeMasterPassword`, WebDAV restore, and every `initializeData`), so a fresh unlock recounts.
    Phase-88 review fixes: the ledger is deduped per NOTE (`note:<pageId>`, `NoteRepository.kt:101-106`)
    so a single broken note — however many of its rows/fields fail — can never trip the threshold on
    its own; `decryptPageIfNeeded` no longer early-returns the raw page when the DEK is null (the
    `lock()` zeroize-before-dispose race now renders the marker, consistent with the other three
    sinks); and `loadSearchCorpus` drops undecryptable pages (`decryptPageOrNullForCorpus`, fails
    recorded only by the display reads, never a rankable marker).
- **Canvas**: `ui/components/AnnotationCanvas.kt:83` (ink canvas, gestures, layers, `pointerInteropFilter`);
  `services/WetBrushEngine.kt:13` (AGSL wet-mixing gating); `ui/components/ShaderCapabilityHelper.kt:5`
  (`isAgslSupported` = SDK ≥ 33); `services/ShapeRecognitionHelper.kt:13` (`trySnapShape()` :27).
  Supporting math: `WetCanvasEngine.kt`, `WetMixingMath.kt`, `BrushStrokeMath.kt`, `StrokeStabilizer.kt`.
- **Plugins**: `plugin-sdk` → `plugins/FrameworkPlugin.kt:58` (`interface NoteflowPlugin`),
  `plugins/PluginCapability.kt:28` (sealed capability set); `plugins/PluginRegistry.kt:75`,
  `plugins/PluginManager.kt:83`; store: `plugins/store/PluginStoreCatalog.kt:57`, `PluginStoreController.kt:45`.
  - **Implemented in phase-67** (B1-AUTH-03, see `workspace/phase-67/REPORT.md`): the plugin
    lifecycle is vault-lock-gated. `PluginRegistry` gained a pure-JVM pause/resume gate —
    `pauseLifecycle` (`PluginRegistry.kt:219`) tears down every live onEnable hook with
    `onDisable` + clears `enabledNotified`; `resumeLifecycle` (`:238`) re-fires hooks via
    `onProcessStart`; `onProcessStart` early-returns while paused (`:184`) and the `setEnabled`
    enable path is guarded by `!lifecyclePaused` (`:279`). `NoteflowViewModel`'s init block now
    boots the plugin layer ONLY for a passwordless already-authenticated start
    (`if (!settings.hasMasterPassword) startPluginLifecycle()`, `:258-272`); the new idempotent
    `startPluginLifecycle()` (`:285-312`) owns store re-materialization + hook firing, called
    from both unlock paths (`verifyMasterPassword` `:2489`, `verifyBiometricsAndUnlock` `:2643`);
`lock()` pauses the lifecycle + resets the flag (`:3204-3205`). No plugin code runs
    before unlock. Phase-67 review-fix (same commit): the gate now covers the whole
    live-Context surface, not just hook firing — `containedAvailability` reports
    `Unavailable` without invoking `plugin.availability(context)` while paused
    (every derived-state query + capability route fails closed on the LockScreen,
    incl. the previously out-of-scope post-lock dispatch), `setEnabled` disable +
    `uninstallPlugin` only fire `onDisable` for a plugin whose `onEnable` ran this
    process, `notifyConfigChanged` returns early while paused, the ViewModel's
    `refreshPluginStates()`/`testPlugin()` no-op while `pluginRegistry.isLifecyclePaused`
    (`:361`,`:381`), and `pluginLifecycleStarted` is `@Volatile` + double-checked
    (`synchronized`) so racing unlock paths can never boot the layer twice.
- **Downloadable runtime**: `plugins/runtime/RuntimePluginLoader.kt:68`; `services/AppClassLoaderFactory.kt:23`
  (`DexClassLoader`); `services/AppFacadeHost.kt:27` (deny-by-default facade, NO direct DB/keystore handles);
  `plugins/runtime/PinnedCertHash.kt:25`; `plugins/runtime/ArtifactSignatureVerifier.kt:52`.
  - **Implemented in phase-76** (B2-DEPS-04, see `workspace/phase-76/REPORT.md`): the downloadable-plugin
    SIGNING identity is no longer a public default or an ephemeral build-bred keystore.
    `plugins/llm/build.gradle.kts` deleted the hardcoded default signing password and the `keytool
    -genkeypair` fallback that minted a fresh self-signed JKS into `build/plugin-signing/` on every local
    build; the signing tasks now FAIL LOUDLY — a `gradle.taskGraph.whenReady` gate
    (`PLUGIN_SIGNING_TASK_NAMES = signPlugin|verifyPluginSignature|pluginMetadata`, mirroring the `:app`
    phase-57 release gate) plus `requirePluginSigningKeystoreB64()`/`requirePluginSigningStorePass()`
    throw a `GradleException` when `PLUGIN_SIGNING_KEYSTORE_B64`/`PLUGIN_SIGNING_STORE_PASS` are unset
    (`PLUGIN_SIGNING_KEY_PASS` optional — the key password defaults to the store password, never a
    committed constant). The dangling `:app:generateLlmPluginSeed` claim became a REAL task in
    `app/build.gradle.kts`: `dependsOn(":plugins:llm:pluginMetadata")` (fails without the signing env),
    validates the signed artifact's `sha256` (64-hex) + `pinnedCertHash` (`sha256/<base64>`), and
    rewrites the committed `app/.../plugins/runtime/GeneratedLlmPluginPin.kt` seed (`null` = fail-closed,
    no release pinned yet). `CompileTimePluginPins.RELEASES` folds that seed in via
    `buildReleaseTable(*listOfNotNull(llmPluginSeedRelease).toTypedArray())`, so the app's compiled-in
    pin can only ever match the ONE real CI key identity. A latent pre-existing bug — `signPlugin`'s
    `dependsOn(pluginSigningKeystore)` passed a `Provider<RegularFile>` as the task dependency (a
    Gradle hard error), so signing could never run even with a keystore — was fixed by materializing the
    keystore inside the task action instead. Positive path proven with a throwaway `/tmp` keystore (seed
    emitted the exact pin of the key that signed the artifact; pin reverted, never committed). Tests:
    `B2Deps04PluginSigningTest` (9).
  - **Implemented in phase-80** (B2-DOS-04, see `workspace/phase-80/REPORT.md`): `AppFacadeHost.httpGet`
    enforces its response-size cap DURING the read, never after `readBytes()` already slurped the whole
    body. New pure-JVM `services/FacadeHttpGetPolicy.kt` is the single decision table
    (`MAX_FACADE_GET_BYTES` = 10 MB, `READ_BUFFER_BYTES` = 64 KiB, `readCapped` — the bounded streaming
    loop mirroring `WebPageFetcher` — throws `ResponseTooLargeException` mid-stream on the first chunk
    that crosses the cap, so a chunked/unknown-length (Content-Length: -1) or slow-chunked response can
    never pin more than the budget + one buffer in heap). `AppFacadeHost.kt:91-94` routes every body
    read through `FacadeHttpGetPolicy.readCapped` (the dead post-check on `readBytes().size` and the
    private `MAX_FACADE_GET_BYTES` companion are gone); the early `contentLengthLong` header pre-check
    (`AppFacadeHost.kt:82-85`) stays, and the B1-NET-05 manual-redirect posture (`instanceFollowRedirects
    = false`, per-hop `StrictRedirectPolicy` re-validation) is retained so every redirect hop carries its
    own 10 MB budget. API-26+ floor, pure java.io, no new deps, no fallback needed.
    Tests: `B2Dos04FacadeGetStreamingCapTest` (7).
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
  - **Implemented in phase-66** (B1-CRYPTO-08, see `workspace/phase-66/REPORT.md`): the artifact-signer
    pin binds the FULL signer set — not a "last signed entry seen" cert — and the pinned cert must be
    currently usable. `ArtifactSignatureVerifier.collectSignerSet` (`ArtifactSignatureVerifier.kt:162`,
    replacing `findSignerCertificate`) force-verifies the JAR (`JarFile(verify=true)`) and rejects ANY
    unsigned non-META-INF entry (`:173`), any multi-signer entry, any archive mixing different signers
    across entries (`:189`, whole-chain comparison), and an EMPTY verified signer set (`:199`) — never
    a fallback to a last-seen value. "One signer" is judged PER SIGNER CHAIN, not per certificate:
    `singleSignerChain` (`:235`) splits the JAR verifier's leaf-first chain list on certificate
    boundaries (issuer-DN match AND a verifiable signature), so a single CA-issued signer (leaf +
    issuers) is accepted while a second signer's chain is detected as a boundary and rejected. New
    pure-JVM `plugins/runtime/SignerCertificatePolicy.kt` is the single decision table run by `verify()`
    (`:115`): `checkValidity(now)` rejects expired/not-yet-valid certs and a `KeyUsage` extension
    lacking the digitalSignature bit (bit 0) is rejected (absent extension = unrestricted, RFC 5280); a
    key-usage-invalid cert is also refused by the signer-set gate when the platform JAR verifier
    surfaces such entries with `null` certificates. Pin compare runs first (`:107`) so a wrong key
    reports the accurate "pinned certificate hash" reason. Pure JVM, API 26+ floor, no new deps. Tests:
    `B1Crypto08SignerSetTest` (19 — includes a CA-chain-signed positive control and synthetic
    `singleSignerChain`/`sameChain` decision-table tests).
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
  - **Implemented in phase-68** (B1-AUTH-04, see `workspace/phase-68/REPORT.md`):
    markdown inline-image destinations resolve ONLY inside an allowlisted
    app-private subtree. New pure-JVM `services/InlineImagePathPolicy.kt` is the
    single resolver behind `MarkdownInlineImage` (`ui/components/ImageViewer.kt:129-131`):
    absolute paths rejected outright, any `..` path segment (incl. backslash-aware
    `..\..`) rejected before file I/O, and the candidate must exist + be a
    non-directory whose canonical path is a STRICT descendent of the canonical
    `baseDir` (symlink escape refused) — the old `file.isAbsolute && file.exists()`
    / `File(baseDir, dest).exists()` accept branches are deleted, so a crafted
    note can no longer read-and-display arbitrary process-readable files; a
    blocked reference (absolute / `..`) is classified by
    `InlineImagePathPolicy.isBlockedDestination` and shown a distinct non-alarming
    "Image location blocked" note rather than echoed as "File not found", so
    out-of-subtree existence is not disclosed, and the decode path re-canonicalizes
    before reading (symlink-swap refused). Covers preview, split,
    and hybrid-editor panes via the single composable. Tests:
    `B1Auth04InlineImagePathTest` (14).
  - **Implemented in phase-69** (B1-AUTH-05, see `workspace/phase-69/REPORT.md`):
    a note's `pages.sourceFilePath` may only ever point inside the app-private
    imports root (`ImportExportService.getImportsDir(context)` =
    `File(filesDir,"noteflow/imports")`). New pure-JVM
    `services/SourceFilePathPolicy.kt` is the single confinement decision
    (`confine`/`isConfined`/`isBlocked`: blank/null and RELATIVE values refused,
    any `..` segment in either `/` or `\` refused before file I/O, canonical
    value must be a STRICT canonical descendent of the canonical root — symlink
    escapes refused; null/non-directory root fails closed). Enforcement is at
    every boundary: `ImportExportService.sanitizeRestoredSourceFilePaths`
    (`ImportExportService.kt:1794-1834`, run in `validateAndPrepareRestoredDb`
    `:1704-1708` right after `sanitizeRestoredStrokeGeometry`) NULLs
    `sourceFilePath`+`sourceFileType` for every unconfined restored row;
    `NoteRepository` owns `importsRoot` (constructor `:22`) and confines in
    `createPage`/`updatePageSource`/`migrateLegacyPlaintextNoteBodies`;
    `NoteBodyVaultPolicy.resolveBodyForDisplay`/`deleteLegacyNoteTextBody` and
    `WikiLinkParser.getFullTextForPage`/`readFullText` (full-text cache keyed by
    `(pageId, importsRootPath)`) only read/delete a CONFINED stored path, with
    every caller passing the root (both `MainActivity.kt:438/539` body reads,
    `DocumentTextExtractor`, VM `updatePageSource` + both unlock-flush deletes,
    KnowledgeGraph/Backlinks/TagExplorer builders, command-palette index). Since
    phase-44 no plaintext `.writeText()` source-path write survives; this closes
    every remaining read/delete surface. Tests: `B1Auth05SourceFilePathTest` (17).
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
  - **Implemented in phase-78** (B2-DOS-02, see `workspace/phase-78/REPORT.md`):
    vault search is bounded at every layer. New pure-JVM
    `services/VaultSearchPolicy.kt` is the single decision table
    (`SEARCH_CORPUS_CAP = 1500`, `DEEP_SCAN_BATCH_SIZE = 1500`,
    `exceedsCorpusCap`, `cachedWindowSize`, `isBlankQuery`, `pageMatches`,
    `refineNoticeMessage`). `NoteRepository.loadSearchCorpus`
    (`NoteRepository.kt:107-126`) now ALWAYS caches the decrypted window —
    loaded through the bounded DAO read `NotePageDao.getAllActivePagesBounded`
    (`Daos.kt`, `LIMIT :limit`) — so a keystroke never re-decrypts the vault;
    a vault over the cap is flagged via `NoteRepository.searchCorpusCapped`
    (recomputed per load) instead of silently dropping the cache. `searchPages`
    filters the cached window only; the explicit user-approved refine path is
    `NoteRepository.deepSearchPages` (`:412-434`), paged in bounded batches via
    `getAllActivePagesPaged` (`LIMIT :limit OFFSET :offset`) with only matches
    retained (never the whole vault pinned). `NoteflowViewModel.searchVault`
    shares ONE cancellable `Job` (`searchVaultJob?.cancel()` before every new
    launch, `if (isActive)` callback guard) and `deepSearchVault`
    (`NoteflowViewModel.kt:1926-1948`) shares it, so a keystroke pre-empts an
    in-flight deep scan. `HomeScreen` shows a one-time non-alarming
    "Search covers the most recent pages" banner + "Search all pages" action
    (`HomeScreen.kt`, gated on `viewModel.repository.searchCorpusCapped` +
    `refinedSearchDone`). The command palette / quick-switcher now indexes the
    same bounded cached window (consistent, bounded). Tests:
    `B2Dos02VaultSearchBoundedTest` (10).
- **WebDAV sync**: `services/WebDavSyncService.kt:28` (encrypted vault archives, HTTPS enforced).
  - **Implemented in phase-40**: server-supplied PROPFIND hrefs are re-resolved against the
    configured server origin by the new pure-JVM `services/WebDavHrefResolver.kt`
    (`resolveDownloadHref`), and EVERY connection is origin-gated in `createConnection`
    (`WebDavSyncService.kt:147`) before the `Authorization: Basic` header is attached
    (`:164`), with `instanceFollowRedirects=false` (`:158`). Off-origin/private-IP hrefs
    and 3xx redirects are refused with a clear `SyncResult(false, "Sync refused: ...")` —
    closes B1-NET-01 + the WebDAV slice of B1-NET-05 (see `workspace/phase-40/REPORT.md`).
  - **Implemented in phase-86** (B1-NET-07): the remote-listing → download slice is a
    pure-JVM decision table `services/WebDavRemoteListingPolicy.kt` — the "latest"
    backup is chosen by the MAXIMUM FILENAME TIMESTAMP across both name generations
    (`noteflow_vault_backup_<epochMillis>.nfb` legacy + `noteflow_vault_backup_<yyyy-MM-dd>_<token>.nfb`
    B2-CRYPTO-06), never the last href in XML document order (`newestBackupHref`,
    timestamps compared ASCENDING so `maxWithOrNull` yields the newest — unparseable
    names score lowest, same-timestamp ties break deterministically by href); the `.nfb`
    GET is streamed under the `MAX_DOWNLOAD_BYTES` (400 MB) cap by `copyBounded`
    (mid-stream abort, typed `DownloadTooLargeException`, no target-file over-budget,
    IDLE_READ_LIMIT stall guard); `remoteFolderName` is validated as ONE path segment
    and RFC 3986 percent-encoded by `encodedRemoteFolderSegment` (blank/`.`/`..`/
    separators/control chars rejected) at every URL interpolation. Review-fix (same
    lineage): the too-large catch deletes the partial cache file, and the download
    source pins are scoped to the download path only (the upload PUT legitimately keeps
    `input.copyTo(output)`). See `workspace/phase-86/REPORT.md`.
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
  - **Implemented in phase-85 (B1-NET-06, LOW)**: the /24 `legacyHttpScan`
    register sweep is now an EXPLICIT per-search opt-in, never a default.
    Single pure-JVM decision table `services/localsend/LocalSendDiscoveryPolicy.kt`
    (`DISCOVERY_REQUIRES_EXPLICIT_USER_ACTION = true`,
    `LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT = false`, `SENDER_ALIAS = "InkFlow"`,
    `senderDeviceModel = null`; `mayRunDiscovery(userInitiated)` /
    `mayRunLegacyHttpScan(userOptedIn)` fail closed). `LocalSendSender.discoverDevices`
    (`LocalSendSender.kt:104-118`) defaults `includeLegacyHttpScan` to
    `LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT` and only consults the sweep when UDP
    discovery found nothing (`udpResults.isEmpty()`); `LocalSendSendDialog` seeds
    a "Also check every address on this Wi-Fi…" Checkbox from the same constant
    and feeds `legacyHttpScanOptIn` into the single discovery call (the old
    hard-coded `= true` is gone). `discover()` still fires ONLY from the explicit
    "Find nearby devices"/"Refresh" `onClick` handlers, so opening the dialog
    transmits nothing. The announce/identity (`LocalSendProtocol.senderIdentity`
    `:105-114`) is now wired to the same policy constants (alias `InkFlow`,
    no device model — `Build.MODEL` long gone since phase-110/B1-NET-09).
- **Web Capture / Citation fetch (SSRF)**: `services/SsrfHostPolicy.kt:30` (shared pure-JVM host
  blocklist — loopback/RFC-1918/link-local-metadata/CGNAT/ULA/`.local`/embedded-IPv4, structural, no DNS),
  `plugins/webcapture/WebPageFetchPolicy.kt:31` (`validateUrl`) + `:80` (`rejectHop`),
  `plugins/webcapture/WebPageFetcher.kt:22` (every-hop revalidation + redirect-advance fix),
  `plugins/citation/HttpsTitleFetcher.kt:39` (manual 5-hop redirect loop, hop-scheme+blocklist
  revalidation, `instanceFollowRedirects=false`), `plugins/citation/CitationFormatterCore.kt:26`.
  - **Implemented in phase-51**: B1-NET-04 closed — Web Capture and Citation title-fetch can no
    longer reach localhost/LAN/cloud-metadata endpoints, either directly or via a redirect hop:
    entry gates refuse blocked hosts (`WebPageFetchPolicy.validateUrl`,
    `CitationFormatterCore.validateUrl`) and every redirect `Location` is re-parsed and re-validated
    against the same scheme allow-list + `SsrfHostPolicy` before connecting (incl. an HTTPS→HTTP
    downgrade refusal under the citation fetcher's default `httpsOnly`). See `workspace/phase-51/REPORT.md`.
    Review fix (2026-08-15): `SsrfHostPolicy.isOpaqueIpv4Literal` refuses ambiguous numeric encodings
    (`0x7f.0.0.1`, `0177.0.0.1`) whose per-segment value differs by resolver; `normalize` strips a bare
    `host:port`; `WebCaptureEngine.captureWebPage` fetches the normalized `Validation.url`. Name-based
    DNS-rebinding remains a tracked out-of-scope residual (`docs/security-report.md`).
- **Implemented in phase-52** (B1-NET-05): HTTPS→HTTP redirect downgrades are
    closed at EVERY base `HttpURLConnection` transport. New pure-JVM
    `services/StrictRedirectPolicy.kt` (`checkTlsHop` `:31`, `resolveNextTlsHop`
    `:57`, `RedirectRefusedException`, `MAX_REDIRECTS = 5`) is the single hop
    policy: every hop — the entry URL AND every resolved 3xx `Location` — must
    be `https` and pass the B1-NET-04 `SsrfHostPolicy` blocklist; loops,
    malformed and blank targets are rejected. Wired with
    `instanceFollowRedirects = false` (+ manual loop) into `DuckDuckGoClient`
    `:163`, `OpenMeteoClient` (`WeatherClient.kt:104`), `DictionaryClient.kt:69`,
    and `AppFacadeHost.httpGet` `:67` (previously `= true`); `LocalSendSender`
    `:512` now also refuses redirects on its pinned payload connections. All
    four transport constructors gained an injectable `connectionFactory` (default
    = `openConnection`) so each is behavior-tested with a fake `HttpURLConnection`
    (`B1Net05RedirectDowngradeTest`, 28 tests). Review fix (2026-08-15): the
    last base-app redirect-following hole was closed — `LocalSendSender.httpRegisterProbe`
    (`LocalSendSender.kt:235`) now also sets `instanceFollowRedirects = false`
    (`:240`, previously the platform's implicit `true`); the source-pin test now
    enforces a per-file count invariant (every `openConnection()` must be matched
    by an `instanceFollowRedirects = false`, 29 tests) so no future un-paired
    connection can slip through. See `workspace/phase-52/REPORT.md`.
- **Palette**: `services/PaletteCatalog.kt:131` (swatches + `familyFor`), `PaletteMath` :24.
- **Brush preview**: `ui/components/PenNibVisualPreview.kt:50` (driven by `services/NibPreviewMath.kt`).
- **Glass theme**: `theme/GlassSurfaces.kt:44` (`GlassBlurGate`), :80 (`GlassSurfaceMath`), :140
  (`FrostedGlassSurface`), :192 (`innerLuminescence`); `theme/Motion.kt:50` (`MotionSystem`, `LocalReduceMotion` :16);
  `theme/Type.kt:70`; `theme/Theme.kt:239` (`NoteflowTheme`, dynamic + paper/sepia/dark/AMOLED).
- **ViewModel/nav**: `ui/viewmodel/NoteflowViewModel.kt:105` (builds SecurityService/NoteRepository/PluginRegistry
  :121/PluginManager :131/PluginRuntime :170/PluginStoreController :196; ~60 capability suspend fns);
  `MainActivity.kt:73` (single activity, **`mutableStateOf` nav** — NOT Navigation Compose).
  - **Implemented in phase-60** (B1-PLAT-4, see `workspace/phase-60/REPORT.md`): the vault lock
    boundary is no longer reachable only via ON_STOP / next-touch. Pure-JVM
    `services/AutoLockPolicy.kt` owns the default (`DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS = 300`,
    read by `SettingsManager.autoLockTimeoutSeconds` — auto-lock ships ENABLED), the decision
    (`shouldAutoLock`, `>=` boundary, 0/negative = off) and the poll cadence
    (`IDLE_CHECK_INTERVAL_MS`). `MainActivity` runs a continuous 1 s idle poll while the vault is
    authenticated (`LaunchedEffect(autoLockTimeoutSeconds, authenticated)`), stamps a fresh idle
    baseline at each unlock, keeps the `pointerInput` touch handler timestamp-only, locks instantly
    on a runtime `ACTION_SCREEN_OFF` receiver (register in onCreate / deregister in onDestroy;
    API 33+ uses the flagged registration, below that the plain system-broadcast registration), and
    applies FLAG_SECURE unconditionally (debug clearFlags carve-out deleted). `ON_STOP` → lock
    retained. `ON_PAUSE` → lock explicitly NOT chosen (system-overlay pauses like phase-59's SAF
    pickers, biometric prompts and the share sheet must not force a lock).
  - **Implemented in phase-72** (B2-UI-2, see `workspace/phase-72/REPORT.md`): `NoteflowViewModel.lock()`
    (`NoteflowViewModel.kt:3219-3234`) scrubs the system clipboard as its FIRST statement —
    `ClipboardGuard.scrubIfOwnCopy(appContext)`, before `repository.zeroizeKey()` and before the
    passwordless-vault gate — so EVERY lock path (manual "Lock Vault Now", idle auto-lock, ON_STOP,
    ACTION_SCREEN_OFF) clears an app-owned clipboard copy inside its window even though the app stays
    foregrounded (ON_PAUSE may never fire). The decide → clear → forget decision is the new pure-JVM
    `services/ClipboardScrubPolicy.kt` (single decision table, `SCRUB_WINDOW_MS = 60_000`); the
    Android clipboard write stays in `services/ClipboardGuard.kt` (clearPrimaryClip API 28+ / empty
    setPrimaryClip API 26-27, best-effort, `clearPrimaryClipOverride` = pure-JVM test seam) and after a
    scrub the guard forgets its timestamp so a foreign (other-app) copy is never wiped. ON_PAUSE scrub
    retained as defense-in-depth; both note-content copy sources stamp the guard before writing
    (`OcrResultDialog.kt:149-150`, `MediaEmbedComponents.kt:352-354`). Tests: `B2Ui2ClipboardScrubTest` (13).
- **Import/export**: `services/ImportExportService.kt:30` (encrypted backup/restore, `validateBackupPassword`,
  PDF/HTML/image export).
  - **Implemented in phase-55** (B1-DB-5, see `workspace/phase-55/REPORT.md`): the HTML/Obsidian
    zip import readers are zip-bomb-safe. New pure-JVM `services/ImportArchivePolicy.kt` owns the
    budgets (50MB/entry, 200MB total, 100× declared-vs-actual ratio 4KB floor, 10k entries, 200MB
    archive input) with single-settle accounting (`checkEntryChunk` per chunk, `settleEntryRead`
    once per completed entry) and raises `ImportSizeLimitException` (an `IllegalStateException`).
    `readUriBytes` (`ImportExportService.kt:89-118`) streams under a hard cap and re-throws the
    dedicated exception; `importHtmlZipOrFolder` (`:2063`) and `importObsidianVaultZip` (`:2250`,
    single-pass) route every entry through `claimEntry`/`readEntryBounded`; wholesale `zis.readBytes()`
    is gone. Restore callers keep the 400MB `MAX_BACKUP_INPUT_BYTES` cap; HomeScreen surfaces a
    non-alarming `"Import skipped: …"` snackbar. Tests: `B1Db05ImportZipBombTest` (13) — 1142 green.
  - **Implemented in phase-56** (B1-DB-7, see `workspace/phase-56/REPORT.md`): restore can no longer
    accept a legacy PLAIN (unencrypted) zip nor open a backup's SQLCipher DB with the empty
    passphrase. Two pure-JVM file-level helpers in `ImportExportService.kt`:
    `isPlainPkBackupBytes` (raw `PK`-header classifier) + `backupRestoreOpenCandidates`
    (`listOfNotNull(backupDekHex, currentDekHex).filter { it.isNotBlank() }.distinct()`, the
    historic `""` empty-key entry is gone AND stripped fail-closed). `importBackup` rejects a raw
    plain zip BEFORE any decrypt/extract; the authenticated device-DEK-encrypted legacy path and
    the NFLB2 password-v2 path are untouched. `HomeScreen` picker refuses a PK zip with a
    snackbar before any confirm dialog; the device-keyed legacy dialog warns UNTRUSTED/UNSIGNED.
    Tests: `B1Db07PlainZipRestoreRejectedTest` (12) — 1154 green.
  - **Implemented in phase-59** (B1-PLAT-3, see `workspace/phase-59/REPORT.md`): no export
    auto-writes to public Downloads. New pure-JVM `services/ExportDestinationPolicy.kt` classifies
    every `ExportKind` (`ENCRYPTED_BACKUP`, `OBSIDIAN_VAULT`, `HTML_SITE`, `VAULT_ZIP`, `PAGE_PNG`,
    `PAGE_WEBP`, `PAGE_PDF`, `DOCUMENT_PDF`, `NOTE_HTML`, `LAYERED_PSD`) — MIME + suggested name +
    `requiresPlaintextWarning` (true only for the whole-vault plaintext kinds). New
    `ui/components/SaFExporter.kt` (`rememberSaFExporter`) is the SINGLE route for every user-facing
    export: `ACTION_CREATE_DOCUMENT` picker (API 19+, below minSdk 26, no fallback) with a bold
    "Export is NOT encrypted" consent dialog ahead of the picker for the 3 whole-vault plaintext
    kinds, and transfer-then-delete of the cacheDir staging copy on success. All 6 public-Downloads
    copies in `ImportExportService.kt` + the PSD copy are removed; HomeScreen (5 flows) +
    EditorScreen (7 flows) route through the exporter; LocalSend's cacheDir payload path unchanged.
    Tests: `ExportDestinationPolicyTest` (11) + `B1Plat03ExportConsentTest` (5) — 1196 total.
  - **Implemented in phase-81** (B2-DOS-05, see `workspace/phase-81/REPORT.md`): attachment/import
    ingestion is bounded DURING the read. New pure-JVM `services/AttachmentIngestPolicy.kt` is the
    single decision table (`MAX_ATTACHMENT_BYTES` = 25 MB, `READ_BUFFER_BYTES` = 64 KiB):
    `boundedReadBytes(input, maxBytes)` streams over a fixed buffer and throws
    `ImportArchivePolicy.ImportSizeLimitException` mid-stream on the first chunk crossing the cap
    (heap never exceeds budget + one buffer); `readTextHead(file, maxBytes)` is a head-bounded,
    prefix-preserving UTF-8 text head read (a multi-byte char split at the cap decodes lossily to a
    single replacement char — no over-read past the budget; empty for missing/unreadable/empty).
    `EditorScreen`'s 3 pickers (`:236` custom-bg, `:263` paper-texture, `:829` photo embed) route
    through `boundedReadBytes` with a dedicated per-site size-limit snackbar;
    `NoteflowViewModel.restoreEncryptedBackupFromZip` (`:3145-3147`) replaced `sourceZip.readBytes()`
    with `boundedReadBytes(it, ImportExportService.MAX_BACKUP_INPUT_BYTES)` (400 MB restore budget
    preserved, enforced in-flight); `DocumentTextExtractor` reads only a 25 MB PDF-head
    (`MAX_EXTRACT_BYTES`) / 1 MB text-head (`MAX_TEXT_HEAD_BYTES`); legacy plaintext-file-body reads
    in `NoteBodyVaultPolicy.kt:64` and `WikiLinkParser.kt:274` use `readTextHead`. Tests:
    `B2Dos05AttachmentIngestTest` (14) — 1470 total green.
    **Phase-81 review fixes** (same discovering commit lineage):
    - `NoteRepository.migrateLegacyPlaintextNoteBodies` (`:515` pre-fix) — the last unbounded
      `file.readText()` on the legacy-body surface — now refuses files > `MAX_ATTACHMENT_BYTES`
      (never read into the column, never deleted) and reads within-cap bodies head-bounded;
    - `restoreEncryptedBackupFromZip` over-budget archives now fail CLOSED with a truthful
      "Backup is too large to restore (max 400 MB)" message (`(Boolean, String?) -> Unit`
      callback consumed by `WebDavSyncDialog`); the pre-fix generic "Failed to restore…" only
      remains for non-budget failures;
    - `NoteBodyVaultPolicy.resolveBodyForDisplay` no longer returns a truncated head for a legacy
      body > `MAX_ATTACHMENT_BYTES` — it falls through to the full encrypted column and leaves
      the oversized file untouched (a truncated head would be written back on the next save and
      the full file deleted);
    - `EditorScreen`'s 3 picker reads moved off the main thread (`withContext(Dispatchers.IO)`);
    - `AttachmentIngestPolicy.boundedReadBytes` guards a contract-breaking stream that returns 0
      repeatedly (throws `IOException` after 16 idle reads instead of busy-spinning).
    Tests: `B2Dos05AttachmentIngestTest` (15) — 1471 total green.
  - **Implemented in phase-82** (B2-DOS-06, see `workspace/phase-82/REPORT.md`): layered PSD
    export no longer materializes N full-page ARGB bitmaps + N per-layer channel buffers at
    once (a 25-layer export dropped from ~350 MB peak to a bounded ~125-132 MB). New pure-JVM
    `services/PsdExportPolicy.kt` (`MAX_EXPORT_LAYER_COUNT` = 16, `capLayerCount`/
    `omittedLayerCount`/`noticeMessage`) is the single layer-budget decision table;
    `ImportExportService.exportPageToPsd` (`:2422`) keeps the TOP 16 layers of
    `layers.sortedBy { it.zOrder }` (highest zOrder = front-most) via
    `takeLast(exportedDataLayers)`/`dropLast(exportedDataLayers)` BEFORE any per-layer bitmap
    is created, and returns the new `PsdExportService.PsdExportOutcome(file,
    exportedLayerCount, omittedLayerCount)` so `EditorScreen.kt:1407-1420` shows a one-time
    non-alarming "layers omitted (max 16)" snackbar when the cap bites (shown only after a
    successful export so a cancelled picker cannot hide it). The omitted BOTTOM layers are
    folded into ONE bounded merged-preview bitmap (`compositeExtras`) so the PSD's flattened
    composite still shows the full page. `PsdExportService.exportLayersToPsd` writes the
    layer-and-mask section STREAMING: info records in a tiny bounded buffer
    (`layerRecordBytes`), per-layer channel pixels straight to the destination
    `DataOutputStream` one channel at a time (`writeChannelPixels`) — the pre-fix
    `layerPixelBlocks` full-size channel-block accumulation and per-layer `IntArray` are
    gone, and ONE `IntArray(width*height)` is reused for every layer + the composite
    (additionally clamped to `MAX_EXPORT_LAYER_COUNT + 1`; composite extras bounded too);
    pure-JVM helpers `channelSizeFor`/`channelDataLength`/`layerSectionLength` keep the
    section length byte-identical.
    Tests: `B2Dos06PsdExportLayerCapTest` (16) — 1487 total green.
  - **Implemented in phase-83** (B2-DOS-07, see `workspace/phase-83/REPORT.md`): backup EXPORT no
    longer builds the ENTIRE vault zip in heap and then makes a second full-size AES-GCM copy
    (pre-fix `baos.toByteArray()` + `cipher.doFinal(zipData)` / `encrypt(zipData, key)` → Base64 —
    ~600 MB+ peak on a few-hundred-MB vault, OOM on every attempt). New pure-JVM
    `services/BackupExportPolicy.kt` owns the bounded streamers: `zipVaultEntriesToStream`
    (zip written entry-by-entry straight into a `ZipOutputStream(FileOutputStream)` staging FILE,
    never a `ByteArrayOutputStream`), `encryptStreamGcm` (file-to-file AES-GCM: header, then a
    bounded `ENCRYPT_CHUNK_BYTES`=64 KiB `Cipher.update` loop, then `doFinal` tail+tag — the JCE
    stream-mode contract makes it BYTE-IDENTICAL to the old single `doFinal`, so the `NFLB2`
    on-disk layout is unchanged and legacy restores read it unmodified), and
    `encryptStreamDeviceKeyedBase64` (GCM chunked + `java.util.Base64.Encoder.wrap(NonClosingSink)`
    — same legacy `[version][iv][ciphertext+tag]` wire format, no ~1.37x in-heap expansion).
    `ImportExportService.exportBackup` (`:1268-1369`) stages the zip under
    `File(context.cacheDir, BackupExportPolicy.stagingFileName(backupName))` (`.zip-staging`,
    deleted in `finally` :1366) and encrypts file-to-file; all callers (HomeScreen device-keyed +
    password, WebDAV C2b, LocalSend VAULT_BACKUP) consume the returned cacheDir `File` unchanged.
    Peak heap is one 64 KiB chunk + one `Cipher.update` output + the tag, never the archive.
    `EncryptionService` GCM constants flipped `private`→`internal` for the policy.
    Tests: `B2Dos07BackupExportStreamingTest` (8) — full suite green + `assembleDebug` green.
- **Update / self-install**: `services/UpdateService.kt:128` (`checkForDownloadedUpdates` — scans ONLY app-private
  `filesDir`/`cacheDir` through `UpdateTrustPolicy.isScanSafeDirectory`; public Downloads/external dirs are NEVER
  scanned, B1-PLAT-7), `:60` (`inspectApkFile` — classifies via the policy, trust-neutral copy), `:175`
  (`installApk` — first check is `UpdateTrustPolicy.mayInstall(trust, userConfirmedUntrusted)`, refuses unconfirmed
  UNTRUSTED files before any staging); `ui/components/Dialogs.kt` `AppUpdateDialog` — "Scan App Storage for APK"
  + strong untrusted-confirmation gate on "Install Update".
  - **Implemented in phase-61** (B1-PLAT-7, see `workspace/phase-61/REPORT.md`): new pure-JVM
    `services/UpdateTrustPolicy.kt` owns the trust model — no official channel ⇒ every locally-present APK is
    `UpdateSourceTrust.UNTRUSTED_LOCAL` (`classifySource`/`hasOfficialChannel`), `isPubliclyWritableDirectory`
    structurally refuses `/sdcard`·`/storage/emulated`·`…/Android/data/…` mounts, and `mayInstall` fail-closed-gates
    UNTRUSTED installs behind explicit user confirmation. The old scan of `getExternalFilesDir` + `/sdcard/Download`
    + `/storage/emulated/0/Download` and the "New update detected in local storage" conditioning wording are gone.
    Tests: `B1Plat07UpdateTrustTest` (11 test methods; review fix 2026-08-15 — see `workspace/phase-61/REPORT.md` "Addendum").

## Build / CI essentials

- `app/build.gradle.kts`: `namespace = "com.authorss81.noteflow"` (:11),
  `applicationId = "com.aistudio.inkflow.app.bkxjrz"` (:15), compileSdk 36, minSdk 26, JVM 17,
  Room schema → `app/schemas`, R8 minify on for release, `jniLibs.useLegacyPackaging = true`.
- **No Gradle wrapper** — use system `gradle`. Tests: `gradle testDebugUnitTest`; build: `gradle assembleDebug`
  / `assembleRelease`. Runs in GitHub Actions (gradle 8.13, JDK 17).
  - **Implemented in phase-75** (B2-DEPS-03, `workspace/phase-75/REPORT.md`): `gradle/verification-metadata.xml`
    is committed — Gradle 8.13 auto-enables STRICT checksum verification (deps + metadata + build plugins)
    whenever that file exists. To add/upgrade a dependency, regenerate with
    `gradle --write-verification-metadata sha256 testDebugUnitTest assembleDebug`, review the diff, then
    commit. There is NO `dependencyVerification {}` settings DSL in Gradle 8.13 — do NOT add one (it breaks
    settings compilation). The `google()` content filters (`com.android.*`/`com.google.*`/`androidx.*`) are
    mirrored in `dependencyResolutionManagement` and must stay in sync with `pluginManagement`.
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