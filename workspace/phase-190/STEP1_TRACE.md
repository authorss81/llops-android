# Phase 190 — Step 1: reproduce + trace (submitted as the step-1 commit)

USER REQUIREMENT: *"if I upload an APK of the same app, it should update the app."*

An update flow exists (`AppUpdateDialog`, `ui/components/Dialogs.kt:33-290`;
`UpdateService`, `services/UpdateService.kt`) but the self-update does not
actually work RELIABLY end-to-end. This document traces the ENTIRE path from a
local APK on the device to the platform installer and pinpoints why a
same-app, same-signer, newer APK can fail to update.

## 1. The two user entry points

### 1a. "Select Local APK File..." (document picker)
`AppUpdateDialog` (`Dialogs.kt:48-90`) launches `ActivityResultContracts.GetContent()`
with MIME `application/vnd.android.package-archive`. The picked `content://`
URI is read with `ImportExportService.readUriBytes(...)` (`ImportExportService.kt:90`,
cap = `MAX_BACKUP_INPUT_BYTES` = 400 MB), written to `context.cacheDir/<name>.apk`
(`Dialogs.kt:63-67`), then `UpdateService.inspectApkFile(context, destFile)`.

### 1b. "Scan App Storage for APK"
`AppUpdateDialog` button → `UpdateService.checkForDownloadedUpdates(context)`
(`UpdateService.kt:128`): scans ONLY `context.filesDir` + `context.cacheDir`
DIRECT children for `*.apk` through `UpdateTrustPolicy.isScanSafeDirectory`
(B1-PLAT-7: public Downloads / `/sdcard` / `/storage/emulated` /
`.../Android/data/...` are structurally refused).

Both entry points land on the same triage function.

## 2. Triage: `UpdateService.inspectApkFile` (`services/UpdateService.kt:60-116`)

1. `getPackageArchiveInfo(apkFile.absolutePath, 0)` (`:67`) — parses the
   manifest with NO flags: gives packageName + versionCode/versionName, but
   signing is NOT read here.
2. `trust = UpdateTrustPolicy.classifySource(hasOfficialChannel())` (`:79`) —
   always `UNTRUSTED_LOCAL` today (no official channel / no remote-verified
   signing key).
3. `verifyApkSignature(context, apkFile)` (`:84` → `:248-299`) — RE-PARSES with
   `GET_SIGNING_CERTIFICATES` (`:254`) and compares the archive's
   `signingInfo.apkContentsSigners` with the INSTALLED app's
   `currentInfo.signingInfo.apkContentsSigners` (`:268-294`). A mismatch
   refuses (`:84-95`).
4. `isNewer = apkVersionCode > currentCode || isVersionNameNewer(...)` (`:97`,
   `:230-246`) — versionCode (Int) first, digit-filtered versionName tiebreak.
5. Returns `UpdateInfo(hasUpdate=...)`.

Then the user confirms the B1-PLAT-7 "untrusted file" dialog (`Dialogs.kt:238-288`,
`UpdateTrustPolicy.confirmationTitle/Message`) and `Install Update` calls
`UpdateService.installApk(context, apkFile, trust, userConfirmedUntrusted=true)`.

## 3. Install: `UpdateService.installApk` (`services/UpdateService.kt:177-228`)

1. `UpdateTrustPolicy.mayInstall(trust, userConfirmedUntrusted)` gate first
   (`:183`) — fail-closed for UNTRUSTED.
2. TOCTOU re-verify: `verifyApkSignature` on the CURRENT bytes (`:192`).
3. Stage a copy into `File(context.filesDir, "apk")` (= `filesDir/apk/`,
   the ONLY dir the FileProvider exposes, `app/src/main/res/xml/file_paths.xml:5`
   `files-path name="internal_apk" path="apk/"`).
4. `FileProvider.getUriForFile(context, "${packageName}.fileprovider", stagedApk)`
   → `Intent(ACTION_VIEW)` with `application/vnd.android.package-archive` +
   `NEW_TASK | GRANT_READ_URI_PERMISSION` → `context.startActivity(intent)`
   (the platform PackageInstaller).

## 4. Why a REAL same-app same-signer newer APK can still fail (reproduced by reading)

### FAILURE 1 — NO PACKAGE-IDENTITY CHECK (the headline gap)
`inspectApkFile` compares signer + version only. It NEVER verifies
`archiveInfo.packageName == context.packageName`. Consequences:
- A same-signer, DIFFERENT-package APK (e.g. a fork, or a rename/build-param
  drift) is offered as an "update" and handed to the platform installer, which
  then refuses with a confusing OS error ("App not installed / different
  signatures") or installs it as a SEPARATE app — the user's update "does not
  work".
- There is no honest "this is not the same app" refusal at all.
- The install-time TOCTOU re-verify (`:192`) checks only the signer, not the
  package, so a same-signer different-package swap between confirm and staging
  is still not caught.
- The runtime applicationId is NOT the Kotlin namespace
  (`com.authorss81.noteflow` vs `com.aistudio.inkflow.app.bkxjrz`,
  `app/build.gradle.kts:15`) — package identity MUST come from
  `context.packageName`, not a hardcoded literal.

### FAILURE 2 — THE PICKER HOLDS THE WHOLE APK IN HEAP (reliability/OOM)
`Dialogs.kt:54-67` reads the entire APK through `readUriBytes` into a
`ByteArrayOutputStream` ByteArray and copies it AGAIN with `file.writeBytes(bytes)`.
A 50–150 MB APK is 2–3× in heap at once → OOM/ANR on low-RAM devices (the
AGENTS.md 2-core/low-RAM rule) right at the moment the user tries to update.
The bounded restore path already streams file-to-file
(`stageBackupUriToFile`, `ImportExportService.kt:2081`); the APK picker does
not.

### FAILURE 3 — THE DIALOG LIES ABOUT REFUSALS (no honest failure surface)
When `inspectApkFile` refuses a file (signature mismatch today, package mismatch
after Failure-1's fix), it returns `hasUpdate=false` + a refusal string in
`releaseNotes` — but `AppUpdateDialog` IGNORES `releaseNotes` for the
non-update case and prints the misleading
"Selected APK version (...) is equal to or older than current version (...)"
(`Dialogs.kt:76-78`). The user is told a WRONG-x file is merely stale. Every
failure point must surface an honest, non-alarming message — silently never.

### FAILURE 4 — VERSION-COMPARE EDGE CASES (false-stale / int-wrap)
- `archiveInfo.longVersionCode.toInt()` + `apkVersionCode > currentCode`
  (`:72-76,97`) wraps for a versionCode above `Int.MAX_VALUE` (2.147 B) — a
  legitimately NEWER long-code APK would compare NEGATIVE and be classified
  stale. (Play's ceiling is ~2.1 B, but the compare should be done in Long —
  `versionCode` is `long` on every API this app targets.)
- `isVersionNameNewer` is the only gate when versionCodes are equal: its
  digit-filter treats "1.0" ≡ "1.0.0" (equal, fine) but is an undocumented
  private free function inside `UpdateService`, not a testable policy.

### FAILURE 5 — SHARE-SHEET "UPLOAD" IS A DEAD END
The app ALREADY registers `ACTION_SEND`/`SEND_MULTIPLE` with `*/*`
(`AndroidManifest.xml:74-83`). Sharing an APK into InkFlow today routes it
through ClipShare (`MainActivity.readShareIntent:1042` →
`SharedClipParser.parse` → `ClipKind.FILES`) and, after the "Clip into
InkFlow?" confirm, stores it as an ENCRYPTED NOTE ATTACHMENT (a file inside a
note), NOT as an update candidate. The natural "upload an APK" gesture does
nothing the user wants and offers no update path. Nothing may scan public
Downloads (B1-PLAT-7) — but a stream the user EXPLICITLY share-sheets into the
app can be staged to app-private storage (`cacheDir`) and offered through the
update dialog (the phase-158 `PendingShareState` discipline: stage to
app-private storage, never auto-install, gate behind the existing
untrusted-confirm).

### CONFIRMED FINE
- FileProvider wiring: staging into `filesDir/apk/` matches
  `file_paths.xml:5`; authority = `${packageName}.fileprovider`
  (`AndroidManifest.xml:25`); only `apk/` + `exports/` (cache) are exposed.
- B1-PLAT-7 trust chain: `classifySource`/`mayInstall`/`isScanSafeDirectory`,
  trust-neutral announcement copy — intact and pinned by
  `B1Plat07UpdateTrustTest`.
- `checkForDownloadedUpdates` only scans app-private dirs; a file staged by the
  picker into `cacheDir` IS found (direct child).

## 5. Reproducer
- Package a new debug build of the SAME app (`com.aistudio.inkflow.app.bkxjrz`,
  bumped `versionCode`, same signer) → `haxeload on device` → open
  ⋮ → App Version & Update → "Select Local APK File". The outcome depends on
  which failure hits: OOM on low-RAM devices (Failure 2), or the dialog
  mis-surfacing refusals (Failure 3), or a same-signer different-package APK
  being offered and then refused by the OS (Failure 1). A share-sheet APK
  silently becomes a note attachment (Failure 5).
- `gradle assembleDebug` + `gradle testDebugUnitTest` are the CI gates; all
  failures above are source-level (no device needed to prove the missing
  package gate / in-heap picker read / misleading dialog copy).

## 6. Fix list (Step 2)
1. New pure-JVM `services/UpdateApkDecisionPolicy.kt` — package-identity gate,
   Long versionCode compare, versionName policy, apk-stream detection, honest
   refusal copy.
2. `UpdateService` — package gate in `inspectApkFile`; unified
   `verifyApkIdentity` (package + signer in ONE GET_SIGNING_CERTIFICATES parse)
   used at offer AND install time; `isNewer` through the policy (keeps a
   `private fun isVersionNameNewer` delegate so the phase-61 source-pin anchor
   survives).
3. `ImportExportService.stageApkUriToFile` — bounded STREAMING stage into
   `cacheDir` (never the in-heap read).
4. `AppUpdateDialog` — use the streaming stage; surface `releaseNotes` honestly
   on refusals; auto-scan app storage once on open.
5. `MainActivity.readShareIntent` — intercept a share whose stream(s) are APKs,
   stage app-private, snackbar, and route to the update dialog (never a note
   clip).