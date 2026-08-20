# Phase 178: Share-sheet capture (ACTION_SEND) + home-screen widget - the feasible part only [NOT STARTED]

You are working on **InkFlow/Noteflow**. ROADMAP item **22.5** is still open: "SHARE-SHEET
CAPTURE (`ACTION_SEND` INTENT FILTER) + HOME-SCREEN WIDGET (HABIT LOOPS). **Widget
deferred (needs device); share-sheet capture still to do.**"

Phase-15 already added `ACTION_SEND` intent filters to `AndroidManifest.xml:43-67`
for the base app. THIS phase implements the missing "share-sheet capture" HANDLER:
receiving text/URLs/notes shared FROM another app INTO Noteflow, and saving them
into the vault. The home-screen widget is explicitly OUT OF SCOPE (needs a real
device to test - deferred).

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-178 step N: <desc>" && git push`
after EVERY step. Never sit on uncommitted work.

## Step 1 - Inventory (commit it)
- Read `AndroidManifest.xml:43-67` ACTION_SEND filters - which activities declare
  them, what MIME types are claimed (text/plain, text/markdown, image/*, application/pdf?),
  and whether `MainActivity` or a dedicated activity is the launch target.
- Find how inbound URIs flow today (SAF pickers, imports) so the new capture path
  reuses the SAME quarantine/import machinery (`ImportExportService`) instead of
  duplicating it.
- COMMIT this step.

## Step 2 - Receive ACTION_SEND content (text + URIs)
- Handle `Intent.ACTION_SEND` (extra `EXTRA_TEXT` for text/URLs, `EXTRA_STREAM` for
  file URIs) in the declared entry activity. Persist a pending-capture flag + the
  received payload, then either:
  - launch the existing import flow if the vault is unlocked, or
  - show the capture on next successful unlock if the vault was locked (never
    write plaintext while locked - reuse the deferred-write pattern).
- Support single AND multiple files (`ACTION_SEND_MULTIPLE`) if the filters claim it.
- Take content:// URIs through the existing permission-take + bounded-read path
  (`readUriBytes` with the size cap, zip-bomb/plain-zip guards per phases 55/56).
- COMMIT this step.

## Step 3 - Save into the vault
- Shared text/URLs -> a new note (or append to a chosen notebook); shared files ->
  imported via `ImportExportService` (markdown/html/obsidian/image paths already
  exist). Non-alarming confirmation Snackbar on success; clear non-alarming message
  on refusal (oversized / unencrypted-backup-like / unsupported type).
- COMMIT this step.

## Step 4 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Add pure-JVM tests for the capture-payload policy: defer-while-locked (never
  plaintext), bounded read, unsupported-type refusal, single vs multiple.

## Definition of done
- Sharing "send to Noteflow" from another app lands the content in the vault
  (via existing import/quarantine machinery) with honest non-alarming feedback.
- `workspace/phase-178/REPORT.md`: manifest filters before/after, handler wiring,
  how locked-vs-unlocked is handled, test list.
- Widget NOT implemented (documented as deferred, needs device).
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Never write plaintext content to disk while the vault is locked.
- Reuse existing import/security machinery - do not create a parallel write path.