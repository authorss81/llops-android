# Phase 127: Plugin Store — compact/collapsed descriptions [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE BUG:** in the **Plugin Store** (`PluginStoreDialog`, reachable from
HomeScreen ⋮ → "Plugin Store"; see `docs/PLUGINS.md`), plugin descriptions
**take too much vertical space** — long text makes each card huge and the list
hard to scan.

## What to do
- Make plugin cards **compact**: show a short one/two-line summary (ellipsized
  with `maxLines` + `TextOverflow.Ellipsis`) by default, with the full
  description **collapsible/expandable** on tap (or a chevron "more" control).
- Keep the install/enable action and any badges (e.g. version, size) visible
  and tappable without opening the expand.
- Ensure the collapsed state is the default so the list fits more plugins per
  screen; the expanded state is per-card, remembered only while the dialog is
  open (no persistence needed).
- Do not change plugin metadata or install behavior.

## Verification
- Pure-JVM unit tests where feasible (summary truncation logic, expand/collapse
  state per card); otherwise verify by review with file:line evidence.
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- Plugin Store cards are compact with ellipsized summaries and expandable full
  descriptions; install/enable actions remain one tap.
- `workspace/phase-127/REPORT.md` committed with file:line evidence.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact.