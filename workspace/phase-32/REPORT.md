# Phase 32 — APK attack: download the built APK and bombard it with hacking tools

- **Status:** `DONE`
- **Date:** 2026-08-15
- **Targets:** local builds (no CI artifacts / `gh` token on this runner)
  - `app/build/outputs/apk/debug/app-debug.apk` — 173,608,246 B — SHA-256 `056c3a6729d031deb22afc418772bc15756929128782265be9620afc0f4bc966`
  - `app/build/outputs/apk/release/app-release.apk` — 142,159,171 B (shipping artifact, R8-minified)
- **No code / schema / workflow changes this phase.** Working-tree changes produced by this phase = `workspace/phase-32/REPORT.md`, this appended `docs/security-report.md` section, and `docs/phase-status.md`/`docs/ARCHITECTURE.md` (committed in `44a7210`); gradle outputs are git-ignored, and the APKs were built locally so the gradle repo state is otherwise unchanged. New findings appended to `docs/security-report.md` as the PROMPT requires.

## 1. APK acquisition

`gh run list` needs `GH_TOKEN` (unset on this runner) → no CI artifacts downloadable. Built both variants with system gradle 8.13 / JDK 21 / SDK 36:

```
gradle :app:assembleDebug     → BUILD SUCCESSFUL in 5m 39s
gradle :app:assembleRelease   → BUILD SUCCESSFUL in 4m 29s  (R8 minify + lintVitalRelease + packageRelease)
```

`apksigner verify --print-certs` on the release APK: `V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug` (SHA-256 `81a2980a…`); `zipalign -c` = `Verification successful`.

## 2. Tool battery (all installed cleanly; evidence captured under /tmp/opencode/pentest/)

| Tool | Version | Ran | Key output |
|------|---------|-----|------------|
| `unzip` + `python3 zipfile` | — | structure + per-entry size accounting | §3 |
| `apktool d` | apt 2.x | full decode to `smali*/` | manifest/smali/resources mined throughout |
| `jadx` | 1.5.1 | decompile (no-res) | 1070 app `*.java` under `jadx/out/sources/com/authorss81` |
| `apkid` | 3.1.0 | fingerprint every dex | `compiler: r8` everywhere; `anti_vm` hits traced to libraries only (§5) |
| `androguard axml` | 4.1.4 | binary manifest parse | full component/permission dump (§4) |
| `aapt dump badging` | SDK 37 | manifest summary | package/ABI/permissions/launchable |
| `strings` | binutils | 947,174 dex lines + 118,628 native lines | URL/secrets/crypto hunt (§6) |
| `apksigner` | SDK 37 | signing-scheme + cert audit | V2-only, debug cert (§8) |
| `readelf` | binutils | native hardening | all `.so`: GNU_RELRO + NX + ET_DYN (§9) |

Dynamic (emulator/Frida/objection) tooling was NOT possible — no `adb` device/AVD in the CI image. Noted for the ROADMAP PHASE 34.9 dynamic re-run.

## 3. Size / packaging audit (NEW findings)

```
release: APK_size = 142.0 MB, 906 entries
   language-models/  raw=207.6 MB  packed=80.2 MB  (56% of the APK!)
   native .so        raw=128.5 MB  packed=55.4 MB
debug:   APK_size = 173.5 MB, 919 entries (same LM pack + 4-ABI natives + unminified 23 dex)
```

- Root dir `language-models/` = the **lingua** language-detection library's bundled n-gram corpus (`com.github.pemistahl:lingua:1.2.2`, `app/build.gradle.kts:185`, used by `plugins/langdetect/LanguageDetectionCore.kt`): 75 languages (`af..zu`), each `bigrams.json` / `fivegrams.json` / `quadrigrams.json` / `trigrams.json` / `unigrams.json` — 199 MB raw (207,608,234 B), 80.2 MB packed. Byte-for-byte identical to the lingua JAR in the gradle cache (`language-models/en/unigrams.json` = 1708 B in both). **This is NOT ML Kit translation data** — ML Kit translate models are runtime-downloaded on explicit user action (`MlKitTranslatorEngine.kt:22-24`). Corrected 2026-08-15 review: the initial run mis-attributed this pack to "ML Kit translation models".
- `lib/` ships all four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) of every native lib: `libmlkit_google_ocr_pipeline.so` (11 MB aarch64), `libtranslate_jni.so` (16 MB aarch64, the ML Kit translate native that IS baked in), `libsqlcipher.so` (5 MB aarch64), `libink.so`, `libandroidx.graphics.path.so`, `libgraphics-core.so`.
- NO `.gguf`, NO `tasks-genai`, NO `com/google/mediapipe` classes in either APK — the on-device LLM stayed out of the base (positive).

**Phase-32-NEW-01 (MEDIUM, packaging/policy):** the base-APK-size hard rule is unmet at binary level — the `language-models/` n-gram pack (80.2 MB packed = 56% of the shipping APK) ships via the compile-time **lingua** "pure-JVM" plugin (which contradicts the "lightweight pure-JVM plugins stay compile-time" carve-out — only 24 of its 75 bundled languages are ever referenced, `LanguageDetectionCore.kt:25-50`), and ML Kit **OCR** + **translate natives** (13–16 MB per ABI) are also baked in, with `plugins/translation/*` and `plugins/ocr/*` compiled straight in. Shipping APK = 142 MB release / 173 MB debug.

**Phase-32-NEW-02 (LOW):** no ABI splits — every device downloads all four ABIs of every native lib (~55.4 MB packed) on top of the 80 MB lingua n-gram pack it may never use. Split-per-ABI (or Play `bundle`) would cut installed/download payload dramatically.

## 4. Manifest audit (release + debug)

- `package="com.aistudio.inkflow.app.bkxjrz"`, `versionCode=2`, `versionName=1.0.0`, `minSdk=26`, `targetSdk=36`, `compileSdk=36`.
- **Release has no `android:debuggable`** (defaults `false`); debug build is `debuggable="true"` (expected for a CI debug artifact).
- `allowBackup="false"`, `fullBackupContent="false"`, `dataExtractionRules` set — backup stays disabled (positive).
- Permissions minimal & justifiable: `RECORD_AUDIO`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `INTERNET`, `ACCESS_NETWORK_STATE` + self-`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No storage/location/camera/SMS (positive).
- Exported surface: `MainActivity` `exported=true` `launchMode=singleTask` with `ACTION_SEND text/plain`, `image/*`, `SEND_MULTIPLE image/*`, `*/*` → **confirms B1-PLAT-2**. Only other exported receiver is stock `androidx.profileinstaller.ProfileInstallReceiver` (`permission=DUMP`) — benign androidx.
- FileProvider `…bkxjrz.fileprovider` exposes `files-path apk/`, `external-path Download`, `cache-path exports/` — matches documented update/export flows (B1-PLAT-7 area; no new hole).
- **No `network_security_config`, no `usesCleartextTraffic`** → cleartext platform-denied on targetSdk 36. The documented "HTTP opt-in for local-network WebDAV hosts" cannot actually connect today (availability quirk, not an insecure-config leak).
- **No `POST_NOTIFICATIONS`** (targetSdk 33+; notifications will be silent — minor UX note, not security).

## 5. APKiD fingerprinting

All 23 dex files `compiler: r8`; debug multidex shows `r8 without marker` (R8 marker only lands in minified release). `anti_vm : Build.MODEL/FINGERPRINT/MANUFACTURER/PRODUCT/HARDWARE check` hits traced to **library code only**: `androidx.biometric.BiometricManager`, `coil.util.-HardwareBitmaps`, `androidx.graphics.lowlatency.LowLatencyCanvasView`, `androidx.compose…LayerManager`, `com.google.android.datatransport.cct.CctTransportBackend`, `com.google.android.gms.internal.mlkit_common.zzi`, `androidx.appcompat…`. **No app-level anti-VM / anti-debug / emulator evasion.** No packer, no obfuscator markers, no malicious YARA hits.

## 6. Secrets / strings hunt

- **No hardcoded API keys / tokens / passwords.** Searched `sk-`, `AIza`, `AKIA`, `password=`, `api_key`, `BEGIN RSA`/`PRIVATE KEY`, storepass variants, `inkflow.2026.plugins`, `changeit`, `android.debugstore`, `secret`… Found only the documented stack: keystore aliases `noteflow_webdav_credentials_key_auth`, `noteflow_dek_key`, the compiled-in pin placeholder (§8).
- All URLs are documented HTTPS endpoints: `https://plugin-updates.inkflow.app/v1/manifest.json`, `https://api.duckduckgo.com/`, `https://api.dictionaryapi.dev/api/v2/entries/en/`, `https://api.open-meteo.com/v1/forecast`, `https://geocoding-api.open-meteo.com/v1/search`, `https://cloud.example.com/remote.php/dav` (sample), `https://dl.google.com/translate/offline/…`. No cleartext endpoints.
- Weak-cipher strings (`RC4`, `DES`, `MD5`, `SSL_RSA_WITH_NULL_MD5`, `HmacSHA1`) are **JSSE's built-in cipher-suite name table** (ships with every Android runtime) — not app-selected ciphers. App crypto = `AES/GCM/NoPadding` + `PBKDF2WithHmacSHA256` + `SHA256` (confirmed in decompiled `services/*`).
- Firebase/Google telemetry surface: `com.google.android.datatransport` + CCT backend + `firebaseinstallations.googleapis.com/v1` / `firebaseremoteconfig.googleapis.com` URLs ARE bundled (ML Kit transitive) but **no `com.google.firebase.FirebaseApp` reference exists** → the CCT backend cannot fire without a FirebaseApp init; B2-LOG-06's "no telemetry SDK" conclusion holds at binary level.

## 7. Prior findings confirmed on the APK

| Finding | Confirmed how |
|---------|---------------|
| `B1-PLAT-1` (debug-keystore release signing) | `apksigner --print-certs`: `CN=Android Debug`, SHA-256 `81a2980a…`, v2-only |
| `B1-PLAT-2` (exported singleTask MainActivity) | binary manifest, `ACTION_SEND */*` filters |
| `B1-PLAT-6` (applicationId/namespace mismatch) | `package=…bkxjrz` vs launchable `com.authorss81.noteflow.MainActivity` |
| `B1-PLAT-7` (UpdateService auto-discovery in base) | `com.authorss81.noteflow.services.UpdateService` present in dex |
| `B1-NET-02`/`B1-NET-06` (LocalSend surface) | `TRUST_ALL_HOSTNAMES.verify()` returns constant `1`; `:53317/api/localsend/v2/{register,prepare-upload,upload,cancel}`; LAN `legacyHttpScan`. NOTE: `LocalSendTrustManager.validate()` still SHA-256-pins the peer leaf cert, so the always-true hostname verifier is mitigated by the cert pin (protocol design; severities unchanged) |
| `B1-NET-03`/`B1-CRYPTO-01` manifest→artifact chain | `plugin-updates.inkflow.app` host + pinned/no-redirect transports present (§8) |
| `B1-AUTH-01` (downloadable plugin in-process) | `services/AppClassLoaderFactory` (`DexClassLoader`) + `plugins/runtime/RuntimePluginLoader` |
| `B2-CRYPTO-06` (timestamped public backup names + WebDAV regex) | strings `noteflow_backup_`, `noteflow_vault_backup_`, `noteflow_vault_backup_[^<]+\.nfb)` regex in smali |

## 8. Plugin trust chain (phase-39 fix) verified in the binary

- `HostedPluginManifestKt.PLUGIN_MANIFEST_CERT_PIN = "sha256/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="` (base64 of bytes 0x00..0x1F) is compiled into both APKs — a well-formed 32-byte placeholder.
- `HttpsManifestTransport$fetch$2` guard order in smali: HTTPS-scheme-only → host allow-list (`plugin-updates.inkflow.app`) → `PinnedTlsConnector.open` → explicit 3xx refusal ("answered with an HTTP redirect") → 256 KB cap ("exceeds the 256 KB cap and was refused").
- `PinnedCertHash.matches` = `ConstantTime.hexEqual` (constant-time); `parse` rejects non-32-byte pins → fail closed.
- **Result:** the phase-39 fix is fully present, and because the pin is still the placeholder, no real cert can match → **every plugin-update check on the shipped build fails closed (availability dead until the operator substitutes the real production leaf pin)**. Security-wise this is the intended fail-closed state; operationally it is a live dead channel noted here.

**Phase-32-NEW-03 (INFO):** release APK is signed with **APK Signature Scheme v2 only** (`apksigner` reports v1/v3/v3.1/v3.2/v4 all `false`). For a minSdk-26 app this removes the versioned signing-key-rotation capability built into v3; combined with the debug-cert fallback signing (B1-PLAT-1) a future signing-key compromise cannot be rotated in place.

**Phase-32-NEW-04 (INFO):** the compiled-in plugin pin placeholder (§8) means the shipped artifact's plugin-update channel is non-functional by design until the operator substitutes the real pin — a documented, deliberate fail-closed availability gap (not a security weakness; it is the B1-CRYPTO-01 fix in its placeholder state).

## 9. Native code hardening (positive)

`readelf -h/-l` on every aarch64 `.so` (`libsqlcipher.so`, `libink.so`, `libmlkit_google_ocr_pipeline.so`, `libtranslate_jni.so`, `libgraphics-core.so`, `libandroidx.graphics.path.so`):

- Type `DYN` (ET_DYN, PIE-able shared objects) — all six.
- `GNU_RELRO` present in all six; `GNU_STACK` = `RW` (NX enabled, no `RWE`) in all six.
- No writable-executable segments, no missing-hardening `.so`.

## 10. Summary

- **NEW findings (4):** Phase-32-NEW-01 (MEDIUM — language-detection n-gram pack (80.2 MB packed = ~199 MB raw, lingua library) + ML Kit OCR/translate natives baked into base, 56% of APK, violating the downloadable-plugin hard rule; attribution corrected 2026-08-15 — the pack is lingua's, not ML Kit translation models), Phase-32-NEW-02 (LOW — no ABI splits, all-4-ABI download), Phase-32-NEW-03 (INFO — v2-only signing scheme, no key-rotation), Phase-32-NEW-04 (INFO — placeholder pin ⇒ plugin-update channel dead until operator substitution; fail-closed by design).
- **Prior findings CONFIRMED on the APK:** B1-PLAT-1 (debug-keystore release signing — apksigner), B1-PLAT-2 (exported singleTask MainActivity), B1-PLAT-6 (applicationId vs namespace), B1-PLAT-7 (UpdateService), B1-NET-02/06 (LocalSend trust-all-hostname + LAN discovery; cert-pin mitigation noted), B1-NET-03/B1-CRYPTO-01 chain + phase-39 fix wiring, B1-AUTH-01 (DexClassLoader plugin runtime), B2-CRYPTO-06 (timestamped backup filenames).
- **Positives confirmed (not findings):** release not debuggable, `allowBackup=false`, FLAG_SECURE applied (`if (!BuildConfig.DEBUG) addFlags(8192)` in decompiled MainActivity), R8 minify ON (single 7.9 MB `classes.dex` in release), minimal permission set, cleartext platform-denied, no MediaPipe/tasks-genai/GGUF in base, no hardcoded secrets in 1M+ extracted strings, strong native hardening, constant-time pin compare, CCT telemetry dormant (no FirebaseApp init).
- **Not possible on this runner:** dynamic instrumented checks (no AVD/device) — left for PHASE 34.9.

Appendix — original tool output (captured before report writing):

```
$ apksigner verify --print-certs app-release.apk
V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug
V2 Signer: certificate SHA-256 digest: 81a2980a9ec0662b5d6eac0749797e4cef5da1736bb81cd476d1e431cef449be
Verified using v1 scheme (JAR signing): false | v2: true | v3: false | v4: false

$ apkid -v app-debug.apk   (23 dex) → compiler : r8 (+ "anti_vm : Build.MODEL check" etc., lib-traced)

$ python3 zipfile accounting
release: APK_size=142.0MB entries=906  language-models packed=80.2MB (56%)  .so packed=55.4MB

$ androguard axml AndroidManifest.xml (release)  → $aapt dump badging
package: name='com.aistudio.inkflow.app.bkxjrz' versionCode='2' versionName='1.0.0'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'

smali (LocalsendSender$TRUST_ALL_HOSTNAMES):
.method public verify(Ljava/lang/String;Ljavax/net/ssl/SSLSession;)Z
    const/4 v0, 0x1
    return v0

smali (HostedPluginManifestKt):
.field public static final PLUGIN_MANIFEST_CERT_PIN:Ljava/lang/String; = "sha256/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
```
