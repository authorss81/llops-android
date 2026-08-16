# Phase 83 — B2-DOS-07 (MEDIUM) — Backup export builds the ENTIRE vault (whole DB + every import file) as one in-heap byte array, then makes a second full-size copy in AES-GCM `doFinal` → OOM on large vaults

2026-08-16 · finding source: `docs/security-report.md` B2-DOS-07, batch 2 (resource exhaustion / DoS)

## The vulnerability (before/after)

**Before** — `ImportExportService.exportBackup` held the ENTIRE vault archive in heap and then made
one (or two) more full-size copies of it:

- `ImportExportService.kt:1154-1177` — the zip (a copy of the whole SQLCipher DB + every imports
  file + every encrypted voice blob) was accumulated in a `ByteArrayOutputStream` and materialized
  by `baos.toByteArray()` (`:1176`) — the entire backup lived in heap as one array.
- `ImportExportService.kt:1188-1190` — the v2 password path fed that array to
  `cipher.doFinal(zipData)` (`:1190`) — a SECOND full-size copy (the GCM ciphertext) plus the
  header write.
- `ImportExportService.kt:1210-1211` — the device-keyed path ran `encrypt(zipData, key)` →
  `java.util.Base64` → a further ~1.37x amplification of the full array before writing the text
  file.

Exploit scenario (from the finding): a vault whose DB+imports reach a few hundred MB (normal after
embedding photos / long voice recordings, or an attacker-injected restore reachable via the
B1-PLAT-2 share flow) turns every "Create backup" (HomeScreen ⋮ menu) into a **~600 MB+
peak-allocation on the IO thread** → recurring crash, worst case aborted mid-write with a torn
file in cacheDir. The backup feature itself became an interactive DoS.

**After** — the archive is never materialized; it is written and encrypted byte-at-a-time:

1. **Zip streams into a transient app-private staging FILE** — new pure-JVM
   `services/BackupExportPolicy.kt::zipVaultEntriesToStream` writes each vault entry via
   `ZipOutputStream(FileOutputStream(cacheDir/<name>.zip-staging))` — no `ByteArrayOutputStream`,
   no `toByteArray()`. `ImportExportService.exportBackup` (`:1281`) passes the existing
   db/imports/voice-blob packing lambda unchanged (the `FileInputStream.copyTo(zos)` inner loop
   did the bulk of the transfer even before; what changed is the sink).

2. **v2 password path encrypts file-to-file with a bounded 64 KiB chunk loop** —
   `BackupExportPolicy.encryptStreamGcm` (`:1338-1345`): writes the header verbatim, then pulls the
   staged zip in `ENCRYPT_CHUNK_BYTES` (64 KiB) reads, writing each `Cipher.update(buffer, 0, n)`
   output to the destination, then `cipher.doFinal()` (the tail + 128-bit tag). Peak heap per iterate
   is one chunk + one `update` output + the tag — **never** the archive. The JCE stream-mode contract
   guarantees `update(...)...update(...) + doFinal()` is byte-identical to one `doFinal()` over the
   whole payload for the same cipher state, so the on-disk `NFLB2` layout is unchanged and the real
   restore decryptor (`decryptBackupPayload`) reads the streamed output unmodified. The stage file
   is deleted in `finally` (`:1366`); the KEK is still zeroized (`:1347`).

3. **Device-keyed path streams through a Base64 encoder** —
   `BackupExportPolicy.encryptStreamDeviceKeyedBase64` (`:1359-1363`): fresh random IV, GCM with the
   `FIELD_AAD` domain, and `java.util.Base64.getEncoder().wrap(NonClosingSink(dest))` so the wire
   file stays `[PAYLOAD_VERSION][12-byte IV][ciphertext+tag]` — byte-identical to the pre-fix
   `EncryptionService.encrypt` output — without ever holding the archive or its ~1.37x Base64
   expansion in one array. `NonClosingSink` keeps `dest`'s close() ownership with the caller so a
   failed encryption can never half-close a shared sink; `encoder.close()` (which flushes residual
   bytes + padding) runs in a `runCatching` `finally`.

Memory bound: one 64 KiB buffer + one `Cipher.update` output + the tag (v2) or + the encoder's
internal buffer (device-keyed) — bounded regardless of vault size.

## File:line evidence (commit after)

| Site | Before | After |
|---|---|---|
| `services/ImportExportService.kt` `exportBackup` | `:1154-1177` zip accumulated in `ByteArrayOutputStream`, `:1176` `baos.toByteArray()` (whole archive in heap) | `:1279` `val stagingZip = File(context.cacheDir, BackupExportPolicy.stagingFileName(backupName))`; `:1281` `BackupExportPolicy.zipVaultEntriesToStream(FileOutputStream(stagingZip)) { zos -> … }` |
| `services/ImportExportService.kt` v2 path | `:1188-1190` `fos.write(header); fos.write(cipher.doFinal(zipData))` (second full-size copy) | `:1338-1345` `BackupExportPolicy.encryptStreamGcm(FileInputStream(stagingZip), FileOutputStream(tempBackupFile), kek, payloadIv, header, BACKUP_PAYLOAD_AAD)` |
| `services/ImportExportService.kt` device-keyed path | `:1210-1211` `val encryptedBase64 = encrypt(zipData, key)` (Base64 ~1.37x of full archive) | `:1359-1363` `BackupExportPolicy.encryptStreamDeviceKeyedBase64(FileInputStream(stagingZip), FileOutputStream(tempBackupFile), key)` |
| `services/ImportExportService.kt` cleanup | — | `:1366` `stagingZip.delete()` in `finally` (plaintext stage is transient + app-private); `:1347` `kek.fill(0.toByte())` retained |
| `services/BackupExportPolicy.kt` (new) | — | `object BackupExportPolicy`: `ENCRYPT_CHUNK_BYTES` = 64 KiB (`:53`), `STAGING_SUFFIX` = `".zip-staging"` (`:56`), `stagingFileName` (`:61`), `zipVaultEntriesToStream` (`:71-73`), `encryptStreamGcm` (`:92-131`), `encryptStreamDeviceKeyedBase64` (`:146-191`), private `NonClosingSink` (`:195-205`), `IDLE_READ_LIMIT` = 16 zero-progress-read guard (`:58`, mirrors `AttachmentIngestPolicy`): throws `IOException` instead of busy-spinning on a contract-breaking stream |
| `services/EncryptionService.kt` | `GCM_IV_LENGTH`/`GCM_TAG_LENGTH`/`PAYLOAD_VERSION`/`FIELD_AAD` were `private` | `:19-21,32` now `internal` (same app module) so the pure-JVM policy reuses the app's exact GCM constants/domain — single source of truth |

Callers unchanged (signature of `exportBackup` untouched): HomeScreen device-keyed (`HomeScreen.kt:533`)
+ password (`:1307`), WebDAV C2b (`NoteflowViewModel.exportEncryptedBackupToZip` `:3119`), LocalSend
VAULT_BACKUP (`LocalSendSendDialog.kt:131`) — all consume the returned cacheDir `File`.

## New/changed files

- Added `app/src/main/kotlin/com/authorss81/noteflow/services/BackupExportPolicy.kt` (pure-JVM bounded streamers + staging-name policy).
- Modified `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt` (`exportBackup` streams zip → stage file, encrypts file-to-file both paths, deletes stage; `encryptBackupPayload` KDoc now documents it as the single-shot layout-compat reference for restore + tests).
- Modified `app/src/main/kotlin/com/authorss81/noteflow/services/EncryptionService.kt` (4 constants `private`→`internal`).
- Added `app/src/test/java/com/authorss81/noteflow/B2Dos07BackupExportStreamingTest.kt` (8 tests).

## Verification

- `gradle :app:testDebugUnitTest --tests "…B2Dos07BackupExportStreamingTest"` — **8/8 pass**
  (byte-equality vs references, decrypt round-trips, chunk-budget invariants).
- `gradle testDebugUnitTest` — **BUILD SUCCESSFUL**, full suite green, **0 failures / 0 errors**
  (1487 pre-existing + 8 new; the `B1Plat01ReleaseSigningTest` asserts previously documented were
  already repaired by the `b9a0b52` CI fix in the phase-80 lineage, so there are no known
  pre-existing failures).
- `gradle assembleDebug` — **BUILD SUCCESSFUL**. First invocation hit a transient
  `mergeExtDexDebug` `DexArchiveMergerException` under the 2 GB default daemon heap; retried clean
  with a 4 GB CLI heap (`-Dorg.gradle.jvmargs="-Xmx4096m …" --no-daemon`, no repo `gradle.properties`
  change), 90/90 tasks, debug APK on disk:
  `app/build/outputs/apk/debug/app-debug.apk` (173,773,954 bytes).
- New tests `B2Dos07BackupExportStreamingTest` (8):
  1. `streamed v2 payload is byte-identical to the one-shot reference and decrypts` — a 6 MiB blob:
     the streamed file's header == `buildBackupHeader` verbatim, and the streamed ciphertext+tag ==
     `encryptBackupPayload`'s single `doFinal` (the JCE stream-mode contract), and the REAL
     `ImportExportService.decryptBackupPayload` restore reader recovers the plaintext.
  2. `streamed device-keyed backup decrypts through the real restore decryptor` — the Base64 file
     decodes to `[PAYLOAD_VERSION][iv][ct+tag]` and `EncryptionService.decrypt` (the restore reader)
     recovers the original zip bytes.
  3. `streaming GCM never reads or writes more than one chunk at a time` — a monitored 6 MiB input is
     pulled in ≥ 96 reads each ≤ 64 KiB; every output write ≤ 64 KiB + 16 (tag slack); read count ≥
     write budget; decrypted output byte-equal via the real decryptor.
  4. `vault zip streams incrementally to the staging file and stays a valid archive` — multi-entry
     vault (2 MiB DB + nested imports) written over many bounded writes (never one whole-archive
     write) and re-read as a valid zip with every entry byte-complete.
  5. `a large synthetic vault rounds-trips through the streamed export path` — ~6.7 MiB vault:
     staged → streamed-GCM encrypted → decrypted via `decryptBackupPayload` back to EXACTLY the
     staged archive → every zip entry matches the vault files.
  6. `exportBackup streams the archive to a staging file instead of a heap array` — source pins:
     no `val zipData` / `baos.toByteArray()` / `fos.write(cipherText)` / `val encryptedBase64` /
     `encrypt(zipData…`; staging-file + `stagingZip.delete()` + both policy streamer calls present.
  7. `BackupExportPolicy holds only bounded chunk buffers, never the archive` — source pins: exactly
     two `ByteArray(ENCRYPT_CHUNK_BYTES)` allocations (one per streamer), no `readBytes()` /
     `.toByteArray()` / `ByteArrayOutputStream` in the policy, Base64 wrap + `FIELD_AAD` + versioned
     wire format + 12-byte IV length.
  8. `staging file never becomes a public download name` — `stagingFileName` always appends
     `.zip-staging`, never `.noteflow` (the public Downloads name stays
     `BackupFileNamePolicy.localBackupFileName()`, B2-CRYPTO-06).

## Checksums / secrets handling

- No new secrets, keys, or passwords introduced; no logging added; `kek` zeroization retained.
- Staging zip is a TRANSIENT PLAINTEXT file in app-private `cacheDir` — it exists only between the
  zip pass and the encrypt pass and is deleted in `finally`. Same trust domain as the pre-existing
  cacheDir outputs (the encrypted `tempBackupFile` is also cacheDir); `cacheDir` is app-private
  (no `allowBackup`; FLAG_SECURE unaffected), so no additional exposure. Never exported out of
  cacheDir (only the encrypted output reaches public Downloads/WebDAV/LocalSend).
- No new dependencies (uses stdlib + JCE + `java.util.Base64`, which is API-26+ = the app's
  minSdk floor — no fallback required, no newer-API gating).
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE intact. No `.github/workflows/` changes.
- No schema change, no migration.

## Out of scope (documented, not fixed here)

- **Restore path** (`importBackup` → `tryParseBackupV2`/`decryptBackupPayload`, and the device-keyed
  legacy `EncryptionService.decrypt`) still reads the input archive in heap — it is an INPUT bounded
  at 400 MB by `MAX_BACKUP_INPUT_BYTES` via `boundedReadBytes` (phase-81), and was hardened
  separately (phases 56/64/81). The B2-DOS-07 finding is the EXPORT multiplier
  (archive + ciphertext + base64 ≈ 2-3x on every attempt).
- WebDAV PROPFIND `readText()` (B2-DOS-08), RDP recursion (B2-DOS-09),
  `lastSavedStrokeHash` growth (B2-DOS-10) — separate phases.
- The zip pass still uses `kotlin.io.copyTo` (8 KiB default) inside each entry while the socket to
  `ZipOutputStream` — bounded per-entry, not archive-sized; the V2Dos07 invariant that matters
  (no archive-sized buffers) is pinned by the tests.