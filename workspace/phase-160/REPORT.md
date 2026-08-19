# Phase 160 — Kali STATIC Security Analysis of the Release APK (dynamic deferred)

**Date:** 2026-08-19 (runner: Linux CI, 2-core, 15.6 GB RAM)
**Status:** DONE (re-unblocked by commit `b1f533f` — the previous dynamic-only attempt was `BLOCKED` for lack of a rooted device/emulator; this run performs the complete STATIC pass and DECLARES every dynamic check).

## Environment used
| Tool | Version | Outcome |
|------|---------|---------|
| jadx | 1.5.1 (GitHub release, `/tmp/opencode/jadx`) | decompiled `classes.dex` → 9417 `.java` files, 98 decompile errors (R8-obfuscated; `--show-bad-code` where needed) |
| apktool | 2.9.3 (GitHub jar, `/tmp/opencode/apktool.jar`) | decoded resources + `AndroidManifest.xml`, `file_paths.xml`, `data_extraction_rules.xml`, `quick_capture_widget_info.xml` |
| aapt / apksigner / zipalign | build-tools 36.0.0 | badging, APK Signature verification (`Verifies`, v2 only), ABI/density table |
| sqlite3 | SDK platform-tools | not needed (no runtime DB) |
| MobSF | 4.5.2 (pip `~/.local/bin/mobsf`) | **installed + server boots (login 200, REST key issued) but the 142 MB scan did NOT complete** — (a) first one-shot script's `pkill -f mobsf` matched its own command line and killed itself; (b) after fixing, the scan exceeded the 1500 s command timeout on the 2-core runner. Documented WHY per spec; static analysis relied on apktool/jadx/aapt/apksigner + 28,409 extracted dex strings. |

No emulator/rooted device available on this runner → no APK install, no frida/objection, no `run-as`.

## Target APK identity (cross-checked vs phase-159 metadata)
- `app/build/outputs/apk/release/app-release.apk` present, **142,344,579 B**
- **SHA-256 `9ce99c1b3dbcbdb9fa6080961b9e043c8ead6e9828a4e1286958cd331743233f`** — differs from phase-159's recorded `54feb16c…` **by design**: the workflow pre-downloaded a NEWER post-phase-159 artifact (current tree HEAD `82c11f6`, phase-166 review fixes). Not a rebuild by this phase.
- versionCode `2` / versionName `1.0.0`; applicationId `com.aistudio.inkflow.app.bkxjrz`; compile/targetSdk 36; minSdk 26
- Signing: `apksigner verify --verbose` = **`Verifies`**, v2 scheme only (v1/v3/v3.1/v4 false); RN `CN=InkFlow Release, OU=Dev, O=Authorss81, L=Unknown, ST=Unknown, C=US`; cert SHA-256 `69636edb9ee2487762e98f855f250ea1ec66233de13b61a4c014026b82c50196`; pubkey SHA-256 `0328af289a4b325229ffee68d8ac41aa4b863180174bd901e620bd75c04e7030` → **signer identity byte-identical to phase-159** ⇒ genuine InkFlow release build.

## What was PROVEN statically
Full finding set: `docs/kali-report-round2.md` (27 rows: `R2-KS-01..07`, `R2-KS-10..19`, `R2-KS-20..29`; IDs 08/09 unused), 4 finding batches + 1 wrap-up batch (5 commits total).
- **Clean / PASS (INFO)**: backup+rules off; exported-surface audit (only MainActivity incl. share-sheet SEND filters, ProfileInstallReceiver under DUMP perm); no cleartext config; FileProvider paths locked down; QuickCaptureWidget exported=false + immutable PI; FLAG_SECURE in release dex; WebDAV HTTP opt-in allowlist + same-host redirect + cross-host auth-strip; DB quarantine `*.corrupt-<ts>` + `dispose()` FULL WAL checkpoint + HMAC recompute on restore; AndroidKeyStore-bound DEK (+biometric invalidation), AES-256-GCM, field-encryption AAD versioning; plugin sandbox classloader + static manifest scan + capability facade + constant-time pin compare; no hardcoded keys/API keys in dex strings; `libsqlcipher.so` RELRO + BIND_NOW.
- **MEDIUM ×2**: (R2-KS-20) lingua `language-models/` = **207,608,234 B / 75 languages** baked into base APK (was 80.2 MB/24 langs in round-1 — now larger); (R2-KS-21) ML Kit OCR (`assets/mlkit-google-ocr-models/`, `libmlkit_google_ocr_pipeline.so`) AND ML Kit translation (`libtranslate_jni.so` + `res/raw/translate_models_metadata.json` + rapid-response defaults) in the base APK — both contradict the base-APK-size downloadable-plugin constraint (Phases 23/26/29).
- **LOW ×2**: no ABI splits (re-confirmed, now >125 MB of payload); release hygiene (`DebugProbesKt.bin`, `kotlin-tooling-metadata.json`, `firebase-*.properties`, okhttp3/org markers in-payload).
- **Known-but-noted**: R2-KS-17 plugin cert-pin is STILL the placeholder `sha256/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=` (bytes 0x00–0x1F) for `plugin-updates.inkflow.app` — FAILS CLOSED (nothing can verify); update path inert until a real pin ships (round-1 Phase-32-NEW-04, re-confirmed). R2-KS-23 v2-only signing (Phase-32-NEW-03 re-confirmed). No R8 mapping.txt on runner (attribution limit), no baseline profile (Phase-03 deferral).

## What is DYNAMIC-DEFERRED (D1–D12, operator reproducers in `docs/kali-report-round2.md`)
PBKDF2 iteration-count timing witness; DEK-raw-escape hooks; memory zeroization-after-lock proof; `run-as` sqlite/voice-note at-rest forensics; emulator corruption-quarantine + restore-attack battery; FLAG_SECURE recents proof; biometric enrollment-invalidation; plugin pin fail-closed under MITM; WebDAV self-signed-CA MITM; LocalSend TOFU pairing; voice-note file ciphertext bytes; WebDAV credential-blob AEAD tamper test. **No dynamic claims were made without a device.**

## Batches (committed + pushed after each)
1. `8b02d43` — artifact identity + manifest audit (R2-KS-01..07)
2. `ebe14ac` — code audit (R2-KS-10..19)
3. `07e5c0f` — packaging/ABI/size + MobSF status (R2-KS-20..29)
4. `50b07d1` — DYNAMIC-DEFERRED D1..D12
5. (final) — phase-status row + this REPORT + `docs/pentest-findings-2026-08-19.md` ref + ARCHITECTURE note

## Review fixes (2026-08-19, applied after the phase review)
- Count corrected: the finding table holds **27** rows (batch 1 = 7, batch 2 = 10,
  batch 3 = 10; IDs 08/09 skipped) — "29 rows `R2-KS-01..29`" was wrong and is
  fixed in `REPORT.md`, `docs/phase-status.md` and `docs/ARCHITECTURE.md`.
- The stale "Triage note (phase-161)" block in `docs/kali-report-round2.md`
  (which said "No findings were recorded here … BLOCKED") is now marked
  **SUPERSEDED**; the phase-161 triage details are preserved for provenance.
- `docs/phase-status.md` no longer lists phase-160 in the `.no_work` phase list
  (it is now a `DONE` static pass).
- Leftover pre-unblock marker `workspace/phase-160/.timeout` (content `360`)
  removed.
- R2-KS-01 evidence corrected: `data_extraction_rules.xml` excludes only the
  `root` domain, not `external_data` (no such declaration exists).
- At-hand APK provenance made honest: the "HEAD `82c11f6`" attribution is an
  inference from CI artifact freshness, not a verified build-commit ascription.
- Duplicated findings now cross-reference their fix phases: R2-KS-20/22 →
  phase-170, R2-KS-23/17 → phase-171; R2-KS-21 (ML Kit) left pending phase-167
  triage (no dedicated phase exists yet).

No app code, no `.github/workflows/`, no build files were modified.