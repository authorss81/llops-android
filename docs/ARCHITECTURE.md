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
| `services/` | `EncryptionService.kt`, `SecurityService.kt`, `DatabaseSecurityHelper.kt`, `VaultKeyHolder.kt`, `WetBrushEngine.kt`, `WebDavSyncService.kt`, `ImportExportService.kt`, `ImportArchivePolicy.kt`, `PaletteCatalog.kt`, `ShapeRecognitionHelper.kt`, `SsrfHostPolicy.kt`, `VoiceNoteCrypto.kt` | Non-UI: crypto/vault, brush math, sync, import/export, zip-import zip-bomb policy (B1-DB-5), palette, SSRF blocklist (B1-NET-04), voice-note audio cryptor (B1-DB-3) |
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