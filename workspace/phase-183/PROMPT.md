# Phase 183: GalleryView typography & title wrapping — no mid-word breaks, no ".md", ellipsis [DONE]

You are working on **InkFlow/Noteflow**. User visual review: in the compact gallery grid,
titles like `2026-08-19.md` wrap mid-extension (`2026-08-19.m` + dangling `d`). The column
is narrow (~160-170dp) and the style uses standard word breaking. Also, the `.md`
extension is displayed.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first. The relevant code is
`ui/components/GalleryView.kt:148-155`.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-183 step N: <desc>" && git push`
after EVERY step.

## Step 1 - Inventory (commit it)
- Read `ui/components/GalleryView.kt` around `:98-212` (card composable, title
  `:148-155`, preview `:174-183`, ink placeholder `:184-212`). Note every place a
  title/filename is rendered.
- COMMIT this step.

## Step 2 - Fix titles
- Strip redundant file extensions for display (`page.title.removeSuffix(".md")` and
  the other known extensions: `.md`, `.markdown`, `.txt`).
- Render with `maxLines = 2`, `overflow = TextOverflow.Ellipsis`, `softWrap = true`,
  no mid-word hyphenation (`Hyphens.None` if available), `FontWeight.SemiBold`,
  `lineHeight` ~18sp, consistent with the review's suggested snippet.
- Apply the same treatment to the pinned/compact/date footer labels if they can
  overflow.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests for the new title policy: `.md`/`.markdown`/`.txt` stripped,
  other names untouched, no extension stripped from a bare filename that IS an
  extension (`foo.md.md` keeps one).

## Definition of done
- Gallery titles display without mid-word/mid-extension breaks, no redundant `.md`,
  ellipsized at 2 lines. `workspace/phase-183/REPORT.md` with before/after + tests.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- The DATABASE title value is NEVER changed — only the display string.