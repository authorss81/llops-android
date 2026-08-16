# Phase 127: Normal text style everywhere (no exaggerated typography) [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE BUG:** some UI text uses **exaggerated/non-standard typography** —
oversized display styles, all-caps, extreme weights, or inconsistent styles
that make parts of the UI look off (e.g. dialogs, menus, pickers, empty
states). Most text in the app is normal; the outliers should be normalized.

## What to do
- Audit `Text(...)` usages across `ui/` (screens, components, dialogs,
  `theme/` typography) for non-standard styles (e.g. `displayLarge/Medium`
  used for body content, all-caps labels, heavy weights on secondary text,
  giant empty-state copy).
- Normalize them to the app's standard body/label/title styles
  (`MaterialTheme.typography.*` defaults as already used by the majority of
  the app), preserving hierarchy (titles stay titles) but removing
  exaggeration.
- Keep accessibility: no tiny text; contrast unchanged; do not touch the
  canvas ink UI's purposefully styled elements (e.g. brush previews) unless
  they are clearly broken.
- Do not change any content strings, only their presentation.

## Verification
- Verify by review with file:line evidence of each change; add pure-JVM tests
  only where a style helper exists.
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- UI text is consistent and normal across screens/dialogs; no exaggerated
  styles remain.
- `workspace/phase-127/REPORT.md` committed with file:line evidence.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact.