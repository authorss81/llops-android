# Phase 132: Command Palette header — fix contracted/squished title text [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE BUG (owner-confirmed):** in the command palette header, the title
"Command Palette" gets **contracted/squished into a narrow vertical strip** on
portrait/mobile viewports.

## What to do
- **Fix `app/src/main/kotlin/com/authorss81/noteflow/ui/components/CommandPaletteOverlay.kt`**:
  in the header row, a long unconstrained shortcut label
  (`"⌘ ↑/↓ · Enter · two-finger swipe down to open"`) is placed directly
  alongside "Command Palette" with `Modifier.weight(1f)`. On portrait/mobile
  viewports the label consumes the available width, squeezing the title.
- Re-architect the header into a **nested Column**: title on its own line
  (`maxLines = 1`, `TextOverflow.Ellipsis`, full width) with the shortcut hint
  rendered **beneath the title** (also single-line, ellipsized if needed).
  Title must keep proper horizontal width at all viewport sizes.
- Where feasible, add a pure-JVM test for any extracted helper (e.g. hint
  string builder / truncation logic). Layout itself is not JVM-testable —
  document the visual verification in REPORT.md.

## Verification
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).
- REPORT.md with before/after (screenshot or layout description) + file:line
  evidence.

## Definition of done
- Title renders un-squished with the hint ellipsized below it; no regression to
  palette open/close/search behavior; REPORT.md committed.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact. Low-end safe.