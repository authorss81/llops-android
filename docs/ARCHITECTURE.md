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
| `services/localsend/` | `LocalSendProtocol.kt`, `LocalSendSender.kt` | Pure-JVM LocalSend v2.2 + real network sender |
| `plugins/` | `NoteflowPlugin.kt`, `PluginRegistry.kt`, `PluginManager.kt`, `PluginDiagnostics.kt`, `PluginLifecycle.kt` | Compile-time plugin framework + typed serving interfaces |
| `plugins/runtime/` | `RuntimePluginLoader.kt`, `SignatureVerifiedPluginRuntime.kt`, `ArtifactSignatureVerifier.kt`, `PinnedCertHash.kt`, `PinnedTlsConnector.kt`, `PluginManifestFetcher.kt`, `HttpsPluginDownloadTransport.kt`, `PluginDownloader.kt`, `PluginUpdateEngine.kt` | Downloadable-plugin runtime: pinned-cert verify (manifest + artifact transports, no redirects), DexClassLoader, updates |
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
  - **Implemented in phase-32** (APK attack, see `workspace/phase-32/REPORT.md`): the release APK was built and audited with apktool/jadx/androguard/APKiD/strings/apksigner/readelf. Confirmed at binary level: release release signing is the well-known Android **debug** cert (`CN=Android Debug`, SHA-256 `81a2980a…`, v2-only scheme — B1-PLAT-1 + new Phase-32-NEW-03); base APK bundles **199 MB of ML Kit translation models** (`language-models/`, 80.2 MB packed = 56% of the 142 MB release APK) + OCR natives despite the downloadable-plugin hard rule (Phase-32-NEW-01); no ABI splits (Phase-32-NEW-02); plugin-manifest cert pin is still the placeholder `sha256/AAECAwQFBgcI…` so hosted plugin updates fail closed until the operator substitutes the real pin (Phase-32-NEW-04, B1-CRYPTO-01 fix wiring verified). Positives re-verified: release not debuggable, FLAG_SECURE wired, allowBackup=false, R8 ON, no tasks-genai/GGUF in base, no hardcoded secrets in 1M+ strings.
- **Implemented in phase-32 review fix (2026-08-15)**: `scripts/phase_runner.sh` only writes a phase's `.done` if the `opencode run` left working-tree changes outside `logs/` + the phase's own markers (`tree_work`/`has_new_work` in `phase_runner.sh`). A zero-work run (opencode exit 0 with no delta — the phase-32 false completion at commit `6b17422`) counts as a failed attempt and leaves a `.no_work` marker; phase-32's bogus `.done` was removed so the pipeline re-selects it.

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