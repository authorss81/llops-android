# Phase 6: WebDAV sync — make it real, safe, and honest (E2EE SYNC) [DONE]

You are working on **InkFlow/Noteflow**, an offline-first encrypted notes + canvas
Android app. The app has a WebDAV sync feature that is DANGEROUS and non-
functional (audit C2). This phase makes it genuinely work end-to-end with zero
data-loss risk, and makes the UI honest about what it does. You MAY add the
`INTERNET` permission (this is the real feature that justifies it).

## Verified problems (from security audit — fix each)

### C2a. Feature cannot run — no INTERNET permission
`AndroidManifest.xml` declares no `INTERNET` permission, so every
`HttpURLConnection` in `WebDavSyncService.kt` throws `SecurityException`. Yet
`WebDavSyncDialog.kt:60` advertises "zero-knowledge encrypted vault… Your data
stays 100% under your control."

**Fix:** Add `<uses-permission android:name="android.permission.INTERNET"/>` to
`AndroidManifest.xml` (the ONE legitimate reason to add it — document that in a
comment near the service). 

### C2b. Upload misses recent edits — no WAL checkpoint
`exportEncryptedBackupToZip` (`NoteflowViewModel.kt` ~:1027-1041) copies the raw
`.sqlite` with NO WAL checkpoint. In WAL mode recent committed transactions live
in the `-wal` file and are silently missing from every sync.

**Fix:** Run a WAL checkpoint (or explicitly copy the `-wal` after checkpointing)
before uploading, using the same checkpoint logic the backup export uses.

### C2c. "Download Vault" = unauthenticated blind overwrite of the LIVE DB
`restoreEncryptedBackupFromZip` (`NoteflowViewModel.kt` ~:1043-1053) does
`sourceZip.copyTo(dbFile, overwrite=true)` with NO integrity check, NO re-key, NO
HMAC re-arm, NO DB close. A DB from another device (different DEK) fails the next
open with "file is not a database" → the auto-corruption handler deletes the
vault (Phase 2 fixes this too). This reintroduces the exact data-loss class the
pentest flagged.

**Fix:** Reuse the SAME transactional restore path the local restore uses
(`ImportExportService.restoreFromZip` — note it is currently `private`; expose a
public/internal variant or factor the temp→integrity→rekey→validate→swap
orchestration into a shared function so WebDAV can call it): download to a temp
file → extract to temp dir → `PRAGMA integrity_check` → re-key to current DEK →
validate `user_version` → atomic swap with HMAC re-arm → close DB + restart.
Never copy over the live DB. (This phase runs AFTER phase-02, so the shared
restore guts phase-02 hardens are already in place — reuse them, don't rewrite.)

### M1. Credentials can go over cleartext HTTP
`WebDavSyncService.createConnection` (`WebDavSyncService.kt:37`) uses
`url.openConnection()` with no HTTPS enforcement; Basic auth is just Base64
(`:43-45`).

**Fix:** Require HTTPS (reject `http://` URLs with a clear error unless the user
explicitly confirms an insecure local-network-only server), and set a TLS
protocol/timeouts.

### M3. Credential/secret hygiene
Verified: WebDAV credentials are currently held only in transient Compose state
(`remember { mutableStateOf("") }` in `WebDavSyncDialog.kt:35-38`) and wiped on
dialog close — NOT persisted to SharedPreferences. That's actually acceptable
(no plaintext-on-disk credential store exists). If you add "remember me" /
persisted credentials, store them in the AndroidKeyStore-backed encrypted storage
the app already uses (`SecurityService`/`EncryptionService` patterns) — never
plain SharedPreferences.

## Honesty (UI labels)
`WebDavSyncDialog.kt` and ROADMAP §26.4 currently claim "E2EE / zero-knowledge"
sync. The real scope is: **encrypted backup files synced to your own server**.
Update the UI copy and ROADMAP §26.4 to say exactly that — "encrypted at rest,
transported to YOUR server; your server operator can read the backup files, not
the plaintext content." Do not claim zero-knowledge.

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes.
- WebDAV upload runs a WAL checkpoint first (recent edits included).
- WebDAV download uses the transactional restore path (integrity check, re-key,
  user_version validation, HMAC re-arm, restart) — never a blind copy.
- HTTPS enforced for WebDAV.
- Credentials stored in the app's encrypted storage.
- UI + ROADMAP copy honestly describe "encrypted backup files on your server".

## Constraints
- Adding `INTERNET` permission is REQUIRED for this phase (this is the real
  feature). Do not add any other permissions.
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- You may not be able to test against a live server in CI — that's fine. Write a
  small JVM unit test for the WebDAV URL validation (https enforcement,
  URL parsing) and for the temp-restore orchestration where it's unit-testable.
