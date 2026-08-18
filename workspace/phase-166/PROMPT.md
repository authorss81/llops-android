# Phase 166: Buttons/text must fit screens & cards — wrap or shorten [NOT STARTED]

You are working on **InkFlow/Noteflow**. User feedback: any button or text that
does not fit in its screen or card gets clipped. Fix the layout so it either
wraps to the next line or is shortened (e.g. paging controls use compact
arrows instead of overflowing labels).

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## Context
- Compose UI. Report the worst offenders found by auditing screens for
  fixed-size containers with long text:
  - Buttons with long labels in fixed-width rows (check HomeScreen dialogs,
    EditorScreen bottom bars, WebDavSyncDialog, ImportExport/export dialogs,
    TagManagerDialog, plugin dialogs).
  - Paging/next-previous controls that overflow (calendar pager, version
    history, backup list, gallery paging) — replace overflowing text labels
    with compact icon buttons (arrows) + short tooltip/contentDescription.
  - Cards whose content exceeds the card width (TagExplorer, KnowledgeGraph
    nodes, gallery cards, command palette).

## Definition of done
- Audit pass documented in `workspace/phase-166/REPORT.md`: each screen checked,
  list of overflow bugs found (screen:line) and how each was fixed.
- Every long button label either wraps (multi-line, centered) or is shortened;
  no text is clipped/ellipsized to nothing on the widest supported screen
  (check at least 360dp width; wrap instead of hard clip).
- Paging controls: overflowing text controls converted to arrow icons with
  contentDescription (next/previous) + a short numeric/summary label that fits.
- Long text in cards uses `maxLines` + `TextOverflow.Ellipsis` deliberately and
  never extends past the card bounds.
- Tests: at minimum a layout/descriptor test or a documented manual check matrix
  on a 360dp and 411dp width.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. Do NOT change the security model.
- Respect phase-127 normal typography (no exaggerated styles).
- Do not shrink tap targets below 48dp — arrows must stay tappable.