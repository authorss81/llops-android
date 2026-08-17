# Phase 148: Logging & error-surface scrub — every remaining raw `e.message` in UI + logcat routed through sanitizers [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-b2b3-LOG-01, R2-b2b3-LOG-02, R2-b2b3-LOG-03) and
`docs/phase-status.md` + `docs/ARCHITECTURE.md`. This phase extends the
phase-71/94 sanitization to the remaining surfaces.

## Source findings (all OPEN — LOW, LOW, INFO)

1. **R2-b2b3-LOG-01** (LOW) — Restore/recovery/backup failure surfaces render
   raw `${e.message}`: `HomeScreen.kt:159,161` (`"Restore failed: ${e.message}"`),
   `:202`, `:600`, `:1417`; `MainActivity.kt:860-862,919-921,1001-1003` (three
   recovery screens); `NoteflowViewModel.kt:2166,2216`; and
   `ImportExportService.kt:1835,1846` interpolates attacker-carried text
   (`"Backup contains unsafe relative path: $entryName"`). Residuals:
   `ui/components/Dialogs.kt:136`, `EditorScreen.kt:865`,
   `services/localsend/LocalSendSender.kt:378` + `:588-595`.
2. **R2-b2b3-LOG-02** (LOW) — `VoiceNoteManager` logs raw `${e.message}` to
   logcat from 8 sites (`VoiceNoteManager.kt:182,224,347,360,390,401,413,428`)
   — the encrypted-voice subsystem leaks app-private file paths (the file's own
   comment at `:232-234` bans path-bearing log lines).
3. **R2-b2b3-LOG-03** (INFO) — `ProtobufBrushLoader` echoes `${e.message}` +
   the brush `name` into logcat (`ProtobufBrushLoader.kt:67,80,88-96`) — dormant
   API today but becomes real if a brush-import feature wires in (see
   phase-155).

## The fix (where & how)

- **R2-b2b3-LOG-01:** Route all listed surfaces through the phase-71/94 style
  fixed-text decision (reuse `WebDavFailurePolicy.scrubForDisplay` /
  `FailureLogPolicy.safeLogMessage`/`classNameToken`) — only fixed,
  user-meaningful strings reach snackbars/recovery screens. Do not echo
  `entryName`/absolute paths.
- **R2-b2b3-LOG-02:** log `FailureLogPolicy.classNameToken(e)` only in
  `VoiceNoteManager`; never `e.message` (scrub `name`/file-derived text too).
- **R2-b2b3-LOG-03:** drop `e.message` (class-name token only) and omit/sanitize
  `name` in `ProtobufBrushLoader`; if the dormant API gains a caller, keep it
  sanitized by default.

## Verification

- New/updated pure-JVM + source-pin unit tests: every listed site emits only
  sanitized/fixed text (repo-wide `${e.message}`-in-UI scan for the named files);
  a VoiceNoteManager log-line pin; a ProtobufBrushLoader pin.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-148/REPORT.md`.

## Definition of done

- All three findings closed with `file:line` before/after evidence.
- No raw `e.message`/attacker-carried text reaches a user-facing error surface
  or logcat in the named files.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.
- Do NOT build/run Gradle locally on a Windows dev machine (AGENTS.md).