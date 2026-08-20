# Phase 190: APK self-update — uploading an APK of the SAME app must update the installed app [NOT STARTED]

You are working on **InkFlow/Noteflow**. USER REQUIREMENT: "if I upload an APK of the same
app, it should update the app." An update flow exists (`AppUpdateDialog`,
`ui/components/Dialogs.kt:33-290`; `UpdateService`, `services/UpdateService.kt`) but the
user reports the self-update does NOT actually work reliably. This phase makes
"upload/select an APK of the same app (same signer, newer version) -> the app updates"
WORK end-to-end.

Read `docs/ARCHITECTURE.md`, `docs/phase-status.md`, `docs/RELEASE.md`, and the
B1-PLAT-7 row in `docs/security-report.md` first (the trust model must stay intact).

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-190 step N: <desc>" && git push`
after EVERY step. Never sit on uncommitted work.

## Step 1 - Reproduce + trace (commit it)
- Trace the whole path in `UpdateService.kt`:
  - `inspectApkFile` (`:60-116`): `getPackageArchiveInfo(apkFile.absolutePath, 0)`
    (`:67`), then `verifyApkSignature` (`:84`) which re-parses with
    `GET_SIGNING_CERTIFICATES` (`:254`) and compares `apkContentsSigners` against
    the INSTALLED app's signers (`:268-294`). `isNewer` = versionCode OR
    versionName comparison (`:97`, `:230-246`).
  - `installApk` (`:177-228`): `UpdateTrustPolicy.mayInstall` gate, TOCTOU
    re-verify, stage into `filesDir/apk/`, FileProvider URI, ACTION_VIEW installer.
- Identify why a same-app, same-signer, newer APK might NOT update:
  1. **Package-identity check missing**: `inspectApkFile` never verifies the APK's
     package name == `context.packageName`. A same-signer different-package APK
     would be offered as an "update". Add the package-name equality check.
  2. **`getPackageArchiveInfo` flags=0** at `:67`: versionName/versionCode are read
     fine, but signing is only re-parsed later — confirm the two parses agree and
     a signed APK truly yields signers (v2/v3 scheme) on the target APIs.
  3. **Version compare edge cases**: same versionCode but versionName e.g. "1.0" vs
     "1.0.0" (digit-filter compare). Confirm an actually-newer APK is never
     classified stale.
  4. **Installer not launched** (`installApk` returns false): staging into
     `filesDir/apk/` failing, FileProvider path mismatch
     (`app/src/main/res/xml/file_paths.xml:5` = `files-path name="internal_apk" path="apk/"`),
     missing `ACTION_VIEW` handler, or the picker MIME filter
     (`Dialogs.kt` `apkPickerLauncher` uses `application/vnd.android.package-archive`)
     not matching how the user "uploads".
  5. **"Upload" entry points**: the user uploads an APK — via the AppUpdateDialog
     "Select Local APK File" picker, or by pushing a file the app should auto-detect.
     Verify the picker path works and consider also accepting an APK the user
     places anywhere reachable (share-sheet ACTION_SEND of an APK into the app is a
     natural addition).
- COMMIT this step with the trace + any reproduced failure.

## Step 2 - Fix so "same app APK -> update" works
- Add the package-name equality check (same package + same signer + newer version =
  offer update; otherwise honest "not the same app" refusal).
- Fix whatever breaks the install launch (staging / provider / intent), and make the
  flow surface a clear non-alarming message at every failure point (never silent).
- Ensure the trust model stays B1-PLAT-7-compliant: UNTRUSTED_LOCAL still requires
  the explicit confirmation dialog, signer still re-verified at install time, only
  the app's own signer is accepted as "same app".
- If adding share-sheet APK capture: reuse the existing `PendingShareState` /
  `ShareCaptureMode` pattern (phase-158) so an APK shared into the app is staged to
  app-private storage and offered through the update dialog — never scanned from
  public Downloads.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests: package-identity gate (same package = offer, different package =
  refuse), same-signer+newer = hasUpdate, older/equal = stale, versionName edge
  cases, and source pins for the FileProvider staging path + the
  `mayInstall`/re-verify gates.

## Definition of done
- Uploading/selecting an APK of the same app (same signer, newer version) offers the
  update and launches the installer; a different-app APK is refused with honest copy;
  trust gates + re-verify intact.
- `workspace/phase-190/REPORT.md`: reproduced failure, fixes, test list.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Keep B1-PLAT-7 intact: never scan public Downloads; UNTRUSTED_LOCAL always behind
  explicit confirmation; signer re-verified at install time.
- Never log APK contents or any decrypted data.