# Phase 183 — GalleryView typography & title wrapping — REPORT

**Status:** DONE (2026-08-20). Review fixes committed (2026-08-20, "llops: phase-183
review fixes") — see "Review fixes" below.

## Problem (user visual review)
In the compact gallery grid (`GridCells.Adaptive(minSize = 168.dp)`, card inner
padding 14dp + type badge), a title like `2026-08-19.md` wrapped mid-extension in
the ~110-140dp text column: `2026-08-19.m` + a dangling `d` on the next line. The
raw stored `.md` suffix was also displayed.

## Root cause
`ui/components/GalleryView.kt:148-155` rendered `page.title` verbatim with standard
word-break. `2026-08-19.md` is one unbreakable "word" that overflows the narrow
column, so Compose broke it at a glyph boundary (splitting the extension), and the
`.md` suffix consumed width that a daily-note title shouldn't show. Note: 
`Hyphens.None` only disables explicit hyphenation; it does NOT stop Compose from
breaking a long unbreakable token at a glyph boundary under `softWrap=true`. The
mechanism that fixes the reported case is the suffix strip (the title becomes short
enough to fit one line); genuinely long titles still split at `maxLines=2` and
ellipsize (acceptable by design).

## Fix
1. **New pure-JVM policy** `services/GalleryTitleDisplayPolicy.kt` —
   `displayTitle(rawTitle)` strips **at most one** redundant `.md`/`.markdown`/`.txt`
   suffix (case-insensitive) **for display only**. The stored DB title is never
   mutated (routing/export/wiki-link consumers in `MainActivity.kt:601,711` and the
   rename dialogs keep the raw value). `foo.md.md -> foo.md` (keeps one suffix); a
   bare `.md` is never fully stripped; all other names round-trip untouched.
2. **`GalleryView.kt:148-156`** — text now `GalleryTitleDisplayPolicy.displayTitle(page.title)`
   and renders with `maxLines = 2`, `overflow = TextOverflow.Ellipsis`,
   `softWrap = true`, `style = titleSmall.copy(hyphens = Hyphens.None)`,
   `fontWeight = FontWeight.SemiBold`, `lineHeight = 18.sp`. (The M3 `Text`
   overload in Compose UI 1.7.6 does not expose a `hyphens` parameter, so
   `Hyphens.None` is applied through the `style` — verified against the resolved
   `ui-text-1.7.6` API.)
3. **`GalleryView.kt:274-279`** — footer date label gained `maxLines = 1` +
   `TextOverflow.Ellipsis` so a long localized date can no longer wrap in the same
   narrow column.

## Before / after
- Before: `2026-08-19.md` → wrapped as `2026-08-19.m` / `d` (mid-extension break),
  `.md` shown.
- After: `2026-08-19.md` → `2026-08-19`, single line; any longer title ellipsizes
  cleanly at 2 lines with no explicit hyphenation, `.md`/`.markdown`/`.txt` hidden.

## Review fixes (2026-08-20, applied per review findings)
1. **Finding 1 — `hyphens` no longer passed as a direct `Text(...)` param.** The
   step-2 intermediate commit `adbeed3` used `Text(hyphens = Hyphens.None, ...)`,
   which cannot compile against material3 1.3.1 (its `Text` overload has no
   `hyphens` parameter). The final code applies it via
   `style = titleSmall.copy(hyphens = Hyphens.None, lineHeight = 18.sp)` — typography
   is one source of truth (both are `.sp`, so they scale with the user's font scale).
   Pinned by new `Phase183GalleryTypographyTest` (2 tests) so the direct-param form
   cannot return.
2. **Finding 2 — dead test assertion removed.** `GalleryTitleDisplayPolicyTest`
   previously contained a tautological `assertEquals("  Note.md  ", "  Note.md  ")`.
   Replaced with a meaningful assertion that whitespace trimming also applies to
   extension-less names.
3. **Finding 3 — display-title consistency across home view modes.** The same
   `displayTitle(page.title)` policy is now applied to the OTHER display-only title
   render sites in the home tab: list view (`HomeScreen.kt:2587`), tag-editor dialog
   item title (`HomeScreen.kt:1920`), Kanban cards (`KanbanBoardView.kt:218`),
   Calendar cards (`CalendarView.kt:241`), Spreadsheet cells
   (`SpreadsheetTableView.kt:100`), the editor app bar (`EditorScreen.kt:1400`), and
   the Markdown preview app bar (`MarkdownPreviewScreen.kt:536`). The stored DB title
   is still NEVER mutated — routing (`MainActivity.kt:601,711`), rename dialogs, and
   export call sites keep the raw value.
4. **Finding 4 — root-cause text corrected.** Documented (above, Root cause) that the
   fix mechanism is the suffix strip for short daily titles; `Hyphens.None` only
   disables explicit hyphenation and long tokens still ellipsize at `maxLines=2`.
5. **Finding 5 — `lineHeight` consolidated into the style.** `lineHeight = 18.sp`
   now lives inside `titleSmall.copy(...)` instead of as a separate overriding
   parameter; it is `.sp`, so ratio with `fontSize` is preserved at large
   accessibility font scales (no added clipping), with an explanatory comment.

## Tests
`GalleryTitleDisplayPolicyTest` (11): `.md`/`.markdown`/`.txt` stripped,
case-insensitive matching, other extensions (`jpg`/`pdf`/`zip`) untouched,
extension-less names untouched, `foo.md.md` keeps one suffix, bare `.md`/`.txt`
never fully stripped, blank/whitespace round-trip, surrounding-whitespace trimmed
only in the display string, interior dots untouched.

## Verification
- `gradle assembleDebug` — **green** (173.x MB debug APK built).
- `gradle testDebugUnitTest` — **2439 tests, 1 failed** = the pre-existing
  `Phase148UiFailureTextScrubTest` UNC-path failure (`\\fileserver\share\secret-wills.docx`
  assert), untouched and reproduced as documented. 2437 (post phase-183 as shipped) + 2
  new review-fix pins (`Phase183GalleryTypographyTest`) = 2439; the report's +11 policy
  tests come from `GalleryTitleDisplayPolicyTest`. ✓

## Constraints honored
- No `.github/workflows/` edits. No new dependencies. No DB schema change.
- The DATABASE title value is never changed — only the display string.
- Base-APK-size rule intact. `.done` marker + REPORT committed and pushed.

## Workflow
- Step 1 (inventory): commit `bd99137` — `workspace/phase-183/INVENTORY.md`.
- Step 2 (fix): commit `adbeed3` — policy + GalleryView + tests.
- Step 3 (proof): this REPORT — commit + push.