# Phase 117 — Build Release APK (verify it assembles without error)

Date: 2026-08-17. Build & verification phase — **NO app feature-code changes,
NO app-code bug fixes**. The goal is a fresh, properly-signed release APK for
phase-118 (Kali dynamic pentest) to attack, built here so phase-118 does not
spend its budget building one.

## Step 1 — Environment check (PASS)

- `KEYSTORE_FILE` = `/home/runner/work/_temp/release.keystore` — set by the
  workflow from the `RELEASE_KEYSTORE_B64` secret (secret itself is not exported
  raw; the expanded file+credentials are). `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD` all set.
- `keytool -list -storepass "$KEYSTORE_PASSWORD"` → valid **PKCS12**, 1 entry,
  alias `inkflow`, PrivateKeyEntry. Cert SHA-256:
  `69:63:6E:DB:9E:E2:48:77:62:E9:8F:85:5F:25:0E:A1:EC:66:23:3D:E1:3B:61:A4:C0:14:02:6B:82:C5:01:96`
- Commit sha: `dd0c5f59` (`llops: phase-116`).
- Toolchain: Gradle 8.13 (system `gradle`, no wrapper — per AGENTS.md),
  Temurin JDK 21.

## Step 2 — Build (PASS, first attempt, no build-config changes)

Command: `gradle assembleRelease`

Result: **BUILD SUCCESSFUL in 8m 6s** — 113 actionable tasks, 113 executed,
0 failures. Key tasks that ran: `:app:compileReleaseJavaWithJavac`,
`:app:minifyReleaseWithR8` (R8 minify ON), `:app:lintVitalRelease` (passed —
no lint-vital errors), `:app:packageRelease`, `:app:assembleRelease`.

No build-configuration fixes were needed. The fail-closed B1-PLAT-1 release
signing (`app/build.gradle.kts:36-60`, `:133-153`) accepted the restored
keystore and produced a genuinely release-signed APK — nothing was weakened.

## Step 3 — Artifact verification (PASS)

- Exists: `app/build/outputs/apk/release/app-release.apk` (142,234,607 bytes).
- `apksigner verify --verbose --print-certs` (SDK build-tools 37.0.0):
  - `Verifies` / `Verified using v2 scheme: true` (v1/v3/v4 false — v2-only).
  - Number of signers: 1.
  - Signer DN: `CN=InkFlow Release, OU=Dev, O=Authorss81, L=Unknown, ST=Unknown, C=US`
  - Signer cert SHA-256: `69636edb9ee2487762e98f855f250ea1ec66233de13b61a4c014026b82c50196`
    (= the keystore entry above; RSA 2048-bit key).
  - **NOT** the Android debug key → B1-PLAT-1 (phase-57) fail-closed release
    signing confirmed on the artifact.
- `sha256sum`: `20849bcfcc72bfc649057464d17bd26f13bd74b76a89f1f0232244816ec85d3d`
- Size sanity: 142.2 MB > 1 MB. R8 minify confirmed (`:app:minifyReleaseWithR8`
  executed). versionCode `2` / versionName `1.0.0` (VERSION_CODE/VERSION_NAME
  env unset → `app/build.gradle.kts:18-19` defaults), confirmed via
  `apkanalyzer manifest version-code/version-name`.

Note (per PROMPT): the full unit-test suite was intentionally NOT re-run here
(phase-116/other phases own that); `assembleRelease`'s own lifecycle steps
(compile + lintVitalRelease) all passed within the successful build.

## Step 4 — Hand-off (PASS)

- `docs/security-report-round2.md` "Audit metadata → APK target" section updated
  with exact filename, commit, version, signing fingerprint, SHA-256 (see the
  section — phase-118 reads it to verify the right target).
- This REPORT.md written.
- The workflow uploads `app/build/outputs/apk/release/app-release.apk` as the
  `noteflow-release-apk` artifact automatically; file confirmed present at that
  path above (not pushed to git, per the PROMPT).

## READY FOR KALI

The phase-118 target is ready: a fresh, v2-signed (real InkFlow Release
keystore), R8-minified release APK built from `dd0c5f59`, SHA-256
`20849bcfcc72bfc649057464d17bd26f13bd74b76a89f1f0232244816ec85d3d`, at
`app/build/outputs/apk/release/app-release.apk`. No app-code changes were made
in this phase.

## Addendum — post-review fixes (2026-08-17)

The phase review (see `logs/phase-117.review.log`; applied in the fix run
`https://github.com/authorss81/llops-android/actions/runs/32043663648`) produced
7 findings. Disposition:

1. **Self-attested claims (REVIEW finding 1)** — the gradle apksigner / sha256sum
   outputs were not retained in git and the 142 MB binary is only the ephemeral
   `noteflow-release-apk` CI artifact (binaries stay out of git). Partial fix
   (workflow edits are prohibited): the verification commands + expected values
   are now recorded in `docs/security-report-round2.md` "APK target"; phase-118
   is instructed to independently re-verify the SHA-256 + signer cert before
   attacking. This addendum records the fix-run URL; the milestone SHAs
   (`dd0c5f5` build source, `20849bcf…` artifact, `69636edb…` cert) remain the
   authoritative identifiers.
2. **Best-effort artifact upload (finding 2)** — `llops.yml:237-244` uploads with
   `continue-on-error: true`, so a failed upload can coexist with a DONE marker.
   Not fixable from this phase (`.github/workflows/` is off-limits); now
   documented in the APK-target section with an explicit "DO NOT attack a missing
   or SHA-mismatched artifact" instruction for phase-118.
3. **v2-only signing (finding 3)** — known INFO (phase-32-NEW-03), disclosed,
   no fix needed.
4. **Doc clarification (finding 4)** — `docs/security-report-round2.md` header now
   distinguishes the round-2 SOURCE-audit commit (`c813c99`) from the APK build
   source (`dd0c5f5`).
5. **Stale JDK claim (finding 5)** — `docs/ARCHITECTURE.md` corrected: CI runs
   Temurin JDK 21 (workflow `:131-132`, `:304-305`), not JDK 17. Also corrected
   the stale "release falls back to debug keystore" note to the real fail-closed
   B1-PLAT-1 behavior.
6. **applicationId (finding 6)** — `com.aistudio.inkflow.app.bkxjrz`
   (`app/build.gradle.kts:15`) now recorded in the APK-target section (verified
   against `docs/ARCHITECTURE.md:993` known-gotchas).
7. **Positives (finding 7)** — no action.

No app source, no build config, no `.github/workflows/` edits made.