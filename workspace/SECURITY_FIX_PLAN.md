# SECURITY_FIX_PLAN - Phase 33 mapping (finding -> phase -> verification)

> Generated 2026-08-14 by Phase 33. Highest existing workspace phase at the time was
> `phase-38`; fix phases start at **phase-39** and run CRITICAL->HIGH->MEDIUM->LOW->INFO.
> The FINAL document-fix phase is **phase-115** (always last). Do NOT implement these
> fixes inside Phase 33 - the phases themselves implement them, one finding per phase,
> each scoped under 30 minutes of AI work. Findings marked `resolved at triage` got no
> phase (see `docs/security-report.md`).

## Fix phases

| Finding id | Phase | Severity | file:line (key evidence) | Verification command |
|-----------|-------|----------|--------------------------|----------------------|
| B1-CRYPTO-01 | phase-39 | CRITICAL | `PluginUpdateChecker.kt:74-82` / `30-38` (manifest values copied verbatim into the persisted active entry),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-NET-01 | phase-40 | HIGH | `WebDavSyncService.kt:263-274` (regex over server XML; absolute-URL branch `latestRemotePath.startsWith("http")` at... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-NET-02 | phase-41 | HIGH | `LocalSendSender.kt:75-84` (announces `protocol="http"` by default), `LocalSendProtocol.kt:178-185`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-NET-03 | phase-42 | HIGH | `HostedPluginManifest.kt:29-57` (offer carries `downloadUrl`, `sha256`, `pinnedCertHash`),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-DB-1 | phase-43 | HIGH | `NoteflowDatabase.kt:287-296` (`isDatabaseCorruptException` returns true for ANY... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-DB-4 + B1-AUTH-06 | phase-44 | HIGH | `ImportExportService.kt:55-75` (persistFile writes plaintext to `filesDir/noteflow/imports`),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-CRYPTO-02 | phase-45 | HIGH | `NoteflowDatabase.kt:335-343` (factory falls back to `security.getOrCreateDek()`), `SecurityService.kt:134-144`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-AUTH-01 | phase-46 | HIGH | `AppClassLoaderFactory.kt:22-28` (`DexClassLoader(artifactPath, optimizedDir, null, parent)` - parent is the app's own... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-AUTH-02 | phase-47 | HIGH | `NoteflowViewModel.kt:2055-2067` (`lock()` calls `repository.zeroizeKey()` only, no `NoteflowDatabase.dispose()`),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-LOG-01 | phase-48 | HIGH | `MainActivity.kt:85-86` (PrivacyCrashReporter registered first, AppStartupLogger second => AppStartupLogger's handler... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-UI-1 | phase-49 | HIGH | `NoteflowViewModel.kt:2055-2067` (`lock()` never cancels pending saves), `EditorScreen.kt:392-402` (`DisposableEffect`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-01 | phase-50 | HIGH | `NoteRepository.kt:443-503` (decrypts + materializes the page's ENTIRE geometry at once),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-NET-04 | phase-51 | MEDIUM | `WebPageFetchPolicy.kt:31-58` (validateUrl only checks scheme+host presence - `localhost`, `127.0.0.1`, `192.168.*`,... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-NET-05 | phase-52 | MEDIUM | `PluginManifestFetcher.kt:98` (`instanceFollowRedirects=true`), `HttpsPluginDownloadTransport.kt:62` (same),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-DB-2 | phase-53 | MEDIUM | `NoteflowDatabase.kt:191-232` `migratePlaintextIfNeeded`: on success `dbFile.delete(); tempFile.renameTo(dbFile)`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-DB-3 | phase-54 | MEDIUM | `VoiceNoteManager.kt:65-66` (MediaRecorder writes raw MPEG-4/AAC to `filesDir/voice_notes`), the path in... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-DB-5 | phase-55 | MEDIUM | `ImportExportService.kt:1791-1792` (importHtmlZipOrFolder `zis.readBytes()` per entry),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-DB-7 | phase-56 | MEDIUM | `ImportExportService.kt:1392-1403` (legacy path treats any `PK`-headed payload as a plain keyless backup),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-PLAT-1 | phase-57 | MEDIUM | `app/build.gradle.kts:28-54` (releaseConfig falls back to `${rootDir}/debug.keystore` decoded from... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-PLAT-2 | phase-58 | MEDIUM | `AndroidManifest.xml:33-68` (MainActivity `exported="true"`, `launchMode="singleTask"`, SEND/SEND_MULTIPLE filters for... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-PLAT-3 | phase-59 | MEDIUM | `HomeScreen.kt:479-489` (onExportObsidianVault -> plaintext .md vault zip), `HomeScreen.kt:490-500` (onExportHtmlVault... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-PLAT-4 | phase-60 | MEDIUM | `SettingsManager.kt:178-179` (`autoLockTimeoutSeconds` defaults to `0` = disabled), `MainActivity.kt:97-109` (ON_PAUSE... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-PLAT-7 | phase-61 | MEDIUM | `UpdateService.kt:104-137` (checkForDownloadedUpdates scans `getExternalFilesDir`, `cacheDir`, `filesDir`,... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-CRYPTO-03 | phase-62 | MEDIUM | `NoteflowViewModel.kt:1794-1795` (`settings.masterPasswordSalt = ...` then `settings.masterPasswordWrappedDek = ...` as... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-CRYPTO-04 | phase-63 | MEDIUM | `NoteflowViewModel.kt:1773` (`MIN_PASSWORD_LENGTH = 6`), no complexity/entropy check; `EncryptionService.kt:31-35`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-CRYPTO-05 | phase-64 | MEDIUM | `SecurityService.kt:134-144` (readDek returns null on ANY failure incl. keystore key loss -> generateDek() -> storeDek... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-CRYPTO-07 | phase-65 | MEDIUM | `SecurityService.kt:38-45` (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` applied ONLY when... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-CRYPTO-08 | phase-66 | MEDIUM | `ArtifactSignatureVerifier.kt:89-105` (findSignerCertificate iterates entries in JarFile order, takes... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-AUTH-03 | phase-67 | MEDIUM | `NoteflowViewModel.kt:211-227` (init block loads ALL entries + `pluginRegistry.onProcessStart(appContext)`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-AUTH-04 | phase-68 | MEDIUM | `ImageViewer.kt:123-132` (MarkdownInlineImage resolves `destination` as `File(dest)` and accepts it if `file.isAbsolute... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-AUTH-05 | phase-69 | MEDIUM | `MainActivity.kt:311-341` (`File(page.sourceFilePath).readText()` / `.writeText(newText)` for every .md/.txt note),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-LOG-02 | phase-70 | MEDIUM | `AppStartupLogger.kt:79-88` (append-only FileWriter, no length check, no rotation, no delete), `AppStartupLogger.kt:17`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-LOG-03 | phase-71 | MEDIUM | `ImportExportService.kt:256,261,345,350,1076,1740,1773,1816,1891,1945,2008,2076,2133` - all pass the exception object,... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-UI-2 | phase-72 | MEDIUM | `MainActivity.kt:99-106` (ON_PAUSE -> ClipboardGuard.scrubIfOwnCopy; ON_STOP -> viewModel.lock() - separate events, the... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-UI-3 | phase-73 | MEDIUM | `NoteRepository.kt:511` (plain `mutableMapOf<Int>`-typed single shared map for ALL pages),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-UI-5 | phase-74 | MEDIUM | `MainActivity.kt:312-318,410-416` (`produceState` reads `File(path).readText()` on IO),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DEPS-03 | phase-75 | MEDIUM | `gradle/` contains only `libs.versions.toml` (no `verification-metadata.xml`), `settings.gradle.kts:14-20`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DEPS-04 | phase-76 | MEDIUM | `plugins/llm/build.gradle.kts:147-148` (`KEYSTORE_ALIAS = "plugin-signing"`, `DEFAULT_KEY_PASSWORD =... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DEPS-05 | phase-77 | MEDIUM | `plugins/llm/src/main/kotlin/com/authorss81/noteflow/llm/engine/AssistantModelDownloader.kt:69-74` (plain... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-02 | phase-78 | MEDIUM | `NoteRepository.kt:58-76` (loadSearchCorpus: when corpus > `searchCorpusMaxPages` (1500) the cache is NOT stored, so... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-03 | phase-79 | MEDIUM | `VoiceNoteManager.kt:20` (`CoroutineScope(Dispatchers.Main + ...)`), `VoiceNoteManager.kt:96-111` (every 100 ms... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-04 | phase-80 | MEDIUM | `AppFacadeHost.kt:52` (`instanceFollowRedirects = true`), `AppFacadeHost.kt:57-58` (pre-check uses `contentLengthLong`,... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-05 | phase-81 | MEDIUM | `EditorScreen.kt:720` (photo embed `readBytes()`), `EditorScreen.kt:215,237` (background/texture pickers - same),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-06 | phase-82 | MEDIUM | `ImportExportService.kt:2095-2123` (exportPageToPsd creates one 1080x1528 ARGB_8888 Bitmap per layer ~6.6 MB each, all... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-07 | phase-83 | MEDIUM | `ImportExportService.kt:1154-1177` (exportBackup zips DB + imports into a ByteArrayOutputStream, then... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-CRYPTO-04 | phase-84 | MEDIUM | `ImportExportService.kt:1181` (require `backupPassword.length >= 6` - length only), `ImportExportService.kt:1182-1198`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-NET-06 | phase-85 | LOW | `LocalSendSender.kt:195-218` (legacyHttpScan walks 1..254 of the active /24), `LocalSendSender.kt:230-258` (POST... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-NET-07 | phase-86 | LOW | `WebDavSyncService.kt:263-274` (`matches.last()` = last href in XML document order, not newest),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-DB-6 | phase-87 | LOW | `DatabaseSecurityHelper.computeDatabaseHmac` `DatabaseSecurityHelper.kt:49-65` streams only `noteflow.sqlite`; the DB... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-DB-8 | phase-88 | LOW | `NoteRepository.getStrokesForPage` `NoteRepository.kt:449-457` (catches any decrypt exception and returns `rawText` -... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-PLAT-5 | phase-89 | LOW | `PrivacyCrashReporter.kt:77` (regex `/data/user/\d+/com\.authorss81\.noteflow/\S+`) vs the real runtime data dir... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-PLAT-8 | phase-90 | LOW | `NoteflowViewModel.kt:1773-1774` (`MIN_PASSWORD_LENGTH = 6`), `:1778` (enforced), `:1883-1908` (PBKDF2-HMAC-SHA256... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-CRYPTO-06 | phase-91 | LOW | `DatabaseSecurityHelper.kt:146-154` verifyDatabaseIntegrity: stored==null -> `updateStoredChecksum(context); return... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-AUTH-07 | phase-92 | LOW | `NoteflowViewModel.kt:1920-1937` - the side-effect-free verifier ignores `lockoutActive()` and never bumps... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-LOG-04 | phase-93 | LOW | `PluginDownloader.kt:133-137` (failure text contains the raw `entry.downloadUrl`), `DownloadablePluginInstaller.kt:105`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-LOG-05 | phase-94 | LOW | `WebDavSyncService.kt:189` (`SyncResult(false, "Connection failed: ${e.localizedMessage ?: e.message}")`), `:232` and... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-UI-4 | phase-95 | LOW | `NoteflowViewModel.kt:1123` (`dataInitialized = false`), `:1125-1127` (`initializeData()` early-returns when set),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-UI-6 | phase-96 | LOW | `HomeScreen.kt:48` (`rememberCoroutineScope()` - cancelled when HomeScreen leaves composition, i.e. on every lock),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DEPS-01 | phase-97 | LOW | `gradle/libs.versions.toml:28` (`jsoup = "1.17.2"`, lib at `:74`), used only by `WebToMarkdownExtractor.kt:3,27`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-08 | phase-98 | LOW | `WebDavSyncService.kt:250-259` (`listConn.inputStream.bufferedReader().use { it.readText() }` at :259 - full response... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-09 | phase-99 | LOW | `RamerDouglasPeucker.kt:14-37` (recursive split at :31-32; worst-case depth ~ points), invoked on every committed... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-10 | phase-100 | LOW | `NoteRepository.kt:511` (map field), `NoteRepository.kt:501/585` (adds a hash per loaded/saved stroke id),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DOS-11 | phase-101 | LOW | `WikiLinkParser.kt:90-110` (findBacklinks: for each of allPages, `getFullTextForPage` re-reads the note file (:59-76)... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-CRYPTO-01 | phase-102 | LOW | `DatabaseSecurityHelper.kt:153` (`return stored == current` on hex Strings - Kotlin `==` is `String.equals` with a... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-CRYPTO-02 | phase-103 | LOW | `ArtifactSignatureVerifier.kt:57` (`if (!sha256.equals(expectedSha256.trim(), ignoreCase = true))` - lexical early-exit... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-CRYPTO-03 | phase-104 | LOW | `ImportExportService.kt:1185` (wraps the DEK with `EncryptionService.encrypt(key, kek)` which authenticates... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-CRYPTO-05 | phase-105 | LOW | `EncryptionService.kt:85-93` (a payload whose first byte == PAYLOAD_VERSION and >=13 bytes is decrypted versioned... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-CRYPTO-06 | phase-106 | LOW | `ImportExportService.kt:1158` (`noteflow_backup_${System.currentTimeMillis()}.noteflow`), `WebDavSyncService.kt:202`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-CRYPTO-09 | phase-107 | LOW | `FIELD_AAD = "Noteflow-Vault-Field-Encryption-v1"` (`EncryptionService.kt:21`) applied identically to every field... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-CRYPTO-10 | phase-108 | LOW | `createPage` stores empty extractedText as raw `""` (`NoteRepository.kt:358-362`); `saveStrokesForPage` stores blank... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-NET-08 | phase-109 | INFO | `WebDavCredentialStore.kt:49-61` (`KeyGenParameterSpec` without `setUserAuthenticationRequired`),... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B1-NET-09 | phase-110 | INFO | `WebDavSyncService.kt:154` (`User-Agent: Noteflow-Android-WebDAV-Sync/2026`), `PluginManifestFetcher.kt:100` +... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-LOG-07 | phase-111 | INFO | `JankStatsHelper.kt:38` (`Log.w(TAG, "Jank detected on $screenName!...")`, threshold 16 ms -> fires on every slow frame... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-DEPS-02 | phase-112 | INFO | `gradle/libs.versions.toml:12` (securityCrypto = "1.1.0-alpha06", lib at :56), `app/build.gradle.kts:142`... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-CRYPTO-07 | phase-113 | INFO | `EncryptionService.deriveKey` feeds `PBEKeySpec(password.toCharArray(), ...)` (`EncryptionService.kt:31-35`) with no... | `gradle testDebugUnitTest` + `gradle assembleDebug` |
| B2-CRYPTO-08 | phase-114 | INFO | Every IV/salt/DEK comes from a fresh `SecureRandom()` (`EncryptionService.kt:24-29,37-42,61-62`; backup payload IV... | `gradle testDebugUnitTest` + `gradle assembleDebug` |

## Resolved at triage (no phase - see report for reasons)

| Finding id | Severity | Reason |
|-----------|----------|--------|
| B1-PLAT-6 | INFO | applicationId vs namespace mismatch. Resolved at triage: aligning `namespace` to the applicationId is a MAJOR architectural change (Phase 21.10, requires explicit user approval per AGENTS.md hard rule) with no safe standalone code fix; its only runtime consequence (hardcoded regex in PrivacyCrashReporter) is fixed by phase-89 (B1-PLAT-5). |
| B2-LOG-06 | INFO | No telemetry/crash SDK ships in the base APK (positive finding). Resolved at triage: nothing to fix independently - the disclosed accidental channels it contrasts are fixed by phase-48 (B2-LOG-01) and phase-70 (B2-LOG-02), and keeping the no-SDK posture is the fix. |
| B2-DEPS-06 | INFO | Dependency age / no-additional-CVE sweep (positive finding). Resolved at triage: no confirmed CVE beyond B2-DEPS-01 (fixed in phase-97); the recommended dependency-bump cadence and CI advisory scanner are maintenance/process items for ROADMAP, not a code fix, and the CI-scanner recommendation would require editing `.github/workflows/` which is prohibited. |

## Final phase

| Phase | Purpose |
|-------|---------|
| phase-115 | Document fix - final status & consistency sweep (re-audits ALL phases, marks every heading, updates phase-status/ROADMAP/AGENTS, closes all report findings) |
