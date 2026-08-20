# Phase 190 — Self-update: "an uploaded APK of the same app must update the app"

Delivered 2026-08-20. Commits: `c71c059` (step 1 trace), `48dc50a` (step 2 implementation), (step 3 tests + docs).

## Problem / user requirement

"Uploading an APK of the same app should update the installed app." Three of the
five failure causes traced in `STEP1_TRACE.md` were LIVING, pre-existing defects
in the update path; two more were untraceable paths (in-desktop-browser heap
read + a share-sheet dead-end) that made the supported gesture fail:

1. **No package-identity gate — the core defect.** `inspectApkFile` compared
   ONLY signer + version (`UpdateService.kt` pre-190). A same-signer but
   DIFFERENT-package APK (e.g. a test build with the `com.authorss81.noteflow`
   namespace manifest vs the runtime `com.aistudio.inkflow.app.bkxjrz`) was
   offered as an "update"; the platform installer then refused it ("App not
   installed") or installed it as a separate app.
2. **In-heap picker read.** The document picker read the whole APK through
   `ImportExportService.readUriBytes` into a heap `ByteArray` and copied it a
   SECOND time with `File.writeBytes` — a 100+ MB APK was 2–3x in heap at once
   (OOM/ANR on the app's low-RAM target devices at the exact moment of update).
3. **Misleading refusal copy.** `!hasUpdate` displayed "Selected APK version
   (…) is equal to or older than current version" even when the real refusal
   was a signature mismatch (and pre-fix would have been a different package).
4. **versionCode Int wrap.** `getCurrentVersionCode`/display downcast
   `longVersionCode` to `Int`; a code > 2^31-1 read as negative and a
   genuinely-newer APK was classified "older".
5. **Share-sheet APK = dead-end.** A tested APK shared INTO the app (ACTION_SEND
   "upload" gesture) was framed as note content — there was no path from a
   shared `.apk` stream to the update offer at all.

## Fix

All identity/version/messaging decisions moved to a new pure-JVM policy
`services/UpdateApkDecisionPolicy.kt`; `UpdateService`, the dialog, and the
share path route through it.

- **Package-identity gate** (`UpdateApkDecisionPolicy.samePackage`) at OFFSERTIME
  (`inspectApkFile`) and INSTALL time. Install-time re-verify is now a UNIFIED
  `verifyApkIdentity` — one `GET_SIGNING_CERTIFICATES` parse returns package +
  signers and both must match, so offer-time and install-time checks read the
  SAME parse (and the same bytes at install, closing the B1-PLAT-7 swap window).
  The old `verifyApkSignature` (signer-only) is deleted. Identity comes from
  `context.packageName` (runtime), never a hardcoded namespace string.
- **Long versionCode compare.** `inspectApkFile` reads `longVersionCode` (API
  28+) / `versionCode.toLong()` (pre-P) and delegates the decision to
  `UpdateApkDecisionPolicy.isNewer(code, name)` in Long — the Int wrap is gone.
  The `UpdateInfo.newVersionCode: Int?` display field is untouched (the
  offer-time gate has already run by then).
- **versionName tie-breaker hardened.** Only the LEADING digit-run of each
  dot-segment counts (`takeWhile`), so `2.0.0-rc1` compares equal to `2.0.0`
  (pre-release < release; the old digit-`filter` leaked `rc1` → `101`).
- **Streaming picker.** `AppUpdateDialog` stages the selected URI via the new
  `ImportExportService.stageApkUriToFile(context, uri)` — bounded 64 KiB buffer,
  256 MB cap (`MAX_APK_INPUT_BYTES`), over-budget fails loudly, output is a
  DIRECT `cacheDir` child (B1-PLAT-7-RESTRICTED; found by the next
  "Scan App Storage"). The dialog ALSO auto-scans app-private storage ONCE on
  open so a staged APK is offered immediately.
- **Honest refusal copy.** Different package → `differentAppMessage()`; signer
  mismatch → `signatureMismatchMessage()`; both trust-neutral (never "new
  update detected" — B1-PLAT-7 social-engineering half preserved).
- **Share-sheet APK interception** (`MainActivity.readShareIntent`). When an
  ENTIRE share is APK stream(s) (`isApkStream`: exact package MIME or `.apk`
  filename via `OpenableColumns.DISPLAY_NAME`), the file is staged
  app-privately + a non-secret snackbar ("N APK file(s) received and staged…")
  — and `return`s BEFORE a note clip is ever built. The confirmed note-clip
  path for non-APK shares is unchanged. The locked-vault snackbar policy is
  respected (non-secret message only).

Trust semantics unchanged: a same-package + same-signer + newer APK is still
`UNTRUSTED_LOCAL` until a remote-verified official channel exists, and its
install still fails closed behind the explicit confirmation (B1-PLAT-7). Public
Downloads are never scanned.

## Verification

- `gradle compileDebugKotlin` — clean.
- `gradle assembleDebug` — green (SHA-256 of APK recorded at CI).
- `gradle testDebugUnitTest` — **2522 total / 3 failed**; all three are the
  documented pre-existing flakes/failures, reproduced in isolation:
  `Phase148UiFailureTextScrubTest` (pre-existing UNC-path failure, fails in
  isolation, untouched — documented in AGENTS.md at every phase since 148),
  `Phase151MarkdownMainThreadPerfTest` + `WikiLinkParserCacheUnitTest` (timing/
  concurrency flakes — **pass in isolation**). Nothing in phase-190 touches
  those files.
- New `Phase190ApkSelfUpdateTest` (13 tests): 6 real pure-JVM policy tests
  (identity, Long compare incl. the >2^31 wrap case, versionName incl. the
  rc1-suffix regression from the step-2 run, `isApkStream`, refusal copy) + 7
  source-wiring pins (B1Plat07 style: no `verifyApkSignature` leftover, package
  gate BEFORE signer gate, Long compares, install-time re-verify, streaming
  picker not heap, honest refusal copy, dialog scan, share interception before
  the clip path, bounded staging).
- Re-ran `B1Plat02ShareConfirmationTest` + `B1Plat07UpdateTrustTest` targeted —
  green (regions/anchors preserved).

## Files

- `app/src/main/kotlin/com/authorss81/noteflow/services/UpdateApkDecisionPolicy.kt` — NEW.
- `app/src/main/kotlin/com/authorss81/noteflow/services/UpdateService.kt` — package gate, Long compare, unified `verifyApkIdentity` (+`signaturesMatch`), policy-routed refusal copy.
- `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt` — `MAX_APK_INPUT_BYTES`, `stageApkUriToFile`.
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/Dialogs.kt` — streaming picker, honest refusal copy, app-storage auto-scan.
- `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt` — APK share interception + `displayNameOf`.
- `app/src/test/java/com/authorss81/noteflow/Phase190ApkSelfUpdateTest.kt` — NEW.
- `workspace/phase-190/STEP1_TRACE.md` — step-1 trace (committed `c71c059`).

No schema change, no new dependencies, `.github/workflows/` untouched,
base-APK-size rule intact, `allowBackup="false"` + FLAG_SECURE + encrypted-fields
discipline untouched.