# Phase 119: Scrollable overflow menus (Home ⋮ + Canvas ⋮) [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE BUG:** the three-dot (⋮) overflow menu on the **home page**
(`ui/screens/HomeScreen.kt`) and the **canvas/editor overflow menu**
(`ui/screens/EditorScreen.kt`) open a menu list that **cannot be scrolled** when
its content is taller than the screen (or taller than the available space),
so lower entries are unreachable on small screens / large fonts / landscape.

## What to do
- Find every `DropdownMenu` / overflow menu in `HomeScreen.kt` and
  `EditorScreen.kt` (plus any other overflow menus in
  `KnowledgeGraphScreen.kt` / `MarkdownPreviewScreen.kt`).
- Make each menu **scrollable**: add a scrollable container inside the menu
  (e.g. `Modifier.verticalScroll(rememberScrollState())` on the menu content or
  a `LazyColumn`), with a **max height** constraint (e.g. `heightIn(max = ...)`
  based on screen height) so it never overflows the screen bounds.
- The scroll affordance must be discoverable (thin scrollbar indicator when
  content overflows) and must work with hardware keyboard arrows where the menu
  already supports keyboard navigation.
- Do not change menu contents, ordering, or behavior otherwise.

## Verification
- Pure-JVM unit tests where possible (menu item count / overflow math), and
  verify by review that every `DropdownMenu` in the touched screens has both a
  max-height cap and vertical scroll.
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- Every overflow menu in the app scrolls when its content exceeds the
  available height; nothing is unreachable.
- `workspace/phase-119/REPORT.md` committed with file:line evidence of each
  changed menu.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact. Low-end safe (AGENTS.md hardware rule):
  no janky list — keep it simple and cheap.