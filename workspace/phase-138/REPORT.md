# Phase 138 — v2/v3 restore: stream the decrypt file-to-file, align export/restore caps, guarantee reopen after any failure

**Status: DONE (2026-08-18)**

Closes the OPEN finding `R2-B1D-04` (LOW) from `docs/security-report-round2.md`.

---

## R2-B1D-04 (LOW) — restore decrypt holds the encrypted input AND the decrypted zip in heap at once (~800MB peak), extraction hits a LOWER 200MB cap, and a mid-restore failure leaves the vault closed with no automatic reopen

### Before

1. **In-heap decrypt.** `ImportExportService` decrypted v2/v3 backups in heap:
   the encrypted payload was assembled into a `ByteArrayOutputStream` and the
   decrypted zip was materialized as another full `ByteArray` (`:1563-1636` at
   audit). A legitimate ~400MB backup therefore peaked around ~800MB of heap
   (400MB ciphertext + 400MB plaintext) → OOM on the 512MB-limit, 2-core device
   class the app explicitly supports.
2. **Asymmetric budgets.** The wire cap was `MAX_BACKUP_INPUT_BYTES = 400MB`
   (`:1134`) but extraction enforced **200MB total / 50MB single** (`:1816-1856`).
   A DB copy (or archive) whose decompressed size fell between the two caps could
   be EXPORTED successfully but could never be RESTORED — an "exportable and
   unrestorable" backup.
3. **No guaranteed reopen.** `HomeScreen.performRestore` ran
   `repository.closeDatabase()` BEFORE `importBackup`
   (`HomeScreen.kt:132-164` at audit). The phase-09 H1 reopen only ran on the
   catch paths that RETURNED; an unchecked `Throwable` (e.g. the OOM the in-heap
   decrypt could trigger) escaped the `catch (e: Exception)` and left the vault
   closed with the app on a dead Room instance.

### After

#### 1. The decrypt is now file-to-file (memory: one 64KiB chunk)

`BackupExportPolicy` gained the streaming decrypt mirrors of
`encryptStreamGcm` — same `ENCRYPT_CHUNK_BYTES`, same bounded no-progress guard,
same AAD+header re-feed, all-or-nothing `doFinal` tag:

- `decryptStreamGcm(cipherIn, dest, kek, payloadIv, header, payloadAad)`
  (`BackupExportPolicy.kt:150`)
- `decryptStreamGcmLegacyZeroAad(...)` — the v2 pre-B2-CRYPTO-03 rescue retry
  (`BackupExportPolicy.kt:200`)
- `skipFully(input, bytesToSkip)` — looped header skip for the v3 payload prefix
  (`BackupExportPolicy.kt:239`)

`ImportExportService` consumes them:

| step | before | after |
|---|---|---|
| v2/v3 parse | `tryParseBackupV2` on the whole byte array | `tryParseBackupV2File(backupFile, password)` — 128-byte head probe (`readFileHead` `:1634`) then `decryptPayloadToFile` streamed into a transient staging FILE (`:1528`, `:1661`) |
| v3 key half | in-heap prefix slice | `readFileHead(staging, BACKUP_WRAP_KEY_HALF_SIZE)` + `combineWrapKey` (`:1728-1729`); the zip starts at `offsetBytes = 16` |
| password verify | `validateBackupPassword(bytes, …)` | `validateBackupPasswordFile(file, …)` — v2 cheap wrapped-DEK probe, v3 ONE full streaming payload decrypt + DEK unwrap (`:1779`) |
| legacy device-keyed | `String(bytes) + Cipher.doFinal` | `decryptDeviceKeyedToFile` — streaming `Base64.Decoder.wrap` + 64KiB GCM chunk loop + `doFinal` tag (`:1992`) |
| UI staging | whole archive `ByteArray` across pickers/VM | `stageBackupUriToFile(context, uri, maxBytes)` → cache FILE, bounded read, deletes on failure (`:1879`); `importBackup(context, backupFile: File, …)` (`:1910`) |
| extraction | `restoreFromZip(bytes, …)` | `restoreFromZip(zipFile: File, offsetBytes, …)` + `extractBackupEntriesTo(zipFile, offsetBytes, …)` with `BackupExportPolicy.skipFully` for the v3 prefix (`:2053`, `:2084`) |

The KEK zeroization contract is unchanged (zeroized on every outcome), and the
decrypted staging file is deleted on every outcome (`importBackup` `finally`
`:1941-1946`, legacy `finally` `:1979-1981`).

#### 2. One shared budget — export and restore can never drift

New pure-JVM `services/BackupBudgetPolicy.kt` is the SINGLE source of truth:

- `MAX_ENTRY_BYTES = 100MB`, `MAX_TOTAL_BYTES = MAX_INPUT_BYTES = 400MB`,
  `MAX_ENTRY_COUNT = 40,000`, `MAX_EXPANSION_RATIO = 100x` (floor 4KiB).
- `MAX_TOTAL_BYTES == MAX_INPUT_BYTES` is the strongest possible binding:
  AES-GCM output is exactly input + 16 bytes, so any archive that passed the
  wire cap can decompress to at most ~400MB — a restorable archive is NEVER
  rejected by the total budget, and nothing over the wire cap can silently
  decompress past it.
- Export packer: `claimPackFile` (`BackupBudgetPolicy.kt:118`) refuses, at PACK
  time, any file over the per-entry cap or any archive whose packed sum would
  exceed the total — the "exportable-unrestorable" archive is now impossible
  (`ImportExportService.exportBackup` `:1353-1384`, every entry routed through
  the guarded `packFile`).
- Restore extractor: `claimRestoreChunk`/`settleRestoreEntry` (`:136`, `:156`)
  enforce the SAME budget over ACTUAL decompressed bytes read (a forged declared
  size cannot bypass it), consumed by `copyWithLimit` (`ImportExportService.kt:1462`).

#### 3. Guaranteed reopen after ANY post-close failure

New pure-JVM `services/RestoreFailSafe.kt`:

```kotlin
suspend fun <R> guaranteeReopenAfterRestore(
    closeDatabase: () -> Unit,
    restore: suspend () -> R,
    reopenDatabase: () -> Unit
): R {
    closeDatabase()
    try {
        return restore()
    } catch (t: Throwable) {
        runCatching { reopenDatabase() }   // absorbed — never masks the real error
        throw t
    }
}
```

It catches **any `Throwable` including `Error`/OOM** (the exact crash class the
old in-heap decrypt could trigger), best-effort reopens, then rethrows untouched.
Success leaves the repository closed for the swap + restart. Wired into all four
restore entry points, replacing the ad-hoc `catch (e: Exception)` reopens:

- `NoteflowViewModel.attemptRecoveryFromBackup` (`:2313`)
- `NoteflowViewModel.attemptKeystoreKeyLostRecoveryFromBackup` (`:2403`)
- `NoteflowViewModel.restoreEncryptedBackupFromZip` (WebDAV) (`:3772`) — plus a
  pre-close `File.length()` cap gate with the truthful "too large to restore
  (max 400 MB)" message (`:3755`)
- `HomeScreen.performRestore` (`:172`)

The EmptyVault paths now RELY on the failsafe's reopen already having run
(confirmed in the `EmptyVaultRestoreDecisionException` branches).

#### Bonus (real gap, found while wiring the picker)

`HomeScreen` classified only `NFLB2` as "password-protected"; NFLB3 backups fell
to the legacy **UNTRUSTED/UNSIGNED** device-keyed dialog. The file-head
classifiers `isNflbBackupFile`/`isPlainPkBackupFile`
(`ImportExportService.kt:3116/:3129`) now send **both** NFLB2 and NFLB3 to the
password dialog (`HomeScreen.kt:224`), plain PK zips to the refuse snackbar
(`:234`), and anything else to the legacy confirm — correct routing for every
format.

---

## Verification (PROMPT.md)

- **decrypted-payload factory that streams to file and never materializes both
  buffers** — `Phase138RestoreStreamingTest`: multi-MB `encryptStreamGcm` →
  `decryptStreamGcm` file-to-file round trip (byte-identical, one 64KiB chunk);
  zero-AAD legacy payload rescued only by the retry path; v2 parse/verify
  factory returns a FILE payload with no leftover decrypt staging after a failed
  parse. (Source: only 4 fixed `ByteArray(ENCRYPT_CHUNK_BYTES)` buffers in
  `BackupExportPolicy`, no `readBytes(`/`toByteArray()` archive materialization.)
- **cap-parity assertions** — `Phase138RestoreStreamingTest`: total == wire cap;
  the packer refuses exactly what the extractor refuses (over-budget entry on
  both sides, over-total on both sides); the inverse property (a golden vault
  under every cap is accepted by both and agrees byte-for-byte); the 100x ratio
  seal on both sides.
- **reopen-after-failure model test (fake repository seam)** —
  `Phase138RestoreStreamingTest`: failure → reopen before the error escapes;
  unchecked `OutOfMemoryError` → reopen; a reopen failure never masks the real
  error; success leaves the repo closed with zero reopens.

Commands (Linux/CI, no gradle wrapper):

```
gradle testDebugUnitTest     # 1939 total (app 1889 + plugins:llm 50), 0 fail/0 err/0 skip
gradle assembleDebug         # BUILD SUCCESSFUL
```

## Definition of done

- [x] R2-B1D-04 closed with `file:line` before/after evidence (above).
- [x] Large legitimate vaults restore without OOM on 512MB devices — restore
      decrypt is now bounded by one 64KiB chunk plus a 128-byte head probe; the
      archive and the decrypted zip are never both in heap (were ~800MB peak).
- [x] Any restore failure leaves the vault in a clean recoverable state — the
      failsafe reopens the repository on ANY post-close `Throwable` (incl. OOM)
      at all four entry points; the process restart dialog is reused unchanged.

## Constraints

- [x] No DB schema change; no new dependencies; `.github/workflows/` untouched.
- [x] No keys/passwords/decrypted content logged. KEK zeroized on every outcome;
      decrypted staging files deleted on every outcome.
- [x] `allowBackup=false` untouched.

## Residual notes (documented, NOT fixed here)

- `Dialogs.kt` (APK-update dialog) still reads a picked APK through
  `ImportExportService.readUriBytes` (whole file in heap) before writing it to
  cache (`Dialogs.kt:54-60`). Bounded by `MAX_BACKUP_INPUT_BYTES` but not
  streamed. Outside this finding's scope (backup-restore path); flagged for the
  Phase 27 bug-fix queue if APKs grow large.
- `ImportExportService.readUriBytes` remains for the non-restore import paths
  (DOCX/HTML/Obsidian), where the artifact is bounded by
  `ImportArchivePolicy`'s own caps.

## Review fixes (2026-08-18)

Findings from the phase-138 review, all applied:

1. **HomeScreen staged-file leak (LOW)** — the picker's `stageBackupUriToFile`
   cache files were never deleted: the plain-PK branch deleted, but every
   cancel/dismiss only nulled `pendingRestoreFile` and `performRestore` never
   cleaned the input (only the decrypt staging). Fixed: `HomeScreen` gained
   `clearPendingRestore()` (delete + null) used by all cancel/dismiss paths, and
   `performRestore` deletes the staged file in its `finally` (incl. the
   in-flight-gate early return) — the file now lives only for the duration of
   the pick→confirm→restore cycle. `HomeScreen.kt:140-221,1337-1510`.
2. **Residual wire-cap asymmetry (LOW)** — the packer sized by uncompressed sum
   only, so a vault at the 400MB ceiling with incompressible media produced an
   encrypted file slightly over the 400MB wire cap that restore then refused.
   Fixed: `exportBackup` now enforces the SAME 400MB input cap on the finished
   encrypted file (`tempBackupFile.length()`, loud fail-closed delete+throw,
   `ImportExportService.kt:1455-1467`) — the last "exportable-unrestorable"
   band is closed.
3. **Entry-count belt asymmetry (LOW)** — restore counted directory records
   against the 40k belt while export counted only packed files. Fixed: the
   extractor now charges `claimEntry()` to LEAF entries only, exactly mirroring
   `claimPackFile` (`ImportExportService.kt:2107-2117`).
4. **Un-backupable >100MB artifacts (INFO, design)** — deliberately fail-closed:
   a vault with a single artifact over the per-entry cap (or total over 400MB)
   is refused at export with the file named; the trade-off is now documented at
   the pack site (`ImportExportService.kt:1344-1351`). No exclusion-with-warning
   path by design (out of scope; a shrunk artifact is the only remedy).
5. **Failsafe wrong-key reopen on keystore-lost (LOW)** — `repository.encryptionKey`
   was swapped to the fresh DEK BEFORE the restore, so any post-close failure
   (now auto-recovered by `RestoreFailSafe`) opened the untouched OLD vault with
   the NEW key. Fixed: the swap is deferred until AFTER the restore succeeds
   (`NoteflowViewModel.kt:2398-2442`); `importBackup` takes the DEK as an
   explicit param, so a failure still reopens the old vault under its old key.
6. **Doc count (INFO)** — `Phase138RestoreStreamingTest` has 12 tests, not 10;
   corrected in `docs/ARCHITECTURE.md` + `docs/phase-status.md`.
7. **Dead "corrupted" rethrow (INFO/style)** — the wire decrypt never raises a
   human "corrupted…" message, so the `contains("corrupted")` early rethrow in
   `tryParseBackupV2File` was dead code; removed and replaced with a comment
   making the per-candidate corruption decision explicit
   (`ImportExportService.kt:1684-1706`).

Retest after fixes: `gradle :app:testDebugUnitTest` (1889, 0 fail) +
`gradle assembleDebug` (green) — unchanged totals, all green.
