# Phase 159 Report — Build Release APK (verify it assembles without error)

**Status: DONE (2026-08-19) — READY FOR KALI**
**Target commit: `a9d8918c933ce498ed4ad7c2780218ad2b606392`**

## Step 1 — Environment check
- `printenv KEYSTORE_FILE` = `/home/runner/work/_temp/release.keystore` (set).
- `test -f "$KEYSTORE_FILE"` → exists (2746 bytes, restored by workflow to `$RUNNER_TEMP`).
- `keytool -list` → valid **PKCS12** keystore, 1 entry:
  - alias `inkflow`, `PrivateKeyEntry` (Aug 16, 2026)
  - certificate fingerprint SHA-256: `69:63:6E:DB:9E:E2:48:77:62:E9:8F:85:5F:25:0E:A1:EC:66:23:3D:E1:3B:61:A4:C0:14:02:6B:82:C5:01:96`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS` (=`inkflow`), `KEY_PASSWORD` all set (values not printed, per constraints).
- System `gradle` = Gradle 8.13 (no wrapper jar in repo — used `gradle`, not `./gradlew`).
- `VERSION_CODE`/`VERSION_NAME` unset → build defaults from `app/build.gradle.kts:18-19` (versionCode `2`, versionName `1.0.0`) — recorded, no build-config change needed.
- Commit sha confirmed: `a9d8918c933ce498ed4ad7c2780218ad2b606392`.

## Step 2 — Release build
- Command: `gradle assembleRelease`.
- Result: **BUILD SUCCESSFUL in 7m 8s**, 113 actionable tasks (113 executed).
- `:app:validateSigningRelease` passed (signing config materialized from env `KEYSTORE_FILE`,
  `app/build.gradle.kts:36-59`); `:app:minifyReleaseWithR8` + `:app:lintVitalRelease` ran
  cleanly (no fatal lint violations). Success/failure lines in build output showing
  deprecation warnings only (`w:` — no `e:` errors).
- **No build-config fixes were required** — this was a clean build from the first run using
  the workflow-provided keystore env. No `app/build.gradle.kts`, `proguard-rules.pro`,
  resource, or manifest edits were made. Fail-closed signing (B1-PLAT-1,
  `app/build.gradle.kts:133-153`) left untouched.

## Step 3 — Artifact verification
- APK path exists: `app/build/outputs/apk/release/app-release.apk` (142,339,635 bytes, 1981-01-01 01:01 stamps).
- `apksigner verify --verbose --print-certs` (build-tools 36.1.0):
  - `Verifies` — **v2 scheme: true**; v1/v3/v3.1/v4: false. Number of signers: 1.
  - Signer #1 DN: `CN=InkFlow Release, OU=Dev, O=Authorss81, L=Unknown, ST=Unknown, C=US`
  - Signer #1 certificate SHA-256: `69636edb9ee2487762e98f855f250ea1ec66233de13b61a4c014026b82c50196`
    (matches the `keytool` fingerprint from Step 1 — the APK is signed by the workflow keystore)
  - Signer #1 key: RSA 2048.
- `sha256sum app/build/outputs/apk/release/app-release.apk` →
  `54feb16c3533c6966f071414095c2256966c69161d845d9a67f7224d82bb455a`
- Size sanity: 142.3 MB > 1 MB ✓.
- R8/minify: release `isMinifyEnabled = true` (`app/build.gradle.kts:70`); build log shows
  `:app:minifyReleaseWithR8` executed. Single `classes.dex` present in the APK.
- versionCode/versionName (from `apkanalyzer manifest`): **2 / 1.0.0**. ApplicationId
  `com.aistudio.inkflow.app.bkxjrz` (`app/build.gradle.kts:15`).
- Full unit-test suite NOT run (other phases own that, per phase instructions); no
  lint/test failures surfaced during `assembleRelease`'s lifecycle.

## Step 4 — Handoff
- `docs/kali-report-round2.md` created with the "APK target" section (filename, commit sha,
  versionCode/versionName, signer DN + SHA-256 fingerprints, APK SHA-256, size, minify status).
- APK is uploaded by the workflow as the `noteflow-release-apk` artifact automatically;
  the binary is NOT pushed to git.
- File confirmed present at `app/build/outputs/apk/release/app-release.apk` before finishing.

## Definition of done
- [x] `gradle assembleRelease` → `BUILD SUCCESSFUL` (no error) — 7m 8s, 113 tasks.
- [x] `app/build/outputs/apk/release/app-release.apk` exists, signed (v2), SHA-256 recorded.
- [x] `docs/kali-report-round2.md` "APK target" section updated.
- [x] `workspace/phase-159/REPORT.md` written.
- [x] Commit + push (per Git workflow).

## READY FOR KALI
`app/build/outputs/apk/release/app-release.apk` (commit `a9d8918c`, v2.1.0.0, signed v2,
SHA-256 `54feb16c…455a`) is ready for the phase-160 Kali dynamic-pentest pass. No app
feature code was changed; no build-config changes were needed.