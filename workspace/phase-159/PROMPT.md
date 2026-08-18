# Phase 159: Build Release APK (verify it assembles without error) [NOT STARTED]

You are working on **InkFlow/Noteflow**. The Kali dynamic-pentest phase
(phase-160) needs a FRESH, signed release APK to attack — and it must NOT spend
its own budget building one. YOUR job is to produce that APK and PROVE it builds
without error. This is a build/verification phase: you do NOT change app feature
code and you do NOT fix app-code bugs. If the build fails, you diagnose and fix
BUILD CONFIGURATION so it assembles cleanly.

## Step 1 - Environment check
- The workflow restores the release keystore to `$RUNNER_TEMP/release.keystore`
  and exports `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
  Verify:
  - `printenv KEYSTORE_FILE` (if unset, the release build will fail closed —
    that is EXPECTED and correct; record it and stop with an explanation).
  - `test -f "$KEYSTORE_FILE"` exists and is a valid JKS/PKCS12 keystore
    (`keytool -list -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD"`).
- Confirm the current commit sha: `git rev-parse HEAD`.

## Step 2 - Build the release APK (the core of this phase)
- Run `gradle assembleRelease` (NOT `./gradlew` — there is no wrapper jar).
- On ANY failure: do NOT paper over it. Diagnose with the failing task, the
  compile/test error and `file:line` evidence. Fix BUILD CONFIGURATION only
  (e.g. `app/build.gradle.kts`, signing config, resource files, `proguard-rules`,
  manifest). Never weaken the fail-closed signing behavior
  (`B1Plat01ReleaseSigningTest`, `docs/RELEASE.md`). If the root cause is an
  app-code defect that would require app-code changes, STOP and report it in
  `workspace/phase-159/REPORT.md` + the Kali report file (see below) under
  "build blockers" — do NOT edit app source.
- Rerun until `assembleRelease` completes with `BUILD SUCCESSFUL`.

## Step 3 - Verify the artifact (do not hand a broken APK to Kali)
- The APK must exist at `app/build/outputs/apk/release/app-release.apk`.
- Confirm it is signed, not just built:
  - `apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk`
    (Android SDK build-tools on PATH) OR `jarsigner -verify` as a fallback.
  - Record the `signer` DN + the SHA256 fingerprint in the report.
- Compute and record: `sha256sum app/build/outputs/apk/release/app-release.apk`
- Sanity-check size (>1 MB) and that R8 minify ran (release build config has
  `minifyEnabled`). Note the versionCode/versionName from
  `app/build.gradle.kts` (or `apkanalyzer manifest` if available).
- Do NOT run the full unit-test suite here (other phases own that); but if
  `assembleRelease` ran `lint`/`test` as part of the lifecycle and they failed,
  that is part of "builds without error" — surface it.

## Step 4 - Record and hand off
- Update the **`docs/kali-report-round2.md`** file (create it with a header if
  missing) "APK target" section: exact filename, commit sha it was built from,
  versionCode/versionName, signing fingerprint, and the SHA256. This is what
  phase-160 (Kali) reads to verify it has the right target.
- Write `workspace/phase-159/REPORT.md`: env check results, build command, any
  build-config fixes you made (with `file:line`), the apksigner output, the
  SHA256, and a clear "READY FOR KALI" statement.
- The workflow uploads `app/build/outputs/apk/release/app-release.apk` as the
  `noteflow-release-apk` artifact automatically for this phase — you do NOT need
  to push the binary to git. Verify in REPORT.md that the file exists at that
  path before finishing.

## Definition of done
- `gradle assembleRelease` completes with `BUILD SUCCESSFUL` (no error).
- `app/build/outputs/apk/release/app-release.apk` exists, is signed, and its
  SHA256 is recorded.
- `docs/kali-report-round2.md` "APK target" section updated.
- `workspace/phase-159/REPORT.md` written.
- Commit + push.

## Constraints
- NO app feature-code changes. Build configuration fixes are allowed and must be
  documented with `file:line` + why.
- Do NOT edit `.github/workflows/`.
- Never weaken fail-closed release signing. Never log passwords or keystore
  contents — the keystore path/password are env vars, reference them, don't dump.
- If the build fails for an app-code reason, mark it clearly in the report and
  the Kali report file rather than hacking the build to skip it.