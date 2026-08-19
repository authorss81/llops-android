# Phase 160: Kali Static Security Analysis of the release APK (dynamic deferred) [NOT STARTED]

You are working on **InkFlow/Noteflow**. You are Kali, running on the CI Linux
runner. This runner has NO rooted Android device/emulator — so DYNAMIC tests
(frida hooks, objection memory dump, `run-as` DB forensics, installing the APK
on an emulator) are NOT possible here. That is fine: your deliverable is a
COMPLETE STATIC security analysis of the fresh release APK, evidence-backed,
using the tools that DO work on this runner. Dynamic-only checks are explicitly
listed as "not testable on this runner — deferred to operator device" and are
NOT a blocker.

Read `docs/pentest-plan.md`, `docs/ARCHITECTURE.md` and `docs/phase-status.md`
first.

## Step 0 - Environment (tools that exist on this runner)
- `sudo apt update && sudo apt install -y jadx apktool sqlite3 python3-pip default-jdk unzip`
- `pip install --user mobsf` OR `docker run -p 8000:8000 opensecurity/mobile-security-framework-mobsf`
  (if neither works, note it and rely on jadx/apktool/aapt — evidence over tooling).
- Do NOT attempt to boot an emulator or install the APK — the runner cannot.

## Step 1 - Get the target APK
- The workflow pre-downloads the `noteflow-release-apk` artifact to
  `app/build/outputs/apk/release/app-release.apk`. Verify it exists
  (`ls -la`), compute its SHA-256, and cross-check against the metadata in
  `docs/kali-report-round2.md` (phase-159 recorded the expected APK SHA-256,
  versionCode/Name, signer cert). If it is missing, fail the report with the
  exact error — do NOT rebuild from source (phase-159 owns the build).

## Step 2 - STATIC analysis (the real deliverable)
Run each of these against the BUILT APK and record findings in
`docs/kali-report-round2.md` (YOUR file, schema `R2-xxx | Severity | Area |
Evidence (file:line or command output) | Reproducer | Suggested fix`):
1. **Manifest audit** (apktool d / aapt dump badging / jadx):
   - Exported components (activities/receivers/services/providers) — which are
     `android:exported="true"` and reachable, their intent filters, the
     QuickCapture widget receiver.
   - `allowBackup` (must be false), `usesCleartextTraffic` /
     `networkSecurityConfig` (must not be cleartext), `FLAG_SECURE`,
     `data_extraction_rules.xml`, file provider paths (`file_paths.xml`).
   - Permissions: any unnecessary/`INTERNET`-without-feature, external storage.
2. **Code audit** (jadx decompile; R8-obfuscated — still audit):
   - Re-verify round-1 claims the fix phases made: plaintext auxiliary files,
     restore non-transactionality, tamper-HMAC cleared on restore, dex/
     fileProvider exposure, WebDAV cleartext.
   - Crypto: PBKDF2 600k, AES-GCM params, DEK handling, no hardcoded keys/secrets
     in the dex/strings.
   - The downloadable-plugin runtime (`RuntimePluginLoader`,
     `SignatureVerifiedPluginRuntime`, `PluginManifestFetcher`): cert-pin
     verification path, capability-facade (never direct DB/keystore handles).
   - SSRF/redirect surfaces: WebDav sync, web search, web capture, HttpsTitleFetcher.
3. **Packaging/ABI/size** (from the APK, no device): lingua `language-models/`
   corpus size vs used languages, ABI splits, v2-vs-v3 signing (apksigner),
   R8 mapping presence, baseline profile.
4. **MobSF report** if it runs (manifest/static/permission analysis) — append its
   key rows to your report.

## Step 3 - Dynamic items: DECLARE, do not attempt
For each item that needs a device (frida PBKDF2 timing, DEK read hooks,
zeroization-on-lock proof, memory dump searches, run-as sqlite pull, emulator
restore/import attacks), append ONE line to the report:
`DYNAMIC-DEFERRED | <check> | not testable on this runner (no rooted
device/emulator) — operator must run on hardware; reproducer steps below`.
List the reproducer steps so an operator CAN run them later.

## Step 4 - Write incrementally, commit + push
- Append each finding to `docs/kali-report-round2.md` as you finish each batch
  (Step 2.1, 2.2, 2.3, 2.4, Step 3). Commit + push after EVERY batch — never
  save them up. `git add -A && git commit -m "llops: phase-160 kali static batch N" && git push`.
- This is CRITICAL: the phase previously blocked because no work was ever
  committed. Every batch must leave the tree dirty→committed.

## Definition of done
- APK SHA-256 verified against phase-159's recorded metadata.
- Static analysis COMPLETE: manifest audit + code audit (jadx) + packaging/
  size/signing + MobSF (if runnable) all recorded in
  `docs/kali-report-round2.md` with evidence and severities.
- Every dynamic check DECLARED as `DYNAMIC-DEFERRED` with operator reproducer
  steps (no fake "tested OK" claims).
- Findings appended INCREMENTALLY, each batch committed + pushed.
- `workspace/phase-160/REPORT.md`: env used, target APK sha, tool versions,
  what was proven statically vs what is deferred.
- Commit + push.

## Constraints
- Attack ONLY the app + its own artifacts (the release APK). Do NOT attack
  third-party servers/other users.
- Do NOT edit `.github/workflows/`. Do NOT fix findings in app code — findings
  go to `docs/kali-report-round2.md`; a later phase turns them into fix phases.
- Never log real secrets/decrypted content beyond what's needed as evidence.
- No dynamic claims without a device — everything unverifiable here is marked
  `DYNAMIC-DEFERRED`.
- If a tool genuinely cannot run, document WHY with the error, then move on.