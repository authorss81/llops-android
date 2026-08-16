# Phase 129: FLAG_SECURE gated on !BuildConfig.DEBUG (verify + pin) [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**CONTEXT:** FLAG_SECURE must be applied to the window **only in non-debug
builds** so debug/emulator streaming environments (e.g. cloud Android
emulators that mirror the display buffer) render the UI instead of a
pitch-black surface, while release builds keep screenshot/recording
protection. AGENTS.md hard rule: "FLAG_SECURE is applied in non-debug builds".

## What to do
- Verify `MainActivity.kt` applies the window flag exactly as:
  `if (!BuildConfig.DEBUG) { window.addFlags(FLAG_SECURE) }` — and that NO
  other code path adds FLAG_SECURE unconditionally (grep the whole app for
  `FLAG_SECURE`).
- Ensure `BuildConfig.DEBUG` is available in the module (buildConfig feature
  enabled) — fix if missing.
- Ensure the unit test pins this behavior: a test asserting FLAG_SECURE is
  active for non-debug builds (e.g. extend/update
  `B1Plat04AutoLockTest`-style coverage or add one — follow the existing test
  layout). A pure-JVM test may only assert the *decision logic*; extract the
  decision into a tiny testable helper if needed (e.g. `fun shouldApplySecureFlag(debug: Boolean)`).
- Confirm release builds keep the flag (R8/minify must not strip it).

## Verification
- Pure-JVM unit tests for the decision helper (debug → false, release → true);
  source-level grep proof that FLAG_SECURE appears only under the
  `!BuildConfig.DEBUG` guard.
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- FLAG_SECURE applied only when `!BuildConfig.DEBUG`; tests pin the decision;
  REPORT.md documents the verification with file:line evidence.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard` intact.