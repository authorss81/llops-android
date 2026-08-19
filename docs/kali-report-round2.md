# Kali Dynamic Pentest — Round 2 (Pass 2)

> Target prepared by **phase-159** (release-APK build & verification, 2026-08-19).
> Security findings from the Kali dynamic/instrumented pass (phase-160) go here.
> The pentest plan lives in `docs/pentest-plan.md`; the summary report is `docs/pentest-report.md`.
>
> **Scope note:** this file carries ONLY dynamic/instrumented Kali findings. The
> round-2 *source-audit* findings (43: 0 CRITICAL · 0 HIGH · 12 MEDIUM · 26 LOW ·
> 5 INFO) live in `docs/security-report-round2.md` and were triaged by phase-119
> into phases 129-158.

## APK target (verified by phase-159)

| Field | Value |
|-------|-------|
| Exact filename | `app/build/outputs/apk/release/app-release.apk` |
| Build commit sha | `a9d8918c933ce498ed4ad7c2780218ad2b606392` |
| versionCode | `2` |
| versionName | `1.0.0` |
| ApplicationId | `com.aistudio.inkflow.app.bkxjrz` |
| Signing scheme | APK Signature Scheme v2 (verified, `Verifies`) |
| Signer DN | `CN=InkFlow Release, OU=Dev, O=Authorss81, L=Unknown, ST=Unknown, C=US` |
| Signer certificate SHA-256 | `69636edb9ee2487762e98f855f250ea1ec66233de13b61a4c014026b82c50196` |
| Signer public-key SHA-256 | `0328af289a4b325229ffee68d8ac41aa4b863180174bd901e620bd75c04e7030` |
| APK SHA-256 | `54feb16c3533c6966f071414095c2256966c69161d845d9a67f7224d82bb455a` |
| Size | 142,339,635 bytes (~142.3 MB, > 1 MB sanity pass) |
| R8 minify | ON (release `isMinifyEnabled = true`; `:app:minifyReleaseWithR8` executed) |
| Signing keys source | env `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` (fail-closed B1-PLAT-1; no debug-keystore fallback) |

Verify identity against the keystore before attacking:
`keytool -list -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD"` must list the same
certificate SHA-256 fingerprint.

## Phase-160 STATIC run — artifact identity (unblocked by `b1f533f`)

> Commit `b1f533f` re-unblocked phase-160 as a **STATIC security analysis** of the
> release APK (dynamic-only checks are `DYNAMIC-DEFERRED` below; no rooted
> device/emulator on this runner). The workflow pre-downloaded the
> `noteflow-release-apk` artifact to the workspace and this run audited THAT
> artifact. **Note the SHA-256 MISMATCH vs phase-159's recorded artifact:** the
> delivered APK is a *newer* build (post-phase-159; current tree HEAD `82c11f6`
> phase-166 review fixes). Signer identity is byte-identical, so it is a genuine
> InkFlow release build — the old record is retained for provenance.

| Field | Phase-159 recorded artifact | Phase-160 at-hand artifact |
|-------|-----------------------------|----------------------------|
| Build commit sha | `a9d8918c933ce498ed4ad7c2780218ad2b606392` | current HEAD `82c11f6` (phase-166 review fixes) |
| APK SHA-256 | `54feb16c3533c6966f071414095c2256966c69161d845d9a67f7224d82bb455a` | `9ce99c1b3dbcbdb9fa6080961b9e043c8ead6e9828a4e1286958cd331743233f` |
| Size | 142,339,635 B | 142,344,579 B |
| versionCode / Name | `2` / `1.0.0` | `2` / `1.0.0` |
| Signer cert SHA-256 | `69636edb9ee2487762e98f855f250ea1ec66233de13b61a4c014026b82c50196` | identical |
| Signer pubkey SHA-256 | `0328af289a4b325229ffee68d8ac41aa4b863180174bd901e620bd75c04e7030` | identical |
| Signing scheme | v2 only | v2 only (v1/v3/v3.1/v4 false), `Verifies` |

Verification commands: `sha256sum`, `apksigner verify --verbose --print-certs` (build-tools 36.0.0).

## Static findings — Phase-160 (schema: `R2-xxx | Severity | Area | Evidence | Reproducer | Suggested fix`)

### Batch 1 — Manifest audit (Step 2.1)

| ID | Severity | Area | Evidence | Reproducer | Suggested fix |
|----|----------|------|----------|------------|---------------|
| R2-KS-01 | INFO | backup | `apktool d` manifest: `android:allowBackup="false"` + `android:fullBackupContent="false"` + `android:dataExtractionRules="@xml/data_extraction_rules"`; `res/xml/data_extraction_rules.xml` excludes all domains (cloud-backup + device-transfer, incl. `external_data`). | `aapt dump xmltree` on the built APK, verify each attr. | None — PASS. |
| R2-KS-02 | INFO | exported components | Only `MainActivity` exported (launcher + share-sheet `SEND`/`SEND_MULTIPLE` filters for text/plain, image/*, `*/*`; `launchMode="singleTask"`). `QuickCaptureWidget` receiver `exported="false"`, `updatePeriodMillis="0"`; FileProvider `...fileprovider` `exported="false"` + `grantUriPermissions="true"`; all ML Kit/datastransport/Room components `exported="false"`. `ProfileInstallReceiver` `exported="true"` but guarded by `android:permission="android.permission.DUMP"` (system/shell-only; standard AndroidX component, no baseline profile installed). | AST via `grep -n exported AndroidManifest.xml`. | None for MainActivity (intended share-target; captures only after authenticated frame, `MainActivity.java:621-649`). |
| R2-KS-03 | INFO | cleartext | No `android:usesCleartextTraffic`, no `networkSecurityConfig` in manifest → targetSdk 36 default = cleartext denied (except localhost). WebDAV HTTP is additionally code-gated (R2-KS-14). | `grep -c usesCleartextTraffic AndroidManifest.xml` → 0. | None — PASS. |
| R2-KS-04 | INFO | file provider | `res/xml/file_paths.xml` exposes ONLY `files-path apk/` (`internal_apk`) and `cache-path exports/` — no root/`external` paths; FileProvider `exported="false"`. | `aapt dump xmltree` (provider portion). | None — PASS. |
| R2-KS-05 | INFO | quick-capture widget | Receiver exported=false; immutable `PendingIntent` (flags `0x0C000000` = `FLAG_UPDATE_CURRENT|FLAG_IMMUTABLE`) launching MainActivity MAIN/LAUNCHER with `...intent.extra.QUICK_CAPTURE`; vault is appended only after `authenticated` gate (`services/WidgetLaunchPolicy.kt`; `QuickCaptureWidget.java:27-32`). | Review receiver intent-filters + PI flags in decoded manifest/dex. | None — PASS. |
| R2-KS-06 | INFO | permissions | Permissions: `RECORD_AUDIO` (voice notes), `USE_BIOMETRIC`/`USE_FINGERPRINT` (biometric vault unlock), `INTERNET` + `ACCESS_NETWORK_STATE` (WebDAV sync, DDG web search, plugin store fetch, LocalSend) — every permission maps to a live feature; no external-storage permission. | `aapt dump badging`. | None — PASS. |
| R2-KS-07 | INFO | FLAG_SECURE | `MainActivity.java:389` `getWindow().addFlags(8192/*FLAG_SECURE 0x2000*/)` present in the RELEASE dex (not debug-gated). | `grep -n 'addFlags(8192)' jadx sources`. | Confirm on device (recents/tile) → `DYNAMIC-DEFERRED`. |

## Triage note (phase-161, 2026-08-19)

- **No findings were recorded here.** The Kali dynamic/instrumented pass
  (phase-160) was BLOCKED on CI: `workspace/phase-160/.blocked` + `.no_work` +
  3 attempts + timeout 360 — no rooted Android device/emulator is available on
  the Linux runner, so no dynamic findings could be produced or appended.
- The static re-review performed during phase-161 (finding → fix phase) is
  documented in `workspace/phase-161/REPORT.md` and generated phases 170-174:
  - **phase-170** — round-1 APK finding Phase-32-NEW-01 (MEDIUM: lingua
    `language-models/` 80.2 MB pack, 24/75 languages used) + Phase-32-NEW-02
    (LOW: no ABI splits).
  - **phase-171** — Phase-32-NEW-03 (INFO: v2-only signing) + Phase-32-NEW-04
    (INFO: plugin-manifest cert-pin placeholder → operator runbook).
  - **phase-172..174** — feature phases (editor/canvas productivity, FileTransfer
    plugin over LocalSend, reading & authoring UX).
- Re-running the dynamic pass requires operator-provided rooted hardware/AVD;
  the pipeline cannot supply it on this runner.
