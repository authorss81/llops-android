# Phase 160: Kali Full-Environment Dynamic Pentest (all tools) [NOT STARTED]

You are working on **InkFlow/Noteflow**. Unlike the earlier Kali runs
(`docs/pentest-findings-2026-08-08.md`, static-only; phase-118, blocked), THIS
phase runs with the **full environment installed**: sudo/root, jadx, apktool,
sqlite3, frida, objection, MobSF, and a rooted Android emulator (or rooted
device). The goal is DYNAMIC, evidence-backed testing against a FRESH release
APK. You have a 6-hour budget — use it.

## Step 0 - Environment setup (all tools, no excuses)
- `sudo apt update && sudo apt install -y jadx apktool sqlite3 python3-pip default-jdk adb`
- `pipx install mobsf` or `docker run -p 8000:8000 opensecurity/mobile-security-framework-mobsf`
- `pip install frida-tools objection`
- Android emulator with root (or connected rooted device): `adb devices`,
  boot an AVD (API 33+ recommended for AGSL/dynamic-color checks). If the CI
  runner cannot host an emulator, use a local emulator and attach - document how.

## Step 1 - Get the target APK (built by phase-159)
- The release APK was built and verified by phase-159 and is already available:
  - The workflow restores/keeps it at `app/build/outputs/apk/release/app-release.apk`.
  - If it is missing, download the `noteflow-release-apk` artifact (which the
    phase-159 run uploaded), or as a last resort build:
    `gradle assembleRelease`. Record APK SHA256 + versionCode/Name in
    **`docs/kali-report-round2.md`** (YOUR OWN report file) and cross-check
    against phase-159's recorded SHA256 there so you know you have the right target.
- Run MobSF on the APK and record its report (manifest analysis, exported
  components, permissions, code/binary analysis) into your report file.

## Step 2 - Static re-verification (jadx/apktool on the BUILT APK)
- Decompile with jadx/apktool. Re-verify round-1 claims that the fix phases
  claim to have closed (e.g. plaintext auxiliary files, restore non-
  transactionality, tamper-HMAC cleared on restore, dex/fileProvider exposure).
- Confirm R8 hardening holds (obfuscated classes, no mapping.txt, string
  literals survive).

## Step 3 - Dynamic (frida/objection on the rooted emulator)
- Frida hooks to PROVE behavior, not guess:
  - PBKDF2 iteration count (600k) and whether it runs on the main thread.
  - `SecurityService.readDek` - is DEK obtainable WITHOUT user-auth?
  - `lock()` - is the DEK zeroized AND are decrypted StateFlows cleared?
  - `NoteRepository.saveStrokes` - does pointsJson go out plaintext?
  - The downloadable-plugin runtime (`RuntimePluginLoader`,
    `SignatureVerifiedPluginRuntime`) - can pinning/verification be bypassed or
    a crafted artifact be loaded? Can the capability facade be asked for a
    direct DB/keystore handle?
- objection: `android root disable`, `memory dump` / heap search for
  "password"/"dek"/decrypted content after unlock + after lock.
- DB forensics: `adb shell run-as ... databases/`; pull `noteflow.sqlite`;
  verify which columns are field-encrypted vs plaintext.
- Restore/import attacks (crafted `.nfbackup` / path-traversal import names) on
  the emulator to confirm non-transactional restore is really closed.
- Voice-note / imports plaintext check in `filesDir`.

## Step 4 - Append ALL findings to YOUR OWN report file
- Append every finding to **`docs/kali-report-round2.md`** (a NEW file — do NOT
  touch `docs/security-report-round2.md`, that belongs to the phase-116 source
  audit), schema `R2-xxx | Severity | Area | Evidence (file:line or command
  output) | Reproducer | Suggested fix`. Mark each with `[dynamic]` or
  `[static]` and which tool proved it.
- Write INCREMENTALLY as each test batch finishes; commit + push after each
  batch so no work is lost (your 6h budget means many batches — never save them
  up).

## Definition of done
- Full env (sudo, jadx/apktool/frida/objection/MobSF, rooted emulator) actually
  used - no "no sudo / no device" excuse. If a tool truly cannot run, say why
  with evidence.
- MobSF report + static re-verification + dynamic frida/objection tests + DB
  forensics all performed on the CURRENT release APK.
- Every finding appended to `docs/kali-report-round2.md` (YOUR file),
  evidence-backed, severity-rated, `[dynamic]`/`[static]` marked.
- `workspace/phase-160/REPORT.md` documents env, target APK sha, tool versions,
  and what was dynamically proven vs still not testable.
- Commit + push.

## Constraints
- Attack ONLY the app + its own artifacts (the release APK). Do NOT attack
  third-party servers/other users.
- Do NOT edit `.github/workflows/`. Do NOT fix findings in app code - findings go
  to `docs/kali-report-round2.md`; phase-161 turns them into fix phases.
- Never log real secrets/decrypted content beyond what's needed as evidence.
- If a dynamic test would break the app data, use a throwaway emulator/AVD.