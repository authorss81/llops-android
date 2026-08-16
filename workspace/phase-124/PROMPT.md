# Phase 124: Enhanced interactive tutorial (more slides, more interactive) [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.** The existing tutorial lives in
`ui/components/InteractiveTutorial.kt` (entry from `HomeScreen.kt`, first-run
gate in `SettingsManager`/`NoteflowViewModel`).

**THE GOAL:** make the interactive tutorial **much more enhanced** — more
slides, more interaction, more helpful — covering the app's real feature
surface.

## What to do
- Expand the tutorial from its current slide set to a **substantially larger,
  structured curriculum** (at least 3–4× the current slides), organized in
  sections: getting started, note-taking & markdown, canvas & brushes, layers,
  colours (incl. the new rainbow mode), erasers (both types), knowledge graph,
  plugins (off-by-default), backup/sync, and security features.
- Make it **interactive, not just text**: include step-through demos where the
  user performs a real action (e.g. "draw a stroke", "tap the eraser", "create
  a layer") and the tutorial advances/progress-checks on completion; progress
  indicator; skip/back/resume; persisted completion state.
- Respect the **low-end hardware rule**: animations must be cheap; offer a
  "skip" and a "don't show again" that truly persists.
- Do not ship tutorial content that claims features that don't exist — keep it
  aligned with the app's actual capabilities (check `docs/ARCHITECTURE.md`).

## Verification
- Pure-JVM unit tests where feasible (tutorial state machine: slide advance,
  progress-check completion, persistence, skip/resume).
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- Tutorial is expanded (3–4× slides) and interactive with progress-checked
  actions, skip/resume, and persisted state.
- `workspace/phase-124/REPORT.md` committed with file:line evidence.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact.