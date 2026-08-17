# Phase 138: v2/v3 restore — stream the decrypt file-to-file + align caps + guaranteed reopen after any failure [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (finding R2-B1D-04) and `docs/phase-status.md` + `docs/ARCHITECTURE.md`.
This phase removes the ~800MB in-heap restore peak and the asymmetric
export/restore budgets.

## Source finding (OPEN, LOW)

**R2-B1D-04** — `ImportExportService.kt:1563-1636` decrypts in-heap: encrypted
`ByteArrayOutputStream` + decrypted zip both materialized, cap
`MAX_BACKUP_INPUT_BYTES` = 400MB `:1134`, extraction cap 200MB total / 50MB
single `:1816-1856`. `performRestore` does `closeDatabase()` first
(`HomeScreen.kt:132-164`) so an exception mid-restore leaves the vault closed
with no automatic reopen (phase-09 H1 handles only failures that return).

## The fix (where & how)

- Stream the decrypt file-to-file, mirroring `BackupExportPolicy.encryptStreamGcm`
  (`services/BackupExportPolicy.kt`) — write the decrypted zip to a staging file
  under `cacheDir` instead of a `ByteArrayOutputStream`.
- Align the export and restore budgets (or document the mismatch explicitly):
  a DB copy whose decompressed size exceeds the extraction cap must not be
  exportable-and-unrestorable.
- Guarantee the post-restore-failure reopen/recovery path regardless of
  exception type (reuse `NoteflowDatabase.dispose()` + `NoteRepository.reopenDatabase`
  + the existing restart dialog on any post-close failure).

## Verification

- New/updated pure-JVM unit tests: a decrypted-payload factory that streams to
  file and never materializes both buffers; cap-parity assertions; a
  reopen-after-failure model test (fake repository seam).
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-138/REPORT.md`.

## Definition of done

- R2-B1D-04 closed with `file:line` before/after evidence.
- Large legitimate vaults restore without OOM on 512MB devices.
- Any restore failure leaves the vault in a clean recoverable state.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.