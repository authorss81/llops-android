# Phase 183 — Inventory: every place a title/filename is rendered in the compact gallery grid

## Scope
The compact gallery grid = `ui/components/GalleryView.kt` (`GalleryView` + private
`GalleryCardItem`). Cell width ≈ 168dp min (`GridCells.Adaptive(minSize = 168.dp)`,
`GalleryView.kt:60`), padding 12dp sheet + 14dp card inner; effective text column
≈ 168 − 28 − icon badge ≈ **~110-140dp** depending on density. This is why a
`2026-08-19.md` title wrapped mid-extension (`2026-08-19.m` + dangling `d`).

### Title/filename render sites inside `GalleryView.kt` (the phase target)
| Site | File:line | Current behavior | Overflow risk |
|------|-----------|------------------|---------------|
| **Card header title** (the defect) | `GalleryView.kt:148-155` | `Text(text = page.title, style = titleSmall, fontWeight = SemiBold, maxLines = 2, overflow = Ellipsis)` on a `Row` with `weight(1f)` | YES — renders the raw stored title including `.md`; standard word-break lets a hyphen/extension split mid-word (`.m` + `d`). Phase-183 fix target. |
| Preview body text | `GalleryView.kt:176-182` | `page.extractedText.trim()` as `bodySmall`, `maxLines = 3`, `overflow = Ellipsis` | Already capped+ellipsized; not a filename. No change. |
| Ink placeholder label | `GalleryView.kt:206-210` | `pageTypeLabel(page)` — static strings (`"PDF page"`, `"Image page"`, `"Empty page"`, `"Ink & canvas page"`) | Static, no filename, never overflows. No change. |
| Tag chips | `GalleryView.kt:235-243` | `"#$tag"`, `labelSmall`, `maxLines = 1`, `overflow = Ellipsis` | Already capped. Not a filename. No change. |
| Hidden-tag count chip | `GalleryView.kt:250-255` | `"+$n"` | Tiny, no overflow. No change. |
| **Footer date** | `GalleryView.kt:271-275` | `dateFormat.format(Date(page.updatedAt))`, `labelMedium` — NO maxLines/overflow | A long localized date (`MMMM d, yyyy` under some locales) can wrap on the same narrow column. Add `maxLines = 1` + `Ellipsis`. |
| Type badge + pin icons | `GalleryView.kt:134-146`, `:157-163` | icons only | No text. |
| Helpers `pageTypeIcon`/`pageTypeLabel` | `GalleryView.kt:283-295` | derived from `sourceFileType` | No title text. |

### Title render sites in the SAME home tab's other view modes (out of phase-183 scope — noted for the report only)
| View | File:line |
|------|-----------|
| List view mode 0 (`NotePageCard`) | `HomeScreen.kt:2586` `text = page.title` |
| Kanban mode 2 | `KanbanBoardView.kt:217` `text = page.title` |
| Calendar mode 3 | `CalendarView.kt:240` `text = page.title` |
| Spreadsheet mode 4 | `SpreadsheetTableView.kt:99` `text = page.title` |
| Wiki-suggestion card | `HomeScreen.kt:1919` `itemTitle = page.title` |
| Rename dialogs (raw stored value, correct to keep) | `HomeScreen.kt:608,838,1359,1389` `initialDialogText = page.title` |

Non-display consumers of `page.title` (routing only, must keep the RAW value):
`MainActivity.kt:601,711` (`endsWith(".md")` checks driving editor choice).

## Constraint
The DATABASE title value is NEVER changed — only the display string. All fixes are
display-side (a pure-JVM policy + the composable Text params).