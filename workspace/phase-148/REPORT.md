# Phase 148 report — Logging & error-surface scrub (R2-b2b3-LOG-01 + LOG-02 + LOG-03)

Status: **DONE** — all three findings closed (verified `gradle testDebugUnitTest` = 2012 apps tests green + `gradle assembleDebug` green).

Final grep evidence: no raw `${e.message}` reaches any user-facing error surface or logcat in any of the named files — `app/src/main/kotlin/com/authorss81/noteflow` scan for `\$\{e\.message\}` finds only a KDoc mention in the new policy file plus one out-of-scope residual (`AppFacadeHost.kt:120`, plugin-host result string — documented below).

## Findings fixed

### R2-b2b3-LOG-01 (LOW) — Restore/recovery/backup failure surfaces render raw `${e.message}`

**Before** — raw exception text on user-facing surfaces:
- `HomeScreen.kt:159/161` (now `:198/200`) `"Restore failed: ${e.message}"`, `:202` snackbar, `:600` (now `:270`) restore snackbar, `:1417` (now `:1494`) `"Backup failed: ${e.message}"`.
- `MainActivity.kt:860-862/919-921/1001-1003` — three recovery screens render whatever the VM sends.
- `NoteflowViewModel.kt:2166/2216` (now `:2405/2498`) `onError(e.message ?: "Recovery failed.")`.
- `ImportExportService.kt:1835/1846` (now `:2148/2159`) interpolates attacker-carried text: `"Backup contains unsafe relative path: $entryName"`.
- Residuals: `Dialogs.kt:136` (now `:86`) `"Error reading APK: ${e.message}"`, `EditorScreen.kt:865` (now `:968`) `"Failed to attach photo: ${e.message}"`, `LocalSendSender.kt:378` (now `:384`) `e.message ?: "Unexpected response…"` and `:588-595` (now `:605-611`) `mapTransportError` returns `e.message`.

**Fix (phase-148):**
- New pure-JVM decision table `services/UiFailureTextPolicy.kt` (mirrors the phase-71 `FailureLogPolicy` + phase-94 `WebDavFailurePolicy` pattern, 212 lines): every restore/recovery/backup/import path returns a FIXED constant. The exception message is read ONLY for internal `when` classification — the output constant contains zero attacker-carried text. Includes `restoreFailureMessage(e)`, `recoveryMessage(e)`, `backupFailureMessage(e)`, `importSkippedMessage(e)` and a defensive `scrubForUi(text)` (URL userinfo removal, host-collapse, `?k=v` drop, `/data|/storage|/sdcard` path redaction) for any residual trusted-internal text that must reach the UI.
- `HomeScreen.kt`: 3× `restoreFailureMessage(e)` (restart dialog `:201` + two snackbars `:200`,`:272`), 3× `importSkippedMessage(e)` (`:326`,`:339`,`:360`), 2× `backupFailureMessage(e)` (`:673`,`:1496`).
- `NoteflowViewModel.kt`: 2× `recoveryMessage(e)` (`:2407`,`:2498`, both recovery paths incl. `attemptKeystoreKeyLostRecoveryFromBackup`), 2× `restoreFailureMessage(e)` (`:3843`,`:3863`, WebDAV `onComplete(false, …)` + `EmptyVaultRestoreDecisionException` path). Because the recovery screens in `MainActivity` render VM-callback strings, this sanitizes all three earlier `MainActivity` surfaces without editing them.
- `ImportExportService.kt` **defense-in-depth at the source**: both unsafe-path rejections now throw a fixed sentence `"Backup contains an unsafe relative path in the archive."` — the attacker-controlled `$entryName` is no longer part of the exception at all (`:2148`,`:2159`).
- `Dialogs.kt:86` → `"Could not read the selected APK file."`
- `EditorScreen.kt:968` → `"Could not attach the photo. It may be unreadable or unavailable."`
- `LocalSendSender.kt:384` → fixed `"The receiving device returned an unexpected response."`; `mapTransportError` else-branch (`:606-611`) → `"Transfer failed (${FailureLogPolicy.classNameToken(e)})."` — class-name token only, never `e.message`.

### R2-b2b3-LOG-02 (LOW) — VoiceNoteManager logs raw `${e.message}` from 8 sites; app-private file paths leak to logcat

**Before:** `VoiceNoteManager.kt:182,224,347,360,390,401,413,428` all `Log.*("VoiceNoteManager", "… ${e.message}")` — MediaRecorder/playback exceptions routinely carry app-private temp-file paths, violating the file's own `:232-234` comment that bans path-bearing log lines.

**Fix (phase-148):** all 8 sites now `Log.*("VoiceNoteManager", "<fixed label> (${FailureLogPolicy.classNameToken(e)})")` — class-name token only. The two remaining non-failure `Log.w` lines (`:234` "Recording produced no audio data (file too small)", `:254` "Recording could not be encrypted — plaintext temp destroyed") are static text with no interpolation and stay as-is.

### R2-b2b3-LOG-03 (INFO) — ProtobufBrushLoader echoes `${e.message}` + brush `name` into logcat

**Before:** `ProtobufBrushLoader.kt:67` `"Failed to parse .inkbrush protobuf for $name: ${e.message}"`, `:80` `"Failed to read brush stream $name: ${e.message}"`, `:88-96` `"Failed to load brush file: ${e::class.java.simpleName}"`.

**Fix (phase-148):** all three catches log `(${FailureLogPolicy.classNameToken(e)})` with no `name`/file echo (`:67`,`:80`,`:93`). The dormant phase-155 API now sanitizes by default.

## New tests

- `Phase148UiFailureTextScrubTest.kt` (18 tests): classifier matrix for every fixed constant; "never echoes archive text" assertions (no absolute path / entry name / `/data/`); `scrubForUi` URL+path redaction; source pins proving HomeScreen/NoteflowViewModel route through the policy and that no `${e.message}` interpolation survives in `MainActivity`, `ImportExportService`, `Dialogs`, `EditorScreen`, `LocalSendSender`, `VoiceNoteManager` (8 interpolated failure logs, each interpolating ONLY `FailureLogPolicy.classNameToken(e)`), and `ProtobufBrushLoader` (3 catches, no `name` echo).
- Updated `B1Db05ImportZipBombTest.kt:321-333` — the pre-existing source pin that asserted the OLD `viewModel.showSnackbar("Import skipped: ${e.message}", …)` is now updated to pin the sanitized routing `showSnackbar(UiFailureTextPolicy.importSkippedMessage(e), …)` (this is the phase-148 fix, not scope creep).

## Verification

- `gradle testDebugUnitTest` — **green; 2012 app tests, 0 failures** (incl. all suites). One run was needed: the first full run failed ONLY on `B1Db05ImportZipBombTest.kt:326` because its source pin asserted the pre-fix `"Import skipped: ${e.message}"` literal — updated to the sanitized pin, then full suite green.
- `gradle assembleDebug` — **green**. (The first attempt hit a transient `DexMergingWorkAction` failure on the low-RAM runner; a plain re-run succeeded — no code change, tooling-only flake.)
- Repo-wide scan: no `${e.message}` interpolation remains in any named file.

## Residuals / observations (out of phase scope)

- `services/AppFacadeHost.kt:120` — `FacadeResult.Failed("HTTP GET refused: ${e.message}")` is a plugin-capability *host result string* (phase-23 plugin architecture); not in the named files, not a user UI surface, and plugin-facing by design. Left untouched per the "do NOT fix other findings" constraint; if phase-155/156 wires a plugin that renders these results, revisit there.
- Defensive `scrubForUi` exists but currently has no in-tree caller — kept as the guaranteed-safe choke point for any future trusted-internal-→-UI text.

## Definition of done

- [x] All three findings closed with `file:line` before/after evidence (above + `docs/security-report-round2.md`).
- [x] No raw `e.message` / attacker-carried text reaches a user-facing error surface or logcat in the named files (test-pinned).
- [x] `gradle testDebugUnitTest` green; `gradle assembleDebug` green.
- [x] Docs updated: `docs/phase-status.md` row DONE + `docs/ARCHITECTURE.md` note; `docs/security-report-round2.md` findings marked `FIXED in phase 148`.
- [x] No DB schema change, no new dependencies, no `.github/workflows/` edits.