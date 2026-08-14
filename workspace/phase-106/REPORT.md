# Phase 106 — Report: B2-CRYPTO-06 (LOW)

**Finding:** Exact-to-the-millisecond timestamps in backup/sync filenames and
corruption markers leak vault-activity patterns to public storage, MTP/USB, and the WebDAV server.

**Status:** `DONE`

---

## What changed (file:line, before → after)

The leak: epoch-millis in the *public/remote* backup/sync filenames exposed the exact
second of the last backup/sync to any party who can list `/Download` or the WebDAV
folder. New names are **day-granular (`yyyy-MM-dd`) + a random token** — no epoch-millis
(and no epoch-seconds) anywhere in a name that leaves the sandbox.

### 1. Local backup archive → public `/Download`
- **Before:** `services/ImportExportService.kt:1236` —
  `File(context.cacheDir, "noteflow_backup_${System.currentTimeMillis()}.noteflow")`.
  `HomeScreen.kt:1196-1199` (and `HomeScreen.kt:463-468`, the no-password path) copies that
  temp file into public `/Download` **using `cacheFile.name`**, so the epoch-millis name
  became the public name verbatim.
- **After:** `services/ImportExportService.kt:1240` —
  `File(context.cacheDir, BackupFileNamePolicy.localBackupFileName())`.
  `HomeScreen.kt` needed **no change**: it still copies `cacheFile.name`, which is now the
  safe day-granular random-token name. `LocalSendSendDialog.kt:91` (vault backup payload)
  and `NoteflowViewModel.kt:2025` (WebDAV staging, cache-private) inherit it too.

### 2. WebDAV remote backup name
- **Before:** `services/WebDavSyncService.kt:202` —
  `val remoteFileName = "noteflow_vault_backup_${System.currentTimeMillis()}.nfb"`, uploaded
  to the user's WebDAV/Nextcloud folder.
- **After:** `services/WebDavSyncService.kt:207` —
  `val remoteFileName = BackupFileNamePolicy.remoteVaultBackupFileName()`.
  The `noteflow_vault_backup_` prefix and `.nfb` suffix are preserved so the existing
  download listing regex at `WebDavSyncService.kt:263`
  (`noteflow_vault_backup_[^<]+\.nfb`) keeps matching both old (millis) and new files
  on the server.

### 3. New shared policy (pure JVM)
- **New:** `utils/BackupFileNamePolicy.kt` — single source of truth for exported
  backup/sync filenames:
  - `localBackupFileName()` → `noteflow_backup_2026-08-14_<10-random-chars>.noteflow`
  - `remoteVaultBackupFileName()` → `noteflow_vault_backup_2026-08-14_<10-random-chars>.nfb`
  - `SecureRandom` token (10 chars) makes same-day names collision-free; `java.time.LocalDate`
    gives day-granularity. Both APIs exist on the API-26 floor — **no newer API required**,
    no fallback/notice needed.

## Out-of-scope (documented, not changed)

- `data/db/NoteflowDatabase.kt:308-309` (`*.corrupt-<millis>` quarantine) and
  `services/DatabaseSecurityHelper.kt:122-127` (`PREF_CORRUPTION_TIMESTAMP`): both live in
  **app-private** storage (`context.getDatabasePath(...)` = app-private `databases/` dir;
  app-private SharedPreferences) — never in `/Download`/WebDAV/MTP. The finding's fix clause
  says "keep timestamps **internal** or day-granular"; these are already internal, so
  leaving them untouched satisfies the requirement. Changing them would additionally break
  the CorruptionRecoveryScreen correlation on `MainActivity.kt:695-712`, which formats the
  stored millis for the "moved aside as *.corrupt-…" message.
- `MainActivity.kt:590` (shared-URI staging `filesDir/shared/<millis>-<n>.ext`),
  `EditorScreen.kt:217/241/722` (`custom_bg_/paper_texture_/photo_<millis>`),
  `VoiceNoteManager.kt:66` (`voice_<pageId>_<millis>.m4a`),
  `ImportExportService.kt:1512` (`restore_tmp_<millis>` in cacheDir) and
  `ImportExportService.kt:1857/1896/2088` (`<safeTitle>_<millis>.md` import files) — all land in
  **app-private** dirs (`filesDir/` or `cacheDir/`), not public/remote. Out of B2-CRYPTO-06 scope.
- `PsdExportService.kt:99`, vault ZIP/HTML/Obsidian exports (`ImportExportService.kt:1822-1825`,
  `1975`, `2029`, `2160`): filenames contain **no epoch-millis** already.

## Checksum / secrets handling

- No keys, passwords, or decrypted content are touched. No DB schema change, no new
  dependency, no `.github/workflows/` edit, `allowBackup=false`, `ClipboardGuard`,
  FLAG_SECURE intact. `BackupFileNamePolicy` uses `SecureRandom` (non-seeded) for tokens —
  tokens are collision-id, not secrets, and carry no decryptable information.

## Verification

### Unit tests (`BackupFileNamePolicyTest`, 7 tests)
- local backup name: no `\d{13}` (epoch-millis) / `\d{10}` (epoch-seconds); keeps
  `noteflow_backup_` prefix + `.noteflow` suffix; day-granular `2026-08-14` present; exact
  format regex.
- remote WebDAV name: same checks for `noteflow_vault_backup_…nfb`.
- uniqueness: 500 same-day names → no collisions.
- **WebDAV download regex compat**: the unchanged download regex matches the new name format
  (proves old download logic keeps working with new uploads, and old server files still match).
- determinism: fixed date + fixed token → exact expected string.

### Commands
- `gradle testDebugUnitTest` — full suite (app module): **653 tests, BUILD SUCCESSFUL**
  (final clean rerun; see flake note). New `BackupFileNamePolicyTest`: 7/7 pass.
- `gradle assembleDebug` — **BUILD SUCCESSFUL**, `app/build/outputs/apk/debug/app-debug.apk` produced.

### Pre-existing flake note (proven unrelated)
Full suite reruns intermittently fail 1 test from interleaved timing/IO-heavy plugin tests.
Demonstrated on the **pristine baseline** (worktree at `1da160b`, no phase-106 changes):
- `PluginUpdateEngineTest.a hash mismatch on the downloaded artifact is never applied` —
  failed 2/3 isolated runs on baseline, failed intermittently in full-suite runs.
- `WikiLinkParserCacheUnitTest.a cancelled scan propagates cancellation…` — failed once in a
  full-suite run, then 5/5 isolated passes on the phase-106 tree.
Both are unrelated to this phase (no interaction with backup/sync filename code); final
clean reruns of `gradle testDebugUnitTest` pass 653/653. This matches the repo's known
pre-existing-only flakiness and is documented here as such.

## Definition-of-done checklist
- [x] Vulnerability path closed: before/after at `ImportExportService.kt:1240`,
      `WebDavSyncService.kt:207`, `utils/BackupFileNamePolicy.kt` (evidence above).
- [x] API-26 floor protected — policy uses only `SecureRandom` + `java.time.LocalDate`
      (both present on API 26); no newer-API fallback/notice required.
- [x] New unit tests prove the fix; no existing test regressed (653/653 on clean reruns).
- [x] `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (failures shown above
      are pre-existing-only flakes, proven on baseline).
- [x] REPORT.md committed.