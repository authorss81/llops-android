# Phase 123: Immediate effect when selecting colour / layer / tool [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE BUG:** when the user selects a **new colour, layer, or tool** it does
**not take effect immediately** — the user must switch pens (or perform another
gesture) for the selection to apply. State changes are being deferred or
snapshotted instead of applied live.

## What to do
- Trace the selection → apply path in `EditorScreen.kt` /
  `AnnotationCanvas.kt` / `NoteflowViewModel.kt` for: colour changes, layer
  switches, tool changes (and eraser type where applicable).
- Find where the change is queued/deferred (e.g. applied only on next
  `onDraw` of a different tool, or a stale `remember` value that doesn't
  recompose) and make it **apply immediately**: the next stroke, the current
  picker preview, and the live canvas state must reflect the selection at once.
- Ensure the picker UI itself updates instantly (selection highlight follows
  the tap) and the canvas cursor/preview follows.
- Add regression coverage proving a selection is effective for the very next
  stroke without any intermediate action.

## Verification
- Pure-JVM unit tests (state propagation: select colour → next stroke uses it;
  switch layer → next stroke lands on it; switch tool → next stroke is that
  tool). Reuse existing test layout in `app/src/test`.
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- Colour/layer/tool changes take effect immediately — no pen-switch needed.
- `workspace/phase-123/REPORT.md` committed with file:line evidence
  (before/after).

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact.