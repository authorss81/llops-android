# Phase 140 — Vault-content exposure windows: ON_PAUSE cover, per-dialog FLAG_SECURE, persistent auth-gated share state

**Status: DONE (2026-08-18)**

Closes three OPEN findings from `docs/security-report-round2.md`:

- `R2-B1A-03` (LOW) — decrypted content stays on screen across ON_PAUSE-only covers
- `R2-b2b1-UI-02` (LOW) — Compose `Dialog`/`AlertDialog` windows lack FLAG_SECURE
- `R2-B1P-05` (INFO) — share-confirmation state is activity-scoped and unpersisted

All three are windows in which decrypted vault content (or attacker-chosen
preview text) can be visually read or screenshot while the activity is paused or
locked. None require a DB schema change or new dependencies.

---

## R2-B1A-03 (LOW) — decrypted content stays on screen across ON_PAUSE-only covers

### The root cause

`MainActivity.kt` (post-fix anchors `:145-156`, `:122-128`) scrubbed the
clipboard on ON_PAUSE but locked only on ON_STOP / ACTION_SCREEN_OFF /
foreground-idle. A `SYSTEM_ALERT_WINDOW` overlay, an OEM in-call UI, or a
translucent anti-theft app can therefore sit over the unlocked vault for an
arbitrary time while the last decrypted note stays rendered beneath the cover —
`FLAG_SECURE` (`:141`) blocks capture, not visual reading.

Locking on ON_PAUSE was rejected in phase-60 because it breaks SAF pickers,
biometric prompts, and share-sheet returns. The finding itself sanctions the
alternative: *"lock (or render an opaque cover) on ON_PAUSE"*.

### Fix chosen — the opaque cover option

New pure-JVM decision table `services/OnPauseCoverPolicy.kt`:

```kotlin
object OnPauseCoverPolicy {
    // Cover ONLY an authenticated has-master-password vault; ANY resume dismisses.
    fun shouldCoverOnPause(hasMasterPassword: Boolean, authenticated: Boolean) =
        hasMasterPassword && authenticated
    fun shouldDismissOnResume(coverActive: Boolean) = coverActive
}
```

`MainActivity.kt`:
- ON_PAUSE (`MainActivity.kt:155-179`): if `shouldCoverOnPause(
  viewModel.hasMasterPassword.value, viewModel.authenticated.value)` — raise
  `pauseCoverActive`, dismiss the un-confirmed share-confirm dialog
  (`viewModel.cancelPendingShareConfirm()`, attacker-chosen preview text) and
  hide the Command Palette (`showCommandPalette = false`, decrypted note-title
  list) — both are SEPARATE `Dialog` windows that would float ABOVE the activity
  cover (this also closes the rotation/local re-entry of R2-B1A-03's sibling
  window threat).
- ON_RESUME (`:180-185`): `shouldDismissOnResume` clears the cover on ANY
  return — SAF picker / biometric prompt / share sheet still work after a pick
- Cover composition: while `pauseCoverActive && shouldCoverOnPause(...)` the
  Box's LAST child is an opaque full-screen `Surface` (`MainActivity.kt:714-724`),
  so nothing beneath it can be visually read by software drawn over/behind it,
  and no dialog window can outrank it.

Passwordless vaults are never covered — no lock boundary by design
(`clearOnLock`/cover both key on `hasMasterPassword`, per B1-AUTH-02).

## R2-b2b1-UI-02 (LOW) — every Compose dialog window lacks FLAG_SECURE

### The root cause

`FLAG_SECURE` was set only on the activity window (`MainActivity.kt:138-141`).
Compose `Dialog(...)`/`AlertDialog(...)` render in separate `WindowManager`
windows that do NOT inherit the activity's flags, so on a rooted/adb device a
`screencap` captured the Command Palette (decrypted note-title list) or the OCR
dialog (full OCR'd note text) with no bypass of the activity flag.

### Fix chosen — the reusable `remember`-hook option

The finding offers two options: a small per-dialog `remember`-hook applying
FLAG_SECURE, or rendering overlays as composition layers inside the activity.
The hook option is cleaner (no re-architecture of the overlay hosts) and the
phase-130 debug carve-out must be preserved (emulator streaming must keep
rendering).

New pure-JVM gate `services/SecureDialogPolicy.kt` reuses
`SecureWindowPolicy.shouldApplySecureFlag(BuildConfig.DEBUG)`:

```kotlin
object SecureDialogPolicy {
    fun dialogWindowsAreSecure(debug: Boolean): Boolean = SecureWindowPolicy.shouldApplySecureFlag(debug)
}
```

New `@Composable` helper `ui/components/SecureDialogProperties.kt` maps the gate
to the (stable in compose-ui 1.7.6, non-experimental) secure policy:

```kotlin
@Composable
fun secureDialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    usePlatformDefaultWidth: Boolean = true,
    decorFitsSystemWindows: Boolean = true,
): DialogProperties = DialogProperties(
    dismissOnBackPress = dismissOnBackPress,
    dismissOnClickOutside = dismissOnClickOutside,
    usePlatformDefaultWidth = usePlatformDefaultWidth,
    decorFitsSystemWindows = decorFitsSystemWindows,
    securePolicy = if (SecureDialogPolicy.dialogWindowsAreSecure(BuildConfig.DEBUG)) {
        SecureFlagPolicy.SecureOn
    } else {
        SecureFlagPolicy.Inherit
    },
)
```

Wired into EVERY finding-listed dialog window (`properties =
secureDialogProperties(...)`):

| Dialog host | content at risk |
|---|---|
| `CommandPaletteOverlay.kt` (`Dialog`, `usePlatformDefaultWidth = false`) | decrypted note-title list |
| `OcrResultDialog.kt` | full OCR'd note text |
| `MarkdownPreviewScreen.kt` transform-confirm + TextTools + LanguageDetection | note body context |
| `WebSearchDialog.kt` | query + snippet from note body |
| `Phase16PluginDialogs.kt` (Dictation/ReadAloud/Translation) | dictation/read-aloud text |
| `Phase26PluginDialogs.kt` (Dictionary/Weather/UnitConverter/Outline/Citation) | note-derived text |

## R2-B1P-05 (INFO) — share-confirm state is activity-scoped and unpersisted

### The root cause

`pendingShareConfirm`/`pendingShare` were activity `mutableStateOf` fields
(`MainActivity.kt:109-114`). The activity is `singleTask` with no
`configChanges`, so rotation recreates it with the ORIGINAL SEND intent
(`AndroidManifest.xml` `*/*` ACTION_SEND filters) and `readShareIntent` in
`onCreate` re-parsed and re-displayed the confirm — an answered confirm was
re-prompted (or an in-flight one dropped). The confirm `AlertDialog` rendered
OUTSIDE the lock branch (`MainActivity.kt:586-612`), so after a screen-off lock
(`:122-127`) it stayed composed ABOVE `LockScreen`, showing attacker-chosen
preview text, and the deferred "Clip" auto-applied at the NEXT unlock with no
per-session expiry.

### Fix chosen — hoist both states into the ViewModel, gate + flush at the lock boundary

- Pure-JVM models + `PendingSharePolicy` in `services/PendingShareState.kt`
  (`PendingShareConfirmState(clip, uriStrings)`, `PendingShareState(text,
  imagePaths, rawUris)`, `shouldStage`/`toPendingShare`, `clearOnLock`).
- `NoteflowViewModel.kt:1377-1381` — `_pendingShareConfirm`/`_pendingShare`
  StateFlows with transitions `stagePendingShare` (`:1384-1389`) → explicit
  `confirmPendingShare` (`:1396-1405`, guarded no-op if already confirmed) /
  `cancelPendingShareConfirm` (`:1391-1394`); consumption only via
  `consumePendingShare` (`:1407-1411`).
- `MainActivity.readShareIntent` (`.kt:787`) BAILS while a share is in flight —
  a rotated-recreated activity re-firing the ORIGINAL SEND intent cannot
  re-prompt a confirm the user already handled.
- The confirm `AlertDialog` renders ONLY under `authenticated` (`MainActivity.kt:683`)
  — it can never float above `LockScreen` after a screen-off lock.
- `lock()`: the master-password session-end block (`.kt:4154-4163`) drops BOTH
  states via `PendingSharePolicy.clearOnLock` BEFORE `NoteflowDatabase.dispose()`,
  so a pre-lock "Clip" cannot auto-apply at the next unlock. Passwordless vaults
  keep the flow (no lock boundary).
- No bytes move in the flow: `stagePendingShare` → explicit confirm →
  post-unlock bounded `copySharedUris`/paste-snackbar path is unchanged
  (B1-PLAT-2 staging untouched).

## Verification results

New/updated tests (all comment-stripped source pins against the real files):

- `Phase140OnPauseCoverPolicyTest` (4): decision-table matrix (PW+auth /
  PW-no-auth / no-PW / auth-only) + wiring pins (ON_PAUSE raises cover +
  dismisses share-confirm + hides palette under the table; ON_RESUME clears;
  cover Surface condition).
- `Phase140DialogSecurityTest` (7): the pure-JVM gate table (debug→Inherit,
  release→SecureOn) + one comment-stripped source pin per dialog file that every
  `Dialog(`/`AlertDialog(` passes `secureDialogProperties(` (each file also
  pinned to not set `securePolicy = Inherit` hard-coded in production code).
- `Phase140ShareStateLockTest` (7): model/policy transitions (shouldStage on
  nothing-pending, dedupe on pending; toPendingShare maps confirm→share; confirm
  no-op when share already set; clearOnLock true only for has-master-password)
  + wiring pins (lock() clears both flows in the master-password block, never for
  passwordless; readShareIntent bail token; dialog render gate token).
- `B1Plat02ShareConfirmationTest` (5): rewritten against the new wiring —
  intent-arrival path stages into `viewModel.stagePendingShare` and must NOT call
  `copySharedUris`; the "user explicitly confirmed" transition token now lives in
  the ViewModel; apply-effect pin on `LaunchedEffect(authenticated, pendingShare)`
  + `consumePendingShare`; the `readShareIntentRegion` pin is bounded at the
  phase-140 comment marker so it stops before `copySharedUris`.
- Two test-authoring bugs fixed during the run: a backticked test name containing
  `/` (illegal Kotlin identifier char) renamed ("SecureOn or Inherit"); the
  `readShareIntentRegion` boundary over-ran into `copySharedUris` causing a false
  "must NOT call copySharedUris" failure — bounded by the new comment marker.

Commands (Linux/CI, system `gradle`, no wrapper):

```
gradle :app:compileDebugKotlin     # BUILD SUCCESSFUL
gradle testDebugUnitTest           # 1963 total (app 1913 + plugins:llm 50), 0 fail / 0 err / 0 skip
gradle assembleDebug               # BUILD SUCCESSFUL
```

Debug artifact: `app/build/outputs/apk/debug/app-debug.apk`
(173,973,566 bytes; SHA-256 `b17afc59c09f335db00b96a2b83a926ddbf66174f6aa3ecf413b1542f26a7e73`).
Release build intentionally NOT run locally: `RELEASE_KEYSTORE_B64`/`KEYSTORE_FILE`
are unset in this environment and the release build fails closed when unset
(B1-PLAT-1, see `docs/RELEASE.md`).

## Definition of done

- [x] R2-B1A-03 closed — opaque ON_PAUSE cover for has-master-password +
      authenticated, dismissed on ANY resume, with the separate-window threat
      (share-confirm, Command Palette) dismissed in the same hook
      (`MainActivity.kt:155-179`, cover `:721-724`; `OnPauseCoverPolicy.kt`).
- [x] R2-b2b1-UI-02 closed — every finding-listed dialog window passes
      `secureDialogProperties(...)` whose gate is release-only
      (`SecureDialogPolicy.kt` + `SecureDialogProperties.kt`), preserving the
      phase-130 debug streaming carve-out (`SecureFlagPolicy.Inherit` in debug).
- [x] R2-B1P-05 closed — share state in ViewModel StateFlows
      (`NoteflowViewModel.kt:1377-1381`), `readShareIntent` in-flight bail
      (`MainActivity.kt:787`), confirm dialog under `authenticated`
      (`MainActivity.kt:683`), both flows dropped on lock
      (`NoteflowViewModel.kt:4154-4163`).
- [x] Docker-verified before/after with `file:line` evidence and grep-pins.

## Constraints

- [x] No DB schema change; no new dependencies; `.github/workflows/` untouched.
- [x] FLAG_SECURE remains release-only via `SecureWindowPolicy.shouldApplySecureFlag`
      (debug/emulator rendering preserved).
- [x] No keys/passwords/decrypted note content logged anywhere.
- [x] B1-PLAT-2 staging untouched: the share flow still moves zero bytes; the
      post-unlock bounded copy path is unchanged.

## Residual notes (documented, NOT fixed here)

- **The cover is visual-only** — like every FLAG_SECURE/covers approach, it
  protects against VISUAL reading, not a rooted capture of the paused activity's
  window; the dialog FLAG_SECURE (R2-b2b1-UI-02) closes the screenshot vector,
  and hardware attacks are out of scope (phase-60 residual).
- **ON_PAUSE cover + no-keyguard:** on a device with no keyguard the cover is
  still raised on ON_PAUSE, so an overlay app can force the vault under a cover
  until the app resumes. The cover only shows the last decrypted frame for the
  paused duration necessary — visually this is equivalent to the activity being
  backgrounded, which the threat model accepts.
- **R2-b2b1-UI-04 (root SnackbarHost over lock) is phase-153** — the share-confirm
  dialog (R2-B1P-05) is now gated; the general snackbar message channel is a
  distinct finding and intentionally not folded in here (see
  `docs/security-report-round2.md` line ~694).