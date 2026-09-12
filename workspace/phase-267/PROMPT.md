# Phase 267 — Settings: commit, clamp, keys, threads

## Goal
Stop async-loss + unclamped/corrupt prefs + KeyStore races; no SettingsScreen to build (out of scope).

## Evidence
- HIGH 100% `SharedPreferences noteflow_prefs`, no DataStore/EncryptedSharedPreferences (accepted for non-secrets, but): `wipePluginState:783` + `clearSecuritySettings:905` (credential/biometric delete!) use `apply()` → kill-before-flush leaves wrapper on disk (`hasMasterPassword` stays true). Fix: `commit()` + bool return (like `commitMasterPasswordCredential:138`).
- HIGH/MEDIUM unclamped reads: `tutorialResumeIndex:47` getter raw; `autoLockTimeoutSeconds:632` any Int persisted (ADB -1 disables lock; huge → timer never fires); `pressureCurve/symmetry/eraserMode/brushColorMode/velocityIntensity/nibAngles/deviceTierOverride:294-593` raw. Fix: sanitize on read+write via policy `coerceIn` (create `AutoLockPolicy.sanitize 0..86400`).
- MEDIUM migrations `apply()` flags re-run I/O; no PREF_VERSION; unbounded `templatePrefsJson/importedBrushPresetsJson/paperTexturePaths`.
- MEDIUM `getRecentSearches/setRecentSearches:436-484` KeyStore load/gen unsynchronized + per-entry Cipher alloc (perf) + `KeyAlreadyExists` drop.
- MEDIUM `MainActivity:363 FloatingWindowNoticeLauncher` news `SettingsManager(this)` per composition vs `viewModel.settings` singleton (two SP instances race); settings not StateFlow-observed.
- LOW `wrappedDek` structural check missing (garbage counts as has-password → permanent fail); `failedUnlockAttempts/lockoutUntil` no ceiling (ADB future = permanent lockout); ViewModel `_autoLockTimeout:1584` seeded once.

## Files
- `services/SettingsManager.kt`, `ui/viewmodel/NoteflowViewModel.kt`, `MainActivity.kt`

## Tests
- `Phase267SettingsTest`: security wipes use commit (source pin); autoLock sanitize bounds (JVM); corrupt enum falls back (JVM).

## Constraints
- No Room schema change / migration
- No new dependencies (pure Kotlin/Compose only)
- No `.github/workflows/` edits
- Follow AGENTS.md hard rules (allowBackup=false stays, no plaintext rows, fail closed)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-NNN/REPORT.md` with file:line evidence table (claim / reality / status / evidence)
