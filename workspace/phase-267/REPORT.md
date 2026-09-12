# Phase 267 — Settings: commit, clamp, keys, threads — REPORT

## 1. What the PROMPT asked

Stop async-loss + unclamped/corrupt prefs + KeyStore races (`services/SettingsManager.kt`,
`ui/viewmodel/NoteflowViewModel.kt`, `MainActivity.kt`). No Room schema change, no new
dependencies, no `.github/workflows/` edits, `verification-metadata.xml` untouched.

## 2. What was done

**New pure-JVM policy** `services/SettingsPrefsPolicy.kt` — total sanitize/budget decision
table (auto-lock 0..86400, tutorial index 0..10_000, attempts 0..10_000, lockout capped at
now + 15 min, enum-key round-trips, velocity 0..1, nibs −45..90, tier-override allow-list,
template/presets/path byte budgets, `CURRENT_PREFS_VERSION = 1`). `AutoLockPolicy.sanitize`
(`services/AutoLockPolicy.kt:30-38`) owns the 0..86400 window per the PROMPT.

**`SettingsManager.kt`:**
- `wipePluginState` (`:982`) and `clearSecuritySettings` (`:1112`) use `commit()` and return
  the disk-acknowledged `Boolean` (kill-before-flush can no longer half-delete state).
- Migration flags `fieldAadMigrated` (`:78`), `noteBodyPlaintextMigrated` (`:90`),
  `voiceNotesEncryptedMigrated` (`:102`) use `commit()`; new versioned `prefsVersion` (`:112`)
  + `stampPrefsVersion()` (`:119`).
- Every PROMPT-listed read is sanitized on read AND write: `tutorialResumeIndex` (`:48`),
  `autoLockTimeoutSeconds` (`:811`), `pressureCurveKey` (`:341`), `symmetryModeKey` (`:352`),
  `eraserModeKey` (`:499`), `brushColorModeKey` (`:662`), `velocityModulationIntensity`
  (`:709`), `calligraphicNibAngleDeg` (`:724`), `chiselNibAngleDeg` (`:740`),
  `deviceTierOverride` (`:759`, unknown → null = auto-detect), `failedUnlockAttempts` (`:173`),
  `lockoutUntilEpochMs` (`:184`, capped at now + one max backoff window).
- `templatePrefsJson` (`:416`) / `importedBrushPresetsJson` (`:483`): over-budget writes
  refused (old value kept), over-budget reads fail safe to `{}`/`[]`;
  `setPaperTexturePathForPage` refuses absurd paths.
- Recent searches (`:525-590`): KeyStore load-or-mint + encrypt/decrypt batches run under
  `recentSearchLock` (`:1133`) with a process-cached key (`:1137`) and ONE Cipher per batch
  (re-init per entry — every ENCRYPT init mints a fresh IV); cross-process mint race
  re-reads the winner instead of dropping the batch. Fail-closed (no keystore → no write,
  never plaintext) unchanged.
- `hasCorruptMasterPasswordCredential` (`:842`): structural check — blob present but
  unparseable. `hasMasterPassword` stays fail-closed (locked); unlock returns WITHOUT
  burning the lockout counter on unwinnable credentials (`NoteflowViewModel.kt:3715`).

**`NoteflowViewModel.kt`:** `setAutoLockTimeoutSeconds` (`:2162`) and
`updateTutorialResumeIndex` (`:2190`) publish the post-sanitize read-back (flow and disk can
never disagree); new `refreshAutoLockTimeout()` (`:2176`) fixes the seed-once staleness;
`recordFailedMasterPasswordVerification` publishes the capped read-back;
`removeMasterPassword` aborts before flipping state when the wipe commit fails (`:3976`);
`initializeDataCore` stamps the prefs version (`:2023`).

**`MainActivity.kt`:** `FloatingWindowNoticeLauncher` receives `viewModel.settings` (`:371`)
— the per-composition `SettingsManager(this)` second-instance race is gone; `ON_RESUME`
re-arms the auto-lock flow (`:228`).

**Tests:** new `Phase267SettingsTest` (21 — commit source pins, wipe-fail behavior,
auto-lock bounds, corrupt-enum fallback, counters/lockout ceilings, dial clamps, string
budgets, corrupt-credential structure, keystore fail-closed, version stamp, singleton +
abort wiring pins). `B1Plat04AutoLockTest` default-ENABLED pin updated to the sanitized
shape (intent preserved — still reads through the policy default).

## 3. Evidence table (claim / reality / status / evidence)

| Claim | Reality | Status | Evidence |
|---|---|---|---|
| Security wipes use commit + bool return | `wipePluginState: Boolean` + `clearSecuritySettings: Boolean`, both `.commit()` | DONE | `SettingsManager.kt:982,1112`; `Phase267SettingsTest` wipe/commit-fail behavior tests |
| Auto-lock sanitize 0..86400 | `AutoLockPolicy.sanitize` + read+write routing | DONE | `AutoLockPolicy.kt:30-38`; `SettingsManager.kt:811`; `Phase267SettingsTest` bounds tests |
| Corrupt enum falls back | read+write normalization for 4 enum keys + tier override | DONE | `SettingsManager.kt:341,352,499,662,759`; `SettingsPrefsPolicy.kt`; fallback tests |
| Tutorial/attempts/lockout clamped | index 0..10k, attempts 0..10k, lockout ≤ now+15min | DONE | `SettingsManager.kt:48,173,184`; ceiling tests |
| Migration flags + PREF_VERSION | 3 flags commit(); `prefsVersion` stamped in `initializeDataCore` | DONE | `SettingsManager.kt:78,90,102,112,119`; `NoteflowViewModel.kt:2023`; source pins |
| KeyStore races closed | lock + cached key + one Cipher/batch + race re-read | DONE | `SettingsManager.kt:525-590,1133,1137`; JVM fail-closed test |
| Two-SP-instances race closed | singleton passed to launcher | DONE | `MainActivity.kt:371`; source pin |
| VM seeded-once staleness | `refreshAutoLockTimeout()` on `ON_RESUME` + sanitized publish | DONE | `NoteflowViewModel.kt:2162,2176`; `MainActivity.kt:228` |
| `wrappedDek` structural check | `hasCorruptMasterPasswordCredential`; no counter burn | DONE | `SettingsManager.kt:842`; `NoteflowViewModel.kt:3715`; structure tests |
| Unbounded JSON/path prefs | write-refuse + read-fail-safe budgets | DONE | `SettingsManager.kt:416,483`; `setPaperTexturePathForPage`; budget tests |

## 4. Verification (DoD)

- `gradle :app:assembleDebug` — green (BUILD SUCCESSFUL).
- `gradle :app:testDebugUnitTest` — **3834 / 0 failures / 0 errors / 0 skipped**
  (baseline 3813 + 21 new; `B1Plat04AutoLockTest` 11/11 incl. the re-shaped pin).
- `gradle :app:lintDebug` — 0 errors.
- No schema change, no new dependencies, `.github/workflows/` + `verification-metadata.xml`
  untouched, `allowBackup=false` untouched, no plaintext rows added (refusals keep old
  values; recent-search fail-closed path unchanged).

## 5. Known limitations / follow-ups

- The corrupt-credential state is detectable (`hasCorruptMasterPasswordCredential`) but has
  no dedicated UI copy — unlock just fails without burning lockout; recovery is the
  existing restore-from-backup / start-fresh flow. A LockScreen "credential unreadable"
  notice is a small follow-up (no architecture change).
- `commit()` on the UI thread for migration flags/version stamp: infrequent one-time
  writes (~ms); the hot paths (autosave, editor) are untouched.
- The stale `B1Plat04AutoLockTest` exact-text pin was reshaped (same intent); noted here
  per the repo's pin-maintenance discipline.
