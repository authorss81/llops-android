# Phase 14 — Production-Readiness Audit + LocalSend File Transfer (2026-08-13)

Scope: verify EVERYTHING shipped by Phases 2–13 against the code, fix anything
false, harden the security surface, run all three Gradle gates, and add a *real*
LocalSend-protocol file transfer. No new third-party dependency was added (the
transfer uses the existing `androidx`/`gson`/JVM `java.net` stack).

Every statement below was checked against source at this commit; where something
could not be verified on hardware it is explicitly labeled **not verified**.

---

## Part A1 — Per-phase verdict table

Verdict legend:
- **PASS** — claim is real: wired, called, works.
- **FIXED** — claim was false/dead, corrected in this phase.
- **REMOVED** — the "feature" was deleted as a fake (Phase 3).
- **PARTIAL** — real but narrower than claimed (details given).
- **NOT DONE** — requested earlier but never implemented.

| Phase | Claim | Verdict | Evidence |
|---|---|---|---|
| 2 | Stroke geometry `pointsJson` + title/extract/textContent field-encrypted at rest | PASS | `NoteRepository.kt:356-362, 449-468, 537-559, 658-712, 764-794`; cross-device map `ImportExportService.kt:1107-1112` |
| 2 | `checkpointWal()` executes (fully steps cursor) | PASS | `NoteRepository.kt:136-144`; called `NoteflowViewModel.kt:997,1211`, `HomeScreen.kt:452,1173` |
| 2/9 | H2 corrupt/wrong-key DB no longer deletes vault; quarantines + recovery screen | PASS | `NoteflowDatabase.kt:247-296, 307-329`; `DatabaseSecurityHelper.kt:122-144`; `MainActivity.kt:276-281, 633-701` |
| 2/9 | H1 failed restore no longer bricks app (password validated before close, DB reopened, restart) | PASS | `ImportExportService.kt:1300-1328`; `HomeScreen.kt:114-129`; `NoteflowViewModel.kt:822-835`; `NoteRepository.kt:242-245`; `NoteflowDatabase.kt:374-379` |
| 2/9 | C1 `note_versions.{title,extractedText}` re-encrypted | PASS | `NoteRepository.kt:165-230, 206-228`; `Daos.kt:247-250` |
| 3 | LibMyPaint C++ stub + engine-selector stack deleted | PASS | zero matches for `mypaint_jni`, `LibMyPaint`, `HardwareProfiler`, `BrushEngineFactory`, `renderingEngineOverride` in `app/`; legacy `sealed interface BrushEngine` remnant **removed in this phase** (`StrokeModels.kt`) |
| 3 | Handwriting recognition stub deleted | PASS | zero matches in `app/` (comments only in CHANGELOG/ROADMAP) |
| 3 | FtsSearchEngine deleted; vault search = cached decrypted corpus | PASS | `NoteRepository.kt:58-76, 260-268` |
| 3 | `baseline-prof.txt` deleted, no DSL | PASS | no file, no `baselineProfile` DSL anywhere |
| 3 | Shape auto-snap wired (geometry-gated) | PASS | `AnnotationCanvas.kt:730-776`; `ShapeRecognitionHelper.kt:29-43, 58-60, 119-122, 145-148` |
| 4 | AGSL wet-mixing wired via `WetBrushEngine`, gated by `isAgslSupported` + `gpuWetBrushesEnabled` | PARTIAL (real, live-preview-scoped) | `AnnotationCanvas.kt:399-401, 417-442, 2102-2109, 2242-2349`; `WetBrushEngine.kt:43-47`; `ShaderCapabilityHelper.kt:6-7`; `SettingsManager.kt:69-71` — Gated to a live wet stroke on a layer; committed strokes bake through the plain path (documented in ROADMAP) |
| 4 | `WetCanvasEngine` not dead | PASS | used `AnnotationCanvas.kt:402, 412, 718, 1587-1617, 2262` |
| 5 | Markdown back-save data-loss fixed | PASS | `MarkdownPreviewScreen.kt:138-155, 239-246`; `MainActivity.kt:332-343` (NonCancellable IO write) |
| 5 | 48dp targets / snackbars / reduce-motion / status-bar polarity | PASS | `EditorScreen.kt:790-953`, `AnnotationCanvas.kt:923-1618`; `MainActivity.kt:144-152, 486-489`; `Motion.kt:29-88`, `Theme.kt:209-224`; `MainActivity.kt:123-140` |
| 6 | WebDAV sync engine real, INTERNET-scoped, HTTPS enforced, no fake auto-sync | PASS | `WebDavSyncService.kt:61-155, 138-156, 196-301`; `AndroidManifest.xml:4-11`; no `autoSync`/`WorkManager` anywhere |
| 6 | WebDAV UI reachable | **FIXED (was dead)** | `HomeScreen.kt:2376-2381` previously invoked a default empty lambda; now `onOpenWebDavSync = { showWebDavDialog = true }` (HomeScreen menu + dialog render) |
| 7 | Stroke stabilizer, pressure curves, symmetry, color harmony | PASS | `AnnotationCanvas.kt:610-692, 557-564, 1997-2020`; `SettingsManager.kt:80-92`; `EditorScreen.kt:304-306, 1528-1545`; `ColorPickerBottomSheet` `EditorScreen.kt:2159, 2389-2450` |
| 7 | Reference layer | **NOT DONE** — requested in the Phase-7 prompt, absent from code (only "Linked References" is unrelated) |
| 7 | Paper textures, WebP | PASS | `SettingsManager.kt:96-118`, `AnnotationCanvas.kt:1688-1717, 2465-2517`; WebP export `ImportExportService.kt:36-53`, `EditorScreen.kt:995-1021` |
| 8 | Perf fixes: PBKDF2 off-main, bounded decode, IO image loading, bitmap caching, BitmapPool, JankStats, thermal | PASS | `NoteflowViewModel.kt:971-1133`; `ImportExportService.kt:138-193`; `ImageViewer.kt:74,136`; `AnnotationCanvas.kt:392-398, 2033-2063, 2135-2186`; `MainActivity.kt:112,559-565`; `EditorScreen.kt:128,408-419,3220`; `AnnotationCanvas.kt:429-435` (thermal status query; the listener APIs have no callers — PARTIAL on thermal) |
| 10 | Plugin framework (sealed capabilities, static registry, loud failure, opt-in off-by-default, settings dialog, Rot13 wired) | PASS | `PluginCapability.kt:28-53`; `PluginRegistry.kt:48-98, 561-565`; `PluginManager.kt:100-107, 174-228`; `SettingsManager.kt:164-170`; `HomeScreen.kt:443,1073-1078,2356-2360`; `MarkdownPreviewScreen.kt:252-277`; `PluginFrameworkTest.kt` (10 tests) |
| 11 | Plugin lifecycle / error isolation / deps / settings namespacing | PASS (deps = ID-only, no version ranges — broadened than claimed) | `PluginLifecycle.kt:27-34`, `PluginRegistry.kt:253-265, 405-483`; `PluginManager.kt:206-253`; `PluginManifest.kt:67-77`; `PluginSettings.kt:51-54`; tests: `PluginLifecycleStateMatrixTest` (8), `PluginErrorIsolationTest` (12), `PluginDependencyConflictTest` (12), `PluginSettingsNamespacingTest` (4) |
| 12 | OCR plugin (ML Kit, offline, no key, wired, no path leak) | PASS | `OnDeviceOcrPlugin.kt:35-105`; `MlKitOcrEngine.kt:8-11,64-66`; `OcrInputValidation.kt:20`; `EditorScreen.kt:1272, 1378-1399`; `OcrResultDialog.kt:74, 131-137`; `OcrPluginWrapperTest` (13) |
| 12 | Web Search plugin (URL-safe, wired, off-main, keyless) | PASS (+ fixed in this phase) | `DuckDuckGoQueryUrl.build` `DuckDuckGoClient.kt:29-40` (URL-encoded); `MarkdownPreviewScreen.kt:282-299, 490-505`; **Phase 14 adds a 1 MB response-size limit** (`DuckDuckGoClient.kt:131-185`); `WebSearchPluginTest` (14) |
| 13 | Brush presets, stickers, styled sticky notes, item rotation | PASS | `BrushPreset.kt:28-127` (UI `EditorScreen.kt:3014-3036, 3646-3727`); `StickerCatalog.kt:17-45` (`AnnotationCanvas.kt:3496-3531`); sticky notes `AnnotationCanvas.kt:2912-3302`; rotation `CanvasItemRotationMath.kt` + `graphicsLayer { rotationZ }` `AnnotationCanvas.kt:3042-3049, 3373-3377, 3702-3764`; DB column `media_embeds.rotationDegrees` (`NoteflowDatabase.kt:165-169`) |
| 13 | Persist→load→render round-trip unit test for canvas items | **NOT DONE** (Phase-13 DoD said "verified by round-trip test"; no such test exists — no androidTest source set, and the storage path is Room/instrumentation-only) |

### Honesty corrections applied in this phase

1. **Dead WebDAV menu trigger fixed** (`HomeScreen.kt`) — the ⋮ "WebDAV / Nextcloud E2EE Sync" item called an empty lambda; `showWebDavDialog` was never set true. Now wired.
2. **AGENTS.md** stale Phase-10 sentence ("OCR/web search … capabilities are declared but unserved") replaced with the real Phase-12 status; `PluginFrameworkTest` count corrected 9 → 10; WebDAV "real sync is a later phase" bullet corrected.
3. **ROADMAP.md** — added "✅ SINCE THE AC781DE AUDIT" table documenting rows fixed by Phases 2–14 (field encryption, tamper checksum, WebDAV, AGSL, FLAG_SECURE, R8, LayerBitmapCache, BitmapPool, thermal, encrypted backups, OCR).
4. **Dead `sealed interface BrushEngine` remnant removed** (`StrokeModels.kt`) along with the test that referenced it — makes the Phase-3 "BrushEngine … deleted" claim literally true.
5. **`docs/RELEASE.md`** written (real keystore via keytool + GitHub secrets; the current debug-keystore fallback is documented as NOT publishable).
6. **`CHANGELOG.md`** updated with an honest Phase 2–14 summary including the "Not done / honest notes" section.

---

## Part A2 — Security checklist

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | `allowBackup=false`, `data_extraction_rules.xml` intact | PASS | `AndroidManifest.xml:15-17`; `res/xml/data_extraction_rules.xml` excludes all domains for cloud-backup + device-transfer |
| 2 | No exported components beyond share-target MainActivity | PASS | Only `MainActivity` (`:35`) + non-exported `FileProvider` (`:23-31`); SEND/images intent-filters read only `EXTRA_TEXT`/`EXTRA_STREAM` and copy to app-private storage before grants expire (`MainActivity.kt:497-554`) |
| 3 | No secrets / keys / decrypted content logged | PASS | all 35 `Log.*` sites inspected; none log passwords/keys/DERIVE material/note text. **Fixed in this phase**: `UpdateService` `e.printStackTrace()`, private-path logs in `VoiceNoteManager` and `ProtobufBrushLoader`, and the `PrivacyCrashReporter` hex regex typo |
| 4 | `ClipboardGuard` on ALL copy paths | PASS | exactly two clipboard writers, both guarded: `OcrResultDialog.kt:149-150`, `MediaEmbedComponents.kt:353-354`; scrub on `ON_PAUSE` (`MainActivity.kt:101`) |
| 5 | Crypto intact: PBKDF2 600k, AES-256-GCM (12-byte IV/128-bit tag), AndroidKeyStore DEK, zeroization on lock | PASS | `EncryptionService.kt:31-75`; `SecurityService.kt:21-151,146`; field-encrypt `NoteRepository.kt` (as above) |
| 6 | New plugins added no unsafe surface | PASS (+1 fix) | Web-search URL fully percent-encoded; plugin errors isolated per-plugin (`PluginManager.kt:206-253`); OCR errors map to generic user messages; **fixed**: DDG response had no size cap → now 1 MB limit |
| 7 | No INTERNET permission creep | PASS | permissions = `RECORD_AUDIO`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `INTERNET` only. Every socket is WebDAV (HTTPS-gated) or the DDG search client (fixed HTTPS); **LocalSend adds NO permission** (see Part B) |
| 8 | No debug-only code in release | PASS | `BuildConfig.DEBUG` used exactly once (`MainActivity.kt:89` FLAG_SECURE) — release sets FLAG_SECURE, debug clears it; no debug-only features/logs. `isMinifyEnabled=true` (`app/build.gradle.kts:71`), clean ProGuard (`proguard-rules.pro`) |
| 9 | WebDAV HTTPS enforcement | PASS | `WebDavSyncService.kt:61-88, 119-127`; `http://` only with explicit opt-in AND a local-network host |
| 10 | WebDAV credentials stored encrypted | PASS | `WebDavCredentialStore.kt:74-127` (AndroidKeyStore-backed prefs) |

---

## Part A3 — Release readiness

All three gates ran successfully on the CI Linux runner (Gradle 8.13, system gradle):

| Gate | Result |
|---|---|
| `gradle testDebugUnitTest` | **PASS** — full suite incl. new `LocalSendProtocolTest` (18) — see below |
| `gradle assembleDebug` | **PASS** |
| `gradle assembleRelease` | **PASS** (R8 minify on) |

- **Release artifact**: `app/build/outputs/apk/release/app-release.apk`
  (versionCode 2 / versionName 1.0.0 defaults; set `VERSION_CODE`/`VERSION_NAME` envs to override).
- **Signing**: still the auto-generated **debug keystore** fallback because no
  `KEYSTORE_FILE` exists on the runner — this is EXPECTED and documented.
  `docs/RELEASE.md` explains exactly how a maintainer wires a real keystore via
  `keytool` + GitHub secrets (upload base64 secret → decode before
  `assembleRelease`). The project does NOT fake a production keystore.

---

## Part B — LocalSend file transfer

### What shipped

A **real, sender-only LocalSend Protocol v2.2 implementation** (no internet,
no cloud, local network only):

- `services/localsend/LocalSendProtocol.kt` — pure JVM: announce JSON, discovery
  response parsing, `/prepare-upload` request/response, `/upload` + `/cancel`
  URL building, file hashing, TLS-fingerprint comparison, mime guessing.
- `services/localsend/LocalSendSender.kt` — network + UI-facing service:
  UDP multicast (`224.0.0.167:53317`) + broadcast announcement, unicast-response
  listening, legacy HTTP `/api/localsend/v2/register` subnet scan fallback
  (works under Wi-Fi AP isolation), and the upload flow run on
  `Dispatchers.IO` with byte-level progress and cancellation
  (`cancelActiveTransfer()` force-disconnects the in-flight HTTP call).
- `ui/components/LocalSendSendDialog.kt` — Home → ⋮ → **"Send to Nearby Device
  (LocalSend)"**; user picks the payload (single note as HTML, encrypted `.nfb`
  backup, Obsidian ZIP, HTML-site ZIP) and then taps a discovered device. Shows
  discovery, "awaiting acceptance", progress bar, cancel, success/failure.
- Unit tests: `LocalSendProtocolTest` (18 tests, no network) — JSON shapes,
  discovery parsing, upload/cancel URL building, prepare-upload response,
  SHA-256 vectors incl. file hashing, fingerprint normalization, mime mapping,
  IPv6 base-URL bracketing, protocol constant defaults. **All pass.**

### Interop facts (honest)

- Endpoints match the current official spec (`localsend/protocol` v2.2):
  `POST /api/localsend/v2/register`, `/prepare-upload`, `/upload?sessionId&fileId&token`,
  `/cancel?sessionId`. Discovery uses 224.0.0.167:53317 + broadcast + legacy scan.
- **Confirm flow is enforced by the protocol itself**: the receiver's
  `/prepare-upload` only returns `200 {sessionId, files}` after a human accepts
  on the receiving device; the sender surfaces 403 "declined", 409 "busy",
  401 "PIN required" etc. This app never auto-accepts and never *receives*
  (sender-only).
- HTTPS receivers are verified by their announced TLS fingerprint (SHA-256 of
  the cert); a mismatch fails loudly rather than downgrading to plaintext.
- **Not verified this phase (hardware)** — no second device was available on
  the CI runner, so actual two-phone LocalSend interop has NOT been exercised.
  Protocol-level correctness is verified by the 18 unit tests against the spec's
  JSON/URL shapes, but "it works between two LocalSend devices on your Wi-Fi"
  still needs a real on-lan smoke test. This is stated rather than assumed.
- **Permissions**: none added. Raw UDP broadcast/multicast-send and LAN HTTP
  need only the already-declared `INTERNET` permission. `NEARBY_WIFI_DEVICES`
  is deliberately NOT added because the app does not use Wi-Fi scanning APIs
  (`WifiManager`/`WifiNetworkSuggestion`); adding it would be unnecessary scope.
  Documented in the class KDoc and in the manifest comment.

### Known limitation stated

- Discovery depends on the receiver answering a LAN announce (LocalSend app
  open, same subnet). Where the router has AP isolation, UDP announce may be
  dropped — the included legacy HTTP subnet scan is the fallback for that case,
  but a fully isolated network (VLAN separation) will not show devices.

---

## Release artifact

```
app/build/outputs/apk/release/app-release.apk
```

Signed with the CI debug-keystore fallback (documented, not publishable).
See `docs/RELEASE.md` to wire a real keystore + CI secrets.