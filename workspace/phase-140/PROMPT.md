# Phase 140: Vault-content exposure windows — ON_PAUSE cover, dialog FLAG_SECURE, and share-confirm flush on lock [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-B1A-03, R2-b2b1-UI-02, R2-B1P-05) and `docs/phase-status.md`
+ `docs/ARCHITECTURE.md`. This phase closes the three windows where decrypted
content or attacker text can sit above/around a locked or paused vault.

## Source findings (all OPEN — 1 LOW, 1 LOW, 1 INFO)

1. **R2-B1A-03** (LOW) — Decrypted content stays on screen across ON_PAUSE-only
   covers (overlays/in-call UI/PIP); lock only fires on ON_STOP, SCREEN_OFF, or
   foreground-idle (`MainActivity.kt:145-156` ON_PAUSE only scrubs the clipboard;
   `:122-128` SCREEN_OFF lock; `:248-263` idle poll). A `SYSTEM_ALERT_WINDOW`
   overlay can sit over an unlocked vault indefinitely.
2. **R2-b2b1-UI-02** (LOW) — FLAG_SECURE is activity-window-only
   (`MainActivity.kt:138-141`); every Compose `Dialog`/`AlertDialog` renders in
   a separate window WITHOUT the flag: `CommandPaletteOverlay.kt:160` (decrypted
   note-title list), `OcrResultDialog.kt:92` (full OCR text),
   `MarkdownPreviewScreen.kt:732,749,797,805,819,832,841`. Compose dialogs do
   NOT inherit the activity's window flags.
3. **R2-B1P-05** (INFO) — Share-confirmation state is activity-scoped and
   unpersisted (`pendingShareConfirm`/`pendingShare` at `MainActivity.kt:109-114`):
   rotation re-prompts a confirm; the Clip dialog floats ABOVE the LockScreen
   after a screen-off lock (`MainActivity.kt:586-612` — the AlertDialog renders
   outside the lock branch).

## The fix (where & how)

- **R2-B1A-03:** For has-master-password vaults, lock (or render an opaque
  cover) on ON_PAUSE — the screen-away window is currently open until ON_STOP.
  Reconcile with the phase-60 note that ON_PAUSE lock was NOT chosen because it
  breaks SAF pickers/biometric/share-sheet pauses — prefer an opaque cover that
  is dismissed on the legitimate return paths, or a short cover-delay, so
  pickers still work while cover apps never see content.
- **R2-b2b1-UI-02:** Apply FLAG_SECURE to dialog windows as they open (a small
  reusable `remember`-hook per dialog), or render the overlay content as a
  composition layer inside the activity. Cover the CommandPaletteOverlay, OCR
  dialog, and the MarkdownPreviewScreen dialogs.
- **R2-B1P-05:** Make the share state `rememberSaveable` (or process confirm
  against `savedInstanceState`), gate the dialog render under `authenticated`,
  and drop `pendingShare` when `lock()` runs instead of deferring indefinitely.

## Verification

- New/updated pure-JVM + source-pin unit tests: an ON_PAUSE cover/lock policy
  decision table (cover vs. picker return); a dialog-FLAG_SECURE source pin for
  the listed dialogs; share state clears on lock (model test).
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-140/REPORT.md`.

## Definition of done

- All three findings closed with `file:line` before/after evidence.
- No decrypted content is visible beneath a cover or captured via dialog windows.
- SAF/biometric/share-sheet flows still work (no new breakage on API 26-36).

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.