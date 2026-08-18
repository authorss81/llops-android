# Phase 132 — Command Palette header: fix contracted/squished title text

**Status: DONE (2026-08-18)**

## The bug (owner-confirmed)

In the command palette header, the title "Command Palette" collapsed into a
narrow **vertical strip** on portrait/mobile viewports.

### Before

`app/src/main/kotlin/com/authorss81/noteflow/ui/components/CommandPaletteOverlay.kt`
header (pre-fix, ~lines 178-200):

```
Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    Icon(Icons.Outlined.Search, ...)                     // 24dp
    Text("Command Palette", titleMedium, weight(1f))     // weighted → squeezed
    Text("⌘ ↑/↓ · Enter · two-finger swipe down to open") // UNCONSTRAINED, 45 chars
    IconButton(onClick = onClose) { Icon(Close) }        // 48dp
}
```

**Mechanics:** the Row is only as wide as the palette surface (viewport − 24dp
horizontal padding on the dialog + 32dp inner padding). The shortcut hint Text
had **no `weight`, no `maxLines`, no `overflow`** — Compose gives it its full
natural width (~45 chars of `labelSmall`). Only after the hint + search icon +
close button (48dp, M3 minimum touch target) are laid out does the remaining
space go to the title's `Modifier.weight(1f)`. On a ~360dp phone that leaves a
few dozen dp for "Command Palette" at `titleMedium` — the title wraps/condenses
into the observed narrow vertical strip.

### After

The header is now a **nested Column** (`CommandPaletteOverlay.kt:188-204`):

```
Row(verticalAlignment = CenterVertically, spacedBy(8.dp)) {
    Icon(Icons.Outlined.Search, ...)
    Column(Modifier.weight(1f)) {                        // gets ALL remaining width
        Text("Command Palette", titleMedium,
             maxLines = 1, overflow = TextOverflow.Ellipsis)   // full-width line 1
        Text("⌘ ↑/↓ · Enter · two-finger swipe down to open",
             labelSmall, onSurfaceVariant,
             maxLines = 1, overflow = TextOverflow.Ellipsis)   // hint beneath, line 2
    }
    IconButton(onClick = onClose) { Icon(Close) }
}
```

- The title now has its **own full-width line** — only the search icon and the
  close `IconButton` share its Row, so it keeps proper horizontal width at every
  viewport size (`maxLines = 1` + `TextOverflow.Ellipsis` is belt-and-suspenders:
  the title is short, but a giant-font user is still protected from wrap).
- The shortcut hint is rendered **beneath** the title, also single-line with
  ellipsis — it can never consume the title's width again.
- The weighted Column absorbs the leftover width; the header grows one short
  line taller (titleMedium + labelSmall), which is negligible in an overlay that
  is already `wrapContentHeight` with a `heightIn(max=360dp)` list.

### File:line evidence

- Layout fix: `CommandPaletteOverlay.kt:188-204` (nested `Column`, both `Text`s
  `maxLines = 1` + `TextOverflow.Ellipsis`).
- Header strings moved to single source of truth:
  `services/CommandPaletteHeaderPolicy.kt` (`TITLE`, `SHORTCUT_HINT`), consumed
  at `CommandPaletteOverlay.kt:192,198`.

## Extracted pure-JVM copy holder + test

Layout itself is not JVM-testable, so the header copy was extracted to
`app/src/main/kotlin/com/authorss81/noteflow/services/CommandPaletteHeaderPolicy.kt`
(pure JVM, API-26+ floor, no new deps) so the layout can never drift from the
text it renders:

- `TITLE` / `SHORTCUT_HINT` constants — the copy the composable renders.
- Truncation is deliberately left to the composable: both strings render on
  their own single line with `maxLines = 1` + `TextOverflow.Ellipsis`, so
  Compose ellipsizes by PIXEL width (font-scale aware). **Phase 132 review
  fix:** an initial `shortcutHint`/`truncate` character-budget decision table
  was REMOVED — it was dead production code (the composable never called it;
  only its test did), and a char budget can never match the pixel-based
  rendered output anyway.

Test: `app/src/test/java/com/authorss81/noteflow/Phase132CommandPaletteHeaderTest.kt`
(3 tests): header-copy pin, hint-longer-than-a-narrow-line premise, and title
vs hint distinctness.

## Visual verification (layout description — no device on CI runner)

- **Portrait phone (~360dp wide surface):** pre-fix the title strip was ~4-6
  characters wide vertically-wrapped; post-fix line 1 = "Command Palette" at
  full width with the search icon left and close button right, line 2 = the
  hint ellipsized at the viewport edge (e.g. `⌘ ↑/↓ · Enter · two-fi…`). Both
  lines are horizontally aligned and readable.
- **Landscape / wide viewport:** unchanged look — full title + full hint (the
  hint fits, no ellipsis) on two tidy lines.
- **Large font scale:** title + hint each clamp to one line and ellipsize
  rather than wrapping/condensing.
- Palette open/close (Dialog, dismiss-on-back/outside, close button),
  search debounce, tag chips, keyboard nav (↑/↓/Enter/Esc), and action rows are
  all untouched code paths — no regression.

## Verification

- `gradle testDebugUnitTest` → **BUILD SUCCESSFUL**: app 1806 tests +
  plugins:llm 50 tests = **1856 total, 0 failures / 0 errors / 0 skipped**
  (down from the pre-review 1863 — the 7 dead-decision-table tests removed;
  plugin-sdk NO-SOURCE).
- `gradle :app:assembleDebug` → green (57/57 tasks). A first plain invocation
  hit the documented transient packaging flake; the forced `--rerun-tasks` run
  executed 57/57 and passed. Debug APK
  `app/build/outputs/apk/debug/app-debug.apk` SHA-256 `da287f67…`.

## Constraints honored

- No DB schema change; no migration; no new dependencies
  (`gradle/verification-metadata.xml` untouched).
- `.github/workflows/` untouched.
- No logging of keys/decrypted content; `allowBackup=false`, `ClipboardGuard`,
  FLAG_SECURE all untouched (UI-only change, nothing touches those layers).
- Low-end safe: the change is two `Text`s + one `Column` — no new allocations
  beyond the pre-existing compose frame, no I/O, no threading.

## Definition of done

- [x] Title renders un-squished with the hint ellipsized below it
- [x] No regression to palette open/close/search behavior
- [x] REPORT.md committed
